package dev.shehran.rawcull.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.preview.FakePreviewRepository
import dev.shehran.rawcull.data.preview.FullSource
import dev.shehran.rawcull.data.preview.HitLevel
import dev.shehran.rawcull.data.preview.PreviewImage
import dev.shehran.rawcull.data.preview.PreviewKind
import dev.shehran.rawcull.data.preview.PreviewLane
import dev.shehran.rawcull.data.preview.PreviewRepository
import dev.shehran.rawcull.data.preview.PreviewResult
import dev.shehran.rawcull.data.preview.PreviewSource
import dev.shehran.rawcull.data.preview.PreviewSourceInfo
import dev.shehran.rawcull.data.preview.PreviewTiming
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The loupe's zoom pill against a pipeline that hands back a known source (D10). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PreviewUiTest {
    @get:Rule
    val compose = createComposeRule()

    /** A fit preview of the right shape; the 100 % source is [fullWidth] × [fullHeight]. */
    private class Repo(val fullWidth: Int, val fullHeight: Int, val kind: PreviewKind) : PreviewRepository by FakePreviewRepository() {
        override suspend fun fitPreview(source: PreviewSource, widthPx: Int, heightPx: Int, lane: PreviewLane): PreviewResult {
            val scale = minOf(1f, minOf(widthPx.toFloat() / fullWidth, heightPx.toFloat() / fullHeight))
            val bitmap = Bitmap.createBitmap((fullWidth * scale).toInt().coerceAtLeast(1), (fullHeight * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val info = PreviewSourceInfo(kind, fullWidth, fullHeight)
            return PreviewResult.Ready(PreviewImage(bitmap, 1, info, FullSource(info, 1), PreviewTiming(HitLevel.FILE)))
        }
    }

    private val photo = PhotoAsset(
        id = "p1",
        rootUri = "content://root",
        fileName = "DSC0001.ARW",
        fileUri = "content://root/DSC0001.ARW",
        format = PhotoFormat.ARW,
        fileSizeBytes = 25_000_000,
        fileLastModified = 1L,
        dateTaken = 1L,
        width = 6000,
        height = 4000,
    )

    private fun show(repository: PreviewRepository) {
        compose.setContent {
            CompositionLocalProvider(LocalPreviewRepository provides repository) {
                ZoomableLoupeView(photo = photo, onNext = {}, onPrevious = {}, onSyncChipClick = {})
            }
        }
        compose.waitUntilAtLeastOneExists(hasTestTag("loupe_raw_image"), TIMEOUT_MS)
    }

    private fun pill() = compose.onNodeWithTag("zoom_pill_text", useUnmergedTree = true)

    private fun previewOnly() = compose.onNodeWithTag("zoom_preview_only", useUnmergedTree = true)

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `a full-size source reads its fit percentage, then 100 percent`() {
        show(Repo(6000, 4000, PreviewKind.COMPANION_JPEG))
        compose.waitUntil(TIMEOUT_MS) { pill().fetchText().let { it.endsWith("%") && it != "100%" } }
        val fit = pill().fetchText()
        compose.onNodeWithTag("one_to_one_zoom_button").performClick()
        compose.waitForIdle()
        pill().assertTextEquals("100%")
        compose.onNodeWithTag("one_to_one_zoom_button").performClick()
        compose.waitForIdle()
        pill().assertTextEquals(fit)
        compose.onNodeWithTag("zoom_preview_only", useUnmergedTree = true).assertDoesNotExist() // A full-size source
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `a small embedded preview says so on a phone`() {
        show(Repo(1616, 1080, PreviewKind.EMBEDDED_JPEG))
        compose.waitUntil(TIMEOUT_MS) { runCatching { previewOnly().assertTextEquals("Preview only 1616 × 1080") }.isSuccess }
        compose.onNodeWithTag("one_to_one_zoom_button").performClick()
        compose.waitForIdle()
        pill().assertTextEquals("100%")
        previewOnly().assertTextEquals("Preview only 1616 × 1080")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `a preview no larger than fit toggles to twice fit`() {
        show(Repo(400, 300, PreviewKind.EMBEDDED_JPEG))
        compose.waitUntil(TIMEOUT_MS) { runCatching { previewOnly().assertTextEquals("Preview only 400 × 300") }.isSuccess }
        val fitText = pill().fetchText()
        compose.onNodeWithTag("one_to_one_zoom_button").performClick()
        compose.waitForIdle()
        val zoomed = pill().fetchText()
        val fit = fitText.substringBefore('%').toInt()
        val after = zoomed.substringBefore('%').toInt()
        assert(after in (2 * fit - 1)..(2 * fit + 1)) { "$fitText → $zoomed" }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.fetchText(): String =
        fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
