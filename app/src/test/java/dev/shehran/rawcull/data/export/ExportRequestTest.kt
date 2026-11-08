package dev.shehran.rawcull.data.export

import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.SortOrder
import dev.shehran.rawcull.library.ExportContent
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportRequestTest {
    private fun photo(id: String, rating: Int = 0) = PhotoAsset(
        id = id,
        rootUri = "tree",
        fileName = "$id.ARW",
        fileUri = "doc/$id",
        format = PhotoFormat.ARW,
        fileSizeBytes = 1,
        dateTaken = 1_700_000_000_000L,
        rating = rating,
    )

    private fun request(sort: SortOrder, onlyIds: List<String>? = null) =
        ExportRequest("tree", 1, "Trip", sort, onlyIds, ExportOptions(ExportContent.ORIGINALS, keepOrder = true))

    // Members arrive in their custom order.
    private val members = listOf(photo("c", rating = 1), photo("a", rating = 5), photo("b", rating = 3))

    @Test
    fun `custom order is the members' order`() {
        assertEquals(listOf("c", "a", "b"), request(SortOrder.CUSTOM).order(members).map { it.id })
    }

    @Test
    fun `another sort applies, and filters don't unless only the shown ones go`() {
        assertEquals(listOf("a", "b", "c"), request(SortOrder.RATING_DESC).order(members).map { it.id })
        assertEquals(listOf("a", "c"), request(SortOrder.RATING_DESC, onlyIds = listOf("c", "a")).order(members).map { it.id })
    }
}
