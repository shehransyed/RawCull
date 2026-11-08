package dev.shehran.rawcull.util.xmp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.shehran.rawcull.data.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android's XML parser and serialiser are not the JDK's, so the JVM tests can't vouch for what ends
 * up in a file on a device. These run the same fixtures through the platform implementations.
 */
@RunWith(AndroidJUnit4::class)
class XmpDocumentDeviceTest {
    private val fixtures = listOf(
        "lightroom_classic_develop.xmp",
        "darktable.xmp",
        "bridge_reject.xmp",
        "element_form.xmp",
        "multi_description.xmp",
        "rawcull_v0_legacy.xmp",
        "no_xpacket.xmp",
    )

    private fun bytes(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("xmp/$name").use { it.readBytes() }

    private fun load(name: String) = XmpDocument.parse(bytes(name)).getOrThrow()

    /** Attribute assignments and element text, which is what must survive, whatever the formatting. */
    private fun content(xml: String): List<String> {
        val body = xml.replace(Regex("""<xmp:Rating>[^<]*</xmp:Rating>"""), "")
        val attributes = Regex("""[\w-]+:[\w-]+="[^"]*"""").findAll(body).map { it.value }
        val elements = Regex(""">([^<>]*\S[^<>]*)<""").findAll(body).map { it.groupValues[1] }
        return (attributes + elements).filterNot { it.startsWith("xmp:Rating=") }.toList().sorted()
    }

    @Test
    fun everythingButTheRatingSurvivesARoundTrip() {
        fixtures.forEach { name ->
            val original = bytes(name).toString(Charsets.UTF_8)
            val doc = load(name).apply { set(XmpProps.RATING, "3") }
            val saved = doc.toBytes()

            assertEquals(name, 3, XmpDocument.parse(saved).getOrThrow().rating)
            assertEquals(name, content(original), content(saved.toString(Charsets.UTF_8)))
        }
    }

    @Test
    fun namespacesStayDeclaredOnceAndNewOnesAreDeclared() {
        val saved = load("lightroom_classic_develop.xmp")
            .apply { set(XmpProps.RAWCULL_PICK, "1") }
            .toBytes().toString(Charsets.UTF_8)

        assertEquals(saved, 1, Regex("""xmlns:crs=""").findAll(saved).count())
        assertEquals(saved, 1, Regex("""xmlns:rawcull=""").findAll(saved).count())
        assertTrue(saved, "rawcull:Pick=\"1\"" in saved)
    }

    @Test
    fun specialCharactersRoundTrip() {
        val tags = listOf("R&D", "<test>", "\"quoted\"", "emoji 📷", "বাংলা ছবি")
        val label = "a \"b\" <c> & 📷 বাংলা"
        val doc = XmpDocument.newEmpty("RawCull test").apply {
            setSubjects(tags)
            set(XmpProps.LABEL, label)
        }
        val saved = XmpDocument.parse(doc.toBytes()).getOrThrow()
        assertEquals(tags, saved.subjects)
        assertEquals(label, saved.label)
    }

    @Test
    fun readsEveryFormOnTheDevice() {
        assertEquals(4, load("element_form.xmp").rating)
        assertEquals(2, load("multi_description.xmp").rating)
        assertEquals(-1, load("darktable.xmp").rating)
        assertEquals(listOf("Wedding", "Dhaka", "Golden Hour"), load("lightroom_classic_develop.xmp").subjects)
    }

    @Test
    fun xpacketIsKeptAddedAndNeverInvented() {
        listOf(load("bridge_reject.xmp"), XmpDocument.newEmpty("RawCull test")).forEach { doc ->
            val saved = doc.toBytes().toString(Charsets.UTF_8)
            assertTrue(saved, saved.startsWith("<?xpacket begin=\"﻿\""))
            assertTrue(saved, saved.trimEnd().endsWith("<?xpacket end=\"w\"?>"))
        }
        assertFalse("xpacket" in load("no_xpacket.xmp").toBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun locationReadsAndWritesOnTheDevice() {
        // The Lightroom fixture holds exif:GPSLatitude="23,46.1234N" and exif:GPSLongitude="90,22.5678E".
        val doc = load("lightroom_classic_develop.xmp")
        val onFile = XmpGps.read(doc)!!
        assertEquals(23.0 + 46.1234 / 60.0, onFile.latitude, 1e-7)
        assertEquals(90.0 + 22.5678 / 60.0, onFile.longitude, 1e-7)

        XmpGps.write(doc, GeoPoint(-33.8688, -151.2093, 58.25))
        val saved = XmpDocument.parse(doc.toBytes()).getOrThrow()
        val read = XmpGps.read(saved)!!
        assertEquals(-33.8688, read.latitude, 1e-7)
        assertEquals(-151.2093, read.longitude, 1e-7)
        assertEquals(58.25, read.altitude!!, 0.005)
        assertEquals(listOf("Wedding", "Dhaka", "Golden Hour"), saved.subjects)

        XmpGps.write(doc, null)
        assertTrue(XmpGps.read(XmpDocument.parse(doc.toBytes()).getOrThrow()) == null)
    }

    @Test
    fun badContentFailsToParse() {
        assertTrue(XmpDocument.parse(bytes("malformed.xmp")).isFailure)
        assertTrue(XmpDocument.parse(ByteArray(0)).isFailure)
        assertTrue(XmpDocument.parse("<html><body>hi</body></html>".toByteArray()).isFailure)
        val doctype = """<!DOCTYPE x [<!ENTITY a "b">]><x:xmpmeta xmlns:x="adobe:ns:meta/">
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"/></x:xmpmeta>"""
        assertTrue(XmpDocument.parse(doctype.toByteArray()).isFailure)
    }
}
