package dev.shehran.rawcull.device.robots

import android.net.Uri
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.shehran.rawcull.ui.importer.ImportViewModel

/** Steps on the Import tab. R8.5 extends it with the import flow itself. */
class ImportRobot(private val rule: MainRule) {

    val viewModel: ImportViewModel by lazy { rule.activityViewModel(ImportViewModel.Factory) }

    fun openTab() = apply {
        rule.onNodeWithTag(NAV_TAB, useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    /** What the folder picker would hand the app. */
    fun pickSource(treeUri: Uri) = apply {
        rule.runOnUiThread { viewModel.onFolderPicked(treeUri) }
    }

    fun awaitPreviewTiles(count: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS) = apply {
        rule.waitUntil(timeoutMs) { rule.countNodes(hasTestTag(PREVIEW_TILE)) == count }
    }

    fun tap(tag: String) = apply {
        rule.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    companion object {
        const val NAV_TAB = "nav_import"
        const val PREVIEW_TILE = "preview_tile"
        const val CULL_IN_LIBRARY = "cull_in_library_button"
        const val OPEN_COLLECTION = "open_collection_button"
    }
}
