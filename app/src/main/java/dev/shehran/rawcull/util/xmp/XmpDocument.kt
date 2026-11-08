package dev.shehran.rawcull.util.xmp

import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.XMLConstants
import javax.xml.namespace.QName
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** The properties RawCull reads or manages. Everything else in a sidecar is left exactly as found. */
object XmpProps {
    const val NS_RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    const val NS_META = "adobe:ns:meta/"
    const val NS_XMP = "http://ns.adobe.com/xap/1.0/"
    const val NS_DC = "http://purl.org/dc/elements/1.1/"
    const val NS_CRS = "http://ns.adobe.com/camera-raw-settings/1.0/"
    const val NS_XMP_DM = "http://ns.adobe.com/xmp/1.0/DynamicMedia/"
    const val NS_RAWCULL = "http://ns.rawcull.dev/xmp/1.0/"
    const val NS_EXIF = "http://ns.adobe.com/exif/1.0/"
    const val NS_PHOTOSHOP = "http://ns.adobe.com/photoshop/1.0/"

    val RATING = QName(NS_XMP, "Rating", "xmp")
    val LABEL = QName(NS_XMP, "Label", "xmp")
    val METADATA_DATE = QName(NS_XMP, "MetadataDate", "xmp")
    val SUBJECT = QName(NS_DC, "subject", "dc")
    val RAWCULL_RATING = QName(NS_RAWCULL, "Rating", "rawcull")
    val RAWCULL_PICK = QName(NS_RAWCULL, "Pick", "rawcull")
    val XMP_DM_PICK = QName(NS_XMP_DM, "pick", "xmpDM")
    val CRS_PICK = QName(NS_CRS, "Pick", "crs")

    /** The capture times an ordered export rewrites in its copies (R7 D10). */
    val EXIF_DATE_TIME_ORIGINAL = QName(NS_EXIF, "DateTimeOriginal", "exif")
    val XMP_CREATE_DATE = QName(NS_XMP, "CreateDate", "xmp")
    val PHOTOSHOP_DATE_CREATED = QName(NS_PHOTOSHOP, "DateCreated", "photoshop")
}

class XmpParseException(message: String) : Exception(message)

/**
 * A sidecar held as a namespace-aware DOM, so that saving it keeps everything RawCull doesn't
 * manage: develop settings, history, keywords hierarchies and whatever other apps put there.
 *
 * Only `rdf:Description` elements directly under `rdf:RDF` describe the photo. Nested ones, such as
 * `xmpMM:Pantry` entries, describe other resources, so they are neither read nor changed.
 */
class XmpDocument private constructor(
    private val dom: Document,
    private val rdf: Element,
) {
    val rating: Int? get() = ratingOf(XmpProps.RATING)
    val rawcullRating: Int? get() = ratingOf(XmpProps.RAWCULL_RATING)
    val label: String? get() = value(XmpProps.LABEL)

    /** `rawcull:Pick`, then `xmpDM:pick`, then `crs:Pick` as written by older RawCull versions. */
    val pick: Int?
        get() = PICK_PRECEDENCE.firstNotNullOfOrNull { prop ->
            value(prop)?.toIntOrNull()?.takeIf { it in -1..1 }
        }

    /** Only `dc:subject/rdf:Bag/rdf:li`; `lr:hierarchicalSubject` and other lists are not tags. */
    val subjects: List<String>
        get() = descriptions()
            .flatMap { it.childElements(XmpProps.SUBJECT) }
            .flatMap { subject -> subject.childElements(XmpProps.NS_RDF, "Bag") }
            .flatMap { bag -> bag.childElements(XmpProps.NS_RDF, "li") }
            .map { it.textContent.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** A simple property's value, from the first Description that has it. */
    fun valueOf(prop: QName): String? = value(prop)

    fun has(prop: QName): Boolean = descriptions().any { description ->
        description.hasAttributeNS(prop.namespaceURI, prop.localPart) || description.childElements(prop).isNotEmpty()
    }

    /**
     * Removes every occurrence of [prop], attribute or element, in any Description. A non-null
     * [value] is then set as an attribute on the first Description.
     */
    fun set(prop: QName, value: String?) {
        remove(prop)
        if (value == null) return
        val description = firstDescription()
        val prefix = prefixFor(description, prop.namespaceURI, prop.prefix)
        description.setAttributeNS(prop.namespaceURI, "$prefix:${prop.localPart}", value)
    }

    /** Replaces the `dc:subject` bag; an empty list removes it. `lr:hierarchicalSubject` is untouched. */
    fun setSubjects(subjects: List<String>) {
        remove(XmpProps.SUBJECT)
        if (subjects.isEmpty()) return
        val description = firstDescription()
        val dcPrefix = prefixFor(description, XmpProps.NS_DC, XmpProps.SUBJECT.prefix)
        val rdfPrefix = prefixFor(description, XmpProps.NS_RDF, RDF_PREFIX)
        val bag = dom.createElementNS(XmpProps.NS_RDF, "$rdfPrefix:Bag")
        subjects.forEach { subject ->
            bag.appendChild(dom.createElementNS(XmpProps.NS_RDF, "$rdfPrefix:li").apply { textContent = subject })
        }
        val element = dom.createElementNS(XmpProps.NS_DC, "$dcPrefix:${XmpProps.SUBJECT.localPart}")
        element.appendChild(bag)
        description.appendChild(element)
    }

    /** Lightroom compares `xmp:MetadataDate` to notice that a sidecar changed outside it. */
    fun touchMetadataDate(now: Date, zone: TimeZone = TimeZone.getDefault()) {
        val format = SimpleDateFormat(ISO_8601_WITH_OFFSET, Locale.US).apply { timeZone = zone }
        set(XmpProps.METADATA_DATE, format.format(now))
    }

    /** UTF-8. Existing content isn't reformatted, and any `<?xpacket?>` wrapper stays in place. */
    fun toBytes(): ByteArray {
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.METHOD, "xml")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "no")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        }
        val out = ByteArrayOutputStream()
        transformer.transform(DOMSource(dom), StreamResult(out))
        return out.toByteArray()
    }

    private fun value(prop: QName): String? = descriptions().firstNotNullOfOrNull { description ->
        val attribute = description.getAttributeNodeNS(prop.namespaceURI, prop.localPart)?.value
        (attribute ?: description.childElements(prop).firstOrNull()?.textContent)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** XMP types ratings as reals, so "4.0" is valid. */
    private fun ratingOf(prop: QName): Int? =
        value(prop)?.toDoubleOrNull()?.toInt()?.takeIf { it in -1..5 }

    private fun remove(prop: QName) {
        descriptions().forEach { description ->
            description.removeAttributeIfPresent(prop)
            description.childElements(prop).forEach { it.removeWithLeadingWhitespace() }
        }
    }

    private fun descriptions(): List<Element> = rdf.childElements(XmpProps.NS_RDF, "Description")

    private fun firstDescription(): Element = descriptions().firstOrNull() ?: run {
        val prefix = prefixFor(rdf, XmpProps.NS_RDF, RDF_PREFIX)
        val description = dom.createElementNS(XmpProps.NS_RDF, "$prefix:Description")
        description.setAttributeNS(XmpProps.NS_RDF, "$prefix:about", "")
        rdf.appendChild(description)
        description
    }

    /**
     * The prefix bound to [namespace] at [element], declaring [preferred] there if there is none.
     * Reads `nodeName` rather than `localName`, because parsers disagree on the latter for xmlns.
     */
    private fun prefixFor(element: Element, namespace: String, preferred: String): String {
        val bindings = HashMap<String, String>()
        var node: Node? = element
        while (node is Element) {
            val attributes = node.attributes
            for (index in 0 until attributes.length) {
                val attribute = attributes.item(index) as Attr
                if (attribute.nodeName.startsWith(XMLNS_PREFIX)) {
                    bindings.putIfAbsent(attribute.nodeName.removePrefix(XMLNS_PREFIX), attribute.value)
                }
            }
            node = node.parentNode
        }
        bindings.entries.firstOrNull { it.value == namespace }?.let { return it.key }

        val prefix = generateSequence(0) { it + 1 }
            .map { if (it == 0) preferred else "$preferred$it" }
            .first { it !in bindings }
        element.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "$XMLNS_PREFIX$prefix", namespace)
        return prefix
    }

    companion object {
        private const val RDF_PREFIX = "rdf"
        private const val XMLNS_PREFIX = "xmlns:"
        private const val ISO_8601_WITH_OFFSET = "yyyy-MM-dd'T'HH:mm:ssXXX"
        private const val XPACKET_ID = "W5M0MpCehiHzreSzNTczkc9d"
        private const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"
        private val PICK_PRECEDENCE = listOf(XmpProps.RAWCULL_PICK, XmpProps.XMP_DM_PICK, XmpProps.CRS_PICK)

        /** Fails on anything that isn't XMP, so that the caller can refuse to overwrite it. */
        fun parse(bytes: ByteArray): Result<XmpDocument> = runCatching {
            val dom = newBuilder().parse(ByteArrayInputStream(bytes))
            // XMP forbids DTDs. Not every parser can reject them up front, so check here as well.
            if (dom.doctype != null) throw XmpParseException("Sidecar has a DOCTYPE")
            val rdf = dom.getElementsByTagNameNS(XmpProps.NS_RDF, "RDF").item(0) as? Element
                ?: throw XmpParseException("Sidecar has no rdf:RDF element")
            XmpDocument(dom, rdf)
        }

        fun newEmpty(toolkit: String): XmpDocument {
            val dom = newBuilder().newDocument()
            val meta = dom.createElementNS(XmpProps.NS_META, "x:xmpmeta").apply {
                setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:x", XmpProps.NS_META)
                setAttributeNS(XmpProps.NS_META, "x:xmptk", toolkit)
            }
            val rdf = dom.createElementNS(XmpProps.NS_RDF, "rdf:RDF").apply {
                setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:rdf", XmpProps.NS_RDF)
            }
            rdf.appendChild(
                dom.createElementNS(XmpProps.NS_RDF, "rdf:Description").apply {
                    setAttributeNS(XmpProps.NS_RDF, "rdf:about", "")
                }
            )
            meta.appendChild(rdf)
            dom.appendChild(dom.createProcessingInstruction("xpacket", "begin=\"﻿\" id=\"$XPACKET_ID\""))
            dom.appendChild(meta)
            dom.appendChild(dom.createProcessingInstruction("xpacket", "end=\"w\""))
            return XmpDocument(dom, rdf)
        }

        private fun newBuilder(): DocumentBuilder {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
            try {
                factory.setFeature(DISALLOW_DOCTYPE, true)
            } catch (_: ParserConfigurationException) {
                // Android's parser doesn't know this feature; parse() checks the result instead.
            }
            return factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
                setErrorHandler(ThrowingErrorHandler)
            }
        }
    }

    /** The default handler prints to stderr; a bad sidecar is an expected result here. */
    private object ThrowingErrorHandler : ErrorHandler {
        override fun warning(exception: SAXParseException) = Unit
        override fun error(exception: SAXParseException) = throw exception
        override fun fatalError(exception: SAXParseException) = throw exception
    }
}

private fun Element.childElements(namespace: String, localName: String): List<Element> {
    val children = ArrayList<Element>()
    var child = firstChild
    while (child != null) {
        if (child is Element && child.namespaceURI == namespace && child.localName == localName) children += child
        child = child.nextSibling
    }
    return children
}

private fun Element.childElements(prop: QName): List<Element> = childElements(prop.namespaceURI, prop.localPart)

private fun Element.removeAttributeIfPresent(prop: QName) {
    if (hasAttributeNS(prop.namespaceURI, prop.localPart)) removeAttributeNS(prop.namespaceURI, prop.localPart)
}

/** Takes the indentation with the element, so repeated edits don't leave blank lines behind. */
private fun Element.removeWithLeadingWhitespace() {
    val parent = parentNode
    val previous = previousSibling
    if (previous != null && previous.nodeType == Node.TEXT_NODE && previous.nodeValue.isBlank()) {
        parent.removeChild(previous)
    }
    parent.removeChild(this)
}
