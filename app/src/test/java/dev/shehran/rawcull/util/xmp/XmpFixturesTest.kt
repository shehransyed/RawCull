package dev.shehran.rawcull.util.xmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import javax.xml.parsers.DocumentBuilderFactory

/** Guards the fixtures themselves, so a later failure always points at the code under test. */
class XmpFixturesTest {
    private fun parse(name: String): Document {
        val builder = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
        builder.setErrorHandler(object : ErrorHandler {
            override fun warning(e: SAXParseException) = Unit
            override fun error(e: SAXParseException) = throw e
            override fun fatalError(e: SAXParseException) = throw e
        })
        return builder.parse(XmpFixtures.bytes(name).inputStream())
    }

    @Test
    fun validFixturesAreWellFormed() {
        XmpFixtures.valid.forEach { assertEquals(it, "xmpmeta", parse(it).documentElement.localName) }
    }

    @Test
    fun malformedFixtureIsNotWellFormed() {
        assertTrue(runCatching { parse(XmpFixtures.MALFORMED) }.isFailure)
    }

    @Test
    fun lightroomFixtureCarriesAboutThirtyDevelopSettings() {
        val description = parse(XmpFixtures.LIGHTROOM).getElementsByTagNameNS(RDF, "Description").item(0)
        val attributes = description.attributes
        val crs = (0 until attributes.length).count { attributes.item(it).namespaceURI == CRS }
        assertTrue("found $crs", crs >= 30)
    }

    private companion object {
        const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        const val CRS = "http://ns.adobe.com/camera-raw-settings/1.0/"
    }
}
