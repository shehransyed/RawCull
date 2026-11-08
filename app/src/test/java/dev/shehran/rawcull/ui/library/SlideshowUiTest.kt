package dev.shehran.rawcull.ui.library

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import dev.shehran.rawcull.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R6 D4-D6 on the demo library: every way in, Space and Esc. Timing is covered with a fake clock in the ViewModel tests. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w1280dp-h800dp")
class SlideshowUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String) = compose.waitUntil(TIMEOUT_MS) { exists(tag) }

    private fun waitGone(tag: String) = compose.waitUntil(TIMEOUT_MS) { !exists(tag) }

    private fun openDemoLibrary() {
        waitFor("nav_library")
        compose.onNodeWithTag("nav_library").performClick()
        compose.waitUntil(TIMEOUT_MS) { exists("empty_demo_button") || exists("library_top_bar") }
        if (exists("empty_demo_button")) compose.onNodeWithTag("empty_demo_button").performClick()
        waitFor("grid_card_0")
    }

    private fun press(key: Key) {
        compose.onNodeWithTag("library_screen").performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun showControls() {
        compose.onAllNodes(hasTestTag("zoomable_loupe_container")).onLast().performClick()
        waitFor("slideshow_play_pause")
    }

    @Test
    fun `the grid's menu starts a show and Esc ends it back on the grid`() {
        openDemoLibrary()
        compose.onNodeWithTag("library_overflow_button").performClick()
        waitFor("slideshow_menu_item")
        compose.onNodeWithTag("slideshow_menu_item").performClick()
        waitFor("full_window")

        press(Key.Escape)

        waitGone("full_window")
        waitFor("grid_card_0")
    }

    @Test
    fun `ctrl enter starts and ends a show`() {
        openDemoLibrary()
        compose.onNodeWithTag("library_screen").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        waitFor("full_window")
        showControls()

        compose.onNodeWithTag("library_screen").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        waitGone("full_window")
    }

    @Test
    fun `the loupe's button and the full window's play button both start a show`() {
        openDemoLibrary()
        press(Key.E)
        waitFor("loupe_slideshow_button")
        compose.onNodeWithTag("loupe_slideshow_button").performClick()
        waitFor("full_window")
        press(Key.Escape)
        waitGone("full_window")

        press(Key.F)
        waitFor("full_window")
        compose.onAllNodes(hasTestTag("zoomable_loupe_container")).onLast().performClick()
        waitFor("full_window_play")
        compose.onNodeWithTag("full_window_play").performClick()
        waitFor("slideshow_play_pause")
    }

    @Test
    fun `space pauses and resumes while a show plays`() {
        openDemoLibrary()
        compose.onNodeWithTag("library_screen").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        waitFor("full_window")
        showControls()
        // Frozen, so neither the controls' timer nor the show's own clock moves.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("slideshow_play_pause").assertContentDescriptionEquals("Pause slideshow")

        press(Key.Spacebar)
        compose.onNodeWithTag("slideshow_play_pause").assertContentDescriptionEquals("Play slideshow")

        press(Key.Spacebar)
        compose.onNodeWithTag("slideshow_play_pause").assertContentDescriptionEquals("Pause slideshow")
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
