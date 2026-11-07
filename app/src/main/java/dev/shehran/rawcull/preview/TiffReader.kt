package dev.shehran.rawcull.preview

internal object TiffTag {
    const val COMPRESSION = 0x0103
    const val STRIP_OFFSETS = 0x0111
    const val ORIENTATION = 0x0112
    const val STRIP_BYTE_COUNTS = 0x0117
    const val SUB_IFDS = 0x014A
    const val JPEG_OFFSET = 0x0201
    const val JPEG_LENGTH = 0x0202
    const val PANASONIC_JPG_FROM_RAW = 0x002E
    const val OLD_PREVIEW_IMAGE = 0x0280
    const val EXIF_IFD = 0x8769
    const val MAKER_NOTE = 0x927C
    const val MP_ENTRY = 0xB002
}

private const val ENTRY_BYTES = 12
private const val VALUE_POSITION = 8
private const val MAX_ENTRIES = 1024
private const val TYPE_BYTE = 1
private const val TYPE_SHORT = 3
private const val TYPE_UNDEFINED = 7

/** One 12-byte directory entry. Values too big for the entry stay in the file, and [number] is their offset. */
internal class TiffEntry(
    private val reader: TiffReader,
    private val bytes: ByteArray,
    private val at: Int,
    val tag: Int,
    val type: Int,
    val count: Long,
) {
    /**
     * Read as 16 bits whatever the type says: some Canon and Olympus firmwares leave stack garbage
     * in the upper half of the value field.
     */
    fun short(): Int = reader.u16(bytes, at + VALUE_POSITION)

    /** The value of a SHORT, LONG or IFD field, or the offset of anything longer. */
    fun number(): Long = when (type) {
        TYPE_SHORT -> if (count <= 2) short().toLong() else offset()
        TYPE_BYTE, TYPE_UNDEFINED -> if (count <= 4) bytes[at + VALUE_POSITION].toLong() and 0xFF else offset()
        else -> offset()
    }

    /** The raw value field, for tags that use the count as a length and this as the position. */
    fun offset(): Long = reader.u32(bytes, at + VALUE_POSITION)

    /** Several LONGs, stored in the entry when one fits. Capped by [limit]. */
    fun longs(source: ByteSource, limit: Int): List<Long> {
        if (count == 1L) return listOf(number())
        val wanted = count.coerceAtMost(limit.toLong()).toInt()
        val table = source.read(offset(), wanted * 4)
        return (0 until table.size / 4).map { reader.u32(table, it * 4) }
    }
}

internal class TiffIfd(val entries: List<TiffEntry>, val next: Long) {
    fun entry(tag: Int): TiffEntry? = entries.firstOrNull { it.tag == tag }
}

/** Reads TIFF directories through a [ByteSource], in either byte order. */
internal class TiffReader private constructor(
    val source: ByteSource,
    val littleEndian: Boolean,
    val firstIfd: Long,
) {
    /** Null if [offset] holds no directory. A count that can't be right stops the walk there. */
    fun readIfd(offset: Long): TiffIfd? {
        if (offset <= 0) return null
        source.size?.let { if (offset + 2 > it) return null }
        val head = source.read(offset, 2)
        if (head.size < 2) return null
        val count = u16(head, 0)
        if (count == 0 || count > MAX_ENTRIES) return null
        val body = source.read(offset + 2, count * ENTRY_BYTES + 4)
        val complete = body.size / ENTRY_BYTES
        val entries = (0 until minOf(count, complete)).map { i ->
            val at = i * ENTRY_BYTES
            TiffEntry(this, body, at, u16(body, at), u16(body, at + 2), u32(body, at + 4))
        }
        val nextAt = count * ENTRY_BYTES
        val next = if (body.size >= nextAt + 4) u32(body, nextAt) else 0L
        return TiffIfd(entries, next)
    }

    fun u16(bytes: ByteArray, at: Int): Int {
        val a = bytes[at].toInt() and 0xFF
        val b = bytes[at + 1].toInt() and 0xFF
        return if (littleEndian) a or (b shl 8) else (a shl 8) or b
    }

    fun u32(bytes: ByteArray, at: Int): Long {
        val a = bytes[at].toLong() and 0xFF
        val b = bytes[at + 1].toLong() and 0xFF
        val c = bytes[at + 2].toLong() and 0xFF
        val d = bytes[at + 3].toLong() and 0xFF
        return if (littleEndian) a or (b shl 8) or (c shl 16) or (d shl 24) else (a shl 24) or (b shl 16) or (c shl 8) or d
    }

    /** The same rules over other bytes, as when a MakerNote counts its offsets from itself. */
    fun over(other: ByteSource, littleEndian: Boolean = this.littleEndian): TiffReader =
        TiffReader(other, littleEndian, firstIfd)

    companion object {
        private const val HEADER_BYTES = 8
        private const val MAGIC_TIFF = 42
        private const val MAGIC_PANASONIC = 0x0055
        private const val MAGIC_OLYMPUS_RO = 0x4F52
        private const val MAGIC_OLYMPUS_RS = 0x5352
        private val MAGICS = setOf(MAGIC_TIFF, MAGIC_PANASONIC, MAGIC_OLYMPUS_RO, MAGIC_OLYMPUS_RS)
        private const val II = 0x49
        private const val MM = 0x4D

        /** Null unless the source starts with a TIFF header or one of the camera variants of it. */
        fun open(source: ByteSource): TiffReader? {
            val header = source.read(0, HEADER_BYTES)
            if (header.size < HEADER_BYTES) return null
            val first = header[0].toInt()
            if (first != header[1].toInt() || (first != II && first != MM)) return null
            val probe = TiffReader(source, first == II, 0)
            if (probe.u16(header, 2) !in MAGICS) return null
            return TiffReader(source, first == II, probe.u32(header, 4))
        }
    }
}
