package dev.shehran.rawcull.preview

import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.preview.JpegBuilder.jpeg
import dev.shehran.rawcull.preview.TiffBuilder.Companion.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ByteSourceTest {
    private val bytes = ByteArray(300_000) { (it % 251).toByte() }

    @Test
    fun `block cache returns the same bytes across block edges`() {
        val cached = BlockCachedSource(ArrayByteSource(bytes), blockSize = 1024)
        assertArrayEquals(bytes.copyOfRange(1000, 3100), cached.read(1000, 2100))
        assertArrayEquals(bytes.copyOfRange(299_990, 300_000), cached.read(299_990, 100))
        assertEquals(0, cached.read(400_000, 10).size)
    }

    @Test
    fun `an IFD walk's small reads coalesce into block reads`() {
        val counted = CountingSource(ArrayByteSource(bytes))
        val cached = BlockCachedSource(counted)
        repeat(200) { cached.read(it * 12L, 12) }
        repeat(200) { cached.read(70_000 + it * 12L, 12) }
        assertEquals(2, counted.reads)
    }

    @Test
    fun `the header slice is one read and serves what follows`() {
        val counted = CountingSource(ArrayByteSource(bytes))
        val cached = BlockCachedSource(counted)
        cached.preload(0, HEADER_SLICE_BYTES)
        repeat(100) { cached.read(it * 2000L, 16) }
        assertEquals(1, counted.reads)
    }

    @Test
    fun `a whole preview bypasses the cache`() {
        val counted = CountingSource(ArrayByteSource(bytes))
        val cached = BlockCachedSource(counted)
        assertEquals(290_000, cached.read(10, 290_000).size)
        assertEquals(1, counted.reads)
        assertEquals(290_000L, counted.bytesRead)
    }

    @Test
    fun `the least recently used block goes first`() {
        val counted = CountingSource(ArrayByteSource(bytes))
        val cached = BlockCachedSource(counted, blockSize = 1024, maxBlocks = 2)
        cached.read(0, 1)
        cached.read(1024, 1)
        cached.read(0, 1)
        cached.read(2048, 1) // Pushes out block 1, not block 0
        cached.read(0, 1)
        assertEquals(3, counted.reads)
        cached.read(1024, 1)
        assertEquals(4, counted.reads)
    }

    @Test
    fun `stream tiers stop at the tier that holds what was asked for`() {
        val stream = StreamTierSource({ ByteArrayInputStream(ByteArray(5_000_000)) })
        stream.read(0, 100)
        assertEquals(HEADER_SLICE_BYTES, stream.bytesRead)
        stream.read(300_000, 100)
        assertEquals(2 * 1024 * 1024, stream.bytesRead)
        assertNull(stream.size)
        stream.read(3_000_000, 100)
        assertEquals(5_000_000L, stream.size)
    }

    @Test
    fun `the stream finds a preview past 2 MB`() {
        val preview = jpeg(200, 100)
        val tiff = TiffBuilder()
        tiff.pad(3 * 1024 * 1024)
        val at = tiff.blob(preview)
        tiff.first(tiff.ifd(listOf(long(0x201, at), long(0x202, preview.size.toLong()))))
        val file = tiff.bytes()

        val source = StreamTierSource({ ByteArrayInputStream(file) })
        val scan = PreviewScanner.forFormat(PhotoFormat.DNG).scan(BlockCachedSource(source))
        assertEquals(200, scan.candidates.single().width)
        assertArrayEquals(preview, source.read(at, preview.size))
    }

    @Test
    fun `nothing past the cap is read`() {
        val stream = StreamTierSource({ ByteArrayInputStream(ByteArray(10_000)) }, tiers = listOf(1000L), cap = 4000)
        assertEquals(0, stream.read(5000, 100).size)
        assertEquals(4000, stream.bytesRead)
    }

    @Test
    fun `a window counts from its own start`() {
        val window = WindowSource(ArrayByteSource(bytes), 1000, 50)
        assertArrayEquals(bytes.copyOfRange(1010, 1050), window.read(10, 100))
        assertEquals(0, window.read(50, 1).size)
    }
}

class PreviewChooserTest {
    private fun candidate(width: Int, height: Int, length: Long) =
        PreviewCandidate(offset = length, length = length, width = width, height = height, origin = PreviewOrigin.TIFF_IFD)

    private val sonyThumb = candidate(160, 120, 8_000)
    private val sonyPreview = candidate(1616, 1080, 400_000)
    private val camera = candidate(6000, 4000, 9_000_000)
    private val cameraPreview = candidate(1920, 1280, 500_000)
    private val raw = PreviewScan(listOf(sonyThumb, sonyPreview), orientation = 6)
    private val companion = PreviewScan(listOf(camera, cameraPreview), orientation = 1)
    private val pool = PreviewChooser.pool(raw, companion)

    @Test
    fun `a thumbnail takes the fewest bytes that are big enough`() {
        assertEquals(sonyPreview, PreviewChooser.pick(pool, PreviewRequest.Thumbnail(512))?.candidate)
    }

    @Test
    fun `a companion is not decoded in full for a thumbnail`() {
        val pair = PreviewChooser.pool(PreviewScan(listOf(sonyThumb)), PreviewScan(listOf(camera, cameraPreview)))
        assertEquals(cameraPreview, PreviewChooser.pick(pair, PreviewRequest.Thumbnail(512))?.candidate)
    }

    @Test
    fun `fit goes by the longer side of the box`() {
        assertEquals(cameraPreview, PreviewChooser.pick(pool, PreviewRequest.Fit(1800, 1000))?.candidate)
        assertEquals(camera, PreviewChooser.pick(pool, PreviewRequest.Fit(1000, 2560))?.candidate)
    }

    @Test
    fun `when nothing is big enough, the largest`() {
        assertEquals(camera, PreviewChooser.pick(pool, PreviewRequest.Fit(8000, 8000))?.candidate)
    }

    @Test
    fun `full resolution takes the most pixels`() {
        val best = PreviewChooser.pick(pool, PreviewRequest.FullResolution)!!
        assertEquals(camera, best.candidate)
        assertEquals(PreviewFile.COMPANION, best.file)
    }

    @Test
    fun `ties go to the primary`() {
        val same = candidate(2000, 1000, 100_000)
        val tied = PreviewChooser.pool(PreviewScan(listOf(same)), PreviewScan(listOf(same)))
        assertEquals(PreviewFile.PRIMARY, PreviewChooser.pick(tied, PreviewRequest.Thumbnail(512))?.file)
        assertEquals(PreviewFile.PRIMARY, PreviewChooser.pick(tied, PreviewRequest.FullResolution)?.file)
    }

    @Test
    fun `each candidate keeps its own file's orientation`() {
        assertEquals(6, pool.first { it.candidate == sonyPreview }.orientation)
        assertEquals(1, pool.first { it.candidate == camera }.orientation)
    }

    @Test
    fun `the ranking is the fallback order`() {
        val ranked = PreviewChooser.ranked(pool, PreviewRequest.Thumbnail(512)).map { it.candidate }
        assertEquals(listOf(sonyPreview, cameraPreview, camera, sonyThumb), ranked)
    }

    @Test
    fun `an empty pool has no pick`() {
        assertNull(PreviewChooser.pick(emptyList(), PreviewRequest.FullResolution))
    }
}

class RegionMathTest {
    private val w = 600
    private val h = 400

    /** Where each orientation sends the source's top-left corner, on the upright image. */
    @Test
    fun `all eight orientations place the top-left corner correctly`() {
        val expected = mapOf(
            1 to (0f to 0f),
            2 to (600f to 0f),
            3 to (600f to 400f),
            4 to (0f to 400f),
            5 to (0f to 0f),
            6 to (400f to 0f),
            7 to (400f to 600f),
            8 to (0f to 600f),
        )
        expected.forEach { (orientation, corner) ->
            val t = RegionMath.transform(w, h, orientation)
            assertEquals("orientation $orientation", corner, t.x(0f, 0f) to t.y(0f, 0f))
        }
    }

    @Test
    fun `a rectangle survives the round trip in every orientation`() {
        val rect = SourceRect(50, 30, 200, 120)
        (1..8).forEach { orientation ->
            val display = RegionMath.sourceToDisplay(rect, w, h, orientation)
            assertEquals("orientation $orientation", rect, RegionMath.displayToSource(display, w, h, orientation))
        }
    }

    @Test
    fun `display size swaps for the rotated orientations only`() {
        (1..4).forEach { assertEquals(600, RegionMath.displayWidth(w, h, it)) }
        (5..8).forEach { assertEquals(400, RegionMath.displayWidth(w, h, it)) }
    }

    @Test
    fun `a rotated portrait's top strip comes from the source's left edge`() {
        // Orientation 6: the top of the upright image is the left of the stored one.
        val top = DisplayRect(0f, 0f, 400f, 100f)
        assertEquals(SourceRect(0, 0, 100, 400), RegionMath.displayToSource(top, w, h, 6))
        // Orientation 8: the bottom of the upright image is the left of the stored one.
        val bottom = DisplayRect(0f, 500f, 400f, 600f)
        assertEquals(SourceRect(0, 0, 100, 400), RegionMath.displayToSource(bottom, w, h, 8))
    }

    @Test
    fun `at 100 percent the visible rectangle is the view, centred`() {
        val visible = RegionMath.visibleRect(6000, 4000, 1000, 800, scale = 1f, panX = 0f, panY = 0f)
        assertEquals(DisplayRect(2500f, 1600f, 3500f, 2400f), visible)
    }

    @Test
    fun `at 200 percent half as much is visible`() {
        val visible = RegionMath.visibleRect(6000, 4000, 1000, 800, scale = 2f, panX = 0f, panY = 0f)
        assertEquals(500f, visible.width)
        assertEquals(400f, visible.height)
    }

    @Test
    fun `panning right shows what's to the left`() {
        val visible = RegionMath.visibleRect(6000, 4000, 1000, 800, scale = 1f, panX = 200f, panY = 0f)
        assertEquals(2300f, visible.left)
    }

    @Test
    fun `the visible rectangle stops at the image edge`() {
        val visible = RegionMath.visibleRect(6000, 4000, 1000, 800, scale = 1f, panX = 2500f, panY = 1600f)
        assertEquals(0f, visible.left)
        assertEquals(0f, visible.top)
    }

    @Test
    fun `the margin is a quarter each side, cut at the image`() {
        val rect = RegionMath.withMargin(DisplayRect(100f, 100f, 500f, 300f), 0.25f, 550, 1000)
        assertEquals(DisplayRect(0f, 50f, 550f, 350f), rect)
    }

    @Test
    fun `the sample size never goes below what the zoom needs`() {
        assertEquals(1, RegionMath.sampleSize(2f))
        assertEquals(1, RegionMath.sampleSize(1f))
        assertEquals(1, RegionMath.sampleSize(0.6f))
        assertEquals(2, RegionMath.sampleSize(0.5f))
        assertEquals(2, RegionMath.sampleSize(0.3f))
        assertEquals(4, RegionMath.sampleSize(0.25f))
    }

    @Test
    fun `sample size to fit allows for rotation`() {
        assertEquals(2, RegionMath.sampleSizeToFit(6000, 4000, 1, 2560, 1600))
        assertEquals(4, RegionMath.sampleSizeToFit(6000, 4000, 6, 1000, 1400))
    }

    @Test
    fun `a tile at 100 percent covers one and a half viewports`() {
        val tile = RegionMath.planTile(6000, 4000, 1, 1000, 800, 1f, 0f, 0f)!!
        assertEquals(1500, tile.source.width)
        assertEquals(1200, tile.source.height)
        assertEquals(1, tile.sampleSize)
    }

    @Test
    fun `a tile at a corner stays inside the image`() {
        val tile = RegionMath.planTile(6000, 4000, 6, 1000, 800, 1f, 1500f, 2600f)!!
        assertTrue(tile.source.left >= 0 && tile.source.top >= 0)
        assertTrue(tile.source.right <= 6000 && tile.source.bottom <= 4000)
    }

    @Test
    fun `a tile for a rotated photo maps back onto what's on screen`() {
        val tile = RegionMath.planTile(6000, 4000, 6, 1000, 800, 1f, 0f, 0f)!!
        val display = RegionMath.sourceToDisplay(tile.source, 6000, 4000, 6)
        val visible = RegionMath.visibleRect(4000, 6000, 1000, 800, 1f, 0f, 0f)
        assertTrue(display.left <= visible.left && display.right >= visible.right)
        assertTrue(display.top <= visible.top && display.bottom >= visible.bottom)
    }

    @Test
    fun `a pan beyond the image gets no tile`() {
        assertNull(RegionMath.planTile(6000, 4000, 1, 1000, 800, 1f, 100_000f, 0f))
    }

    @Test
    fun `the zoom toggle is true 100 percent, or twice fit for a small source`() {
        val big = RegionMath.fitScale(6000, 4000, 2000, 1200)
        assertEquals(1f, RegionMath.toggleScale(big))
        assertFalse(RegionMath.isPreviewOnly(big))
        val small = RegionMath.fitScale(1616, 1080, 2560, 1600)
        assertTrue(RegionMath.isPreviewOnly(small))
        assertEquals(small * 2, RegionMath.toggleScale(small))
    }

    @Test
    fun `the zoom range reaches 200 percent, or twice fit`() {
        assertEquals(2f, RegionMath.maxScale(0.3f))
        assertEquals(3f, RegionMath.maxScale(1.5f))
    }

    @Test
    fun `a pan is clamped to the image and zero when it fits`() {
        assertEquals(1000f, RegionMath.clampPan(5000f, 3000, 1000, 1f))
        assertEquals(-1000f, RegionMath.clampPan(-5000f, 3000, 1000, 1f))
        assertEquals(0f, RegionMath.clampPan(300f, 3000, 1000, 0.2f))
    }

    @Test
    fun `zooming keeps the point under the finger in place`() {
        val pan = RegionMath.panForZoom(focus = 900f, viewExtent = 1000, pan = 0f, oldScale = 0.2f, newScale = 1f)
        // The point 400 px right of centre was 2000 source pixels out; at 100 % it must still be at 900.
        assertEquals(-1600f, pan)
    }

    @Test
    fun `a tile is only needed past the base preview's resolution`() {
        assertFalse(RegionMath.needsTile(0.2f, 1600, 6000))
        assertTrue(RegionMath.needsTile(1f, 1600, 6000))
    }
}
