package dev.shehran.rawcull.ui.shell

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.input.key.Key
import dev.shehran.rawcull.ui.LibraryKeyAction
import dev.shehran.rawcull.ui.LibraryKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellLogicTest {
    private val anything = SaverScope { true }

    @Test
    fun `width classes follow the Material breakpoints`() {
        val widths = listOf(360, 599, 600, 839, 840, 1199, 1200, 1599, 1600).map { WindowLayout.of(it, 900).width }
        assertEquals(
            listOf(
                WidthClass.COMPACT, WidthClass.COMPACT, WidthClass.MEDIUM, WidthClass.MEDIUM, WidthClass.EXPANDED,
                WidthClass.EXPANDED, WidthClass.LARGE, WidthClass.LARGE, WidthClass.EXTRA_LARGE,
            ),
            widths,
        )
    }

    @Test
    fun `phone portrait gets the bottom bar and no side panes`() {
        val layout = WindowLayout.of(360, 640)
        assertTrue(layout.usesBottomBar)
        assertFalse(layout.showsFolderPane)
        assertFalse(layout.showsSideInspector)
        assertFalse(layout.cullingBarAtSide)
        assertTrue(layout.showsFilmstrip)
    }

    @Test
    fun `phone landscape gets a rail and a side culling bar`() {
        val layout = WindowLayout.of(640, 360)
        assertFalse(layout.usesBottomBar)
        assertFalse(layout.showsFolderPane)
        assertTrue(layout.showsSideInspector)
        assertTrue(layout.cullingBarAtSide)
        assertFalse(layout.showsFilmstrip)
    }

    @Test
    fun `tablet portrait keeps the culling bar at the bottom and the tree in a drawer`() {
        val layout = WindowLayout.of(600, 960)
        assertFalse(layout.showsFolderPane)
        assertFalse(layout.cullingBarAtSide)
    }

    @Test
    fun `tablet landscape shows the folder pane with a plain rail`() {
        val layout = WindowLayout.of(840, 800)
        assertTrue(layout.showsFolderPane)
        assertFalse(layout.usesWideRail)
    }

    @Test
    fun `large windows get the wide rail and an open inspector`() {
        assertTrue(WindowLayout.of(1280, 800).usesWideRail)
        assertTrue(WindowLayout.of(1280, 800).opensInspectorByDefault)
    }

    @Test
    fun `a portrait tablet keeps room for the photo`() {
        assertFalse(WindowLayout.of(840, 1280).opensInspectorByDefault)
    }

    @Test
    fun `keys survive encoding`() {
        val keys = listOf(AppKey.Library, AppKey.ImportSource, AppKey.ImportPreview, AppKey.ImportProgress(42), AppKey.Settings)
        assertEquals(keys, keys.map { AppKey.decode(it.encode()) })
    }

    @Test
    fun `unknown keys decode to nothing`() {
        assertNull(AppKey.decode("gone"))
        assertNull(AppKey.decode("import-progress:abc"))
    }

    @Test
    fun `the app starts on the library with a root in every tab`() {
        val stacks = TabStacks()
        assertEquals(TopLevelTab.LIBRARY, stacks.selected)
        TopLevelTab.entries.forEach { assertEquals(listOf(it.root), stacks.stack(it).toList()) }
    }

    @Test
    fun `each tab keeps its own stack`() {
        val stacks = TabStacks()
        stacks.push(AppKey.ImportPreview, TopLevelTab.IMPORT)
        stacks.select(TopLevelTab.SETTINGS)
        stacks.select(TopLevelTab.IMPORT)

        assertEquals(listOf(AppKey.ImportSource, AppKey.ImportPreview), stacks.current.toList())
    }

    @Test
    fun `pushing what's already on top doesn't stack it twice`() {
        val stacks = TabStacks()
        stacks.push(AppKey.ImportProgress(1), TopLevelTab.IMPORT)
        stacks.push(AppKey.ImportProgress(1), TopLevelTab.IMPORT)
        assertEquals(2, stacks.current.size)
    }

    @Test
    fun `back goes down the stack, then to library, then out`() {
        val stacks = TabStacks()
        stacks.push(AppKey.ImportPreview, TopLevelTab.IMPORT)

        assertTrue(stacks.back())
        assertEquals(listOf<AppKey>(AppKey.ImportSource), stacks.current.toList())

        assertTrue(stacks.back())
        assertEquals(TopLevelTab.LIBRARY, stacks.selected)

        assertFalse(stacks.back())
    }

    @Test
    fun `reset leaves the root and the given keys`() {
        val stacks = TabStacks()
        stacks.push(AppKey.ImportPreview, TopLevelTab.IMPORT)
        stacks.resetTo(TopLevelTab.IMPORT, AppKey.ImportProgress(3))
        assertEquals(listOf(AppKey.ImportSource, AppKey.ImportProgress(3)), stacks.current.toList())
    }

    @Test
    fun `selected tab and stacks survive saving`() {
        val stacks = TabStacks()
        stacks.push(AppKey.ImportProgress(9), TopLevelTab.IMPORT)

        val saved = with(TabStacks.Saver) { anything.save(stacks) }!!
        val restored = TabStacks.Saver.restore(saved)!!

        assertEquals(TopLevelTab.IMPORT, restored.selected)
        assertEquals(listOf(AppKey.ImportSource, AppKey.ImportProgress(9)), restored.current.toList())
        assertEquals(listOf<AppKey>(AppKey.Library), restored.stack(TopLevelTab.LIBRARY).toList())
    }

    @Test
    fun `culling keys map to their actions`() {
        assertEquals(LibraryKeyAction.TogglePick, LibraryKeys.actionFor(Key.P))
        assertEquals(LibraryKeyAction.ToggleReject, LibraryKeys.actionFor(Key.X))
        assertEquals(LibraryKeyAction.Unflag, LibraryKeys.actionFor(Key.U))
        assertEquals(LibraryKeyAction.Rate(0), LibraryKeys.actionFor(Key.Zero))
        assertEquals(LibraryKeyAction.Rate(5), LibraryKeys.actionFor(Key.Five))
        assertEquals(LibraryKeyAction.Previous, LibraryKeys.actionFor(Key.DirectionLeft))
        assertEquals(LibraryKeyAction.Next, LibraryKeys.actionFor(Key.NavigateNext))
        assertEquals(LibraryKeyAction.ToggleCompare, LibraryKeys.actionFor(Key.C))
        assertEquals(LibraryKeyAction.ShowAllPhotos, LibraryKeys.actionFor(Key.Escape))
        assertNull(LibraryKeys.actionFor(Key.Q))
    }
}
