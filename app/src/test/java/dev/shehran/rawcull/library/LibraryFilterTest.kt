package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.FilterOptions
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.RatingOperator
import dev.shehran.rawcull.data.model.SortOrder
import dev.shehran.rawcull.data.model.XmpSyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class LibraryFilterTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private val base = 1_700_000_000_000L

    private fun photo(
        id: String,
        rating: Int = 0,
        status: CullStatus = CullStatus.UNFLAGGED,
        label: ColorLabel = ColorLabel.NONE,
        tags: List<String> = emptyList(),
        dir: String = "",
        date: Long = base,
        size: Long = 1,
        camera: String? = null,
        lens: String? = null,
        sync: XmpSyncStatus = XmpSyncStatus.SYNCED,
    ) = PhotoAsset(
        id = id,
        rootUri = "root",
        relativeDir = dir,
        fileName = "$id.ARW",
        fileUri = "uri/$id",
        format = PhotoFormat.ARW,
        fileSizeBytes = size,
        dateTaken = date,
        rating = rating,
        cullStatus = status,
        colorLabel = label,
        tags = tags,
        cameraModel = camera,
        lensModel = lens,
        xmpSyncStatus = sync,
    )

    private fun view(
        rows: List<PhotoAsset>,
        filter: FilterOptions = FilterOptions(),
        sort: SortOrder = SortOrder.DATE_ASC,
        spans: Boolean = false,
    ) = LibraryFilter.apply(rows, filter, sort, spans, now)

    private fun ids(rows: List<PhotoAsset>, filter: FilterOptions, sort: SortOrder = SortOrder.NAME_ASC) =
        view(rows, filter, sort).rows.map { it.id }

    private val rated = (0..5).map { photo("r$it", rating = it) }

    @Test
    fun `rating at least`() {
        assertEquals(listOf("r3", "r4", "r5"), ids(rated, FilterOptions(rating = 3)))
    }

    @Test
    fun `rating exactly`() {
        assertEquals(listOf("r3"), ids(rated, FilterOptions(ratingOperator = RatingOperator.EXACTLY, rating = 3)))
        assertEquals(listOf("r0"), ids(rated, FilterOptions(ratingOperator = RatingOperator.EXACTLY, rating = 0)))
    }

    @Test
    fun `rating at most`() {
        assertEquals(listOf("r0", "r1"), ids(rated, FilterOptions(ratingOperator = RatingOperator.AT_MOST, rating = 1)))
    }

    @Test
    fun `flags are any of`() {
        val rows = listOf(
            photo("p", status = CullStatus.PICK),
            photo("u"),
            photo("x", status = CullStatus.REJECT),
        )
        assertEquals(listOf("p", "u"), ids(rows, FilterOptions(flags = setOf(CullStatus.PICK, CullStatus.UNFLAGGED))))
        assertEquals(listOf("p", "u", "x"), ids(rows, FilterOptions()))
    }

    @Test
    fun `colours are any of, with No label as an option`() {
        val rows = listOf(photo("n"), photo("r", label = ColorLabel.RED), photo("g", label = ColorLabel.GREEN))
        assertEquals(listOf("n"), ids(rows, FilterOptions(colors = setOf(ColorLabel.NONE))))
        assertEquals(listOf("n", "r"), ids(rows, FilterOptions(colors = setOf(ColorLabel.NONE, ColorLabel.RED))))
    }

    @Test
    fun `tags are any of`() {
        val rows = listOf(photo("a", tags = listOf("x", "y")), photo("b", tags = listOf("z")), photo("c"))
        assertEquals(listOf("a", "b"), ids(rows, FilterOptions(tags = setOf("y", "z"))))
    }

    @Test
    fun `search covers name, camera, lens and tags`() {
        val rows = listOf(
            photo("DSC1"),
            photo("b", camera = "ILCE-7M4"),
            photo("c", lens = "FE 24-70mm"),
            photo("d", tags = listOf("Wedding")),
        )
        assertEquals(listOf("DSC1"), ids(rows, FilterOptions(searchQuery = "dsc")))
        assertEquals(listOf("b"), ids(rows, FilterOptions(searchQuery = "ilce")))
        assertEquals(listOf("c"), ids(rows, FilterOptions(searchQuery = "24-70")))
        assertEquals(listOf("d"), ids(rows, FilterOptions(searchQuery = " wed ")))
    }

    @Test
    fun `sync errors only`() {
        val rows = listOf(photo("a"), photo("b", sync = XmpSyncStatus.ERROR))
        assertEquals(listOf("b"), ids(rows, FilterOptions(syncErrorsOnly = true)))
    }

    @Test
    fun `counts ignore filters`() {
        val rows = listOf(
            photo("a", rating = 3, status = CullStatus.PICK, label = ColorLabel.RED, tags = listOf("x", "y")),
            photo("b", rating = 3, tags = listOf("x")),
            photo("c", status = CullStatus.REJECT),
        )
        val counts = view(rows, FilterOptions(flags = setOf(CullStatus.PICK), rating = 5)).counts

        assertEquals(3, counts.total)
        assertEquals(mapOf(CullStatus.PICK to 1, CullStatus.UNFLAGGED to 1, CullStatus.REJECT to 1), counts.flags)
        assertEquals(mapOf(3 to 2, 0 to 1), counts.ratings)
        assertEquals(mapOf(ColorLabel.RED to 1, ColorLabel.NONE to 2), counts.colors)
        assertEquals(mapOf("x" to 2, "y" to 1), counts.tags)
    }

    @Test
    fun `date and name sorts group by folder, each folder once`() {
        val rows = listOf(
            photo("a", dir = "one", date = base),
            photo("b", dir = "two", date = base + day),
            photo("c", dir = "one", date = base + 2 * day),
        )
        val view = view(rows, spans = true)

        assertEquals(listOf("a", "c", "b"), view.rows.map { it.id })
        assertEquals(listOf(FolderSection("one", 0, 2), FolderSection("two", 2, 1)), view.sections)
        assertEquals(2, view(rows, sort = SortOrder.DATE_DESC, spans = true).sections.size)
        assertEquals(2, view(rows, sort = SortOrder.NAME_ASC, spans = true).sections.size)
        assertEquals(2, view(rows, sort = SortOrder.NAME_DESC, spans = true).sections.size)
    }

    @Test
    fun `rating and size sorts stay flat`() {
        val rows = listOf(photo("a", dir = "one", rating = 1), photo("b", dir = "two", rating = 2))
        assertTrue(view(rows, sort = SortOrder.RATING_DESC, spans = true).sections.isEmpty())
        assertTrue(view(rows, sort = SortOrder.SIZE_DESC, spans = true).sections.isEmpty())
    }

    @Test
    fun `custom order keeps the rows as they come, in one run`() {
        val rows = listOf(photo("c", dir = "two", rating = 1), photo("a", dir = "one"), photo("b", dir = "two"))
        val result = view(rows, FilterOptions(flags = setOf(CullStatus.UNFLAGGED)), SortOrder.CUSTOM, spans = true)
        assertEquals(listOf("c", "a", "b"), result.rows.map { it.id })
        assertTrue(result.sections.isEmpty())
    }

    @Test
    fun `a scope with one folder has no sections`() {
        assertTrue(view(listOf(photo("a"), photo("b")), spans = false).sections.isEmpty())
    }

    @Test
    fun `an implausible date sorts by the file date, and undated photos go last`() {
        val unset = photo("unset", date = 0).copy(fileLastModified = base + day)
        val early = photo("early", date = base)
        val undated = photo("undated", date = 0).copy(fileLastModified = 0)

        assertEquals(listOf("early", "unset", "undated"), view(listOf(undated, unset, early)).rows.map { it.id })
        assertEquals(
            listOf("unset", "early", "undated"),
            view(listOf(undated, unset, early), sort = SortOrder.DATE_DESC).rows.map { it.id },
        )
    }

    @Test
    fun `matches agrees with apply`() {
        val filter = FilterOptions(flags = setOf(CullStatus.UNFLAGGED))
        assertTrue(LibraryFilter.matches(photo("a"), filter))
        assertFalse(LibraryFilter.matches(photo("a", status = CullStatus.PICK), filter))
    }

    /** D12: recorded, not asserted, because a build machine's speed says little about a tablet's. */
    @Test
    fun `fifty thousand rows`() {
        val labels = ColorLabel.entries
        val rows = (0 until 50_000).map { index ->
            photo(
                id = "IMG_%05d".format(index),
                rating = index % 6,
                status = CullStatus.entries[index % 3],
                label = labels[index % labels.size],
                tags = if (index % 4 == 0) listOf("tag${index % 40}") else emptyList(),
                dir = "2026/2026-%02d".format(index % 12 + 1),
                date = base + index * 1000L,
                camera = "ILCE-7M4",
            )
        }
        val filters = mapOf(
            "none, date" to (FilterOptions() to SortOrder.DATE_ASC),
            "picks + 3 stars" to (FilterOptions(flags = setOf(CullStatus.PICK), rating = 3) to SortOrder.DATE_ASC),
            "search, name" to (FilterOptions(searchQuery = "IMG_1") to SortOrder.NAME_ASC),
            "none, rating" to (FilterOptions() to SortOrder.RATING_DESC),
        )
        repeat(3) { view(rows, spans = true) } // Warm up the JIT, as a running app would be.
        filters.forEach { (name, setup) ->
            val (filter, sort) = setup
            val best = (1..5).minOf { measureTimeMillis { view(rows, filter, sort, spans = true) } }
            println("LibraryFilter 50k [$name]: ${best} ms")
        }
    }
}
