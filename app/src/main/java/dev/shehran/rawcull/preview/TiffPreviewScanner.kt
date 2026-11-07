package dev.shehran.rawcull.preview

/**
 * ARW, NEF, NRW, CR2, DNG, PEF, SRW, ORF and RW2: IFD0, the chain after it and its SubIFDs. The
 * walk follows offsets wherever they point, so an IFD0 at the end of the file (Capture One) is
 * found like any other. The Exif IFD is only opened to reach an Olympus MakerNote.
 */
object TiffPreviewScanner : PreviewFormatScanner {
    private const val MAX_IFDS = 64
    private const val MAX_SUB_IFDS = 16
    private const val COMPRESSION_OLD_JPEG = 6
    private const val COMPRESSION_JPEG = 7

    override fun scan(source: ByteSource): PreviewScan {
        val collector = CandidateCollector(source)
        var orientation: Int? = null
        collector.guarded {
            val tiff = TiffReader.open(source) ?: return@guarded
            val pending = ArrayDeque<Long>()
            pending.addLast(tiff.firstIfd)
            val visited = HashSet<Long>()
            while (pending.isNotEmpty() && visited.size < MAX_IFDS) {
                val at = pending.removeFirst()
                if (!visited.add(at)) continue
                val ifd = tiff.readIfd(at) ?: continue
                if (orientation == null) {
                    orientation = ifd.entry(TiffTag.ORIENTATION)?.short()?.takeIf { it in 1..8 }
                }
                addJpegs(ifd, collector)
                ifd.entry(TiffTag.SUB_IFDS)?.longs(source, MAX_SUB_IFDS)?.forEach(pending::addLast)
                ifd.entry(TiffTag.EXIF_IFD)?.let { OlympusMakerNote.scan(tiff, it.number(), collector) }
                if (ifd.next > 0) pending.addLast(ifd.next)
            }
        }
        return PreviewScan(collector.candidates, orientation ?: collector.firstOrientation ?: 1)
    }

    private fun addJpegs(ifd: TiffIfd, collector: CandidateCollector) {
        val jpegOffset = ifd.entry(TiffTag.JPEG_OFFSET)
        val jpegLength = ifd.entry(TiffTag.JPEG_LENGTH)
        if (jpegOffset != null && jpegLength != null) {
            collector.add(jpegOffset.number(), jpegLength.number(), PreviewOrigin.TIFF_IFD)
        }

        // Both keep the length in the entry's count and the position in its value.
        ifd.entry(TiffTag.PANASONIC_JPG_FROM_RAW)?.let {
            collector.add(it.offset(), it.count, PreviewOrigin.PANASONIC_JPG_FROM_RAW)
        }
        ifd.entry(TiffTag.OLD_PREVIEW_IMAGE)?.let { collector.add(it.offset(), it.count, PreviewOrigin.MAKER_NOTE) }

        val compression = ifd.entry(TiffTag.COMPRESSION)?.short()
        if (compression == COMPRESSION_OLD_JPEG || compression == COMPRESSION_JPEG) {
            val stripOffset = ifd.entry(TiffTag.STRIP_OFFSETS)?.takeIf { it.count == 1L }
            val stripLength = ifd.entry(TiffTag.STRIP_BYTE_COUNTS)?.takeIf { it.count == 1L }
            if (stripOffset != null && stripLength != null) {
                collector.add(stripOffset.number(), stripLength.number(), PreviewOrigin.TIFF_STRIP)
            }
        }
    }
}

/** Best effort, as in RapidReady: the larger ORF preview lives in the CameraSettings sub-IFD. */
internal object OlympusMakerNote {
    private const val SIGNATURE_BYTES = 16
    private const val TAG_THUMBNAIL = 0x0100
    private const val TAG_CAMERA_SETTINGS = 0x2020
    private const val TAG_PREVIEW_VALID = 0x0100
    private const val TAG_PREVIEW_START = 0x0101
    private const val TAG_PREVIEW_LENGTH = 0x0102
    private const val HEADER_OLD = "OLYMP\u0000"
    private const val HEADER_OLYMPUS = "OLYMPUS\u0000"
    private const val HEADER_OM = "OM SYSTEM\u0000\u0000\u0000"
    private const val IFD_AT_OLD = 8L
    private const val IFD_AT_OLYMPUS = 12L
    private const val IFD_AT_OM = 16L
    private const val II = "II"

    fun scan(tiff: TiffReader, exifIfd: Long, collector: CandidateCollector) {
        val makerNote = tiff.readIfd(exifIfd)?.entry(TiffTag.MAKER_NOTE)?.offset() ?: return
        val signature = String(tiff.source.read(makerNote, SIGNATURE_BYTES), Charsets.ISO_8859_1)

        // The newer headers count offsets from the MakerNote and name their byte order; the old one doesn't.
        val base: Long
        val ifdAt: Long
        val reader: TiffReader
        val window = { from: Long -> WindowSource(tiff.source, from, (tiff.source.size ?: Long.MAX_VALUE) - from) }
        when {
            signature.startsWith(HEADER_OM) -> {
                base = makerNote
                ifdAt = IFD_AT_OM
                reader = tiff.over(window(base), signature.startsWith(II, HEADER_OM.length))
            }
            signature.startsWith(HEADER_OLYMPUS) -> {
                base = makerNote
                ifdAt = IFD_AT_OLYMPUS
                reader = tiff.over(window(base), signature.startsWith(II, HEADER_OLYMPUS.length))
            }
            signature.startsWith(HEADER_OLD) -> {
                base = 0
                ifdAt = makerNote + IFD_AT_OLD
                reader = tiff
            }
            else -> return
        }

        val entries = reader.readIfd(ifdAt) ?: return
        entries.entry(TAG_THUMBNAIL)?.let { collector.add(base + it.offset(), it.count, PreviewOrigin.MAKER_NOTE) }
        val settings = entries.entry(TAG_CAMERA_SETTINGS)?.let { reader.readIfd(it.offset()) } ?: return
        if (settings.entry(TAG_PREVIEW_VALID)?.short() == 0) return
        val start = settings.entry(TAG_PREVIEW_START)?.number() ?: return
        val length = settings.entry(TAG_PREVIEW_LENGTH)?.number() ?: return
        collector.add(base + start, length, PreviewOrigin.MAKER_NOTE)
    }
}
