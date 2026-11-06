package dev.shehran.rawcull.ui.components

import dev.shehran.rawcull.data.model.XmpSyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncChipTest {
    @Test
    fun pendingAndWritingBothReadAsSaving() {
        assertEquals("Saving…", XmpSyncStatus.PENDING.label)
        assertEquals("Saving…", XmpSyncStatus.WRITING.label)
    }

    @Test
    fun onlyProblemsHaveDetails() {
        assertTrue(XmpSyncStatus.ERROR.hasDetails)
        assertTrue(XmpSyncStatus.READ_ONLY.hasDetails)
        assertFalse(XmpSyncStatus.SYNCED.hasDetails)
        assertFalse(XmpSyncStatus.PENDING.hasDetails)
    }

    @Test
    fun theCounterOnlyShowsWhatIsThere() {
        assertNull(syncCounterText(saving = 0, errors = 0))
        assertEquals("3 saving", syncCounterText(saving = 3, errors = 0))
        assertEquals("1 error", syncCounterText(saving = 0, errors = 1))
        assertEquals("3 saving · 2 errors", syncCounterText(saving = 3, errors = 2))
    }
}
