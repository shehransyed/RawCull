package dev.shehran.rawcull.ui

import androidx.compose.ui.input.key.Key

/** What a hardware key does on the Library screen. */
sealed interface LibraryKeyAction {
    data object TogglePick : LibraryKeyAction
    data object ToggleReject : LibraryKeyAction
    data object Unflag : LibraryKeyAction
    data class Rate(val stars: Int) : LibraryKeyAction
    data object Previous : LibraryKeyAction
    data object Next : LibraryKeyAction
    data object ToggleCompare : LibraryKeyAction
    data object ShowAllPhotos : LibraryKeyAction
}

object LibraryKeys {
    fun actionFor(key: Key): LibraryKeyAction? = when (key) {
        Key.P -> LibraryKeyAction.TogglePick
        Key.X -> LibraryKeyAction.ToggleReject
        Key.U -> LibraryKeyAction.Unflag
        Key.Zero -> LibraryKeyAction.Rate(0)
        Key.One -> LibraryKeyAction.Rate(1)
        Key.Two -> LibraryKeyAction.Rate(2)
        Key.Three -> LibraryKeyAction.Rate(3)
        Key.Four -> LibraryKeyAction.Rate(4)
        Key.Five -> LibraryKeyAction.Rate(5)
        Key.DirectionLeft, Key.NavigatePrevious -> LibraryKeyAction.Previous
        Key.DirectionRight, Key.NavigateNext -> LibraryKeyAction.Next
        Key.C -> LibraryKeyAction.ToggleCompare
        Key.Escape -> LibraryKeyAction.ShowAllPhotos
        else -> null
    }
}
