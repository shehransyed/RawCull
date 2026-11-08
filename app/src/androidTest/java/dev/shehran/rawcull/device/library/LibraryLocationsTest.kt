package dev.shehran.rawcull.device.library

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.shehran.rawcull.MainActivity
import dev.shehran.rawcull.RawCullApp
import dev.shehran.rawcull.data.repository.LocationChange
import dev.shehran.rawcull.device.ImportFixtures
import dev.shehran.rawcull.device.deviceRules
import dev.shehran.rawcull.device.robots.LibraryRobot
import dev.shehran.rawcull.qa.FixtureDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** R8.6: Locations: rename, remove, relink, and the refusals while edits are unsaved. */
@RunWith(AndroidJUnit4::class)
class LibraryLocationsTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = deviceRules(compose)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val library by lazy { LibraryRobot(compose) }
    private val container get() = (context.applicationContext as RawCullApp).container

    private fun locationId(): Long = checkNotNull(library.state().activeLocation).id

    private fun rows(sql: String): Long = container.database.openHelper.readableDatabase.query(sql).use {
        it.moveToFirst()
        it.getLong(0)
    }

    private fun setSyncStatus(status: String) =
        container.database.openHelper.writableDatabase.execSQL("UPDATE photo_assets SET xmpSyncStatus = '$status'")

    @Test
    fun renameChangesTheNameOnly() {
        library.seed("loc", 2)
        runBlocking { container.locationRepository.rename(locationId(), "Wedding shoot") }
        library.await { library.state().activeLocation?.displayName == "Wedding shoot" }
        assertEquals(2, library.groupCount())
    }

    @Test
    fun removeForgetsTheLocationButNotTheFiles() {
        val dir = library.seed("gone", 2)
        val id = locationId()

        assertEquals(LocationChange.Done, runBlocking { container.locationRepository.remove(id) })

        library.await { library.state().locations.none { it.location.id == id } }
        assertEquals("files stay", 2, dir.listFiles { f -> f.name.endsWith(".JPG") }!!.size)
        assertEquals(0L, rows("SELECT COUNT(*) FROM library_locations WHERE id = $id"))
    }

    @Test
    fun removeAndRelinkAreRefusedWhileEditsAreUnsaved() {
        library.seed("unsaved", 2)
        val id = locationId()
        FixtureDocumentsProvider.dir(context, "other").also { ImportFixtures.jpegs(it, 1, "OTH") }
        val other = FixtureDocumentsProvider.treeUri(context, "other").toString()
        setSyncStatus("PENDING")

        val removed = runBlocking { container.locationRepository.remove(id) }
        val relinked = runBlocking { container.locationRepository.relink(id, other) }

        assertTrue("remove refused: $removed", removed is LocationChange.Refused)
        assertTrue("relink refused: $relinked", relinked is LocationChange.Refused)
        setSyncStatus("SYNCED")
        assertEquals(LocationChange.Done, runBlocking { container.locationRepository.relink(id, other) })
    }

    @Test
    fun relinkPointsTheLocationAtTheNewTree() {
        library.seed("before", 2)
        val id = locationId()
        FixtureDocumentsProvider.dir(context, "after").also { ImportFixtures.jpegs(it, 3, "NEW") }

        compose.runOnUiThread { library.viewModel.relinkLocation(id, FixtureDocumentsProvider.treeUri(context, "after")) }

        library.await(60_000) { library.state().photos.map { it.fileName }.sorted() == listOf("NEW_0001.JPG", "NEW_0002.JPG", "NEW_0003.JPG") }
    }
}
