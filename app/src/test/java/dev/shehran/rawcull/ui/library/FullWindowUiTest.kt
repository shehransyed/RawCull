package dev.shehran.rawcull.ui.library

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.espresso.Espresso
import dev.shehran.rawcull.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R6 D2: full window on the demo library, entered and left every way the plan lists. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w1280dp-h800dp")
class FullWindowUiTest {
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

    @Test
    fun `the grid's menu opens full window, and F, Esc and Back leave it`() {
        openDemoLibrary()
        compose.onNodeWithTag("library_overflow_button").performClick()
        waitFor("full_window_menu_item")
        compose.onNodeWithTag("full_window_menu_item").performClick()
        waitFor("full_window")

        press(Key.F)
        waitGone("full_window")

        press(Key.F)
        waitFor("full_window")
        press(Key.Escape)
        waitGone("full_window")

        press(Key.F)
        waitFor("full_window")
        Espresso.pressBack()
        waitGone("full_window")
        waitFor("grid_card_0")
    }

    @Test
    fun `the loupe button opens it and the overlay's exit button closes it`() {
        openDemoLibrary()
        press(Key.E)
        waitFor("loupe_full_window_button")
        compose.onNodeWithTag("loupe_full_window_button").performClick()
        waitFor("full_window")

        compose.onAllNodes(hasTestTag("zoomable_loupe_container")).onLast().performClick()
        waitFor("full_window_exit_button")
        compose.onNodeWithTag("full_window_exit_button").performClick()
        waitGone("full_window")
    }

    @Test
    fun `full window survives a configuration change`() {
        openDemoLibrary()
        press(Key.F)
        waitFor("full_window")

        compose.activityRule.scenario.recreate()

        waitFor("full_window")
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
