package dev.shehran.rawcull.preview

import dev.shehran.rawcull.data.model.PhotoFormat

/** Where in the file a candidate was found; shown in reports, not used for choosing. */
enum class PreviewOrigin {
    TIFF_IFD,
    TIFF_STRIP,
    PANASONIC_JPG_FROM_RAW,
    MAKER_NOTE,
    CR3_THUMBNAIL,
    CR3_PREVIEW,
    CR3_FULL,
    RAF_JPEG,
    JPEG_IMAGE,
    EXIF_THUMBNAIL,
    MPF_IMAGE,
    SOI_SEARCH,
}

/** A JPEG inside a file. The dimensions always come from its own frame header. */
data class PreviewCandidate(
    val offset: Long,
    val length: Long,
    val width: Int,
    val height: Int,
    val origin: PreviewOrigin,
) {
    val longEdge: Int get() = maxOf(width, height)
    val pixels: Long get() = width.toLong() * height
}

/** @param orientation the EXIF orientation of the file's pixels, 1 to 8; 1 when nothing says otherwise. */
data class PreviewScan(val candidates: List<PreviewCandidate>, val orientation: Int = 1) {
    companion object {
        val EMPTY = PreviewScan(emptyList())
    }
}

/** Finds the JPEGs in one kind of file. Malformed files give an empty scan; only I/O errors are thrown. */
fun interface PreviewFormatScanner {
    fun scan(source: ByteSource): PreviewScan
}

object PreviewScanner {
    fun forFormat(format: PhotoFormat): PreviewFormatScanner = when (format) {
        PhotoFormat.CR3 -> Cr3PreviewScanner
        PhotoFormat.RAF -> RafPreviewScanner
        PhotoFormat.JPEG -> JpegPreviewScanner
        PhotoFormat.HEIF -> PreviewFormatScanner { PreviewScan.EMPTY } // ImageDecoder reads these
        PhotoFormat.RAW_OTHER -> UnknownRawScanner
        PhotoFormat.ARW, PhotoFormat.DNG, PhotoFormat.CR2, PhotoFormat.NEF, PhotoFormat.NRW,
        PhotoFormat.ORF, PhotoFormat.RW2, PhotoFormat.PEF, PhotoFormat.SRW,
        -> TiffPreviewScanner
    }
}

/** Validates each JPEG as it's found, and keeps what was found if the file turns bad later on. */
internal class CandidateCollector(private val source: ByteSource) {
    private val found = LinkedHashMap<Long, PreviewCandidate>()
    var firstOrientation: Int? = null
        private set

    val candidates: List<PreviewCandidate> get() = found.values.toList()

    /** Adds the JPEG at this range, plus what its own headers point to. Null if it isn't valid. */
    fun add(offset: Long, length: Long, origin: PreviewOrigin, requireEoi: Boolean = true): JpegInfo? {
        if (offset <= 0 && origin != PreviewOrigin.JPEG_IMAGE) return null
        if (offset < 0 || length <= 0 || length > MAX_PREVIEW_BYTES) return null
        source.size?.let { if (offset + length > it) return null }
        if (offset in found) return null
        val info = JpegProbe.probe(source, offset, length, requireEoi) ?: return null
        found[offset] = PreviewCandidate(offset, info.end - offset, info.width, info.height, origin)
        if (firstOrientation == null) firstOrientation = info.orientation
        info.mpfImages.forEach { add(it.offset, it.length, PreviewOrigin.MPF_IMAGE) }
        return info
    }

    /** Runs a walk over untrusted bytes; whatever it found before going wrong still counts. */
    inline fun guarded(walk: () -> Unit) {
        try {
            walk()
        } catch (e: IndexOutOfBoundsException) {
            return
        } catch (e: IllegalArgumentException) {
            return
        } catch (e: ArithmeticException) {
            return
        } catch (e: NegativeArraySizeException) {
            return
        }
    }
}

/** A JPEG file: the image itself, its EXIF thumbnail and any previews in its MPF index. */
object JpegPreviewScanner : PreviewFormatScanner {
    override fun scan(source: ByteSource): PreviewScan {
        val collector = CandidateCollector(source)
        var orientation: Int? = null
        collector.guarded {
            val length = (source.size ?: MAX_PREVIEW_BYTES).coerceAtMost(MAX_PREVIEW_BYTES)
            val info = collector.add(0, length, PreviewOrigin.JPEG_IMAGE, requireEoi = false)
            orientation = info?.orientation
            info?.thumbnail?.let { collector.add(it.offset, it.length, PreviewOrigin.EXIF_THUMBNAIL) }
        }
        return PreviewScan(collector.candidates, orientation ?: 1)
    }
}

/** Fuji's header says where one JPEG is; that JPEG's EXIF and MPF say the rest. */
object RafPreviewScanner : PreviewFormatScanner {
    private const val MAGIC = "FUJIFILMCCD-RAW"
    private const val HEADER_BYTES = 92
    private const val JPEG_OFFSET_AT = 84
    private const val JPEG_LENGTH_AT = 88

    override fun scan(source: ByteSource): PreviewScan {
        val collector = CandidateCollector(source)
        var orientation: Int? = null
        collector.guarded {
            val header = source.read(0, HEADER_BYTES)
            if (header.size < HEADER_BYTES || String(header, 0, MAGIC.length, Charsets.ISO_8859_1) != MAGIC) return@guarded
            val info = collector.add(header.u32be(JPEG_OFFSET_AT), header.u32be(JPEG_LENGTH_AT), PreviewOrigin.RAF_JPEG)
            orientation = info?.orientation
            info?.thumbnail?.let { collector.add(it.offset, it.length, PreviewOrigin.EXIF_THUMBNAIL) }
        }
        return PreviewScan(collector.candidates, orientation ?: 1)
    }
}

internal fun ByteArray.u32be(at: Int): Long =
    ((this[at].toLong() and 0xFF) shl 24) or
        ((this[at + 1].toLong() and 0xFF) shl 16) or
        ((this[at + 2].toLong() and 0xFF) shl 8) or
        (this[at + 3].toLong() and 0xFF)

internal fun ByteArray.u64be(at: Int): Long = (u32be(at) shl 32) or u32be(at + 4)

/** A RAW of no format we know: TIFF if it is one, otherwise whatever a bounded search turns up. */
object UnknownRawScanner : PreviewFormatScanner {
    override fun scan(source: ByteSource): PreviewScan {
        val tiff = TiffPreviewScanner.scan(source)
        if (tiff.candidates.isNotEmpty()) return tiff
        return PreviewScan(SoiSearch.find(source))
    }
}

/** Looks for JPEG start markers when the file's structure gives nothing away. */
internal object SoiSearch {
    private const val SEARCHED_BYTES = 16L * 1024 * 1024
    private const val CHUNK_BYTES = 1024 * 1024
    private const val MAX_PROBES = 256
    private const val MAX_CANDIDATES = 8
    private const val EOI_SEARCH_CHUNK = 256 * 1024
    private const val FF = 0xFF.toByte()
    private const val D8 = 0xD8.toByte()
    private const val D9 = 0xD9.toByte()

    fun find(source: ByteSource): List<PreviewCandidate> {
        val collector = CandidateCollector(source)
        var skipTo = 0L
        var probes = 0
        collector.guarded {
            var chunkStart = 0L
            while (chunkStart < SEARCHED_BYTES && probes < MAX_PROBES && collector.candidates.size < MAX_CANDIDATES) {
                val chunk = source.read(chunkStart, CHUNK_BYTES + 2)
                if (chunk.size < 3) return@guarded
                for (i in 0 until chunk.size - 2) {
                    val at = chunkStart + i
                    if (at < skipTo || chunk[i] != FF || chunk[i + 1] != D8 || chunk[i + 2] != FF) continue
                    probes++
                    val end = findEoi(source, at) ?: continue
                    if (collector.add(at, end - at, PreviewOrigin.SOI_SEARCH) != null) skipTo = end
                    if (probes >= MAX_PROBES) break
                }
                chunkStart += CHUNK_BYTES
            }
        }
        return collector.candidates
    }

    private fun findEoi(source: ByteSource, start: Long): Long? {
        var position = start + 2
        val limit = start + MAX_PREVIEW_BYTES
        while (position < limit) {
            val chunk = source.read(position, EOI_SEARCH_CHUNK)
            if (chunk.size < 2) return null
            for (i in 0 until chunk.size - 1) {
                if (chunk[i] == FF && chunk[i + 1] == D9) return position + i + 2
            }
            position += chunk.size - 1
        }
        return null
    }
}
