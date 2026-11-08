package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.library.LibraryKey.A
import dev.shehran.rawcull.library.LibraryKey.Backslash
import dev.shehran.rawcull.library.LibraryKey.Backspace
import dev.shehran.rawcull.library.LibraryKey.C
import dev.shehran.rawcull.library.LibraryKey.D
import dev.shehran.rawcull.library.LibraryKey.Digit0
import dev.shehran.rawcull.library.LibraryKey.Digit1
import dev.shehran.rawcull.library.LibraryKey.Digit2
import dev.shehran.rawcull.library.LibraryKey.Digit3
import dev.shehran.rawcull.library.LibraryKey.Digit4
import dev.shehran.rawcull.library.LibraryKey.Digit5
import dev.shehran.rawcull.library.LibraryKey.Digit6
import dev.shehran.rawcull.library.LibraryKey.Digit7
import dev.shehran.rawcull.library.LibraryKey.Digit8
import dev.shehran.rawcull.library.LibraryKey.Digit9
import dev.shehran.rawcull.library.LibraryKey.Down
import dev.shehran.rawcull.library.LibraryKey.E
import dev.shehran.rawcull.library.LibraryKey.End
import dev.shehran.rawcull.library.LibraryKey.Enter
import dev.shehran.rawcull.library.LibraryKey.Escape
import dev.shehran.rawcull.library.LibraryKey.F
import dev.shehran.rawcull.library.LibraryKey.G
import dev.shehran.rawcull.library.LibraryKey.Grave
import dev.shehran.rawcull.library.LibraryKey.Home
import dev.shehran.rawcull.library.LibraryKey.K
import dev.shehran.rawcull.library.LibraryKey.L
import dev.shehran.rawcull.library.LibraryKey.Left
import dev.shehran.rawcull.library.LibraryKey.LeftBracket
import dev.shehran.rawcull.library.LibraryKey.O
import dev.shehran.rawcull.library.LibraryKey.P
import dev.shehran.rawcull.library.LibraryKey.Right
import dev.shehran.rawcull.library.LibraryKey.RightBracket
import dev.shehran.rawcull.library.LibraryKey.Slash
import dev.shehran.rawcull.library.LibraryKey.Space
import dev.shehran.rawcull.library.LibraryKey.Tab
import dev.shehran.rawcull.library.LibraryKey.U
import dev.shehran.rawcull.library.LibraryKey.Up
import dev.shehran.rawcull.library.LibraryKey.X
import dev.shehran.rawcull.library.LibraryKey.Y
import dev.shehran.rawcull.library.LibraryKey.Z
import dev.shehran.rawcull.library.LibraryKeyAction.Cull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryKeymapTest {
    private fun press(key: LibraryKey, ctrl: Boolean = false, shift: Boolean = false, caps: Boolean = false) =
        LibraryKeymap.actionFor(key, ctrl = ctrl, shift = shift, capsLock = caps)

    private val pick = CullEdit.Flag(CullStatus.PICK)

    @Test
    fun `flags`() {
        assertEquals(Cull(pick), press(P))
        assertEquals(Cull(CullEdit.Flag(CullStatus.REJECT)), press(X))
        assertEquals(Cull(CullEdit.Flag(CullStatus.UNFLAGGED, toggle = false)), press(U))
        assertEquals(Cull(pick), press(Grave))
        assertEquals(Cull(CullEdit.FlagStep(1)), press(Up, ctrl = true))
        assertEquals(Cull(CullEdit.FlagStep(-1)), press(Down, ctrl = true))
    }

    @Test
    fun `ratings`() {
        listOf(Digit0, Digit1, Digit2, Digit3, Digit4, Digit5).forEachIndexed { stars, key ->
            assertEquals(Cull(CullEdit.Rating(stars)), press(key))
        }
        assertEquals(Cull(CullEdit.RatingStep(-1)), press(LeftBracket))
        assertEquals(Cull(CullEdit.RatingStep(1)), press(RightBracket))
    }

    @Test
    fun `labels, with purple left to the menu`() {
        assertEquals(Cull(CullEdit.LabelToggle(ColorLabel.RED)), press(Digit6))
        assertEquals(Cull(CullEdit.LabelToggle(ColorLabel.YELLOW)), press(Digit7))
        assertEquals(Cull(CullEdit.LabelToggle(ColorLabel.GREEN)), press(Digit8))
        assertEquals(Cull(CullEdit.LabelToggle(ColorLabel.BLUE)), press(Digit9))
    }

    @Test
    fun `shift applies and advances on every culling key`() {
        val culling = listOf(P, X, U, Grave, Digit0, Digit3, Digit5, Digit6, Digit9, LeftBracket, RightBracket)
        culling.forEach { key ->
            val plain = press(key) as Cull
            assertEquals(plain.copy(advance = true), press(key, shift = true))
        }
        assertEquals(Cull(CullEdit.FlagStep(1), advance = true), press(Up, ctrl = true, shift = true))
    }

    @Test
    fun `caps lock advances culling keys and nothing else`() {
        assertEquals(Cull(pick, advance = true), press(P, caps = true))
        assertEquals(Cull(CullEdit.Rating(3), advance = true), press(Digit3, caps = true))
        assertEquals(LibraryKeyAction.Next, press(Right, caps = true))
        assertEquals(LibraryKeyAction.ShowGrid, press(G, caps = true))
    }

    @Test
    fun `navigation`() {
        assertEquals(LibraryKeyAction.Previous, press(Left))
        assertEquals(LibraryKeyAction.Next, press(Right))
        assertEquals(LibraryKeyAction.RowUp, press(Up))
        assertEquals(LibraryKeyAction.RowDown, press(Down))
        assertEquals(LibraryKeyAction.First, press(Home))
        assertEquals(LibraryKeyAction.Last, press(End))
    }

    @Test
    fun `views`() {
        assertEquals(LibraryKeyAction.ShowGrid, press(G))
        assertEquals(LibraryKeyAction.ShowLoupe, press(E))
        assertEquals(LibraryKeyAction.ToggleCompare, press(C))
        assertEquals(LibraryKeyAction.ToggleZoom, press(Z))
        assertEquals(LibraryKeyAction.ToggleZoom, press(Space))
        assertEquals(LibraryKeyAction.TogglePanes, press(Tab))
        assertEquals(LibraryKeyAction.ToggleChrome, press(Tab, shift = true))
        assertEquals(LibraryKeyAction.ToggleFullWindow, press(F))
        assertEquals(LibraryKeyAction.TogglePeaking, press(O))
        assertEquals(LibraryKeyAction.FocusSearch, press(F, ctrl = true))
    }

    @Test
    fun `undo, redo and selection`() {
        assertEquals(LibraryKeyAction.Undo, press(Z, ctrl = true))
        assertEquals(LibraryKeyAction.Redo, press(Z, ctrl = true, shift = true))
        assertEquals(LibraryKeyAction.Redo, press(Y, ctrl = true))
        assertEquals(LibraryKeyAction.SelectAll, press(A, ctrl = true))
        assertEquals(LibraryKeyAction.SelectNone, press(D, ctrl = true))
        assertEquals(LibraryKeyAction.SelectNone, press(D, ctrl = true, shift = true))
    }

    @Test
    fun `filters, tags, deletion, help and esc`() {
        assertEquals(LibraryKeyAction.FocusSearch, press(F, ctrl = true))
        assertEquals(LibraryKeyAction.ToggleFilterBar, press(Backslash))
        assertEquals(LibraryKeyAction.ToggleFilters, press(L, ctrl = true))
        assertEquals(LibraryKeyAction.EditTags, press(K, ctrl = true))
        assertEquals(LibraryKeyAction.DeleteRejects, press(Backspace, ctrl = true))
        assertEquals(LibraryKeyAction.ShowHelp, press(Slash, ctrl = true))
        assertEquals(LibraryKeyAction.ShowHelp, press(Slash, shift = true))
        assertEquals(LibraryKeyAction.Escape, press(Escape))
    }

    @Test
    fun `backspace alone does nothing, and Delete has no key at all`() {
        assertNull(press(Backspace))
        assertTrue(LibraryKey.entries.none { it.name == "Delete" })
    }

    @Test
    fun `unbound combinations are left alone`() {
        assertNull(press(F, shift = true))
        assertNull(press(L))
        assertNull(press(K))
        assertNull(press(P, ctrl = true))
        assertNull(press(Left, shift = true))
        assertNull(press(G, shift = true))
    }

    @Test
    fun `meta counts as ctrl`() {
        assertEquals(LibraryKeyAction.Undo, LibraryKeymap.actionFor(Z, meta = true))
        assertEquals(LibraryKeyAction.Redo, LibraryKeymap.actionFor(Z, meta = true, shift = true))
    }

    @Test
    fun `alt combinations belong to the system`() {
        assertNull(LibraryKeymap.actionFor(P, alt = true))
        assertNull(LibraryKeymap.actionFor(Tab, alt = true))
    }

    @Test
    fun `a focused text field keeps every key but esc`() {
        LibraryKey.entries.filterNot { it == Escape }.forEach { key ->
            assertNull(LibraryKeymap.actionFor(key, textFieldFocused = true))
            assertNull(LibraryKeymap.actionFor(key, ctrl = true, textFieldFocused = true))
        }
        assertEquals(LibraryKeyAction.Escape, LibraryKeymap.actionFor(Escape, textFieldFocused = true))
    }

    @Test
    fun `ctrl enter starts or ends a slideshow, and space pauses only while one is on`() {
        assertEquals(LibraryKeyAction.ToggleSlideshow, press(Enter, ctrl = true))
        assertNull(press(Enter))
        assertEquals(LibraryKeyAction.ToggleZoom, LibraryKeymap.actionFor(Space))
        assertEquals(LibraryKeyAction.SlideshowPause, LibraryKeymap.actionFor(Space, slideshowActive = true))
        // Everything else keeps its meaning in a show: arrows step, Esc leaves, Z still zooms.
        assertEquals(LibraryKeyAction.Next, LibraryKeymap.actionFor(Right, slideshowActive = true))
        assertEquals(LibraryKeyAction.Escape, LibraryKeymap.actionFor(Escape, slideshowActive = true))
        assertEquals(LibraryKeyAction.ToggleZoom, LibraryKeymap.actionFor(Z, slideshowActive = true))
    }

    @Test
    fun `the help sheet lists every binding`() {
        val listed = LibraryKeymap.helpGroups.flatMap { it.rows }.flatMap { it.keys.split(", ") }.toSet()
        LibraryKeymap.boundKeys.forEach { assertTrue("$it is missing from the sheet", it in listed) }
    }
}
