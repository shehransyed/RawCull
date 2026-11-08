package dev.shehran.rawcull.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.XmpSyncStatus
import dev.shehran.rawcull.data.repository.PeakingColor

/** A sync state's chip: its background and its text. */
@Immutable
data class SyncColors(val container: Color, val content: Color)

/** What culling needs beyond Material's roles. */
@Immutable
data class RawCullColors(
    val pick: Color,
    val reject: Color,
    val star: Color,
    val labelRed: Color,
    val labelYellow: Color,
    val labelGreen: Color,
    val labelBlue: Color,
    val labelPurple: Color,
    val syncSaved: SyncColors,
    val syncSaving: SyncColors,
    val syncError: SyncColors,
    val syncReadOnly: SyncColors,
    val peakingRed: Color = PeakingRed,
    val peakingYellow: Color = PeakingYellow,
    val peakingCyan: Color = PeakingCyan,
    val peakingGreen: Color = PeakingGreen,
) {
    fun peaking(color: PeakingColor): Color = when (color) {
        PeakingColor.RED -> peakingRed
        PeakingColor.YELLOW -> peakingYellow
        PeakingColor.CYAN -> peakingCyan
        PeakingColor.GREEN -> peakingGreen
    }

    fun label(label: ColorLabel): Color = when (label) {
        ColorLabel.NONE -> Color.Transparent
        ColorLabel.RED -> labelRed
        ColorLabel.YELLOW -> labelYellow
        ColorLabel.GREEN -> labelGreen
        ColorLabel.BLUE -> labelBlue
        ColorLabel.PURPLE -> labelPurple
    }

    fun sync(status: XmpSyncStatus): SyncColors = when (status) {
        XmpSyncStatus.SYNCED -> syncSaved
        XmpSyncStatus.PENDING, XmpSyncStatus.WRITING -> syncSaving
        XmpSyncStatus.ERROR -> syncError
        XmpSyncStatus.READ_ONLY -> syncReadOnly
    }
}

val DarkroomColors = RawCullColors(
    pick = EmeraldPick,
    reject = RoseReject,
    star = AmberRating,
    labelRed = RoseReject,
    labelYellow = LabelYellow,
    labelGreen = EmeraldPick,
    labelBlue = LabelBlue,
    labelPurple = LabelPurple,
    syncSaved = SyncColors(SyncSavedContainer, PairedMint),
    syncSaving = SyncColors(SyncSavingContainer, PrimaryCyan),
    syncError = SyncColors(ErrorContainer, ErrorText),
    syncReadOnly = SyncColors(SyncReadOnlyContainer, TextChip),
)

val LocalRawCullColors = staticCompositionLocalOf { DarkroomColors }

/** Reads the extension colours: `RawCullTheme.colors.pick`. */
object RawCullTheme {
    val colors: RawCullColors
        @Composable
        @ReadOnlyComposable
        get() = LocalRawCullColors.current
}
