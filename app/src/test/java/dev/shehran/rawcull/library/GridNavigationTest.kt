package dev.shehran.rawcull.library

import org.junit.Assert.assertEquals
import org.junit.Test

class GridNavigationTest {
    private fun step(index: Int, delta: Int, sections: List<FolderSection> = emptyList(), size: Int = 10) =
        GridNavigation.rowStep(index, delta, columns = 4, sections = sections, size = size)

    @Test
    fun `moves a row, keeping the column`() {
        assertEquals(5, step(1, 1))
        assertEquals(1, step(5, -1))
    }

    @Test
    fun `stops at the top and bottom`() {
        assertEquals(1, step(1, -1))
        assertEquals(9, step(9, 1))
    }

    @Test
    fun `a short last row catches the move`() {
        assertEquals(9, step(7, 1))
    }

    @Test
    fun `sections start new rows`() {
        val sections = listOf(FolderSection("a", 0, 6), FolderSection("b", 6, 3))
        assertEquals(7, step(5, 1, sections, size = 9)) // Column 1 of a's last row to column 1 of b.
        assertEquals(5, step(7, -1, sections, size = 9))
        assertEquals(5, step(8, -1, sections, size = 9)) // a's last row is short, so column 2 lands on its end.
    }

    @Test
    fun `odd input stays put`() {
        assertEquals(3, GridNavigation.rowStep(3, 1, columns = 0, sections = emptyList(), size = 10))
        assertEquals(-1, step(-1, 1))
    }
}
