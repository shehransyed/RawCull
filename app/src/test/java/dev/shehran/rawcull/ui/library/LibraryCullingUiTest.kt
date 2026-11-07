package dev.shehran.rawcull.ui.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import dev.shehran.rawcull.MainActivity
import dev.shehran.rawcull.library.LibraryKeymap
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real screen on the demo library, at the plan's window sizes. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryCullingUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitFor(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), TIMEOUT_MS)

    private fun openDemoLibrary() {
        waitFor("nav_library")
        compose.onNodeWithTag("nav_library").performClick()
        // Earlier tests in the process may leave the demo open, since settings outlive an activity.
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesExist("empty_demo_button") || compose.onAllNodesExist("library_top_bar")
        }
        if (compose.onAllNodesExist("empty_demo_button")) compose.onNodeWithTag("empty_demo_button").performClick()
        waitFor("grid_card_0")
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesExist(tag: String) =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun startSelection() {
        compose.onNodeWithTag("grid_card_0").performTouchInput { longClick() }
        waitFor("selection_top_bar")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `wide windows get the filter bar`() {
        openDemoLibrary()
        waitFor("filter_bar")
        compose.onNodeWithTag("filter_sheet_button").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `phones get the row and the sheet`() {
        openDemoLibrary()
        waitFor("filter_sheet_button")
        compose.onNodeWithTag("filter_bar").assertDoesNotExist()

        compose.onNodeWithTag("filter_sheet_button").performClick()
        waitFor("filter_sheet")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `a phone on its side uses the sheet too`() {
        openDemoLibrary()
        compose.onNodeWithTag("filter_bar").assertDoesNotExist()
        compose.onNodeWithTag("library_filter_button").performClick()
        waitFor("filter_sheet_button")
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `a long press brings the contextual bar and back clears it`() {
        openDemoLibrary()
        startSelection()
        compose.onNodeWithTag("selection_count").assertIsDisplayed()

        Espresso.pressBack()

        compose.waitUntil(TIMEOUT_MS) { !compose.onAllNodesExist("selection_top_bar") }
        waitFor("library_title")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `a long press stays selected when the finger lifts`() {
        openDemoLibrary()
        startSelection()
        compose.waitForIdle()
        compose.onNodeWithTag("selection_top_bar").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `the help sheet lists the keymap`() {
        openDemoLibrary()
        compose.onNodeWithTag("library_overflow_button").performClick()
        waitFor("keyboard_shortcuts_button")
        compose.onNodeWithTag("keyboard_shortcuts_button").performClick()
        waitFor("shortcut_help_sheet")

        LibraryKeymap.helpGroups.flatMap { it.rows }.forEach { row ->
            compose.onNodeWithTag("shortcut_help_sheet").performScrollToNode(hasText(row.description))
            compose.waitForIdle()
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `selection survives a configuration change`() {
        openDemoLibrary()
        startSelection()

        compose.activityRule.scenario.recreate()

        waitFor("selection_top_bar")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `filters survive a configuration change`() {
        openDemoLibrary()
        waitFor("filter_bar")
        compose.onNodeWithTag("filter_flag_pick").performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasText("of", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        compose.activityRule.scenario.recreate()

        waitFor("filter_clear_button")
        compose.onNodeWithTag("filter_clear_button").performClick()
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
