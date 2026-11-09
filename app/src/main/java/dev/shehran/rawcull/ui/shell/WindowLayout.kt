package dev.shehran.rawcull.ui.shell

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/** Material width classes; see the window size spec in `docs/plans/R3_shell_library_import.md`. */
enum class WidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
    LARGE,
    EXTRA_LARGE,
}

/** What the current window allows. Screens read this instead of measuring for themselves. */
data class WindowLayout(val width: WidthClass, val isHeightCompact: Boolean) {
    val usesBottomBar: Boolean get() = width == WidthClass.COMPACT
    val usesWideRail: Boolean get() = width >= WidthClass.LARGE
    val showsFolderPane: Boolean get() = width >= WidthClass.EXPANDED
    val showsSideInspector: Boolean get() = width >= WidthClass.MEDIUM
    val showsSideBySide: Boolean get() = width >= WidthClass.MEDIUM

    /** Below this the filter controls live in a sheet, behind a row of the active ones. */
    val showsFilterBar: Boolean get() = width >= WidthClass.EXPANDED

    /** Below this, folder pane plus inspector leave the photo too little room, so it starts closed. */
    val opensInspectorByDefault: Boolean get() = width >= WidthClass.LARGE

    /** A phone on its side has height to spare for neither a bottom culling bar nor a filmstrip. */
    val cullingBarAtSide: Boolean get() = isHeightCompact && width >= WidthClass.MEDIUM
    val showsFilmstrip: Boolean get() = !isHeightCompact

    companion object {
        private const val MEDIUM_DP = 600
        private const val EXPANDED_DP = 840
        private const val LARGE_DP = 1200
        private const val EXTRA_LARGE_DP = 1600
        private const val MEDIUM_HEIGHT_DP = 480

        fun of(widthDp: Int, heightDp: Int): WindowLayout = WindowLayout(
            width = when {
                widthDp >= EXTRA_LARGE_DP -> WidthClass.EXTRA_LARGE
                widthDp >= LARGE_DP -> WidthClass.LARGE
                widthDp >= EXPANDED_DP -> WidthClass.EXPANDED
                widthDp >= MEDIUM_DP -> WidthClass.MEDIUM
                else -> WidthClass.COMPACT
            },
            isHeightCompact = heightDp < MEDIUM_HEIGHT_DP,
        )
    }
}

/** Set only by the device layout tests (R8 D14): the window size, as width to height in dp, to lay out for. Null in the app. */
val LocalWindowSizeOverride = staticCompositionLocalOf<Pair<Int, Int>?> { null }

@Composable
fun rememberWindowLayout(): WindowLayout {
    LocalWindowSizeOverride.current?.let { (width, height) -> return remember(width, height) { WindowLayout.of(width, height) } }
    val sizeClass = currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true).windowSizeClass
    return remember(sizeClass) { WindowLayout.of(sizeClass.minWidthDp, sizeClass.minHeightDp) }
}
