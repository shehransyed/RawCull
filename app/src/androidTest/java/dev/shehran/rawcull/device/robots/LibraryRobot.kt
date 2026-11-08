package dev.shehran.rawcull.device.robots

import android.net.Uri
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import dev.shehran.rawcull.ui.library.LibraryViewModel
import java.io.File

/** Steps on the Library tab. Tags live here so tests read as flows. */
class LibraryRobot(private val rule: MainRule) {

    val viewModel: LibraryViewModel by lazy { rule.activityViewModel(LibraryViewModel.Factory) }

    fun openTab() = apply {
        rule.onNodeWithTag(NAV_TAB, useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    /** What the folder picker would hand the app; for fixture trees no picker is involved. */
    fun addLocation(treeUri: Uri) = apply {
        rule.runOnUiThread { viewModel.addLocation(treeUri) }
    }

    fun groupCount(): Int = rule.countNodes(hasTestTagPrefix(CARD_PREFIX))

    /** Waits until the grid shows exactly [count] groups. */
    fun awaitGroups(count: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS) = apply {
        rule.waitUntil(timeoutMs) { groupCount() == count }
    }

    fun clickGroup(index: Int) = apply {
        rule.onNodeWithTag("$CARD_PREFIX$index", useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    /** Sends [key] the way a keyboard would: to the Library's key handler. */
    fun press(key: Key) = apply {
        val screen = rule.onNode(hasTestTag(SCREEN), useUnmergedTree = true)
        screen.performSemanticsAction(SemanticsActions.RequestFocus)
        screen.performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    /** Waits for a sidecar in [dir] that contains every one of [snippets]; returns its name. */
    fun awaitSidecar(dir: File, vararg snippets: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        var found: File? = null
        rule.waitUntil(timeoutMs) {
            found = dir.listFiles { f -> f.name.endsWith(".xmp") }
                ?.firstOrNull { f -> f.readText().let { text -> snippets.all { it in text } } }
            found != null
        }
        return checkNotNull(found).name
    }

    companion object {
        const val NAV_TAB = "nav_library"
        const val SCREEN = "library_screen"
        const val CARD_PREFIX = "grid_card_"
    }
}
