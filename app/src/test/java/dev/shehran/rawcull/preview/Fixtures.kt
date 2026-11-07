package dev.shehran.rawcull.preview

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/** Real JPEG bytes, with headers injected where a test needs them. */
object JpegBuilder {
    fun jpeg(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, (x * 7 xor y * 13) and 0xFFFFFF)
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
    }

    /** An APP1 EXIF segment after the SOI, with an optional IFD1 thumbnail held inside it. */
    fun withExif(jpeg: ByteArray, orientation: Int, thumbnail: ByteArray? = null, littleEndian: Boolean = true): ByteArray {
        val tiff = TiffBuilder(littleEndian)
        val ifd1 = thumbnail?.let {
            val at = tiff.blob(it)
            tiff.ifd(listOf(TiffBuilder.long(0x201, at), TiffBuilder.long(0x202, it.size.toLong())))
        } ?: 0L
        tiff.first(tiff.ifd(listOf(TiffBuilder.short(0x112, orientation)), next = ifd1))
        return insertSegment(jpeg, 0xE1, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff.bytes())
    }

    /**
     * A primary with an APP2 MPF index, followed by the secondary images.
     *
     * @param attribute the MPF type of each secondary image.
     */
    fun withMpf(primary: ByteArray, secondaries: List<ByteArray>, attribute: Int = 0x010002): ByteArray {
        val signature = "MPF\u0000".toByteArray(Charsets.ISO_8859_1)
        val entryCount = secondaries.size + 1
        val indexBytes = 8 + 2 + 3 * 12 + 4 + entryCount * 16
        val segmentBytes = 4 + signature.size + indexBytes
        val tiffStart = 2 + 4 + signature.size

        var position = primary.size + segmentBytes - tiffStart // Secondaries come after the primary
        val entries = ByteBuffer.allocate(entryCount * 16).order(ByteOrder.LITTLE_ENDIAN)
        entries.putInt(0x030000).putInt(primary.size + segmentBytes).putInt(0).putInt(0)
        secondaries.forEach {
            entries.putInt(attribute).putInt(it.size).putInt(position).putInt(0)
            position += it.size
        }

        val tiff = ByteBuffer.allocate(indexBytes).order(ByteOrder.LITTLE_ENDIAN)
        tiff.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        tiff.putShort(3)
        tiff.putShort(0xB000.toShort()).putShort(7).putInt(4).put("0100".toByteArray())
        tiff.putShort(0xB001.toShort()).putShort(4).putInt(1).putInt(entryCount)
        tiff.putShort(0xB002.toShort()).putShort(7).putInt(entryCount * 16).putInt(8 + 2 + 36 + 4)
        tiff.putInt(0)
        tiff.put(entries.array())

        return insertSegment(primary, 0xE2, signature + tiff.array()) + secondaries.fold(ByteArray(0)) { all, it -> all + it }
    }

    fun insertSegment(jpeg: ByteArray, marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        val header = byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte())
        return jpeg.copyOfRange(0, 2) + header + payload + jpeg.copyOfRange(2, jpeg.size)
    }

    /** The frame header's marker, swapped: lossless (0xC3) is what DNG and CR2 raw data use. */
    fun withFrameMarker(jpeg: ByteArray, marker: Int): ByteArray {
        val out = jpeg.copyOf()
        var at = 2
        while (at < out.size) {
            val current = out[at + 1].toInt() and 0xFF
            if (current == 0xC0 || current == 0xC2) {
                out[at + 1] = marker.toByte()
                return out
            }
            at += 2 + (((out[at + 2].toInt() and 0xFF) shl 8) or (out[at + 3].toInt() and 0xFF))
        }
        error("no frame header")
    }
}

/** Appends blobs and directories in the order a test calls for, in either byte order. */
class TiffBuilder(private val littleEndian: Boolean = true, magic: Int = 42) {
    class Entry(val tag: Int, val type: Int, val count: Long, val value: Long)

    private var data = ByteArray(0)
    private val order = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

    init {
        val mark = (if (littleEndian) 'I' else 'M').code.toByte()
        data = ByteBuffer.allocate(8).order(order).put(mark).put(mark).putShort(magic.toShort()).putInt(0).array()
    }

    val position: Long get() = data.size.toLong()

    fun blob(bytes: ByteArray): Long = position.also { data += bytes }

    fun pad(count: Int) {
        data += ByteArray(count)
    }

    fun first(offset: Long) = putU32(4, offset)

    /** @return where the directory went. */
    fun ifd(entries: List<Entry>, next: Long = 0): Long {
        val at = position
        val buffer = ByteBuffer.allocate(2 + entries.size * 12 + 4).order(order)
        buffer.putShort(entries.size.toShort())
        entries.forEach { entry ->
            buffer.putShort(entry.tag.toShort()).putShort(entry.type.toShort()).putInt(entry.count.toInt())
            if (entry.type == TYPE_SHORT && entry.count == 1L) {
                buffer.putShort(entry.value.toShort()).putShort(0)
            } else {
                buffer.putInt(entry.value.toInt())
            }
        }
        buffer.putInt(next.toInt())
        data += buffer.array()
        return at
    }

    /** Points a directory's next-IFD field somewhere, for cycles and forward references. */
    fun patchNext(ifdAt: Long, entryCount: Int, next: Long) = putU32((ifdAt + 2 + entryCount * 12).toInt(), next)

    fun bytes(): ByteArray = data.copyOf()

    private fun putU32(at: Int, value: Long) {
        ByteBuffer.wrap(data).order(order).putInt(at, value.toInt())
    }

    companion object {
        const val TYPE_SHORT = 3
        const val TYPE_LONG = 4
        const val TYPE_UNDEFINED = 7

        fun short(tag: Int, value: Int) = Entry(tag, TYPE_SHORT, 1, value.toLong())
        fun long(tag: Int, value: Long) = Entry(tag, TYPE_LONG, 1, value)
        fun undefined(tag: Int, offset: Long, length: Long) = Entry(tag, TYPE_UNDEFINED, length, offset)
    }
}

/** ISO-BMFF boxes, enough of them for a CR3. */
object BmffBuilder {
    const val METADATA_UUID = "85c0b687820f11e08111f4ce462b6a48"
    const val PREVIEW_UUID = "eaf42b5e1c984b88b9fbb7dc406e4d16"

    fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8).putInt(payload.size + 8).put(type.toByteArray(Charsets.ISO_8859_1)).array() + payload

    fun largeBox(type: String, payload: ByteArray): ByteArray = ByteBuffer.allocate(16)
        .putInt(1).put(type.toByteArray(Charsets.ISO_8859_1)).putLong(payload.size + 16L).array() + payload

    fun uuid(hex: String, payload: ByteArray): ByteArray =
        box("uuid", ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } + payload)

    fun ftyp(brand: String = "crx "): ByteArray = box("ftyp", brand.toByteArray(Charsets.ISO_8859_1) + ByteArray(8))

    /** `THMB` and `PRVW` as Canon writes them: size fields, then the JPEG. */
    fun embedded(type: String, width: Int, height: Int, jpeg: ByteArray): ByteArray {
        val fields = ByteBuffer.allocate(16).putInt(0).putShort(width.toShort()).putShort(height.toShort())
            .putInt(jpeg.size).putInt(0).array()
        return box(type, fields + jpeg)
    }

    fun cmt1(orientation: Int): ByteArray {
        val tiff = TiffBuilder()
        tiff.first(tiff.ifd(listOf(TiffBuilder.short(0x112, orientation))))
        return box("CMT1", tiff.bytes())
    }

    private fun stbl(offset: Long, size: Int, wide: Boolean): ByteArray {
        val offsets = if (wide) {
            box("co64", ByteBuffer.allocate(16).putInt(0).putInt(1).putLong(offset).array())
        } else {
            box("stco", ByteBuffer.allocate(12).putInt(0).putInt(1).putInt(offset.toInt()).array())
        }
        val sizes = box("stsz", ByteBuffer.allocate(16).putInt(0).putInt(0).putInt(1).putInt(size).array())
        return box("stbl", sizes + offsets)
    }

    private fun trak(offset: Long, size: Int, wide: Boolean): ByteArray =
        box("trak", box("mdia", box("minf", stbl(offset, size, wide))))

    /** A whole file: `ftyp`, `moov`, the preview uuid and an `mdat` holding the full-size JPEG. */
    fun cr3(thumbnail: ByteArray?, preview: ByteArray?, full: ByteArray?, orientation: Int?, wide: Boolean = true): ByteArray {
        fun layout(mdatOffset: Long): ByteArray {
            val metadata = (orientation?.let(::cmt1) ?: ByteArray(0)) +
                (thumbnail?.let { embedded("THMB", 160, 120, it) } ?: ByteArray(0))
            val track = full?.let { trak(mdatOffset, it.size, wide) } ?: ByteArray(0)
            return ftyp() + box("moov", uuid(METADATA_UUID, metadata) + track) +
                (preview?.let { uuid(PREVIEW_UUID, ByteArray(8) + embedded("PRVW", 1620, 1080, it)) } ?: ByteArray(0))
        }
        val head = layout(0)
        val mdatStart = head.size + 16L
        return layout(mdatStart) + largeBox("mdat", full ?: ByteArray(0))
    }
}

object RafBuilder {
    fun raf(jpeg: ByteArray, jpegOffset: Int = 148, declaredLength: Int = jpeg.size): ByteArray {
        val out = ByteArray(jpegOffset + jpeg.size)
        "FUJIFILMCCD-RAW 0201FF129502".toByteArray(Charsets.ISO_8859_1).copyInto(out)
        ByteBuffer.wrap(out).putInt(84, jpegOffset).putInt(88, declaredLength)
        jpeg.copyInto(out, jpegOffset)
        return out
    }
}

/** Counts what reaches the file, so tests can see the block cache working. */
class CountingSource(private val inner: ByteSource) : ByteSource by inner {
    var reads = 0
        private set
    var bytesRead = 0L
        private set

    override fun read(offset: Long, length: Int): ByteArray {
        reads++
        return inner.read(offset, length).also { bytesRead += it.size }
    }
}
