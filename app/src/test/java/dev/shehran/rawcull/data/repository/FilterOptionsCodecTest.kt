package dev.shehran.rawcull.data.repository

import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.FilterOptions
import dev.shehran.rawcull.data.model.PairMode
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.RatingOperator
import dev.shehran.rawcull.data.model.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilterOptionsCodecTest {
    @Test
    fun `filters survive a round trip`() {
        val filter = FilterOptions(
            flags = setOf(CullStatus.PICK, CullStatus.UNFLAGGED),
            ratingOperator = RatingOperator.AT_MOST,
            rating = 2,
            colors = setOf(ColorLabel.NONE, ColorLabel.GREEN),
            tags = setOf("Wedding", "Ceremony"),
            formatFilter = PhotoFormat.ARW,
            pairFilter = PairMode.RAW_AND_RASTER,
            searchQuery = "a7",
            sortOrder = SortOrder.RATING_DESC,
        )
        assertEquals(filter, FilterOptionsCodec.decode(FilterOptionsCodec.encode(filter)))
    }

    @Test
    fun `the sync errors view isn't remembered`() {
        val json = FilterOptionsCodec.encode(FilterOptions(syncErrorsOnly = true))
        assertEquals(FilterOptions(), FilterOptionsCodec.decode(json))
    }

    @Test
    fun `the old shape decodes`() {
        val json = """
            {"cullFilter":"PICKS_AND_UNFLAGGED","minRating":3,"exactRating":null,"colorFilter":"RED",
             "formatFilter":"DNG","pairFilter":null,"tagFilter":"Wedding","searchQuery":"x",
             "sortOrder":"NAME_DESC","syncErrorsOnly":false}
        """
        assertEquals(
            FilterOptions(
                flags = setOf(CullStatus.PICK, CullStatus.UNFLAGGED),
                rating = 3,
                colors = setOf(ColorLabel.RED),
                tags = setOf("Wedding"),
                formatFilter = PhotoFormat.DNG,
                searchQuery = "x",
                sortOrder = SortOrder.NAME_DESC,
            ),
            FilterOptionsCodec.decode(json),
        )
    }

    @Test
    fun `an exact rating in the old shape stays exact`() {
        val decoded = FilterOptionsCodec.decode("""{"cullFilter":"ALL","minRating":0,"exactRating":4}""")
        assertEquals(RatingOperator.EXACTLY, decoded.ratingOperator)
        assertEquals(4, decoded.rating)
    }

    @Test
    fun `garbage and unknown values fall back`() {
        assertEquals(FilterOptions(), FilterOptionsCodec.decode("not json"))
        assertEquals(FilterOptions(), FilterOptionsCodec.decode(null))
        assertEquals(FilterOptions(), FilterOptionsCodec.decode("""{"flags":["MAYBE"],"sortOrder":"SIDEWAYS"}"""))
    }
}
