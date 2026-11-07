package dev.shehran.rawcull.ui.components

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoupeZoomStateTest {
    /** A 6000 × 4000 source in a 1500 × 1000 view: fit is 25 %. */
    private fun large() = LoupeZoomState().apply {
        setView(1500, 1000)
        setFull(6000, 4000)
    }

    /** Sony's 1616 × 1080 preview in a 2560 × 1600 view: fit is above 100 %. */
    private fun small() = LoupeZoomState().apply {
        setView(2560, 1600)
        setFull(1616, 1080)
    }

    @Test
    fun `starts at fit with the real percentage`() {
        val zoom = large()
        assertTrue(zoom.isAtFit)
        assertEquals(0.25f, zoom.scale, 1e-4f)
        assertEquals("25%", zoomPillText(zoom))
    }

    @Test
    fun `the toggle goes to one source pixel per screen pixel and back`() {
        val zoom = large()
        zoom.toggle(focus = null)
        assertFalse(zoom.isAtFit)
        assertEquals(1f, zoom.scale, 1e-4f)
        assertEquals("100%", zoomPillText(zoom))
        zoom.toggle(focus = null)
        assertTrue(zoom.isAtFit)
        assertEquals(0f, zoom.panX, 0f)
    }

    @Test
    fun `zooming at a point keeps that point under it`() {
        val zoom = large()
        // A tap 300 px right of and 200 px below the centre, on the image at fit.
        val tap = Offset(750f + 300f, 500f + 200f)
        zoom.toggle(tap)
        // That point was 1200 × 800 source px from the centre; at 100 % it must still be at the tap.
        assertEquals(300f - 1200f, zoom.panX, 0.5f)
        assertEquals(200f - 800f, zoom.panY, 0.5f)
    }

    @Test
    fun `pan stays inside the image`() {
        val zoom = large()
        zoom.toggle(focus = null)
        zoom.transform(Offset(100_000f, -100_000f), 1f, centroid = null)
        // At 100 % the image is 6000 wide in a 1500 view: 2250 of slack each way.
        assertEquals(2250f, zoom.panX, 0.5f)
        assertEquals(-1500f, zoom.panY, 0.5f)
    }

    @Test
    fun `pinch runs from fit to 200 percent`() {
        val zoom = large()
        zoom.transform(Offset.Zero, 100f, centroid = null)
        assertEquals(2f, zoom.scale, 1e-4f)
        zoom.transform(Offset.Zero, 0.0001f, centroid = null)
        assertTrue(zoom.isAtFit)
        assertEquals(0.25f, zoom.scale, 1e-4f)
    }

    @Test
    fun `a small preview says so and toggles to twice fit`() {
        val zoom = small()
        val fit = zoom.fitScale
        assertTrue(zoom.isPreviewOnly)
        assertTrue(fit > 1f)
        zoom.toggle(focus = null)
        assertEquals(fit * 2, zoom.scale, 1e-4f)
        assertEquals("${zoom.percent}% · Preview only 1616 × 1080", zoomPillText(zoom))
    }

    @Test
    fun `a preview smaller than the sensor says so even when it's larger than fit`() {
        val zoom = LoupeZoomState().apply {
            setView(900, 970)
            setFull(1080, 1616)
        }
        assertFalse(zoom.isPreviewOnly)
        assertEquals("${zoom.percent}% · Preview only 1080 × 1616", zoomPillText(zoom, sensorLongEdge = 6000))
        // A companion JPEG a few pixels short of the sensor is full resolution.
        zoom.setFull(6000, 3984)
        assertEquals("${zoom.percent}%", zoomPillText(zoom, sensorLongEdge = 6000))
    }

    @Test
    fun `a resize at fit stays at fit`() {
        val zoom = large()
        zoom.setView(750, 500)
        assertTrue(zoom.isAtFit)
        assertEquals(0.125f, zoom.scale, 1e-4f)
    }

    @Test
    fun `a resize while zoomed keeps the zoom and clamps the pan`() {
        val zoom = large()
        zoom.toggle(focus = null)
        zoom.transform(Offset(2000f, 0f), 1f, centroid = null)
        zoom.setView(5000, 1000) // Wider: less slack
        assertEquals(1f, zoom.scale, 1e-4f)
        assertEquals(500f, zoom.panX, 0.5f)
    }

    @Test
    fun `nothing happens before the sizes are known`() {
        val zoom = LoupeZoomState()
        zoom.toggle(focus = null)
        zoom.transform(Offset(10f, 10f), 3f, centroid = null)
        assertTrue(zoom.isAtFit)
        assertEquals("100%", zoomPillText(zoom))
    }
}
