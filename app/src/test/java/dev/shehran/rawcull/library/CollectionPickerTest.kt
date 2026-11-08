package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.PhotoCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionPickerTest {
    private fun row(id: Long, name: String, parent: Long? = null, isSet: Boolean = false, created: Long = id) =
        PhotoCollection(id = id, locationId = 1, parentId = parent, name = name, isSet = isSet, createdAt = created)

    private val all = listOf(
        row(1, "Trips", isSet = true),
        row(2, "Iceland", parent = 1),
        row(3, "Portraits"),
        row(4, "Spain", parent = 1),
    )

    @Test
    fun `sets are not offered and paths show where a collection sits`() {
        val paths = CollectionPicker.choices(all, "", null).map { it.path }
        assertEquals(listOf("Trips / Spain", "Portraits", "Trips / Iceland"), paths)
    }

    @Test
    fun `the target comes first, then the newest`() {
        assertEquals(3L, CollectionPicker.choices(all, "", targetId = 3).first().collection.id)
    }

    @Test
    fun `search matches the whole path ignoring case`() {
        assertEquals(listOf(4L), CollectionPicker.choices(all, " TRIPS / sp", null).map { it.collection.id })
        assertEquals(listOf(4L, 2L), CollectionPicker.choices(all, "trips", null).map { it.collection.id })
        assertTrue(CollectionPicker.choices(all, "zzz", null).isEmpty())
    }

    @Test
    fun `a new name is offered only when it is new at the top level`() {
        assertTrue(CollectionPicker.canCreate(all, "Weddings"))
        assertFalse(CollectionPicker.canCreate(all, " portraits "))
        assertFalse(CollectionPicker.canCreate(all, "   "))
        assertTrue(CollectionPicker.canCreate(all, "Iceland")) // Taken inside Trips, not at the top
    }
}
