package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditBadgeTextTest {
    private val base = PhotoAsset(
        id = "a", rootUri = "t", relativeDir = "", fileName = "a.ARW", fileUri = "d/a.ARW",
        format = PhotoFormat.ARW, fileSizeBytes = 1, dateTaken = 0,
    )

    @Test
    fun `each kind of edit has words`() {
        assertEquals("Pick", EditBadgeText.between(base, base.copy(cullStatus = CullStatus.PICK)))
        assertEquals("Reject", EditBadgeText.between(base, base.copy(cullStatus = CullStatus.REJECT)))
        assertEquals("Unflagged", EditBadgeText.between(base.copy(cullStatus = CullStatus.PICK), base))
        assertEquals("3 ★", EditBadgeText.between(base, base.copy(rating = 3)))
        assertEquals("No rating", EditBadgeText.between(base.copy(rating = 3), base))
        assertEquals("Red label", EditBadgeText.between(base, base.copy(colorLabel = ColorLabel.RED)))
        assertEquals("No label", EditBadgeText.between(base.copy(colorLabel = ColorLabel.RED), base))
    }

    @Test
    fun `nothing to say when only other fields or another photo changed`() {
        assertNull(EditBadgeText.between(base, base.copy(tags = listOf("x"))))
        assertNull(EditBadgeText.between(base, base.copy(id = "b", rating = 5)))
    }
}
