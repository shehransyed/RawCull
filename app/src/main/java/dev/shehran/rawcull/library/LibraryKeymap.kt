package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus

/** The keys the Library knows, so this file stays free of Android and Compose types. */
enum class LibraryKey(val label: String) {
    A("A"), B("B"), C("C"), D("D"), E("E"), F("F"), G("G"), H("H"), K("K"), L("L"), O("O"), P("P"), U("U"), X("X"), Y("Y"), Z("Z"),
    Digit0("0"), Digit1("1"), Digit2("2"), Digit3("3"), Digit4("4"), Digit5("5"),
    Digit6("6"), Digit7("7"), Digit8("8"), Digit9("9"),
    Grave("`"), LeftBracket("["), RightBracket("]"), Backslash("\\"), Slash("/"),
    Up("↑"), Down("↓"), Left("←"), Right("→"), Home("Home"), End("End"),
    Tab("Tab"), Space("Space"), Enter("Enter"), Backspace("Backspace"), Escape("Esc"),
}

/** What a key press asks for. */
sealed interface LibraryKeyAction {
    /** @param advance Shift or Caps Lock was on, so the selection moves on afterwards. */
    data class Cull(val edit: CullEdit, val advance: Boolean = false) : LibraryKeyAction
    data object Previous : LibraryKeyAction
    data object Next : LibraryKeyAction
    data object RowUp : LibraryKeyAction
    data object RowDown : LibraryKeyAction
    data object First : LibraryKeyAction
    data object Last : LibraryKeyAction
    data object ShowGrid : LibraryKeyAction
    data object ShowLoupe : LibraryKeyAction
    data object ToggleCompare : LibraryKeyAction
    data object ToggleZoom : LibraryKeyAction
    data object TogglePanes : LibraryKeyAction
    data object ToggleChrome : LibraryKeyAction
    data object ToggleFullWindow : LibraryKeyAction
    data object ToggleSlideshow : LibraryKeyAction
    data object TogglePeaking : LibraryKeyAction
    data object ToggleHistogram : LibraryKeyAction
    data object SlideshowPause : LibraryKeyAction
    data object Undo : LibraryKeyAction
    data object Redo : LibraryKeyAction
    data object SelectAll : LibraryKeyAction
    data object SelectNone : LibraryKeyAction
    data object FocusSearch : LibraryKeyAction
    data object ToggleFilterBar : LibraryKeyAction
    data object ToggleFilters : LibraryKeyAction
    data object EditTags : LibraryKeyAction
    data object ToggleInTargetCollection : LibraryKeyAction
    data object DeleteRejects : LibraryKeyAction
    data object ShowHelp : LibraryKeyAction
    data object Escape : LibraryKeyAction
}

data class ShortcutRow(val keys: String, val description: String)
data class ShortcutGroup(val title: String, val rows: List<ShortcutRow>)

/** The Lightroom Classic Library shortcuts. The help sheet is built from [bindings], so it can't drift. */
object LibraryKeymap {
    private enum class Group(val title: String) {
        FLAGS("Flags"),
        RATINGS("Ratings"),
        LABELS("Colour labels"),
        NAVIGATION("Moving around"),
        VIEWS("Views"),
        EDITING("Editing and selection"),
        FILTERS("Filters"),
    }

    private class Binding(
        val key: LibraryKey,
        val action: LibraryKeyAction,
        val group: Group,
        val description: String,
        val ctrl: Boolean = false,
        val shift: Boolean = false,
    ) {
        val keys: String
            get() = buildString {
                if (ctrl) append("Ctrl+")
                if (shift) append("Shift+")
                append(key.label)
            }
    }

    private fun cull(key: LibraryKey, edit: CullEdit, group: Group, description: String, ctrl: Boolean = false) =
        Binding(key, LibraryKeyAction.Cull(edit), group, description, ctrl)

    private val bindings: List<Binding> = buildList {
        add(cull(LibraryKey.P, CullEdit.Flag(CullStatus.PICK), Group.FLAGS, "Pick"))
        add(cull(LibraryKey.X, CullEdit.Flag(CullStatus.REJECT), Group.FLAGS, "Reject"))
        add(cull(LibraryKey.U, CullEdit.Flag(CullStatus.UNFLAGGED, toggle = false), Group.FLAGS, "Unflag"))
        add(cull(LibraryKey.Grave, CullEdit.Flag(CullStatus.PICK), Group.FLAGS, "Toggle pick and unflagged"))
        add(cull(LibraryKey.Up, CullEdit.FlagStep(1), Group.FLAGS, "Raise flag", ctrl = true))
        add(cull(LibraryKey.Down, CullEdit.FlagStep(-1), Group.FLAGS, "Lower flag", ctrl = true))

        val ratingKeys = listOf(
            LibraryKey.Digit0, LibraryKey.Digit1, LibraryKey.Digit2,
            LibraryKey.Digit3, LibraryKey.Digit4, LibraryKey.Digit5,
        )
        ratingKeys.forEachIndexed { stars, key ->
            add(cull(key, CullEdit.Rating(stars), Group.RATINGS, if (stars == 0) "Clear rating" else "Rate $stars"))
        }
        add(cull(LibraryKey.LeftBracket, CullEdit.RatingStep(-1), Group.RATINGS, "Lower rating"))
        add(cull(LibraryKey.RightBracket, CullEdit.RatingStep(1), Group.RATINGS, "Raise rating"))

        add(cull(LibraryKey.Digit6, CullEdit.LabelToggle(ColorLabel.RED), Group.LABELS, "Red label"))
        add(cull(LibraryKey.Digit7, CullEdit.LabelToggle(ColorLabel.YELLOW), Group.LABELS, "Yellow label"))
        add(cull(LibraryKey.Digit8, CullEdit.LabelToggle(ColorLabel.GREEN), Group.LABELS, "Green label"))
        add(cull(LibraryKey.Digit9, CullEdit.LabelToggle(ColorLabel.BLUE), Group.LABELS, "Blue label"))

        add(Binding(LibraryKey.Left, LibraryKeyAction.Previous, Group.NAVIGATION, "Previous photo"))
        add(Binding(LibraryKey.Right, LibraryKeyAction.Next, Group.NAVIGATION, "Next photo"))
        add(Binding(LibraryKey.Up, LibraryKeyAction.RowUp, Group.NAVIGATION, "Row up; previous photo in the loupe"))
        add(Binding(LibraryKey.Down, LibraryKeyAction.RowDown, Group.NAVIGATION, "Row down; next photo in the loupe"))
        add(Binding(LibraryKey.Home, LibraryKeyAction.First, Group.NAVIGATION, "First photo"))
        add(Binding(LibraryKey.End, LibraryKeyAction.Last, Group.NAVIGATION, "Last photo"))

        add(Binding(LibraryKey.G, LibraryKeyAction.ShowGrid, Group.VIEWS, "Grid"))
        add(Binding(LibraryKey.E, LibraryKeyAction.ShowLoupe, Group.VIEWS, "Loupe"))
        add(Binding(LibraryKey.C, LibraryKeyAction.ToggleCompare, Group.VIEWS, "Compare"))
        add(Binding(LibraryKey.Z, LibraryKeyAction.ToggleZoom, Group.VIEWS, "Toggle fit and 1:1"))
        add(Binding(LibraryKey.Space, LibraryKeyAction.ToggleZoom, Group.VIEWS, "Toggle fit and 1:1"))
        add(Binding(LibraryKey.F, LibraryKeyAction.ToggleFullWindow, Group.VIEWS, "Full window (Esc leaves it)"))
        add(Binding(LibraryKey.Enter, LibraryKeyAction.ToggleSlideshow, Group.VIEWS, "Slideshow: start or end", ctrl = true))
        add(Binding(LibraryKey.O, LibraryKeyAction.TogglePeaking, Group.VIEWS, "Focus peaking on or off"))
        add(Binding(LibraryKey.H, LibraryKeyAction.ToggleHistogram, Group.VIEWS, "Histogram overlay on or off"))
        add(Binding(LibraryKey.Tab, LibraryKeyAction.TogglePanes, Group.VIEWS, "Show or hide side panes"))
        add(Binding(LibraryKey.Tab, LibraryKeyAction.ToggleChrome, Group.VIEWS, "Show or hide all chrome", shift = true))
        add(Binding(LibraryKey.Slash, LibraryKeyAction.ShowHelp, Group.VIEWS, "Keyboard shortcuts", ctrl = true))
        add(Binding(LibraryKey.Slash, LibraryKeyAction.ShowHelp, Group.VIEWS, "Keyboard shortcuts", shift = true))
        add(Binding(LibraryKey.Escape, LibraryKeyAction.Escape, Group.VIEWS, "Step back: dialog, selection, loupe, All photos"))

        add(Binding(LibraryKey.Z, LibraryKeyAction.Undo, Group.EDITING, "Undo", ctrl = true))
        add(Binding(LibraryKey.Z, LibraryKeyAction.Redo, Group.EDITING, "Redo", ctrl = true, shift = true))
        add(Binding(LibraryKey.Y, LibraryKeyAction.Redo, Group.EDITING, "Redo", ctrl = true))
        add(Binding(LibraryKey.A, LibraryKeyAction.SelectAll, Group.EDITING, "Select all", ctrl = true))
        add(Binding(LibraryKey.D, LibraryKeyAction.SelectNone, Group.EDITING, "Select none", ctrl = true))
        add(Binding(LibraryKey.D, LibraryKeyAction.SelectNone, Group.EDITING, "Select none", ctrl = true, shift = true))
        add(Binding(LibraryKey.B, LibraryKeyAction.ToggleInTargetCollection, Group.EDITING, "Add to or remove from the target collection (opens the sheet without one)"))
        add(Binding(LibraryKey.K, LibraryKeyAction.EditTags, Group.EDITING, "Add tags", ctrl = true))
        add(Binding(LibraryKey.Backspace, LibraryKeyAction.DeleteRejects, Group.EDITING, "Delete rejects…", ctrl = true))

        add(Binding(LibraryKey.F, LibraryKeyAction.FocusSearch, Group.FILTERS, "Search", ctrl = true))
        add(Binding(LibraryKey.Backslash, LibraryKeyAction.ToggleFilterBar, Group.FILTERS, "Show or hide the filter bar"))
        add(Binding(LibraryKey.L, LibraryKeyAction.ToggleFilters, Group.FILTERS, "Filters on or off", ctrl = true))
    }

    private val byPress: Map<Triple<LibraryKey, Boolean, Boolean>, Binding> =
        bindings.associateBy { Triple(it.key, it.ctrl, it.shift) }

    /**
     * @param meta treated as Ctrl, for macOS-style keyboards.
     * @return null for a key that means nothing here. While a text field has focus that's every key
     * but Esc, so typing and the field's own shortcuts are left alone.
     */
    fun actionFor(
        key: LibraryKey,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
        capsLock: Boolean = false,
        textFieldFocused: Boolean = false,
        slideshowActive: Boolean = false,
    ): LibraryKeyAction? {
        if (textFieldFocused) return LibraryKeyAction.Escape.takeIf { key == LibraryKey.Escape }
        if (alt) return null
        val control = ctrl || meta
        // In a slideshow Space pauses, since zooming there would stop the show anyway.
        if (slideshowActive && key == LibraryKey.Space && !control && !shift) return LibraryKeyAction.SlideshowPause

        byPress[Triple(key, control, shift)]?.let { return withAdvance(it.action, capsLock) }
        // Shift on a culling key means "and advance"; it has no binding of its own.
        if (shift) {
            val action = byPress[Triple(key, control, false)]?.action
            if (action is LibraryKeyAction.Cull) return action.copy(advance = true)
        }
        return null
    }

    private fun withAdvance(action: LibraryKeyAction, capsLock: Boolean): LibraryKeyAction =
        if (capsLock && action is LibraryKeyAction.Cull) action.copy(advance = true) else action

    /** Every binding for the help sheet; bindings sharing a description share a row. */
    val helpGroups: List<ShortcutGroup> = Group.entries.map { group ->
        val rows = bindings.filter { it.group == group }
            .groupBy { it.description }
            .map { (description, same) -> ShortcutRow(same.joinToString(", ") { it.keys }, description) }
        val extras = when (group) {
            Group.FLAGS -> ADVANCE_ROWS
            Group.VIEWS -> SLIDESHOW_ROWS
            else -> emptyList()
        }
        ShortcutGroup(group.title, rows + extras)
    }

    /** One entry per bound key combination, for checking the sheet against the map. */
    val boundKeys: List<String> get() = bindings.map { it.keys }
}

private val SLIDESHOW_ROWS = listOf(
    ShortcutRow("Space (in a slideshow)", "Pause or resume"),
    ShortcutRow("← → (in a slideshow)", "Previous or next photo, restarting its time"),
)

private val ADVANCE_ROWS = listOf(
    ShortcutRow("Shift + flag, rating or label key", "Apply and go to the next photo"),
    ShortcutRow("Caps Lock", "Every flag, rating and label key advances"),
)
