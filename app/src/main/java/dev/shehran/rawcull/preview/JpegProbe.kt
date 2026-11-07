package dev.shehran.rawcull.preview

/** A range of the file, offset first. */
data class ByteRange(val offset: Long, val length: Long)

/**
 * What a JPEG's own headers say.
 *
 * @param end one past the EOI marker, or the end of the range when no EOI was asked for.
 * @param mpfImages secondary images from APP2, the ones meant as previews of the primary.
 */
data class JpegInfo(
    val width: Int,
    val height: Int,
    val orientation: Int?,
    val thumbnail: ByteRange?,
    val mpfImages: List<ByteRange>,
    val end: Long,
)

/** Reads a JPEG's marker segments without touching its image data. */
object JpegProbe {
    private const val MARKER = 0xFF
    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val SOF_BASELINE = 0xC0
    private const val SOF_EXTENDED = 0xC1
    private const val SOF_PROGRESSIVE = 0xC2
    private const val SOF_LAST = 0xCF
    private const val DHT = 0xC4
    private const val JPG = 0xC8
    private const val DAC = 0xCC
    private const val RST_FIRST = 0xD0
    private const val RST_LAST = 0xD7
    private const val TEM = 0x01
    private const val APP1 = 0xE1
    private const val APP2 = 0xE2
    private const val MAX_SEGMENTS = 512
    private const val SEGMENT_HEADER = 4
    private const val EXIF_SIGNATURE_BYTES = 6
    private const val SOF_BYTES = 6
    private const val MIN_SEGMENT_LENGTH = 2
    private const val EIGHT_BIT = 8
    private const val EOI_SEARCH_BYTES = 8192
    private const val MPF_SIGNATURE_BYTES = 4
    private const val MPF_ENTRY_BYTES = 16
    private const val MPF_MAX_IMAGES = 16
    private const val MPF_TYPE_MASK = 0xFFFFFF
    private const val MPF_LARGE_THUMBNAIL_VGA = 0x010001
    private const val MPF_LARGE_THUMBNAIL_FULL_HD = 0x010002

    private val EXIF_SIGNATURE = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)
    private val MPF_SIGNATURE = byteArrayOf(0x4D, 0x50, 0x46, 0)

    /**
     * @param requireEoi false for a JPEG that is the whole file: what follows it can be anything
     * (a motion photo's video, say), and a cut-off file still shows what it has.
     * @return null unless this is a JPEG that [android.graphics.BitmapFactory] can decode.
     */
    fun probe(source: ByteSource, offset: Long, length: Long, requireEoi: Boolean = true): JpegInfo? {
        if (length < SEGMENT_HEADER) return null
        val start = source.read(offset, 3)
        if (start.size < 3 || start.u8(0) != MARKER || start.u8(1) != SOI || start.u8(2) != MARKER) return null

        val limit = offset + length
        var position = offset + 2
        var orientation: Int? = null
        var thumbnail: ByteRange? = null
        var mpfImages = emptyList<ByteRange>()
        repeat(MAX_SEGMENTS) {
            if (position + SEGMENT_HEADER > limit) return null
            val header = source.read(position, SEGMENT_HEADER)
            if (header.size < SEGMENT_HEADER || header.u8(0) != MARKER) return null
            val marker = header.u8(1)
            when {
                marker == MARKER -> { // Fill byte before a marker
                    position += 1
                    return@repeat
                }
                marker == TEM || marker in RST_FIRST..RST_LAST -> {
                    position += 2
                    return@repeat
                }
                marker == SOS || marker == EOI || marker == SOI -> return null // No frame header came first
            }
            val segmentLength = (header.u8(2) shl 8) or header.u8(3)
            if (segmentLength < MIN_SEGMENT_LENGTH) return null
            val payload = position + SEGMENT_HEADER
            val payloadLength = segmentLength - MIN_SEGMENT_LENGTH

            if (isFrameHeader(marker)) {
                if (marker != SOF_BASELINE && marker != SOF_EXTENDED && marker != SOF_PROGRESSIVE) return null
                val frame = source.read(payload, SOF_BYTES)
                if (frame.size < SOF_BYTES || frame.u8(0) != EIGHT_BIT) return null
                val height = (frame.u8(1) shl 8) or frame.u8(2)
                val width = (frame.u8(3) shl 8) or frame.u8(4)
                val components = frame.u8(5)
                if (width == 0 || height == 0 || (components != 1 && components != 3)) return null
                val end = if (requireEoi) findEnd(source, offset, length) ?: return null else limit
                return JpegInfo(width, height, orientation, thumbnail, mpfImages, end)
            }

            if (marker == APP1 && payloadLength > EXIF_SIGNATURE_BYTES && orientation == null) {
                if (source.read(payload, EXIF_SIGNATURE_BYTES).contentEquals(EXIF_SIGNATURE)) {
                    val tiffStart = payload + EXIF_SIGNATURE_BYTES
                    val exif = readExif(source, tiffStart, (payloadLength - EXIF_SIGNATURE_BYTES).toLong())
                    orientation = exif.first
                    thumbnail = exif.second
                }
            } else if (marker == APP2 && payloadLength > MPF_SIGNATURE_BYTES && mpfImages.isEmpty()) {
                if (source.read(payload, MPF_SIGNATURE_BYTES).contentEquals(MPF_SIGNATURE)) {
                    val tiffStart = payload + MPF_SIGNATURE_BYTES
                    mpfImages = readMpf(source, tiffStart, (payloadLength - MPF_SIGNATURE_BYTES).toLong())
                }
            }
            position += MIN_SEGMENT_LENGTH + segmentLength
        }
        return null
    }

    private fun isFrameHeader(marker: Int): Boolean =
        marker in SOF_BASELINE..SOF_LAST && marker != DHT && marker != JPG && marker != DAC

    /** Declared lengths are often padded, so the EOI is looked for backwards from the end. */
    private fun findEnd(source: ByteSource, offset: Long, length: Long): Long? {
        val searched = minOf(length, EOI_SEARCH_BYTES.toLong()).toInt()
        val tailStart = offset + length - searched
        val tail = source.read(tailStart, searched)
        if (tail.size < searched) return null // The range runs past the end of the file
        for (i in tail.size - 2 downTo 0) {
            if (tail.u8(i) == MARKER && tail.u8(i + 1) == EOI) return tailStart + i + 2
        }
        return null
    }

    private fun readExif(source: ByteSource, tiffStart: Long, tiffLength: Long): Pair<Int?, ByteRange?> {
        val tiff = TiffReader.open(WindowSource(source, tiffStart, tiffLength)) ?: return null to null
        val ifd0 = tiff.readIfd(tiff.firstIfd) ?: return null to null
        val orientation = ifd0.entry(TiffTag.ORIENTATION)?.short()?.takeIf { it in 1..8 }
        val ifd1 = ifd0.next.takeIf { it > 0 }?.let(tiff::readIfd)
        val thumbnailOffset = ifd1?.entry(TiffTag.JPEG_OFFSET)?.number()
        val thumbnailLength = ifd1?.entry(TiffTag.JPEG_LENGTH)?.number()
        val thumbnail = if (thumbnailOffset != null && thumbnailLength != null && thumbnailLength > 0) {
            ByteRange(tiffStart + thumbnailOffset, thumbnailLength)
        } else {
            null
        }
        return orientation to thumbnail
    }

    /** Only "large thumbnails": the other entries are depth maps, HDR gain maps and the like. */
    private fun readMpf(source: ByteSource, tiffStart: Long, tiffLength: Long): List<ByteRange> {
        val window = WindowSource(source, tiffStart, tiffLength)
        val tiff = TiffReader.open(window) ?: return emptyList()
        val index = tiff.readIfd(tiff.firstIfd) ?: return emptyList()
        val entries = index.entry(TiffTag.MP_ENTRY) ?: return emptyList()
        val count = (entries.count / MPF_ENTRY_BYTES).coerceAtMost(MPF_MAX_IMAGES.toLong()).toInt()
        val table = window.read(entries.number(), count * MPF_ENTRY_BYTES)
        return (0 until table.size / MPF_ENTRY_BYTES).mapNotNull { i ->
            val at = i * MPF_ENTRY_BYTES
            val attribute = tiff.u32(table, at).toInt() and MPF_TYPE_MASK
            val size = tiff.u32(table, at + 4)
            val imageOffset = tiff.u32(table, at + 8)
            val isPreview = attribute == MPF_LARGE_THUMBNAIL_VGA || attribute == MPF_LARGE_THUMBNAIL_FULL_HD
            if (isPreview && imageOffset > 0 && size > 0) ByteRange(tiffStart + imageOffset, size) else null
        }
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF
}
