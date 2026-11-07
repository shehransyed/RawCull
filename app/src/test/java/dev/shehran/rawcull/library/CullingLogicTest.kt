package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CullingLogicTest {
    private fun photo(
        id: String,
        rating: Int = 0,
        status: CullStatus = CullStatus.UNFLAGGED,
        label: ColorLabel = ColorLabel.NONE,
        tags: List<String> = emptyList(),
    ) = PhotoAsset(
        id = id,
        rootUri = "root",
        fileName = "$id.ARW",
        fileUri = "uri/$id",
        format = PhotoFormat.ARW,
        fileSizeBytes = 1,
        dateTaken = 0,
        rating = rating,
        cullStatus = status,
        colorLabel = label,
        tags = tags,
    )

    private fun resolve(edit: CullEdit, vararg photos: PhotoAsset) = CullEdits.resolve(edit, photos.toList())

    @Test
    fun `flag toggles on a single photo`() {
        assertEquals(CullStatus.PICK, resolve(CullEdit.Flag(CullStatus.PICK), photo("a"))?.after?.get("a")?.cullStatus)
        assertEquals(
            CullStatus.UNFLAGGED,
            resolve(CullEdit.Flag(CullStatus.PICK), photo("a", status = CullStatus.PICK))?.after?.get("a")?.cullStatus,
        )
    }

    @Test
    fun `a mixed selection is set, not toggled`() {
        val change = resolve(CullEdit.Rating(4), photo("a", rating = 4), photo("b", rating = 1))
        assertEquals(listOf("b"), change?.ids)
        assertEquals(4, change?.after?.get("b")?.rating)
    }

    @Test
    fun `a selection all at the value is cleared`() {
        val change = resolve(CullEdit.Rating(4), photo("a", rating = 4), photo("b", rating = 4))
        assertEquals(setOf("a", "b"), change?.ids?.toSet())
        assertEquals(0, change?.after?.get("a")?.rating)
    }

    @Test
    fun `an edit that changes nothing resolves to null`() {
        assertNull(resolve(CullEdit.RatingStep(1), photo("a", rating = 5)))
        assertNull(resolve(CullEdit.RatingStep(-1), photo("a", rating = 0)))
        assertNull(resolve(CullEdit.AddTag("x"), photo("a", tags = listOf("x"))))
        assertNull(resolve(CullEdit.AddTag("  "), photo("a")))
        assertNull(CullEdits.resolve(CullEdit.Rating(1), emptyList()))
    }

    @Test
    fun `flag steps walk reject, unflagged, pick and stop at the ends`() {
        fun step(status: CullStatus, delta: Int) =
            resolve(CullEdit.FlagStep(delta), photo("a", status = status))?.after?.get("a")?.cullStatus
        assertEquals(CullStatus.UNFLAGGED, step(CullStatus.REJECT, 1))
        assertEquals(CullStatus.PICK, step(CullStatus.UNFLAGGED, 1))
        assertNull(step(CullStatus.PICK, 1))
        assertEquals(CullStatus.UNFLAGGED, step(CullStatus.PICK, -1))
        assertNull(step(CullStatus.REJECT, -1))
    }

    @Test
    fun `labels toggle`() {
        assertEquals(ColorLabel.RED, resolve(CullEdit.LabelToggle(ColorLabel.RED), photo("a"))?.after?.get("a")?.colorLabel)
        assertEquals(
            ColorLabel.NONE,
            resolve(CullEdit.LabelToggle(ColorLabel.RED), photo("a", label = ColorLabel.RED))?.after?.get("a")?.colorLabel,
        )
    }

    @Test
    fun `tags are added and removed per photo`() {
        val added = resolve(CullEdit.AddTag(" Wedding "), photo("a", tags = listOf("x")), photo("b", tags = listOf("Wedding")))
        assertEquals(listOf("a"), added?.ids)
        assertEquals(listOf("x", "Wedding"), added?.after?.get("a")?.tags)
        assertEquals(emptyList<String>(), resolve(CullEdit.RemoveTag("x"), photo("a", tags = listOf("x")))?.after?.get("a")?.tags)
    }

    @Test
    fun `summaries name what changed`() {
        assertEquals("Picked 1 photo", resolve(CullEdit.Flag(CullStatus.PICK), photo("a"))?.summary)
        assertEquals("Rejected 2 photos", resolve(CullEdit.Flag(CullStatus.REJECT), photo("a"), photo("b"))?.summary)
        assertEquals("Rated 2 photos 4 stars", resolve(CullEdit.Rating(4), photo("a"), photo("b"))?.summary)
        assertEquals("Changed the rating on 2 photos", resolve(CullEdit.RatingStep(1), photo("a"), photo("b", rating = 2))?.summary)
        assertEquals("Labelled 1 photo Red", resolve(CullEdit.LabelToggle(ColorLabel.RED), photo("a"))?.summary)
    }

    @Test
    fun `undo and redo of a batch restore each photo's own state`() {
        val stack = UndoStack()
        val change = checkNotNull(
            resolve(
                CullEdit.Rating(4),
                photo("a", rating = 1),
                photo("b", rating = 2, status = CullStatus.PICK),
                photo("c", rating = 0, label = ColorLabel.RED),
            ),
        )
        stack.push(change)

        val undo = checkNotNull(stack.undo())
        assertEquals(1, undo.after["a"]?.rating)
        assertEquals(2, undo.after["b"]?.rating)
        assertEquals(CullStatus.PICK, undo.after["b"]?.cullStatus)
        assertEquals(ColorLabel.RED, undo.after["c"]?.colorLabel)
        assertTrue(stack.canRedo)

        val redo = checkNotNull(stack.redo())
        assertEquals(setOf(4), redo.after.values.map { it.rating }.toSet())
        assertTrue(stack.canUndo)
        assertFalse(stack.canRedo)
    }

    @Test
    fun `undo doesn't depend on what is visible`() {
        val stack = UndoStack()
        stack.push(checkNotNull(resolve(CullEdit.Flag(CullStatus.PICK), photo("a"))))
        // The filter has since hidden "a"; the stored change still knows what it was.
        assertEquals(CullStatus.UNFLAGGED, stack.undo()?.after?.get("a")?.cullStatus)
    }

    @Test
    fun `a new edit clears redo`() {
        val stack = UndoStack()
        stack.push(checkNotNull(resolve(CullEdit.Rating(1), photo("a"))))
        stack.undo()
        stack.push(checkNotNull(resolve(CullEdit.Rating(2), photo("a"))))
        assertFalse(stack.canRedo)
        assertNull(stack.redo())
    }

    @Test
    fun `the stack keeps the latest steps only`() {
        val stack = UndoStack(capacity = 2)
        listOf(1, 2, 3).forEach { stack.push(checkNotNull(resolve(CullEdit.Rating(it), photo("a")))) }
        assertEquals(3, stack.redoAfterUndo())
        assertEquals(2, stack.redoAfterUndo())
        assertFalse(stack.canUndo)
    }

    private fun UndoStack.redoAfterUndo(): Int? = undo()?.before?.get("a")?.rating

    @Test
    fun `clearing empties both directions`() {
        val stack = UndoStack()
        stack.push(checkNotNull(resolve(CullEdit.Rating(1), photo("a"))))
        stack.push(checkNotNull(resolve(CullEdit.Rating(2), photo("a"))))
        stack.undo()
        stack.clear()
        assertFalse(stack.canUndo)
        assertFalse(stack.canRedo)
    }

    @Test
    fun `deleted photos leave their steps`() {
        val stack = UndoStack()
        stack.push(checkNotNull(resolve(CullEdit.Rating(1), photo("a"), photo("b"))))
        stack.push(checkNotNull(resolve(CullEdit.Rating(2), photo("a"))))
        stack.dropIds(setOf("a"))

        val undo = checkNotNull(stack.undo())
        assertEquals(listOf("b"), undo.ids)
        assertFalse(stack.canUndo)
    }

    @Test
    fun `advance moves to the next visible photo`() {
        val visible = listOf("a", "b", "c")
        assertEquals("b", AutoAdvance.next(visible, visible, "a"))
    }

    @Test
    fun `advance stays put at the end of the list`() {
        val visible = listOf("a", "b")
        assertEquals("b", AutoAdvance.next(visible, visible, "b"))
    }

    @Test
    fun `a photo leaving the filter counts as the advance`() {
        assertEquals("b", AutoAdvance.next(listOf("a", "b", "c"), listOf("b", "c"), "a"))
    }

    @Test
    fun `the last photo leaving the filter falls back to the one before`() {
        assertEquals("b", AutoAdvance.next(listOf("a", "b", "c"), listOf("a", "b"), "c"))
    }

    @Test
    fun `nothing left means no selection`() {
        assertNull(AutoAdvance.next(listOf("a"), emptyList(), "a"))
    }

    @Test
    fun `ten picks in an Unflagged filter visit every photo`() {
        var visible = (0 until 10).map { "p$it" }
        var current: String? = visible.first()
        val visited = ArrayList<String>()
        repeat(10) {
            val edited = checkNotNull(current)
            visited += edited
            val after = visible - edited
            current = AutoAdvance.next(visible, after, edited)
            visible = after
        }
        assertEquals((0 until 10).map { "p$it" }, visited)
        assertNull(current)
    }

    @Test
    fun `plausible dates at the boundaries`() {
        val now = 1_800_000_000_000L
        assertTrue(PlausibleDate.isPlausible(PlausibleDate.EARLIEST, now))
        assertFalse(PlausibleDate.isPlausible(PlausibleDate.EARLIEST - 1, now))
        assertTrue(PlausibleDate.isPlausible(now + PlausibleDate.FUTURE_MARGIN_MILLIS, now))
        assertFalse(PlausibleDate.isPlausible(now + PlausibleDate.FUTURE_MARGIN_MILLIS + 1, now))
        assertFalse(PlausibleDate.isPlausible(null, now))
    }

    @Test
    fun `plausible date tries exif, then the file, then gives up`() {
        val now = 1_800_000_000_000L
        val good = 1_700_000_000_000L
        val other = 1_600_000_000_000L
        assertEquals(good, PlausibleDate.pick(good, other, now))
        assertEquals(other, PlausibleDate.pick(0L, other, now))
        assertEquals(other, PlausibleDate.pick(null, other, now))
        assertNull(PlausibleDate.pick(0L, 946_684_800_000L - 400L * 86_400_000L * 365, now))
        assertNull(PlausibleDate.pick(null, null, now))
    }
}
