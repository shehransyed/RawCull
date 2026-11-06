package dev.shehran.rawcull.util.xmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.util.Date
import java.util.TimeZone
import javax.xml.namespace.QName
import javax.xml.parsers.DocumentBuilderFactory

class XmpDocumentTest {
    private fun load(name: String) = XmpDocument.parse(XmpFixtures.bytes(name)).getOrThrow()

    private fun reparse(doc: XmpDocument) = XmpDocument.parse(doc.toBytes()).getOrThrow()

    private fun text(doc: XmpDocument) = doc.toBytes().toString(Charsets.UTF_8)

    /** One line per attribute, element and text value, with its path, so that nothing can go missing unseen. */
    private fun fingerprint(bytes: ByteArray): List<String> {
        val dom = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(bytes.inputStream())
        val lines = ArrayList<String>()

        fun walk(element: Element, path: String) {
            val here = "$path/{${element.namespaceURI}}${element.localName}"
            lines += here
            val attributes = element.attributes
            for (index in 0 until attributes.length) {
                val attribute = attributes.item(index)
                if (attribute.namespaceURI == "http://www.w3.org/2000/xmlns/") continue
                lines += "$here@{${attribute.namespaceURI}}${attribute.localName}=${attribute.nodeValue}"
            }
            var child = element.firstChild
            while (child != null) {
                when {
                    child is Element -> walk(child, here)
                    child.nodeType == Node.TEXT_NODE && child.nodeValue.isNotBlank() ->
                        lines += "$here#${child.nodeValue}"
                }
                child = child.nextSibling
            }
        }
        walk(dom.documentElement, "")
        return lines.sorted()
    }

    private fun List<String>.withoutRating() = filterNot { "{${XmpProps.NS_XMP}}Rating" in it }

    @Test
    fun everythingButTheRatingSurvivesARoundTrip() {
        XmpFixtures.valid.forEach { name ->
            val original = XmpFixtures.bytes(name)
            val doc = XmpDocument.parse(original).getOrThrow()
            doc.set(XmpProps.RATING, "3")
            val saved = doc.toBytes()

            assertEquals(name, 3, XmpDocument.parse(saved).getOrThrow().rating)
            assertEquals(name, fingerprint(original).withoutRating(), fingerprint(saved).withoutRating())
        }
    }

    @Test
    fun developSettingsKeywordsAndHistorySurviveVerbatim() {
        val saved = load(XmpFixtures.LIGHTROOM).apply { set(XmpProps.RATING, "3") }.let(::text)

        listOf(
            "<rdf:li>128, 134</rdf:li>",
            "<rdf:li>Places|Bangladesh|Dhaka</rdf:li>",
            "stEvt:softwareAgent=\"Adobe Photoshop Lightroom Classic 14.3 (Windows)\"",
            "crs:Exposure2012=\"+0.45\"",
            "exif:GPSLatitude=\"23,46.1234N\"",
        ).forEach { assertTrue(it, it in saved) }
    }

    @Test
    fun readsLightroom() {
        val doc = load(XmpFixtures.LIGHTROOM)
        assertEquals(4, doc.rating)
        assertEquals("Blue", doc.label)
        assertNull(doc.pick)
        assertEquals(listOf("Wedding", "Dhaka", "Golden Hour"), doc.subjects)
    }

    @Test
    fun readsDarktableReject() {
        val doc = load(XmpFixtures.DARKTABLE)
        assertEquals(-1, doc.rating)
        assertEquals(listOf("rejected-at-venue", "darktable|styles|none"), doc.subjects)
    }

    @Test
    fun readsBridgeReject() {
        val doc = load(XmpFixtures.BRIDGE_REJECT)
        assertEquals(-1, doc.rating)
        assertEquals("Red", doc.label)
    }

    @Test
    fun readsElementForm() {
        val doc = load(XmpFixtures.ELEMENT_FORM)
        assertEquals(4, doc.rating)
        assertEquals("Green", doc.label)
        assertEquals(listOf("Element"), doc.subjects)
    }

    @Test
    fun readsSecondDescriptionAndIgnoresNestedOnes() {
        val doc = load(XmpFixtures.MULTI_DESCRIPTION)
        assertEquals(2, doc.rating)
        assertEquals("Yellow", doc.label)
        assertEquals(listOf("Second Block"), doc.subjects)
    }

    @Test
    fun readsLegacyRawCullFile() {
        val doc = load(XmpFixtures.LEGACY)
        assertEquals(3, doc.rating)
        assertNull(doc.label)
        assertEquals(1, doc.pick)
        assertEquals(listOf("Portrait", "Golden Hour"), doc.subjects)
    }

    @Test
    fun pickPrecedenceIsRawCullThenXmpDmThenCrs() {
        val doc = load(XmpFixtures.LEGACY)
        doc.set(XmpProps.CRS_PICK, "-1")
        doc.set(XmpProps.XMP_DM_PICK, "0")
        doc.set(XmpProps.RAWCULL_PICK, "1")
        assertEquals(1, doc.pick)
        doc.set(XmpProps.RAWCULL_PICK, null)
        assertEquals(0, doc.pick)
        doc.set(XmpProps.XMP_DM_PICK, null)
        assertEquals(-1, doc.pick)
    }

    @Test
    fun ratingsAreReadAsRealsAndOutOfRangeValuesAreIgnored() {
        val doc = load(XmpFixtures.NO_XPACKET)
        doc.set(XmpProps.RATING, "4.0")
        assertEquals(4, doc.rating)
        doc.set(XmpProps.RATING, "9")
        assertNull(doc.rating)
        doc.set(XmpProps.RATING, "many")
        assertNull(doc.rating)
    }

    @Test
    fun setReplacesAnElementWithAnAttributeOnTheFirstDescription() {
        val saved = load(XmpFixtures.ELEMENT_FORM).apply { set(XmpProps.RATING, "1") }.let(::text)
        assertFalse(saved, "<xmp:Rating>" in saved)
        assertTrue(saved, "xmp:Rating=\"1\"" in saved)
    }

    @Test
    fun setRemovesOccurrencesInEveryDescriptionButNotNestedOnes() {
        val doc = load(XmpFixtures.MULTI_DESCRIPTION).apply { set(XmpProps.RATING, "5") }
        val saved = text(doc)
        assertEquals(5, reparse(doc).rating)
        assertEquals(saved, 2, Regex("xmp:Rating=").findAll(saved).count())
        assertTrue(saved, "xmp:Rating=\"1\"" in saved)
        assertFalse(saved, "xmp:Rating=\"2\"" in saved)
    }

    @Test
    fun setNullRemovesTheProperty() {
        val doc = load(XmpFixtures.ELEMENT_FORM).apply { set(XmpProps.LABEL, null) }
        assertNull(reparse(doc).label)
        assertFalse(reparse(doc).has(XmpProps.LABEL))
    }

    @Test
    fun setDeclaresAMissingNamespaceOnTheElement() {
        val doc = load(XmpFixtures.NO_XPACKET).apply { set(XmpProps.RAWCULL_PICK, "1") }
        val saved = text(doc)
        assertTrue(saved, "xmlns:rawcull=\"${XmpProps.NS_RAWCULL}\"" in saved)
        assertEquals(1, reparse(doc).pick)
    }

    @Test
    fun setReusesAnExistingPrefixAndAvoidsAClashingOne() {
        val doc = load(XmpFixtures.NO_XPACKET)
        doc.set(QName("urn:other", "Thing", "rawcull"), "x")
        doc.set(XmpProps.RAWCULL_PICK, "1")
        doc.set(QName(XmpProps.NS_XMP, "Nickname", "somethingElse"), "y")
        val saved = reparse(doc).let(::text)

        assertEquals(1, reparse(doc).pick)
        assertTrue(saved, "rawcull:Thing=\"x\"" in saved)
        assertTrue(saved, "rawcull1:Pick=\"1\"" in saved)
        assertTrue(saved, "xmp:Nickname=\"y\"" in saved)
    }

    @Test
    fun setSubjectsReplacesOnlyTheSubjectBag() {
        val doc = load(XmpFixtures.LIGHTROOM).apply { setSubjects(listOf("Only")) }
        val saved = text(doc)
        assertEquals(listOf("Only"), reparse(doc).subjects)
        assertTrue(saved, "Events|Wedding" in saved)
        assertFalse(saved, "<rdf:li>Dhaka</rdf:li>" in saved)
    }

    @Test
    fun emptySubjectsRemoveTheBag() {
        val doc = load(XmpFixtures.ELEMENT_FORM).apply { setSubjects(emptyList()) }
        assertFalse(reparse(doc).has(XmpProps.SUBJECT))
    }

    @Test
    fun specialCharactersRoundTripInTagsAndAttributes() {
        val tags = listOf("R&D", "<test>", "\"quoted\"", "emoji 📷", "বাংলা ছবি", "R&D <test>")
        val doc = XmpDocument.newEmpty("RawCull test").apply {
            setSubjects(tags)
            set(XmpProps.LABEL, "a \"b\" <c> & 📷 বাংলা")
        }
        val saved = reparse(doc)
        assertEquals(tags, saved.subjects)
        assertEquals("a \"b\" <c> & 📷 বাংলা", saved.label)
    }

    @Test
    fun metadataDateIsIso8601WithOffset() {
        val doc = XmpDocument.newEmpty("RawCull test")
        doc.touchMetadataDate(Date(1_780_000_000_000L), TimeZone.getTimeZone("GMT+05:30"))
        assertTrue(text(doc), "xmp:MetadataDate=\"2026-05-29T01:56:40+05:30\"" in text(doc))
    }

    @Test
    fun malformedAndNonXmpContentFailToParse() {
        assertTrue(XmpDocument.parse(XmpFixtures.bytes(XmpFixtures.MALFORMED)).isFailure)
        assertTrue(XmpDocument.parse(ByteArray(0)).isFailure)
        assertTrue(XmpDocument.parse("<html><body>hi</body></html>".toByteArray()).isFailure)
    }

    @Test
    fun aDoctypeIsRejected() {
        val xml = """<!DOCTYPE x [<!ENTITY a "b">]><x:xmpmeta xmlns:x="adobe:ns:meta/">
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"/></x:xmpmeta>"""
        assertTrue(XmpDocument.parse(xml.toByteArray()).isFailure)
    }

    @Test
    fun aFileWithNoDescriptionGetsOne() {
        val xml = """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"/>"""
        val doc = XmpDocument.parse(xml.toByteArray()).getOrThrow().apply { set(XmpProps.RATING, "2") }
        assertEquals(2, reparse(doc).rating)
    }

    @Test
    fun theXpacketWrapperIsKeptAddedAndNeverInvented() {
        assertFalse(text(load(XmpFixtures.NO_XPACKET)), "xpacket" in text(load(XmpFixtures.NO_XPACKET)))

        listOf(load(XmpFixtures.BRIDGE_REJECT), XmpDocument.newEmpty("RawCull test")).forEach { doc ->
            val saved = text(doc)
            assertTrue(saved, saved.startsWith("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"))
            assertTrue(saved, saved.trimEnd().endsWith("<?xpacket end=\"w\"?>"))
        }
    }

    @Test
    fun aNewDocumentIsValidXmp() {
        val doc = XmpDocument.newEmpty("RawCull 1.2.3").apply { set(XmpProps.RATING, "2") }
        assertTrue(text(doc), "x:xmptk=\"RawCull 1.2.3\"" in text(doc))
        assertEquals(2, reparse(doc).rating)
    }
}
