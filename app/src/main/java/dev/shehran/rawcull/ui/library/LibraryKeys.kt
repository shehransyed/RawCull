package dev.shehran.rawcull.ui.library

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.shehran.rawcull.library.LibraryKey
import dev.shehran.rawcull.library.LibraryKeyAction
import dev.shehran.rawcull.library.LibraryKeymap

private val KEYS: Map<Key, LibraryKey> = mapOf(
    Key.A to LibraryKey.A,
    Key.B to LibraryKey.B,
    Key.C to LibraryKey.C,
    Key.D to LibraryKey.D,
    Key.E to LibraryKey.E,
    Key.F to LibraryKey.F,
    Key.G to LibraryKey.G,
    Key.H to LibraryKey.H,
    Key.K to LibraryKey.K,
    Key.L to LibraryKey.L,
    Key.O to LibraryKey.O,
    Key.P to LibraryKey.P,
    Key.U to LibraryKey.U,
    Key.X to LibraryKey.X,
    Key.Y to LibraryKey.Y,
    Key.Z to LibraryKey.Z,
    Key.Zero to LibraryKey.Digit0,
    Key.One to LibraryKey.Digit1,
    Key.Two to LibraryKey.Digit2,
    Key.Three to LibraryKey.Digit3,
    Key.Four to LibraryKey.Digit4,
    Key.Five to LibraryKey.Digit5,
    Key.Six to LibraryKey.Digit6,
    Key.Seven to LibraryKey.Digit7,
    Key.Eight to LibraryKey.Digit8,
    Key.Nine to LibraryKey.Digit9,
    Key.NumPad0 to LibraryKey.Digit0,
    Key.NumPad1 to LibraryKey.Digit1,
    Key.NumPad2 to LibraryKey.Digit2,
    Key.NumPad3 to LibraryKey.Digit3,
    Key.NumPad4 to LibraryKey.Digit4,
    Key.NumPad5 to LibraryKey.Digit5,
    Key.NumPad6 to LibraryKey.Digit6,
    Key.NumPad7 to LibraryKey.Digit7,
    Key.NumPad8 to LibraryKey.Digit8,
    Key.NumPad9 to LibraryKey.Digit9,
    Key.Grave to LibraryKey.Grave,
    Key.LeftBracket to LibraryKey.LeftBracket,
    Key.RightBracket to LibraryKey.RightBracket,
    Key.Backslash to LibraryKey.Backslash,
    Key.Slash to LibraryKey.Slash,
    Key.DirectionUp to LibraryKey.Up,
    Key.DirectionDown to LibraryKey.Down,
    Key.DirectionLeft to LibraryKey.Left,
    Key.DirectionRight to LibraryKey.Right,
    Key.NavigatePrevious to LibraryKey.Left,
    Key.NavigateNext to LibraryKey.Right,
    Key.MoveHome to LibraryKey.Home,
    Key.MoveEnd to LibraryKey.End,
    Key.Tab to LibraryKey.Tab,
    Key.Spacebar to LibraryKey.Space,
    Key.Enter to LibraryKey.Enter,
    Key.NumPadEnter to LibraryKey.Enter,
    Key.Backspace to LibraryKey.Backspace,
    Key.Escape to LibraryKey.Escape,
)

/** What this key press means here, or null if it means nothing. */
fun KeyEvent.toLibraryAction(capsLock: Boolean, textFieldFocused: Boolean, slideshowActive: Boolean = false): LibraryKeyAction? {
    val mapped = KEYS[key] ?: return null
    return LibraryKeymap.actionFor(
        key = mapped,
        ctrl = isCtrlPressed,
        shift = isShiftPressed,
        alt = isAltPressed,
        meta = isMetaPressed,
        capsLock = capsLock,
        textFieldFocused = textFieldFocused,
        slideshowActive = slideshowActive,
    )
}

/** Android reports Caps Lock in the event's meta state. */
val KeyEvent.isCapsLockOn: Boolean get() = nativeKeyEvent.isCapsLockOn

/**
 * Sheets are windows of their own, so the Library's key handler never sees Esc while one is open.
 * Put this on the sheet's content. It takes focus so that the key arrives, unless a field in the
 * sheet is about to.
 */
@Composable
fun Modifier.dismissOnEscape(takeFocus: Boolean = true, onDismiss: () -> Unit): Modifier {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (takeFocus) focus.requestFocus() }
    return focusRequester(focus)
        .focusable()
        .onPreviewKeyEvent { event ->
            val isEscape = event.key == Key.Escape
            if (isEscape && event.type == KeyEventType.KeyDown) onDismiss()
            isEscape
        }
}
