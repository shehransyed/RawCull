package dev.shehran.rawcull.device.library

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.shehran.rawcull.MainActivity
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.RatingOperator
import dev.shehran.rawcull.device.deviceRules
import dev.shehran.rawcull.device.robots.LibraryRobot
import dev.shehran.rawcull.library.CullEdit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** R8.6: filters and the counts beside them. */
@RunWith(AndroidJUnit4::class)
class LibraryFilterTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = deviceRules(compose)

    private val library by lazy { LibraryRobot(compose) }

    /** LIB_0001 pick 5 stars red, 0002 reject 2 stars, 0003 pick 3 stars green, 0004 plain, 0005 tagged "wedding". */
    private fun seedEdits() {
        library.seed("filters", 5)
        fun edit(name: String, vararg edits: CullEdit) {
            library.select(name)
            edits.forEach { e -> compose.runOnUiThread { library.viewModel.apply(e) } }
            compose.waitForIdle()
        }
        edit("LIB_0001.JPG", CullEdit.Flag(CullStatus.PICK), CullEdit.Rating(5), CullEdit.LabelToggle(ColorLabel.RED))
        edit("LIB_0002.JPG", CullEdit.Flag(CullStatus.REJECT), CullEdit.Rating(2))
        edit("LIB_0003.JPG", CullEdit.Flag(CullStatus.PICK), CullEdit.Rating(3), CullEdit.LabelToggle(ColorLabel.GREEN))
        edit("LIB_0005.JPG", CullEdit.AddTag("wedding"))
        library.await {
            library.photo("LIB_0005.JPG").tags.contains("wedding") && library.photo("LIB_0003.JPG").colorLabel == ColorLabel.GREEN
        }
    }

    private fun shown() = library.state().filteredPhotos.map { it.fileName }

    @Test
    fun countsIgnoreFiltersAndFiltersNarrowTheGrid() {
        seedEdits()
        val counts = library.state().counts
        assertEquals(5, counts.total)
        assertEquals(2, counts.flags[CullStatus.PICK])
        assertEquals(1, counts.flags[CullStatus.REJECT])
        assertEquals(1, counts.colors[ColorLabel.RED])
        assertEquals(1, counts.tags["wedding"])
        assertEquals(1, counts.ratings[5])

        compose.runOnUiThread { library.viewModel.updateFilter { it.copy(flags = setOf(CullStatus.PICK)) } }
        library.await { shown().size == 2 }
        assertEquals(listOf("LIB_0001.JPG", "LIB_0003.JPG"), shown())
        assertEquals("counts do not follow the filter", 5, library.state().counts.total)
        assertEquals(1, library.state().counts.flags[CullStatus.REJECT])

        compose.runOnUiThread { library.viewModel.updateFilter { it.copy(rating = 4, ratingOperator = RatingOperator.AT_LEAST) } }
        library.await { shown() == listOf("LIB_0001.JPG") }
    }

    @Test
    fun colorTagAndSearchFilters() {
        seedEdits()
        compose.runOnUiThread { library.viewModel.updateFilter { it.copy(colors = setOf(ColorLabel.GREEN)) } }
        library.await { shown() == listOf("LIB_0003.JPG") }

        compose.runOnUiThread { library.viewModel.clearFilters() }
        library.await { shown().size == 5 }
        compose.runOnUiThread { library.viewModel.updateFilter { it.copy(tags = setOf("wedding")) } }
        library.await { shown() == listOf("LIB_0005.JPG") }

        compose.runOnUiThread { library.viewModel.clearFilters() }
        compose.runOnUiThread { library.viewModel.updateFilter { it.copy(searchQuery = "0004") } }
        library.await { shown() == listOf("LIB_0004.JPG") }
    }

    @Test
    fun anEditedPhotoLeavesTheFilteredViewAndClearBringsItBack() {
        seedEdits()
        compose.runOnUiThread { library.viewModel.updateFilter { it.copy(flags = setOf(CullStatus.UNFLAGGED)) } }
        library.await { shown() == listOf("LIB_0004.JPG", "LIB_0005.JPG") }
        library.select("LIB_0004.JPG")
        library.press(Key.P)
        library.await { shown() == listOf("LIB_0005.JPG") }
        compose.runOnUiThread { library.viewModel.clearFilters() }
        library.await { shown().size == 5 }
    }
}
