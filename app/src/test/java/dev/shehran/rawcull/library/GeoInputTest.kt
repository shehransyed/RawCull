package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoInputTest {
    private fun point(text: String): GeoPoint {
        val result = GeoInput.parse(text)
        assertTrue("$text -> $result", result is GeoParse.Ok)
        return (result as GeoParse.Ok).point
    }

    private fun reason(text: String): String {
        val result = GeoInput.parse(text)
        assertTrue("$text -> $result", result is GeoParse.Invalid)
        return (result as GeoParse.Invalid).reason
    }

    private fun assertPoint(text: String, lat: Double, lon: Double, alt: Double? = null) {
        val p = point(text)
        assertEquals(text, lat, p.latitude, 1e-4)
        assertEquals(text, lon, p.longitude, 1e-4)
        assertEquals(text, alt, p.altitude)
    }

    @Test
    fun decimalDegrees() {
        assertPoint("40.4461, -79.9822", 40.4461, -79.9822)
        assertPoint("40.4461 -79.9822", 40.4461, -79.9822)
        assertPoint("  40.4461,-79.9822  ", 40.4461, -79.9822)
        assertPoint("40.4461° N, 79.9822° W", 40.4461, -79.9822)
        assertPoint("40.4461N 79.9822W", 40.4461, -79.9822)
        assertPoint("33.8688 S, 151.2093 E", -33.8688, 151.2093)
        assertPoint("N 40.4461 W 79.9822", 40.4461, -79.9822)
    }

    @Test
    fun lettersMayPutLongitudeFirst() {
        assertPoint("79.9822 W, 40.4461 N", 40.4461, -79.9822)
    }

    @Test
    fun degreesMinutesSeconds() {
        assertPoint("40°26'46.3\"N 79°58'56\"W", 40.446194, -79.982222)
        assertPoint("40d26'46.3\"N 79d58'56\"W", 40.446194, -79.982222)
        assertPoint("40 26' 46.3\" N, 79 58' 56\" W", 40.446194, -79.982222)
        assertPoint("40°26′46.3″N 79°58′56″W", 40.446194, -79.982222)
        assertPoint("40°26'46.3''N 79°58'56''W", 40.446194, -79.982222)
        assertPoint("N40°26'46.3\" W79°58'56\"", 40.446194, -79.982222)
    }

    @Test
    fun degreesDecimalMinutes() {
        assertPoint("40°26.767'N 79°58.933'W", 40.446117, -79.982217)
        assertPoint("40°26.767' N, 79°58.933' W", 40.446117, -79.982217)
    }

    @Test
    fun altitude() {
        assertPoint("40.4461, -79.9822, 120", 40.4461, -79.9822, 120.0)
        assertPoint("40.4461, -79.9822, 120 m", 40.4461, -79.9822, 120.0)
        assertPoint("40.4461 N, 79.9822 W 35.5m", 40.4461, -79.9822, 35.5)
    }

    @Test
    fun googleMapsLinks() {
        assertPoint("https://www.google.com/maps/@40.4461,-79.9822,15z", 40.4461, -79.9822)
        assertPoint(
            "https://www.google.com/maps/place/Foo/@40.5,-80.0,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d40.4461!4d-79.9822",
            40.4461, -79.9822,
        )
        assertPoint("https://www.google.com/maps?q=40.4461,-79.9822", 40.4461, -79.9822)
        assertPoint("https://www.google.com/maps?q=40.4461%2C-79.9822", 40.4461, -79.9822)
        assertPoint("https://maps.google.com/?ll=40.4461,-79.9822&z=10", 40.4461, -79.9822)
        assertPoint("https://www.google.com/maps/search/?api=1&query=40.4461%2C-79.9822", 40.4461, -79.9822)
        assertPoint("https://www.google.com/maps/@?api=1&map_action=map&center=40.4461%2C-79.9822&zoom=12", 40.4461, -79.9822)
        assertPoint("https://www.google.com/maps/place/40.4461,-79.9822", 40.4461, -79.9822)
    }

    @Test
    fun appleAndOpenStreetMapLinks() {
        assertPoint("https://maps.apple.com/?ll=40.4461,-79.9822&q=Pittsburgh", 40.4461, -79.9822)
        assertPoint("https://maps.apple.com/?q=40.4461,-79.9822", 40.4461, -79.9822)
        assertPoint("https://www.openstreetmap.org/#map=15/40.4461/-79.9822", 40.4461, -79.9822)
        assertPoint("https://www.openstreetmap.org/?mlat=40.4461&mlon=-79.9822#map=16/40.4/-79.9", 40.4461, -79.9822)
    }

    @Test
    fun geoUris() {
        assertPoint("geo:40.4461,-79.9822", 40.4461, -79.9822)
        assertPoint("geo:40.4461,-79.9822,120?z=15", 40.4461, -79.9822, 120.0)
        assertPoint("geo:0,0?q=40.4461,-79.9822(Home)", 40.4461, -79.9822)
        assertPoint("geo:40.4461,-79.9822;u=35", 40.4461, -79.9822)
    }

    @Test
    fun shortLinksAreNotFollowed() {
        val expected = GeoInput.SHORT_LINK
        assertEquals(expected, reason("https://maps.app.goo.gl/AbCdEf123"))
        assertEquals(expected, reason("https://goo.gl/maps/AbCdEf123"))
        assertEquals("Open the link and copy the full address or the coordinates.", expected)
    }

    @Test
    fun badInputIsRejectedWithAReason() {
        assertTrue(reason("").isNotBlank())
        assertTrue(reason("Pittsburgh").isNotBlank())
        assertTrue(reason("40.4461").isNotBlank())
        assertTrue(reason("40.4461, -79.9822, abc").isNotBlank())
        assertTrue(reason("https://www.google.com/maps/place/Pittsburgh").isNotBlank())
        assertTrue(reason("https://www.google.com/maps?q=Pittsburgh").isNotBlank())
    }

    @Test
    fun outOfRangeIsRejectedAndNeverSwapped() {
        assertTrue(reason("95.0, 10.0").contains("Latitude"))
        assertTrue(reason("10.0, 190.0").contains("Longitude"))
        // -79 is a valid longitude and 120 an invalid latitude: no guessing that they are the wrong way round.
        assertTrue(reason("120.0, 40.0").contains("Latitude"))
        assertTrue(reason("geo:95,10").contains("Latitude"))
        assertTrue(reason("https://maps.apple.com/?ll=95,10").contains("Latitude"))
        assertTrue(reason("40°75'00\"N 79°58'56\"W").isNotBlank())
    }

    @Test
    fun hemisphereLettersAndSignsMustAgree() {
        assertTrue(reason("-40.4461 N, 79.9822 W").contains("negative"))
        assertTrue(reason("40.4461 N, -79.9822 W").contains("negative"))
        assertTrue(reason("40.4461 N, 79.9822").isNotBlank())
        assertTrue(reason("40.4461 N, 10.0 S").isNotBlank())
        assertTrue(reason("40.4461 E, 79.9822 W").isNotBlank())
    }

    @Test
    fun format() {
        val p = GeoPoint(40.44619, -79.98222, 120.4)
        assertEquals("40.44619° N, 79.98222° W", GeoInput.format(p))
        assertEquals("33.86880° S, 151.20930° E", GeoInput.format(GeoPoint(-33.8688, 151.2093)))
        assertEquals("40.44619, -79.98222", GeoInput.plain(p))
        assertEquals("120 m", GeoInput.formatAltitude(p))
        assertNull(GeoInput.formatAltitude(GeoPoint(1.0, 2.0)))
    }

    @Test
    fun whatTheCardShowsParsesBack() {
        val p = GeoPoint(40.44619, -79.98222)
        assertEquals(p, point(GeoInput.format(p)))
        assertEquals(p, point(GeoInput.plain(p)))
    }
}
