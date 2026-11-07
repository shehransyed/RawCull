package dev.shehran.rawcull.preview

import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.preview.JpegBuilder.jpeg
import dev.shehran.rawcull.preview.TiffBuilder.Companion.long
import dev.shehran.rawcull.preview.TiffBuilder.Companion.short
import dev.shehran.rawcull.preview.TiffBuilder.Companion.undefined
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

private fun scan(format: PhotoFormat, bytes: ByteArray): PreviewScan =
    PreviewScanner.forFormat(format).scan(ArrayByteSource(bytes))

private fun PreviewScan.sizes(): Set<Pair<Int, Int>> = candidates.map { it.width to it.height }.toSet()

class JpegProbeTest {
    @Test
    fun `reads dimensions, orientation and the EOI`() {
        val bytes = JpegBuilder.withExif(jpeg(64, 48), orientation = 6)
        val info = JpegProbe.probe(ArrayByteSource(bytes), 0, bytes.size.toLong())!!
        assertEquals(64 to 48, info.width to info.height)
        assertEquals(6, info.orientation)
        assertEquals(bytes.size.toLong(), info.end)
    }

    @Test
    fun `big-endian EXIF reads the same`() {
        val bytes = JpegBuilder.withExif(jpeg(32, 32), orientation = 8, littleEndian = false)
        assertEquals(8, JpegProbe.probe(ArrayByteSource(bytes), 0, bytes.size.toLong())!!.orientation)
    }

    @Test
    fun `padding after the EOI is trimmed off`() {
        val real = jpeg(32, 32)
        val padded = real + ByteArray(100)
        assertEquals(real.size.toLong(), JpegProbe.probe(ArrayByteSource(padded), 0, padded.size.toLong())!!.end)
    }

    @Test
    fun `no EOI in the range means no candidate, unless it's the whole file`() {
        val cut = jpeg(64, 64).let { it.copyOf(it.size - 40) }
        assertNull(JpegProbe.probe(ArrayByteSource(cut), 0, cut.size.toLong()))
        assertNotNull(JpegProbe.probe(ArrayByteSource(cut), 0, cut.size.toLong(), requireEoi = false))
    }

    @Test
    fun `a lossless frame is raw data, not a preview`() {
        val lossless = JpegBuilder.withFrameMarker(jpeg(32, 32), 0xC3)
        assertNull(JpegProbe.probe(ArrayByteSource(lossless), 0, lossless.size.toLong()))
    }

    @Test
    fun `progressive and extended frames are accepted`() {
        listOf(0xC1, 0xC2).forEach { marker ->
            val bytes = JpegBuilder.withFrameMarker(jpeg(32, 24), marker)
            assertEquals(32, JpegProbe.probe(ArrayByteSource(bytes), 0, bytes.size.toLong())!!.width)
        }
    }
}

class JpegPreviewScannerTest {
    @Test
    fun `finds the image, its EXIF thumbnail and MPF previews`() {
        val withThumb = JpegBuilder.withExif(jpeg(400, 300), orientation = 3, thumbnail = jpeg(40, 30))
        val bytes = JpegBuilder.withMpf(withThumb, listOf(jpeg(160, 120)))
        val result = scan(PhotoFormat.JPEG, bytes)

        assertEquals(setOf(400 to 300, 40 to 30, 160 to 120), result.sizes())
        assertEquals(3, result.orientation)
        assertEquals(PreviewOrigin.JPEG_IMAGE, result.candidates.first().origin)
    }

    @Test
    fun `MPF entries that aren't previews stay out`() {
        val gainMap = 0x000000
        val bytes = JpegBuilder.withMpf(jpeg(200, 100), listOf(jpeg(100, 50)), attribute = gainMap)
        assertEquals(setOf(200 to 100), scan(PhotoFormat.JPEG, bytes).sizes())
    }

    @Test
    fun `a motion photo's trailing video doesn't lose the image`() {
        val bytes = jpeg(100, 100) + Random(1).nextBytes(20_000).map { if (it == 0xD9.toByte()) 0 else it }.toByteArray()
        assertEquals(setOf(100 to 100), scan(PhotoFormat.JPEG, bytes).sizes())
    }
}

class TiffPreviewScannerTest {
    private fun twoPreviews(littleEndian: Boolean): ByteArray {
        val tiff = TiffBuilder(littleEndian)
        val big = jpeg(320, 240)
        val small = jpeg(64, 48)
        val bigAt = tiff.blob(big)
        val smallAt = tiff.blob(small)
        val ifd1 = tiff.ifd(listOf(long(0x201, smallAt), long(0x202, small.size.toLong())))
        tiff.first(tiff.ifd(listOf(short(0x112, 6), long(0x201, bigAt), long(0x202, big.size.toLong())), next = ifd1))
        return tiff.bytes()
    }

    @Test
    fun `walks the IFD chain in both byte orders`() {
        listOf(true, false).forEach { littleEndian ->
            val result = scan(PhotoFormat.ARW, twoPreviews(littleEndian))
            assertEquals(setOf(320 to 240, 64 to 48), result.sizes())
            assertEquals(6, result.orientation)
        }
    }

    @Test
    fun `NEF-style preview in a SubIFD, one entry or several`() {
        val tiff = TiffBuilder()
        val a = jpeg(300, 200)
        val b = jpeg(150, 100)
        val aAt = tiff.blob(a)
        val bAt = tiff.blob(b)
        val subA = tiff.ifd(listOf(long(0x201, aAt), long(0x202, a.size.toLong())))
        val subB = tiff.ifd(listOf(long(0x201, bAt), long(0x202, b.size.toLong())))
        val table = tiff.blob(ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(subA.toInt()).putInt(subB.toInt()).array())
        tiff.first(tiff.ifd(listOf(TiffBuilder.Entry(0x14A, TiffBuilder.TYPE_LONG, 2, table))))
        assertEquals(setOf(300 to 200, 150 to 100), scan(PhotoFormat.NEF, tiff.bytes()).sizes())

        val single = TiffBuilder()
        val at = single.blob(a)
        val sub = single.ifd(listOf(long(0x201, at), long(0x202, a.size.toLong())))
        single.first(single.ifd(listOf(long(0x14A, sub))))
        assertEquals(setOf(300 to 200), scan(PhotoFormat.NEF, single.bytes()).sizes())
    }

    @Test
    fun `CR2-style strip with old-JPEG compression counts, a lossless one doesn't`() {
        val tiff = TiffBuilder()
        val preview = jpeg(500, 300)
        val raw = JpegBuilder.withFrameMarker(jpeg(100, 100), 0xC3)
        val previewAt = tiff.blob(preview)
        val rawAt = tiff.blob(raw)
        val ifd3 = tiff.ifd(listOf(short(0x103, 6), long(0x111, rawAt), long(0x117, raw.size.toLong())))
        tiff.first(tiff.ifd(listOf(short(0x103, 6), long(0x111, previewAt), long(0x117, preview.size.toLong())), next = ifd3))

        val result = scan(PhotoFormat.CR2, tiff.bytes())
        assertEquals(setOf(500 to 300), result.sizes())
        assertEquals(PreviewOrigin.TIFF_STRIP, result.candidates.single().origin)
    }

    @Test
    fun `garbage in the compression field's upper half is ignored`() {
        val tiff = TiffBuilder()
        val preview = jpeg(200, 100)
        val at = tiff.blob(preview)
        val garbage = TiffBuilder.Entry(0x103, 4, 1, 0xABCD0006L) // SHORT by the spec; written as a LONG here
        tiff.first(tiff.ifd(listOf(garbage, long(0x111, at), long(0x117, preview.size.toLong()))))
        assertEquals(setOf(200 to 100), scan(PhotoFormat.CR2, tiff.bytes()).sizes())
    }

    @Test
    fun `an uncompressed strip is left alone`() {
        val tiff = TiffBuilder()
        val preview = jpeg(200, 100)
        val at = tiff.blob(preview)
        tiff.first(tiff.ifd(listOf(short(0x103, 1), long(0x111, at), long(0x117, preview.size.toLong()))))
        assertTrue(scan(PhotoFormat.DNG, tiff.bytes()).candidates.isEmpty())
    }

    @Test
    fun `an IFD0 at the end of the file is found`() {
        val tiff = TiffBuilder()
        val preview = jpeg(200, 100)
        val at = tiff.blob(preview)
        tiff.pad(300_000) // Past the header slice
        tiff.first(tiff.ifd(listOf(short(0x112, 8), long(0x201, at), long(0x202, preview.size.toLong()))))
        val result = scan(PhotoFormat.DNG, tiff.bytes())
        assertEquals(setOf(200 to 100), result.sizes())
        assertEquals(8, result.orientation)
    }

    @Test
    fun `RW2 keeps JpgFromRaw's length in the entry count`() {
        val tiff = TiffBuilder(magic = 0x55)
        val preview = JpegBuilder.withExif(jpeg(400, 300), orientation = 1)
        val at = tiff.blob(preview)
        tiff.first(tiff.ifd(listOf(short(0x112, 3), undefined(0x2E, at, preview.size.toLong()))))
        val result = scan(PhotoFormat.RW2, tiff.bytes())
        assertEquals(PreviewOrigin.PANASONIC_JPG_FROM_RAW, result.candidates.single().origin)
        assertEquals(3, result.orientation)
    }

    @Test
    fun `ORF magic and the MakerNote preview`() {
        val tiff = TiffBuilder(magic = 0x4F52)
        val preview = jpeg(320, 240)
        val thumb = jpeg(64, 48)
        val thumbAt = tiff.blob(thumb)
        val ifd1 = tiff.ifd(listOf(long(0x201, thumbAt), long(0x202, thumb.size.toLong())))

        // The MakerNote: header, IFD, CameraSettings IFD and the JPEG, offsets counted from its start.
        val note = TiffBuilder(magic = 42)
        val headerBytes = 12
        val noteBody = ByteArray(headerBytes).also { "OLYMPUS\u0000II".toByteArray(Charsets.ISO_8859_1).copyInto(it) }
        val entries = 2 + 12 + 4
        val settingsAt = headerBytes + entries
        val settingsBytes = 2 + 3 * 12 + 4
        val jpegAt = settingsAt + settingsBytes
        val makerIfd = ByteBuffer.allocate(entries).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(0x2020.toShort()).putShort(13).putInt(1).putInt(settingsAt).putInt(0).array()
        val settings = ByteBuffer.allocate(settingsBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(3)
            .putShort(0x100).putShort(3).putInt(1).putShort(1).putShort(0)
            .putShort(0x101).putShort(4).putInt(1).putInt(jpegAt)
            .putShort(0x102).putShort(4).putInt(1).putInt(preview.size)
            .putInt(0).array()
        val makerNote = noteBody + makerIfd + settings + preview
        note.toString()

        val noteAt = tiff.blob(makerNote)
        val exif = tiff.ifd(listOf(undefined(0x927C, noteAt, makerNote.size.toLong())))
        tiff.first(tiff.ifd(listOf(short(0x112, 6), long(0x8769, exif)), next = ifd1))

        val result = scan(PhotoFormat.ORF, tiff.bytes())
        assertEquals(setOf(320 to 240, 64 to 48), result.sizes())
        assertEquals(6, result.orientation)
    }

    @Test
    fun `an unknown RAW falls back to searching for JPEGs`() {
        val bytes = Random(7).nextBytes(5000).map { if (it == 0xFF.toByte()) 0 else it }.toByteArray() + jpeg(300, 200) + ByteArray(100)
        val result = scan(PhotoFormat.RAW_OTHER, bytes)
        assertEquals(setOf(300 to 200), result.sizes())
        assertEquals(PreviewOrigin.SOI_SEARCH, result.candidates.single().origin)
    }
}

class Cr3PreviewScannerTest {
    private val thumb = jpeg(160, 120)
    private val preview = jpeg(648, 432)
    private val full = jpeg(1200, 800)

    @Test
    fun `finds all three JPEGs and the orientation`() {
        val result = scan(PhotoFormat.CR3, BmffBuilder.cr3(thumb, preview, full, orientation = 6))
        assertEquals(setOf(160 to 120, 648 to 432, 1200 to 800), result.sizes())
        assertEquals(6, result.orientation)
        assertEquals(
            setOf(PreviewOrigin.CR3_THUMBNAIL, PreviewOrigin.CR3_PREVIEW, PreviewOrigin.CR3_FULL),
            result.candidates.map { it.origin }.toSet(),
        )
    }

    @Test
    fun `stco works like co64`() {
        assertEquals(3, scan(PhotoFormat.CR3, BmffBuilder.cr3(thumb, preview, full, 1, wide = false)).candidates.size)
    }

    @Test
    fun `a HEIF-mode file keeps its JPEG previews`() {
        val notJpeg = ByteArray(5000) { 7 }
        val result = scan(PhotoFormat.CR3, BmffBuilder.cr3(thumb, preview, notJpeg, orientation = 1))
        assertEquals(setOf(160 to 120, 648 to 432), result.sizes())
    }

    @Test
    fun `another MP4 is not a CR3`() {
        val bytes = BmffBuilder.ftyp("isom") + BmffBuilder.box("moov", ByteArray(32))
        assertTrue(scan(PhotoFormat.CR3, bytes).candidates.isEmpty())
    }

    @Test
    fun `a box claiming to run past the file stops the walk`() {
        val bytes = BmffBuilder.ftyp() + ByteBuffer.allocate(8).putInt(Int.MAX_VALUE).put("moov".toByteArray()).array()
        assertTrue(scan(PhotoFormat.CR3, bytes).candidates.isEmpty())
    }
}

class RafPreviewScannerTest {
    @Test
    fun `reads the header's offset, and orientation and MPF from the JPEG`() {
        val inner = JpegBuilder.withMpf(JpegBuilder.withExif(jpeg(600, 400), orientation = 6), listOf(jpeg(160, 120)))
        val result = scan(PhotoFormat.RAF, RafBuilder.raf(inner))
        assertEquals(setOf(600 to 400, 160 to 120), result.sizes())
        assertEquals(6, result.orientation)
    }

    @Test
    fun `a length past the end of the file means no candidate`() {
        val bytes = RafBuilder.raf(jpeg(100, 100), declaredLength = 10_000_000)
        assertTrue(scan(PhotoFormat.RAF, bytes).candidates.isEmpty())
    }
}

/** D1 of the parsers: nothing a file can say makes them throw or hang. */
class MalformedFileTest {
    private val everyFormat = PhotoFormat.entries

    @Test
    fun `an IFD cycle ends`() {
        val tiff = TiffBuilder()
        val preview = jpeg(100, 100)
        val at = tiff.blob(preview)
        val ifd = tiff.ifd(listOf(long(0x201, at), long(0x202, preview.size.toLong())))
        tiff.patchNext(ifd, 2, ifd)
        tiff.first(ifd)
        assertEquals(1, scan(PhotoFormat.ARW, tiff.bytes()).candidates.size)
    }

    @Test
    fun `SubIFDs pointing back at IFD0 end`() {
        val tiff = TiffBuilder()
        val at = tiff.position + 2 + 12 + 4
        tiff.first(tiff.ifd(listOf(long(0x14A, tiff.position))))
        assertEquals(at, tiff.position)
        assertTrue(scan(PhotoFormat.NEF, tiff.bytes()).candidates.isEmpty())
    }

    @Test
    fun `offsets past the end, zero lengths and garbage candidates are dropped`() {
        val cases = listOf(1_000_000L to 500L, 8L to 0L, 8L to 400L)
        cases.forEach { (offset, length) ->
            val tiff = TiffBuilder()
            tiff.pad(600)
            tiff.first(tiff.ifd(listOf(long(0x201, offset), long(0x202, length))))
            assertTrue(scan(PhotoFormat.ARW, tiff.bytes()).candidates.isEmpty())
        }
    }

    @Test
    fun `a length over the cap is refused before anything is allocated`() {
        val tiff = TiffBuilder()
        tiff.first(tiff.ifd(listOf(long(0x201, 8), long(0x202, 0xFFFFFFFFL))))
        assertTrue(scan(PhotoFormat.ARW, tiff.bytes()).candidates.isEmpty())
    }

    @Test
    fun `an absurd entry count is refused`() {
        val bytes = ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8).putShort(0xFFFF.toShort()).array()
        assertTrue(scan(PhotoFormat.ARW, bytes).candidates.isEmpty())
    }

    @Test
    fun `every format survives every truncation of a valid file`() {
        val tiff = TiffBuilder().apply {
            val preview = jpeg(32, 32)
            val at = blob(preview)
            first(ifd(listOf(short(0x112, 6), long(0x201, at), long(0x202, preview.size.toLong()))))
        }.bytes()
        val files = listOf(
            tiff,
            BmffBuilder.cr3(jpeg(16, 16), jpeg(32, 32), jpeg(48, 48), 6),
            RafBuilder.raf(jpeg(32, 32)),
            JpegBuilder.withMpf(JpegBuilder.withExif(jpeg(32, 32), 6, jpeg(8, 8)), listOf(jpeg(16, 16))),
        )
        files.forEach { file ->
            (0 until file.size step 7).forEach { length ->
                everyFormat.forEach { PreviewScanner.forFormat(it).scan(ArrayByteSource(file.copyOf(length))) }
            }
        }
    }

    @Test
    fun `every format survives random corruption`() {
        val base = BmffBuilder.cr3(jpeg(16, 16), jpeg(32, 32), jpeg(48, 48), 6)
        val tiff = TiffBuilder().apply {
            val preview = jpeg(32, 32)
            val at = blob(preview)
            first(ifd(listOf(short(0x112, 6), long(0x201, at), long(0x202, preview.size.toLong()))))
        }.bytes()
        val random = Random(42)
        repeat(300) {
            listOf(base, tiff).forEach { file ->
                val damaged = file.copyOf()
                repeat(20) { damaged[random.nextInt(minOf(damaged.size, 400))] = random.nextInt().toByte() }
                everyFormat.forEach { PreviewScanner.forFormat(it).scan(ArrayByteSource(damaged)) }
            }
        }
    }

    @Test
    fun `empty and tiny files give an empty scan`() {
        everyFormat.forEach { format ->
            listOf(ByteArray(0), ByteArray(3), byteArrayOf(0xFF.toByte(), 0xD8.toByte())).forEach {
                assertTrue(PreviewScanner.forFormat(format).scan(ArrayByteSource(it)).candidates.isEmpty())
            }
        }
    }
}
