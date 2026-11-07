package dev.shehran.rawcull.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.shehran.rawcull.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings → Previews in the real app, at the window sizes of the spec. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PreviewSettingsUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun openSettings() {
        compose.waitUntilAtLeastOneExists(hasTestTag("nav_settings"), TIMEOUT_MS)
        compose.onNodeWithTag("nav_settings").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("settings_preview_cache_used"), TIMEOUT_MS)
    }

    private fun checkCacheSection() {
        compose.onNodeWithTag("settings_preview_cache_used").performScrollTo().assertTextContains("of 1 GB used", substring = true)
        compose.onNodeWithTag("preview_cache_limit_1024").assertIsSelected()
        compose.onNodeWithTag("preview_cache_limit_256").performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) {
            runCatching { compose.onNodeWithTag("settings_preview_cache_used").assertTextContains("of 256 MB used", substring = true) }.isSuccess
        }
        compose.onNodeWithTag("preview_cache_limit_256").assertIsSelected()
        compose.onNodeWithTag("preview_cache_limit_1024").performClick() // Settings outlive the test's app
        compose.onNodeWithTag("settings_clear_preview_cache").performScrollTo().performClick()
        compose.onNodeWithText("Clear preview cache?").assertIsDisplayed()
        compose.onNodeWithTag("confirm_clear_preview_cache").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("Clear preview cache?").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `the cache section works on a phone`() {
        openSettings()
        checkCacheSection()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `the cache section works on a phone on its side`() {
        openSettings()
        checkCacheSection()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `the cache section works on a large window`() {
        openSettings()
        checkCacheSection()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
