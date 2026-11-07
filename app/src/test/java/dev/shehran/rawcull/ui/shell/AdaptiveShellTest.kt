package dev.shehran.rawcull.ui.shell

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.shehran.rawcull.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real app shell at the window sizes of the spec. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AdaptiveShellTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitFor(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), TIMEOUT_MS)

    private fun openDemoLibrary() {
        waitFor("empty_demo_button")
        compose.onNodeWithTag("empty_demo_button").performClick()
        waitFor("library_top_bar")
    }

    /** A bottom bar sits below the middle of the window; a rail starts near its top. */
    private fun isBottomBar(): Boolean {
        val item = compose.onNodeWithTag("nav_library").getBoundsInRoot()
        val root = compose.onRoot().getBoundsInRoot()
        return item.top > root.bottom / 2
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `phone portrait uses a bottom bar`() {
        waitFor("nav_library")
        assertTrue(isBottomBar())
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `phone landscape uses a rail`() {
        waitFor("nav_library")
        val item = compose.onNodeWithTag("nav_library").getBoundsInRoot()
        assertTrue(item.left < 120.dp)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `large windows use a rail`() {
        waitFor("nav_library")
        assertTrue(!isBottomBar())
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `the folder pane is open beside the content on expanded windows`() {
        openDemoLibrary()
        waitFor("folder_pane")
        compose.onNodeWithTag("folder_pane").assertIsDisplayed()
        compose.onNodeWithTag("right_inspector_sidebar").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `the folder pane stays out of the way on phones`() {
        openDemoLibrary()
        compose.onNodeWithTag("right_inspector_sidebar").assertDoesNotExist()
        compose.onNodeWithTag("folder_pane").assertIsNotDisplayed()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `the folder pane is a drawer on medium windows`() {
        openDemoLibrary()
        compose.onNodeWithTag("folder_pane").assertIsNotDisplayed()
    }

    /** A real phone on its side is Expanded by width, but the photo still owns the screen. */
    @Test
    @Config(qualifiers = "w915dp-h412dp")
    fun `the phone landscape loupe has no panes and no navigation`() {
        openDemoLibrary()
        waitFor("folder_pane")
        compose.onNodeWithTag("view_mode_loupe").performClick()
        waitFor("culling_side_bar")

        compose.onNodeWithTag("folder_pane").assertDoesNotExist()
        compose.onNodeWithTag("library_top_bar").assertDoesNotExist()
        compose.onNodeWithTag("nav_library").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `Next stays disabled until there is a source and a destination`() {
        waitFor("nav_import")
        compose.onNodeWithTag("nav_import").performClick()
        waitFor("import_next_button")
        compose.onNodeWithTag("import_next_button").assertIsNotEnabled()
        compose.onNodeWithTag("no_location_warning").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `settings opens inside its nav entry`() {
        waitFor("nav_settings")
        compose.onNodeWithTag("nav_settings").performClick()
        waitFor("settings_screen")
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `the selected tab survives a configuration change`() {
        waitFor("nav_settings")
        compose.onNodeWithTag("nav_settings").performClick()
        waitFor("settings_screen")

        compose.activityRule.scenario.recreate()

        waitFor("settings_screen")
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `the view mode survives a configuration change`() {
        openDemoLibrary()
        compose.onNodeWithTag("view_mode_loupe").performClick()
        waitFor("culling_action_bar")

        compose.activityRule.scenario.recreate()

        waitFor("culling_action_bar")
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
