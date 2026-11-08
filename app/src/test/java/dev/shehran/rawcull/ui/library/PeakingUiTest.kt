package dev.shehran.rawcull.ui.library

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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

/** R6 D7: focus peaking on the demo library, switched by O and the loupe toolbar. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w1280dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PeakingUiTest {
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
    fun `O and the toolbar button switch the overlay in the loupe`() {
        openDemoLoupe()
        assert(!exists("peaking_overlay"))
        compose.onNodeWithTag("loupe_peaking_button").assertIsOff()

        press(Key.O)
        waitFor("peaking_overlay")
        compose.onNodeWithTag("loupe_peaking_button").assertIsOn()

        compose.onNodeWithTag("loupe_peaking_button").performClick()
        waitGone("peaking_overlay")
        compose.onNodeWithTag("loupe_peaking_button").assertIsOff()
    }

    @Test
    fun `the overlay follows the next photo and carries into full window`() {
        openDemoLoupe()
        press(Key.O)
        waitFor("peaking_overlay")

        press(Key.DirectionRight)
        waitFor("peaking_overlay")

        press(Key.F)
        waitFor("full_window")
        waitFor("peaking_overlay")
        press(Key.O)
        waitGone("peaking_overlay")
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
