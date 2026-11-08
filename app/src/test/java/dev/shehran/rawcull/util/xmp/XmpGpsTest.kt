package dev.shehran.rawcull.util.xmp

import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmpGpsTest {
    private fun load(name: String) = XmpDocument.parse(XmpFixtures.bytes(name)).getOrThrow()

    private fun parse(xml: String) = XmpDocument.parse(xml.toByteArray()).getOrThrow()

    private fun XmpDocument.saved() = XmpDocument.parse(toBytes()).getOrThrow()

    private fun description(attributes: String, body: String = "") = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
         <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <rdf:Description rdf:about="" xmlns:exif="http://ns.adobe.com/exif/1.0/"
            xmlns:xmp="http://ns.adobe.com/xap/1.0/" $attributes>$body</rdf:Description>
         </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    private fun state(location: GeoPoint?) =
        CullState(0, CullStatus.UNFLAGGED, ColorLabel.NONE, emptyList(), location)

    private fun assertPoint(expected: GeoPoint, actual: GeoPoint?) {
        assertNotNull(actual)
        assertEquals(expected.latitude, actual!!.latitude, 1e-7)
        assertEquals(expected.longitude, actual.longitude, 1e-7)
        if (expected.altitude == null) assertNull(actual.altitude) else assertEquals(expected.altitude!!, actual.altitude!!, 0.005)
    }

    @Test
    fun readsLightroomsFixture() {
        // exif:GPSLatitude="23,46.1234N" exif:GPSLongitude="90,22.5678E"
        val point = XmpGps.read(load(XmpFixtures.LIGHTROOM))
        assertPoint(GeoPoint(23.0 + 46.1234 / 60.0, 90.0 + 22.5678 / 60.0), point)
    }

    @Test
    fun noLocationInTheOtherFixtures() {
        (XmpFixtures.valid - XmpFixtures.LIGHTROOM).forEach { assertNull(it, XmpGps.read(load(it))) }
    }

    @Test
    fun writesInLightroomsFormat() {
        val doc = XmpDocument.newEmpty("RawCull test")
        XmpGps.write(doc, GeoPoint(40.446194, -79.982222, 123.45))
        assertEquals("2.2.0.0", doc.valueOf(XmpGps.VERSION_ID))
        assertEquals("40,26.771640N", doc.valueOf(XmpGps.LATITUDE))
        assertEquals("79,58.933320W", doc.valueOf(XmpGps.LONGITUDE))
        assertEquals("12345/100", doc.valueOf(XmpGps.ALTITUDE))
        assertEquals("0", doc.valueOf(XmpGps.ALTITUDE_REF))
    }

    @Test
    fun roundTripsEveryHemisphereAndBelowSeaLevel() {
        listOf(
            GeoPoint(40.44619, -79.98222, 120.0),
            GeoPoint(-33.8688, 151.2093, 58.25),
            GeoPoint(-0.5, -0.25),
            GeoPoint(0.0, 0.0),
            GeoPoint(31.5, 35.5, -430.5), // the Dead Sea shore
            GeoPoint(89.9999999, 179.9999999),
        ).forEach { point ->
            val doc = XmpDocument.newEmpty("RawCull test")
            XmpGps.write(doc, point)
            assertPoint(point, XmpGps.read(doc.saved()))
        }
    }

    @Test
    fun minutesNeverPrintAsSixty() {
        assertEquals("41,0.000000N", XmpGps.degreesMinutes(40.99999999999, "N", "S"))
    }

    @Test
    fun altitudeIsOptionalAndRemovedWhenGone() {
        val doc = XmpDocument.newEmpty("RawCull test")
        XmpGps.write(doc, GeoPoint(1.0, 2.0, 50.0))
        XmpGps.write(doc, GeoPoint(1.0, 2.0))
        assertFalse(doc.has(XmpGps.ALTITUDE))
        assertFalse(doc.has(XmpGps.ALTITUDE_REF))
        assertNull(XmpGps.read(doc.saved())!!.altitude)
    }

    @Test
    fun darktableStyleIsRead() {
        // exiv2 writes the same strings, with the rational altitude
        val doc = parse(
            description(
                """exif:GPSVersionID="2.2.0.0" exif:GPSLatitude="40,26.76766N" exif:GPSLongitude="79,58.93332W"
                   exif:GPSAltitude="12345/100" exif:GPSAltitudeRef="0"""",
            ),
        )
        assertPoint(GeoPoint(40.0 + 26.76766 / 60, -(79.0 + 58.93332 / 60), 123.45), XmpGps.read(doc))
    }

    @Test
    fun secondsFormAndElementFormAreRead() {
        val seconds = parse(description("""exif:GPSLatitude="40,26,46.3N" exif:GPSLongitude="79,58,56W" exif:GPSAltitude="30/1" exif:GPSAltitudeRef="1""""))
        assertPoint(GeoPoint(40.0 + 26.0 / 60 + 46.3 / 3600, -(79.0 + 58.0 / 60 + 56.0 / 3600), -30.0), XmpGps.read(seconds))

        val elements = parse(
            description(
                "",
                "<exif:GPSLatitude>40,26.767S</exif:GPSLatitude><exif:GPSLongitude>79,58.933E</exif:GPSLongitude>",
            ),
        )
        assertPoint(GeoPoint(-(40.0 + 26.767 / 60), 79.0 + 58.933 / 60), XmpGps.read(elements))
    }

    @Test
    fun decimalAndSloppyValues() {
        assertPoint(GeoPoint(40.5, -79.5), XmpGps.read(parse(description("""exif:GPSLatitude="40.5N" exif:GPSLongitude="79.5W""""))))
        assertNull("no longitude", XmpGps.read(parse(description("""exif:GPSLatitude="40,26.7N""""))))
        assertNull("wrong axis", XmpGps.read(parse(description("""exif:GPSLatitude="40,26.7E" exif:GPSLongitude="79,58.9W""""))))
        assertNull("out of range", XmpGps.read(parse(description("""exif:GPSLatitude="95,0.0N" exif:GPSLongitude="79,58.9W""""))))
        assertNull("minutes", XmpGps.read(parse(description("""exif:GPSLatitude="40,75.0N" exif:GPSLongitude="79,58.9W""""))))
        assertNull("junk", XmpGps.read(parse(description("""exif:GPSLatitude="north" exif:GPSLongitude="west""""))))
    }

    @Test
    fun mapperWritesOnlyWhenGpsIsAmongTheFields() {
        val doc = XmpDocument.newEmpty("RawCull test")
        val point = GeoPoint(40.0, -79.0)
        XmpMapper.apply(doc, state(point), XmpPrefs(), setOf(XmpField.RATING))
        assertFalse(doc.has(XmpGps.LATITUDE))

        XmpMapper.apply(doc, state(point), XmpPrefs(), setOf(XmpField.GPS))
        assertPoint(point, XmpMapper.read(doc.saved()).location)

        XmpMapper.apply(doc, state(null), XmpPrefs(), setOf(XmpField.GPS))
        assertNull(XmpMapper.read(doc.saved()).location)
        assertFalse(doc.has(XmpGps.VERSION_ID))
    }

    @Test
    fun aNewSidecarSkipsAnUneditedLocation() {
        assertFalse(XmpField.GPS in XmpField.NEW_SIDECAR)
        val doc = XmpDocument.newEmpty("RawCull test")
        XmpMapper.apply(doc, state(GeoPoint(40.0, -79.0)), XmpPrefs(), XmpField.NEW_SIDECAR)
        assertFalse(doc.has(XmpGps.LATITUDE))
    }

    @Test
    fun unknownSidecarContentSurvivesALocationEdit() {
        val original = load(XmpFixtures.LIGHTROOM)
        val before = original.toBytes().toString(Charsets.UTF_8)
        assertTrue(before.contains("crs:"))

        XmpMapper.apply(original, state(GeoPoint(10.0, 20.0, 5.0)), XmpPrefs(), setOf(XmpField.GPS))
        val after = original.toBytes().toString(Charsets.UTF_8)

        assertPoint(GeoPoint(10.0, 20.0, 5.0), XmpGps.read(XmpDocument.parse(after.toByteArray()).getOrThrow()))
        // Everything that was there stays, apart from the location and the metadata date.
        val keep = Regex("""[\w-]+:[\w-]+="[^"]*"""").findAll(before).map { it.value }
            .filterNot { it.startsWith("exif:GPS") || it.startsWith("xmp:MetadataDate") }
        keep.forEach { assertTrue(it, after.contains(it)) }
        assertEquals(XmpMapper.read(load(XmpFixtures.LIGHTROOM)).tags, XmpMapper.read(XmpDocument.parse(after.toByteArray()).getOrThrow()).tags)
    }

    @Test
    fun clearingLeavesOtherExifPropertiesAlone() {
        val doc = parse(description("""exif:GPSLatitude="40,26.7N" exif:GPSLongitude="79,58.9W" exif:DateTimeOriginal="2024-01-01T10:00:00""""))
        XmpGps.write(doc, null)
        val saved = doc.saved()
        assertNull(XmpGps.read(saved))
        assertEquals("2024-01-01T10:00:00", saved.valueOf(XmpProps.EXIF_DATE_TIME_ORIGINAL))
    }
}
