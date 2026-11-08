package dev.shehran.rawcull.data.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import dev.shehran.rawcull.preview.BlockCachedSource
import dev.shehran.rawcull.preview.ByteSource
import dev.shehran.rawcull.preview.ChannelByteSource
import dev.shehran.rawcull.preview.HEADER_SLICE_BYTES
import dev.shehran.rawcull.preview.RegionMath
import dev.shehran.rawcull.preview.SourceRect
import dev.shehran.rawcull.preview.StreamTierSource
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException

/**
 * One descriptor on one file (D5).
 *
 * @param startScan called before parsing, which is the only time the header slice is worth reading.
 */
class OpenPreviewFile(
    val source: ByteSource,
    val startScan: () -> Unit,
    private val closeable: Closeable,
) : Closeable by closeable

/** An image [PreviewFiles] decoded by itself, already upright. */
class DecodedImage(val bitmap: Bitmap, val originalWidth: Int, val originalHeight: Int)

/** A decoder over one JPEG; the seam around [BitmapRegionDecoder]. */
interface RegionDecoder : Closeable {
    fun decode(rect: SourceRect, sampleSize: Int): Bitmap?
}

/** File access, kept apart so the pipeline can be tested without a provider. */
interface PreviewFiles {
    /** @throws IOException when the file can't be opened; a card may simply have gone away. */
    fun open(uri: String): OpenPreviewFile

    /** DNG and HEIF only, through the platform decoder (API 28+). Null when it can't. */
    fun decodeWithPlatform(uri: String, maxEdgePx: Int): DecodedImage?

    fun regionDecoder(uri: String): RegionDecoder?

    fun regionDecoder(jpeg: ByteArray): RegionDecoder?
}

/** Bitmap work, kept apart for the same reason. */
interface PreviewCodec {
    fun decode(jpeg: ByteArray, sampleSize: Int, hardware: Boolean): Bitmap?

    /** Scaled to [longEdgePx] and turned upright, in one pass. */
    fun upright(bitmap: Bitmap, orientation: Int, longEdgePx: Int): Bitmap

    fun encode(bitmap: Bitmap): ByteArray
}

class SafPreviewFiles(context: Context) : PreviewFiles {
    private val resolver = context.applicationContext.contentResolver

    override fun open(uri: String): OpenPreviewFile {
        // Some providers (ExternalStorageProvider) throw IllegalArgumentException for a file that was
        // deleted behind the app's back; that's "gone", like any other missing file.
        val descriptor = try {
            resolver.openFileDescriptor(Uri.parse(uri), "r")
        } catch (e: IllegalArgumentException) {
            throw FileNotFoundException("$uri is gone: ${e.message}")
        } ?: throw FileNotFoundException("No descriptor for $uri")
        val stream = FileInputStream(descriptor.fileDescriptor)
        val channel = stream.channel
        if (descriptor.statSize >= 0 && canSeek(stream)) {
            val cached = BlockCachedSource(ChannelByteSource(channel, descriptor.statSize))
            return OpenPreviewFile(cached, { cached.preload(0, HEADER_SLICE_BYTES) }, descriptor)
        }
        val tiers = StreamTierSource({ stream })
        return OpenPreviewFile(BlockCachedSource(tiers), {}, descriptor)
    }

    private fun canSeek(stream: FileInputStream): Boolean = try {
        stream.channel.position(0)
        true
    } catch (e: IOException) {
        false
    }

    override fun decodeWithPlatform(uri: String, maxEdgePx: Int): DecodedImage? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        var width = 0
        var height = 0
        return try {
            val source = ImageDecoder.createSource(resolver, Uri.parse(uri))
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                width = info.size.width
                height = info.size.height
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val sample = RegionMath.sampleSize(maxEdgePx.toFloat() / maxOf(width, height))
                if (sample > 1) decoder.setTargetSampleSize(sample)
            }
            DecodedImage(bitmap, width, height)
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null // Decoders throw more than IOException for files they don't understand
        }
    }

    override fun regionDecoder(uri: String): RegionDecoder? {
        val descriptor = try {
            resolver.openFileDescriptor(Uri.parse(uri), "r")
        } catch (e: IOException) {
            null
        } ?: return null
        return wrap(descriptor) { newDecoder(descriptor) }
    }

    override fun regionDecoder(jpeg: ByteArray): RegionDecoder? = wrap(null) { newDecoder(jpeg) }

    private inline fun wrap(descriptor: ParcelFileDescriptor?, create: () -> BitmapRegionDecoder): RegionDecoder? = try {
        PlatformRegionDecoder(create(), descriptor)
    } catch (e: IOException) {
        descriptor?.close()
        null // Some JPEGs, progressive ones among them, can't be region-decoded
    } catch (e: RuntimeException) {
        descriptor?.close()
        null
    }

    @Suppress("DEPRECATION") // The replacements are API 31; minSdk is 24
    private fun newDecoder(descriptor: ParcelFileDescriptor): BitmapRegionDecoder =
        BitmapRegionDecoder.newInstance(descriptor.fileDescriptor, false)!!

    @Suppress("DEPRECATION")
    private fun newDecoder(jpeg: ByteArray): BitmapRegionDecoder =
        BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)!!
}

private class PlatformRegionDecoder(
    private val decoder: BitmapRegionDecoder,
    private val descriptor: ParcelFileDescriptor?,
) : RegionDecoder {
    override fun decode(rect: SourceRect, sampleSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return try {
            decoder.decodeRegion(Rect(rect.left, rect.top, rect.right, rect.bottom), options)
        } catch (e: IllegalArgumentException) {
            null // The rectangle fell outside the image
        } catch (e: IllegalStateException) {
            null // Closed while a decode was on its way
        }
    }

    override fun close() {
        decoder.recycle()
        descriptor?.close()
    }
}

class AndroidPreviewCodec : PreviewCodec {
    override fun decode(jpeg: ByteArray, sampleSize: Int, hardware: Boolean): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            if (hardware && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) inPreferredConfig = Bitmap.Config.HARDWARE
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
    }

    override fun upright(bitmap: Bitmap, orientation: Int, longEdgePx: Int): Bitmap {
        val scale = minOf(1f, longEdgePx.toFloat() / maxOf(bitmap.width, bitmap.height))
        val t = RegionMath.transform(bitmap.width, bitmap.height, orientation)
        val matrix = Matrix().apply {
            setValues(floatArrayOf(t.a, t.b, t.c, t.d, t.e, t.f, 0f, 0f, 1f))
            postScale(scale, scale)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun encode(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also {
        bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, it)
    }.toByteArray()

    private companion object {
        const val THUMBNAIL_QUALITY = 80
    }
}
