package dev.shehran.rawcull.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPreviewCacheTest {
    @Test
    fun `deletes the folder once and remembers it`() = runTest {
        var cleared = false
        var deletions = 0
        val cache = LegacyPreviewCache(
            isCleared = { cleared },
            markCleared = { cleared = true },
            delete = { deletions++ },
        )

        cache.clearOnce()
        cache.clearOnce()

        assertEquals(1, deletions)
        assertTrue(cleared)
    }

    @Test
    fun `a failed delete is tried again on the next start`() = runTest {
        var cleared = false
        val cache = LegacyPreviewCache(
            isCleared = { cleared },
            markCleared = { cleared = true },
            delete = { error("disk went away") },
        )

        runCatching { cache.clearOnce() }

        assertFalse(cleared)
    }
}
