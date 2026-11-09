package dev.shehran.rawcull.device.layout

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import dev.shehran.rawcull.device.DeviceOut
import java.io.File

/**
 * R8 D14: geometry checks on the semantics tree of a screen drawn at a forced size. Boxes are in
 * dp of the forced window, relative to its top-left, whatever the real display scaled it by.
 */
object LayoutChecks {
    const val ROOT_TAG = "layout_root"
    private const val MIN_TARGET_DP = 48f
    private const val TOLERANCE_DP = 1f
    private const val MIN_OVERLAP_DP2 = 4f

    data class Control(val name: String, val box: Rect, val scrollable: Boolean)

    data class Findings(val touchTargets: List<String>, val overlaps: List<String>, val clipped: List<String>, val controls: Int)

    fun check(compose: ComposeTestRule, screen: String, density: Float, only: (String) -> Boolean = { true }): Findings {
        val root = compose.onNodeWithTag(ROOT_TAG).fetchSemanticsNode()
        val rootBounds = root.boundsInRoot
        val scale = if (root.size.width == 0) 1f else rootBounds.width / root.size.width
        val unit = scale * density // device pixels as laid out → dp of the forced window
        val viewport = Rect(0f, 0f, rootBounds.width / unit, rootBounds.height / unit)

        val controls = compose.onAllNodes(hasClickAction(), useUnmergedTree = false).fetchSemanticsNodes()
            .filter { it.config.getOrNull(SemanticsProperties.InvisibleToUser) == null }
            .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
            .map { node ->
                val b = node.boundsInRoot
                val box = Rect(
                    (b.left - rootBounds.left) / unit,
                    (b.top - rootBounds.top) / unit,
                    (b.right - rootBounds.left) / unit,
                    (b.bottom - rootBounds.top) / unit,
                )
                Control(nameOf(node), box, inScrollable(node))
            }
            .filter { it.box.overlaps(viewport) }
            .filter { only(it.name) }

        val touch = controls.filter { minOf(it.box.width, it.box.height) < MIN_TARGET_DP - TOLERANCE_DP }
            .map { "$screen: ${it.name} ${it.box.width.toInt()}×${it.box.height.toInt()} dp" }

        val clipped = controls.filterNot { it.scrollable }.filter { !contains(viewport, it.box) }
            .map { "$screen: ${it.name} reaches outside the window (${it.box.format()})" }

        val overlaps = buildList {
            for (i in controls.indices) for (j in i + 1 until controls.size) {
                val a = controls[i]
                val b = controls[j]
                if (contains(a.box, b.box) || contains(b.box, a.box)) continue
                val w = minOf(a.box.right, b.box.right) - maxOf(a.box.left, b.box.left)
                val h = minOf(a.box.bottom, b.box.bottom) - maxOf(a.box.top, b.box.top)
                if (w > 0 && h > 0 && w * h > MIN_OVERLAP_DP2) add("$screen: ${a.name} overlaps ${b.name} (${w.toInt()}×${h.toInt()} dp)")
            }
        }
        return Findings(touch, overlaps, clipped, controls.size)
    }

    /** Saves the forced window as `build/device-screens/<name>.png` (the runner pulls the folder). */
    fun screenshot(compose: ComposeTestRule, name: String) {
        val bitmap = compose.onNodeWithTag(ROOT_TAG).captureToImage().asAndroidBitmap()
        File(DeviceOut.screens(), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    private fun nameOf(node: SemanticsNode): String {
        val tag = node.config.getOrNull(SemanticsProperties.TestTag)
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        val text = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
        return tag ?: description ?: text?.take(24)?.let { "\"$it\"" } ?: "unnamed@${node.boundsInRoot.left.toInt()},${node.boundsInRoot.top.toInt()}"
    }

    private fun inScrollable(node: SemanticsNode): Boolean {
        var parent = node.parent
        while (parent != null) {
            val config = parent.config
            if (config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null ||
                config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null ||
                config.getOrNull(SemanticsActions.ScrollBy) != null
            ) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    private fun Rect.overlaps(other: Rect) = left < other.right && right > other.left && top < other.bottom && bottom > other.top

    private fun contains(outer: Rect, inner: Rect) =
        inner.left >= outer.left - TOLERANCE_DP && inner.top >= outer.top - TOLERANCE_DP &&
            inner.right <= outer.right + TOLERANCE_DP && inner.bottom <= outer.bottom + TOLERANCE_DP

    private fun Rect.format() = "${left.toInt()},${top.toInt()} → ${right.toInt()},${bottom.toInt()}"

}
