package dev.shehran.rawcull.ui.library

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.shehran.rawcull.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** R6 D10: the histogram overlay on the demo library, switched by H, hidden in compare. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w1280dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistogramOverlayUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String) = compose.waitUntil(TIMEOUT_MS) { exists(tag) }

    private fun waitGone(tag: String) = compose.waitUntil(TIMEOUT_MS) { !exists(tag) }

    private fun press(key: Key) {
        compose.onNodeWithTag("library_screen").performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun openDemoLoupe() {
        waitFor("nav_library")
        compose.onNodeWithTag("nav_library").performClick()
        compose.waitUntil(TIMEOUT_MS) { exists("empty_demo_button") || exists("library_top_bar") }
        if (exists("empty_demo_button")) compose.onNodeWithTag("empty_demo_button").performClick()
        waitFor("grid_card_0")
        press(Key.E)
        waitFor("loupe_raw_image")
    }

    @Test
    fun `H switches the overlay in the loupe and it is hidden in compare`() {
        openDemoLoupe()
        try {
            assert(!exists("histogram_overlay"))
            press(Key.H)
            waitFor("histogram_overlay")

            press(Key.C)
            waitFor("compare_loupe_container")
            waitGone("histogram_overlay")
            press(Key.C)
            waitFor("histogram_overlay")

            press(Key.F)
            waitFor("full_window")
            assert(exists("histogram_overlay"))
            press(Key.F)
            waitGone("full_window")
        } finally {
            // Saved settings outlive this test.
            if (exists("histogram_overlay")) press(Key.H)
        }
        waitGone("histogram_overlay")
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
