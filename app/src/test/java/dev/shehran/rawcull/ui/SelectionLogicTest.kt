package dev.shehran.rawcull.ui

import dev.shehran.rawcull.data.model.FilterOptions
import dev.shehran.rawcull.data.model.PairMode
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.SortOrder
import dev.shehran.rawcull.library.LibraryFilter
import dev.shehran.rawcull.ui.components.GridRow
import dev.shehran.rawcull.ui.components.badgeText
import dev.shehran.rawcull.ui.components.buildGridRows
import dev.shehran.rawcull.ui.components.pairChipLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionLogicTest {
    private val order = listOf("a", "b", "c", "d", "e")

    @Test
    fun `a selection that still matches is kept`() {
        assertEquals("c", SelectionLogic.nearestMatching(order, "c", setOf("a", "c")))
    }

    @Test
    fun `the search goes forward first`() {
        assertEquals("d", SelectionLogic.nearestMatching(order, "c", setOf("a", "d")))
    }

    @Test
    fun `it falls back to searching backward`() {
        assertEquals("b", SelectionLogic.nearestMatching(order, "d", setOf("a", "b")))
    }

    @Test
    fun `nothing matching gives null`() {
        assertNull(SelectionLogic.nearestMatching(order, "c", emptySet()))
    }

    @Test
    fun `an unknown or missing selection gives null`() {
        assertNull(SelectionLogic.nearestMatching(order, "zzz", setOf("a")))
        assertNull(SelectionLogic.nearestMatching(order, null, setOf("a")))
    }

    @Test
    fun `a re-sort keeps the same photo selected`() {
        assertEquals("c", SelectionLogic.reconcile(order, order.reversed(), "c"))
    }

    @Test
    fun `flagging a photo out of the filter moves to the next that matches`() {
        assertEquals("d", SelectionLogic.reconcile(order, listOf("a", "b", "d", "e"), "c"))
    }

    @Test
    fun `the last photo leaving the filter moves backward`() {
        assertEquals("d", SelectionLogic.reconcile(order, listOf("a", "b", "c", "d"), "e"))
    }

    @Test
    fun `with no selection yet the first photo is selected`() {
        assertEquals("a", SelectionLogic.reconcile(emptyList(), order, null))
    }

    @Test
    fun `an empty list clears the selection`() {
        assertNull(SelectionLogic.reconcile(order, emptyList(), "c"))
    }

    @Test
    fun `the compare photo keeps its id`() {
        assertEquals("d", SelectionLogic.reconcileCompare(order, "d", "a"))
    }

    @Test
    fun `the compare photo moves off the selected one`() {
        assertEquals("b", SelectionLogic.reconcileCompare(order, "a", "a"))
    }

    @Test
    fun `the compare photo wraps around at the end`() {
        assertEquals("a", SelectionLogic.reconcileCompare(order, "e", "e"))
    }

    @Test
    fun `a single photo is compared with itself`() {
        assertEquals("a", SelectionLogic.reconcileCompare(listOf("a"), null, "a"))
    }

    @Test
    fun `no photos means no compare photo`() {
        assertNull(SelectionLogic.reconcileCompare(emptyList(), "a", null))
    }
}

class PhotoBadgeTest {
    private fun photo(
        id: String,
        format: PhotoFormat = PhotoFormat.ARW,
        companion: PhotoFormat? = null,
        dir: String = "",
    ) = PhotoAsset(
        id = id,
        rootUri = "root",
        relativeDir = dir,
        fileName = "$id.${format.extension}",
        fileUri = "content://$id",
        format = format,
        fileSizeBytes = 1L,
        dateTaken = 0L,
        companionFormat = companion,
        companionFileUri = companion?.let { "content://companion" },
        pairMode = if (companion != null) PairMode.RAW_AND_RASTER else PairMode.RAW_ONLY,
    )

    @Test
    fun `badges name the formats in the asset`() {
        assertEquals("ARW", photo("a").badgeText)
        assertEquals("JPG", photo("a", PhotoFormat.JPEG).badgeText)
        assertEquals("HIF", photo("a", PhotoFormat.HEIF).badgeText)
        assertEquals("ARW+JPG", photo("a", companion = PhotoFormat.JPEG).badgeText)
        assertEquals("ARW+HIF", photo("a", companion = PhotoFormat.HEIF).badgeText)
    }

    @Test
    fun `format chips come from the formats in the scope`() {
        val photos = listOf(photo("a", PhotoFormat.JPEG), photo("b", PhotoFormat.DNG), photo("c", PhotoFormat.JPEG))

        assertEquals(setOf(PhotoFormat.DNG, PhotoFormat.JPEG), LibraryFilter.apply(photos, FilterOptions(), SortOrder.NAME_ASC, false).counts.formats)
    }

    @Test
    fun `the pair chip reflects the companions present`() {
        assertNull(pairChipLabel(emptySet()))
        assertEquals("RAW+JPG", pairChipLabel(setOf(PhotoFormat.JPEG)))
        assertEquals("RAW+HIF", pairChipLabel(setOf(PhotoFormat.HEIF)))
        assertEquals(
            "RAW+Raster",
            pairChipLabel(setOf(PhotoFormat.JPEG, PhotoFormat.HEIF)),
        )
    }

    @Test
    fun `a single folder gets no headers`() {
        val rows = buildGridRows(listOf(photo("a", dir = "DCIM"), photo("b", dir = "DCIM")))

        assertEquals(2, rows.size)
    }

    @Test
    fun `headers appear at each change of folder and keep the photo index`() {
        val rows = buildGridRows(listOf(photo("a", dir = "100"), photo("b", dir = "101"), photo("c", dir = "100")))

        assertEquals(
            listOf("100", "a", "101", "b", "100", "c"),
            rows.map { if (it is GridRow.Header) it.title else (it as GridRow.Photo).photo.id },
        )
        assertEquals(2, rows.filterIsInstance<GridRow.Photo>().last().index)
        assertEquals("row keys must be unique", rows.size, rows.map { it.key }.toSet().size)
    }

    @Test
    fun `files at the root get a readable header`() {
        val rows = buildGridRows(listOf(photo("a", dir = ""), photo("b", dir = "DCIM")))

        assertEquals("Top level", (rows.first() as GridRow.Header).title)
    }
}
