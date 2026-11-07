package dev.shehran.rawcull.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySourcesTest {
    private val internal = "content://com.android.externalstorage.documents/tree/primary%3APictures%2FPhotos"
    private val card = "content://com.android.externalstorage.documents/tree/ABCD-1234%3ADCIM"
    private val cloud = "content://com.example.cloud/tree/root"

    @Test
    fun `cards are told apart from internal storage`() {
        assertTrue(LegacySources.isRemovableVolume(card))
        assertFalse(LegacySources.isRemovableVolume(internal))
    }

    @Test
    fun `other providers aren't removable volumes`() {
        assertFalse(LegacySources.isRemovableVolume(cloud))
    }

    @Test
    fun `the old destination becomes a location`() {
        assertEquals(listOf(internal), LegacySources.locationsToCreate(LegacyFolders(internal, null), setOf(internal)))
    }

    @Test
    fun `an internal source becomes a location, a card doesn't`() {
        assertEquals(listOf(internal), LegacySources.locationsToCreate(LegacyFolders(null, internal), setOf(internal)))
        assertTrue(LegacySources.locationsToCreate(LegacyFolders(null, card), setOf(card)).isEmpty())
    }

    @Test
    fun `a folder without its grant is dropped`() {
        assertTrue(LegacySources.locationsToCreate(LegacyFolders(internal, internal), emptySet()).isEmpty())
    }

    @Test
    fun `the same folder as source and destination gives one location`() {
        assertEquals(listOf(internal), LegacySources.locationsToCreate(LegacyFolders(internal, internal), setOf(internal)))
    }

    @Test
    fun `only grants nothing uses are released`() {
        assertEquals(setOf(card), LegacySources.grantsToRelease(setOf(card, internal), setOf(internal, cloud)))
    }
}
