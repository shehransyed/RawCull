package dev.shehran.rawcull.preview

/**
 * Canon CR3, an ISO-BMFF file. RapidReady byte-scans its header slice for these boxes; this walks
 * them instead, which also reaches the full-size JPEG (track 1 in `mdat`) that the scan can't see.
 */
object Cr3PreviewScanner : PreviewFormatScanner {
    private const val BOX_HEADER = 8
    private const val LARGE_BOX_HEADER = 16
    private const val UUID_BYTES = 16
    private const val FULL_BOX_HEADER = 4
    private const val MAX_BOXES = 4096
    private const val SOI_SEARCH_BYTES = 64
    private const val PREVIEW_UUID_PREFIX_BYTES = 8
    private const val FTYP_BRAND_BYTES = 12
    private const val MAJOR_BRAND = "crx "

    private val CANON_METADATA_UUID = uuid("85c0b687820f11e08111f4ce462b6a48")
    private val CANON_PREVIEW_UUID = uuid("eaf42b5e1c984b88b9fbb7dc406e4d16")

    private class Box(val type: String, val start: Long, val payload: Long, val end: Long)

    override fun scan(source: ByteSource): PreviewScan {
        val collector = CandidateCollector(source)
        var orientation: Int? = null
        collector.guarded {
            val brand = source.read(0, FTYP_BRAND_BYTES)
            if (brand.size < FTYP_BRAND_BYTES) return@guarded
            if (String(brand, 4, 4, Charsets.ISO_8859_1) != "ftyp") return@guarded
            if (String(brand, 8, 4, Charsets.ISO_8859_1) != MAJOR_BRAND) return@guarded

            walk(source, 0, source.size ?: Long.MAX_VALUE) { box ->
                when (box.type) {
                    "moov" -> walk(source, box.payload, box.end) { child ->
                        when (child.type) {
                            "uuid" -> orientation = readUuid(source, child, collector) ?: orientation
                            "trak" -> if (collector.candidates.none { it.origin == PreviewOrigin.CR3_FULL }) {
                                addFirstTrack(source, child, collector)
                            }
                        }
                    }
                    "uuid" -> orientation = readUuid(source, box, collector) ?: orientation
                }
            }
        }
        return PreviewScan(collector.candidates, orientation ?: collector.firstOrientation ?: 1)
    }

    /** @return the orientation, if this box held `CMT1`. */
    private fun readUuid(source: ByteSource, box: Box, collector: CandidateCollector): Int? {
        val id = source.read(box.payload, UUID_BYTES)
        val children = box.payload + UUID_BYTES
        var orientation: Int? = null
        when {
            id.contentEquals(CANON_METADATA_UUID) -> walk(source, children, box.end) { child ->
                when (child.type) {
                    "CMT1" -> orientation = readOrientation(source, child)
                    "THMB" -> addEmbedded(source, child, PreviewOrigin.CR3_THUMBNAIL, collector)
                }
            }
            id.contentEquals(CANON_PREVIEW_UUID) -> walk(source, children + PREVIEW_UUID_PREFIX_BYTES, box.end) { child ->
                if (child.type == "PRVW") addEmbedded(source, child, PreviewOrigin.CR3_PREVIEW, collector)
            }
        }
        return orientation
    }

    private fun readOrientation(source: ByteSource, box: Box): Int? {
        val tiff = TiffReader.open(WindowSource(source, box.payload, box.end - box.payload)) ?: return null
        return tiff.readIfd(tiff.firstIfd)?.entry(TiffTag.ORIENTATION)?.short()?.takeIf { it in 1..8 }
    }

    /** `THMB` and `PRVW` start with a few size fields that vary by version; the JPEG follows them. */
    private fun addEmbedded(source: ByteSource, box: Box, origin: PreviewOrigin, collector: CandidateCollector) {
        val head = source.read(box.payload, SOI_SEARCH_BYTES)
        for (i in 0 until head.size - 2) {
            if (head[i] == 0xFF.toByte() && head[i + 1] == 0xD8.toByte() && head[i + 2] == 0xFF.toByte()) {
                val start = box.payload + i
                collector.add(start, box.end - start, origin)
                return
            }
        }
    }

    private fun addFirstTrack(source: ByteSource, trak: Box, collector: CandidateCollector) {
        val table = findSampleTable(source, trak) ?: return
        var offset: Long? = null
        var length: Long? = null
        walk(source, table.payload, table.end) { box ->
            when (box.type) {
                "co64" -> offset = firstEntry(source, box, wide = true)
                "stco" -> offset = offset ?: firstEntry(source, box, wide = false)
                "stsz" -> length = firstSampleSize(source, box)
            }
        }
        val start = offset ?: return
        val size = length ?: return
        collector.add(start, size, PreviewOrigin.CR3_FULL)
    }

    private fun findSampleTable(source: ByteSource, trak: Box): Box? {
        val mdia = child(source, trak, "mdia") ?: return null
        val minf = child(source, mdia, "minf") ?: return null
        return child(source, minf, "stbl")
    }

    private fun child(source: ByteSource, parent: Box, type: String): Box? {
        var found: Box? = null
        walk(source, parent.payload, parent.end) { box ->
            if (found == null && box.type == type) found = box
        }
        return found
    }

    private fun firstEntry(source: ByteSource, box: Box, wide: Boolean): Long? {
        val wanted = if (wide) 12 else 8
        val bytes = source.read(box.payload + FULL_BOX_HEADER, wanted)
        if (bytes.size < wanted || bytes.u32be(0) == 0L) return null
        return if (wide) bytes.u64be(4) else bytes.u32be(4)
    }

    private fun firstSampleSize(source: ByteSource, box: Box): Long? {
        val bytes = source.read(box.payload + FULL_BOX_HEADER, 12)
        if (bytes.size < 8) return null
        val fixedSize = bytes.u32be(0)
        return when {
            fixedSize != 0L -> fixedSize
            bytes.size == 12 && bytes.u32be(4) > 0 -> bytes.u32be(8)
            else -> null
        }
    }

    private inline fun walk(source: ByteSource, start: Long, end: Long, visit: (Box) -> Unit) {
        var position = start
        var boxes = 0
        while (position + BOX_HEADER <= end && boxes++ < MAX_BOXES) {
            val box = readBox(source, position, end) ?: return
            visit(box)
            position = box.end
        }
    }

    private fun readBox(source: ByteSource, position: Long, end: Long): Box? {
        val header = source.read(position, LARGE_BOX_HEADER)
        if (header.size < BOX_HEADER) return null
        val declared = header.u32be(0)
        val type = String(header, 4, 4, Charsets.ISO_8859_1)
        val headerBytes: Int
        val size: Long
        when (declared) {
            0L -> { // Runs to the end of the file
                headerBytes = BOX_HEADER
                size = end - position
            }
            1L -> {
                if (header.size < LARGE_BOX_HEADER) return null
                headerBytes = LARGE_BOX_HEADER
                size = header.u64be(BOX_HEADER)
            }
            else -> {
                headerBytes = BOX_HEADER
                size = declared
            }
        }
        if (size < headerBytes) return null
        val boxEnd = position + size
        if (boxEnd < position || boxEnd > end) return null
        return Box(type, position, position + headerBytes, boxEnd)
    }

    private fun uuid(hex: String): ByteArray = ByteArray(UUID_BYTES) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
