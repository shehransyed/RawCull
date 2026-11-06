package dev.shehran.rawcull.util.xmp

import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class XmpMapperTest {
    private val prefs = XmpPrefs()
    private val keepStars = XmpPrefs(rejectAsNegativeRating = false)

    private fun load(name: String) = XmpDocument.parse(XmpFixtures.bytes(name)).getOrThrow()

    private fun empty() = XmpDocument.newEmpty("RawCull test")

    private fun state(
        rating: Int = 0,
        status: CullStatus = CullStatus.UNFLAGGED,
        label: ColorLabel = ColorLabel.NONE,
        tags: List<String> = emptyList(),
    ) = CullState(rating, status, label, tags)

    private fun XmpDocument.saved() = XmpDocument.parse(toBytes()).getOrThrow()

    @Test
    fun ratingIsWrittenToBothPlacesIncludingZero() {
        val doc = empty().also { XmpMapper.apply(it, state(rating = 4), prefs) }.saved()
        assertEquals(4, doc.rating)
        assertEquals(4, doc.rawcullRating)

        val cleared = doc.also { XmpMapper.apply(it, state(rating = 0), prefs) }.saved()
        assertEquals(0, cleared.rating)
        assertTrue(cleared.has(XmpProps.RATING))
    }

    @Test
    fun rejectWithStarsWritesMinusOneAndKeepsTheStars() {
        val doc = empty().also { XmpMapper.apply(it, state(rating = 3, status = CullStatus.REJECT), prefs) }.saved()
        assertEquals(-1, doc.rating)
        assertEquals(3, doc.rawcullRating)
        assertEquals(-1, doc.pick)

        val read = XmpMapper.read(doc)
        assertEquals(CullStatus.REJECT, read.cullStatus)
        assertEquals(3, read.rating)
    }

    @Test
    fun unRejectRestoresTheStars() {
        val doc = empty()
        XmpMapper.apply(doc, state(rating = 3, status = CullStatus.REJECT), prefs)
        val onFile = XmpMapper.read(doc.saved())
        XmpMapper.apply(doc, state(rating = onFile.rating, status = CullStatus.UNFLAGGED), prefs)

        assertEquals(3, doc.saved().rating)
        assertEquals(CullStatus.UNFLAGGED, XmpMapper.read(doc.saved()).cullStatus)
    }

    @Test
    fun withTheSettingOffRejectKeepsTheStarsVisible() {
        val doc = empty().also {
            XmpMapper.apply(it, state(rating = 3, status = CullStatus.REJECT), keepStars)
        }.saved()
        assertEquals(3, doc.rating)
        assertEquals(-1, doc.pick)
        assertEquals(CullStatus.REJECT, XmpMapper.read(doc).cullStatus)
        assertEquals(3, XmpMapper.read(doc).rating)
    }

    @Test
    fun pickIsMirroredAndCrsPickIsRemovedFromLegacyFiles() {
        val doc = load(XmpFixtures.LEGACY).also {
            XmpMapper.apply(it, state(rating = 3, status = CullStatus.PICK), prefs)
        }.saved()
        assertFalse(doc.has(XmpProps.CRS_PICK))
        assertTrue(doc.has(XmpProps.RAWCULL_PICK))
        assertTrue(doc.has(XmpProps.XMP_DM_PICK))
        assertEquals(1, doc.pick)
    }

    @Test
    fun crsPickIsMigratedEvenWhenOnlyTheLabelChanged() {
        val doc = load(XmpFixtures.LEGACY).also {
            XmpMapper.apply(it, state(label = ColorLabel.RED), prefs, setOf(XmpField.LABEL))
        }.saved()
        assertFalse(doc.has(XmpProps.CRS_PICK))
        assertEquals(1, doc.pick)
        assertEquals(3, doc.rating)
    }

    @Test
    fun labelsAreWrittenWithTheirStandardNames() {
        val names = mapOf(
            ColorLabel.RED to "Red",
            ColorLabel.YELLOW to "Yellow",
            ColorLabel.GREEN to "Green",
            ColorLabel.BLUE to "Blue",
            ColorLabel.PURPLE to "Purple",
        )
        names.forEach { (label, name) ->
            assertEquals(name, empty().also { XmpMapper.apply(it, state(label = label), prefs) }.saved().label)
        }
    }

    @Test
    fun labelNoneRemovesTheLabel() {
        val doc = load(XmpFixtures.BRIDGE_REJECT).also { XmpMapper.apply(it, state(), prefs) }.saved()
        assertFalse(doc.has(XmpProps.LABEL))
    }

    @Test
    fun labelNoneRemovesTheBlankLegacyLabel() {
        val doc = load(XmpFixtures.LEGACY).also { XmpMapper.apply(it, state(), prefs) }.saved()
        assertFalse(doc.has(XmpProps.LABEL))
    }

    @Test
    fun anUntouchedCustomLabelIsPreserved() {
        val doc = load(XmpFixtures.NO_XPACKET)
        val onFile = XmpMapper.read(doc)
        assertEquals(ColorLabel.NONE, onFile.colorLabel)
        assertEquals("Second", onFile.rawLabel)

        XmpMapper.apply(doc, state(rating = 5, label = onFile.colorLabel), prefs)
        assertEquals("Second", doc.saved().label)
    }

    @Test
    fun aChangedLabelReplacesACustomOne() {
        val doc = load(XmpFixtures.NO_XPACKET).also { XmpMapper.apply(it, state(label = ColorLabel.BLUE), prefs) }
        assertEquals("Blue", doc.saved().label)
    }

    @Test
    fun theSameLabelInAnotherCaseIsLeftAlone() {
        val doc = load(XmpFixtures.NO_XPACKET).apply { set(XmpProps.LABEL, "gReEn") }
        assertEquals(ColorLabel.GREEN, XmpMapper.read(doc).colorLabel)
        XmpMapper.apply(doc, state(label = ColorLabel.GREEN), prefs)
        assertEquals("gReEn", doc.saved().label)
    }

    @Test
    fun tagsReplaceTheSubjectBagOnly() {
        val doc = load(XmpFixtures.LIGHTROOM).also { XmpMapper.apply(it, state(tags = listOf("New")), prefs) }
        assertEquals(listOf("New"), doc.saved().subjects)
        assertTrue("Events|Wedding" in doc.toBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun aSubjectListThatIsNotABagSurvivesWhenTheTagsAreUnchanged() {
        val xml = XmpFixtures.bytes(XmpFixtures.ELEMENT_FORM).toString(Charsets.UTF_8)
            .replace("rdf:Bag", "rdf:Seq")
        val doc = XmpDocument.parse(xml.toByteArray()).getOrThrow()
        XmpMapper.apply(doc, state(rating = 1), prefs)
        assertTrue("<rdf:li>Element</rdf:li>" in doc.toBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun metadataDateIsAlwaysUpdated() {
        val doc = load(XmpFixtures.LIGHTROOM).also {
            XmpMapper.apply(it, state(), prefs, emptySet(), Date(1_780_000_000_000L))
        }
        assertFalse("xmp:MetadataDate=\"2026-06-02T09:15:03+05:30\"" in doc.toBytes().toString(Charsets.UTF_8))
        assertTrue(doc.saved().has(XmpProps.METADATA_DATE))
    }

    @Test
    fun onlyTheGivenFieldsChange() {
        // The catalog hasn't read this file yet, so its defaults must not replace the real values.
        val doc = load(XmpFixtures.LIGHTROOM).also {
            XmpMapper.apply(it, state(label = ColorLabel.RED), prefs, setOf(XmpField.LABEL))
        }.saved()
        assertEquals("Red", doc.label)
        assertEquals(4, doc.rating)
        assertEquals(listOf("Wedding", "Dhaka", "Golden Hour"), doc.subjects)
        assertFalse(doc.has(XmpProps.RAWCULL_RATING))
    }

    @Test
    fun aPendingPickKeepsTheStarsOnFile() {
        val doc = load(XmpFixtures.LIGHTROOM).also {
            XmpMapper.apply(it, state(rating = 0, status = CullStatus.REJECT), prefs, setOf(XmpField.PICK))
        }.saved()
        assertEquals(-1, doc.rating)
        assertEquals(4, doc.rawcullRating)
    }

    @Test
    fun aPendingRatingKeepsARejectOnFile() {
        val doc = load(XmpFixtures.BRIDGE_REJECT).also {
            XmpMapper.apply(it, state(rating = 2), prefs, setOf(XmpField.RATING))
        }.saved()
        assertEquals(-1, doc.rating)
        assertEquals(2, doc.rawcullRating)
    }

    @Test
    fun aPendingRatingKeepsTheCatalogPickWhenTheFileHasNone() {
        val doc = load(XmpFixtures.LIGHTROOM).also {
            XmpMapper.apply(it, state(rating = 2, status = CullStatus.PICK), prefs, setOf(XmpField.RATING))
        }.saved()
        assertEquals(1, doc.pick)
    }

    @Test
    fun readingFixtures() {
        val lightroom = XmpMapper.read(load(XmpFixtures.LIGHTROOM))
        assertEquals(4, lightroom.rating)
        assertNull(lightroom.cullStatus)
        assertEquals(ColorLabel.BLUE, lightroom.colorLabel)

        val darktable = XmpMapper.read(load(XmpFixtures.DARKTABLE))
        assertEquals(0, darktable.rating)
        assertEquals(CullStatus.REJECT, darktable.cullStatus)

        val bridge = XmpMapper.read(load(XmpFixtures.BRIDGE_REJECT))
        assertEquals(CullStatus.REJECT, bridge.cullStatus)
        assertEquals(ColorLabel.RED, bridge.colorLabel)

        val legacy = XmpMapper.read(load(XmpFixtures.LEGACY))
        assertEquals(3, legacy.rating)
        assertEquals(CullStatus.PICK, legacy.cullStatus)
        assertEquals(ColorLabel.NONE, legacy.colorLabel)
        assertNull(legacy.rawLabel)
    }

    @Test
    fun fieldBitsRoundTrip() {
        assertEquals(XmpField.ALL, XmpField.fromBits(XmpField.ALL_BITS))
        assertEquals(setOf(XmpField.PICK, XmpField.TAGS), XmpField.fromBits(XmpField.PICK.bit or XmpField.TAGS.bit))
        assertTrue(XmpField.fromBits(0).isEmpty())
    }
}
