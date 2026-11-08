package dev.shehran.rawcull.device.importer

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.shehran.rawcull.MainActivity
import dev.shehran.rawcull.device.Samples
import dev.shehran.rawcull.device.Smoke
import dev.shehran.rawcull.device.deviceRules
import dev.shehran.rawcull.device.robots.ImportRobot
import dev.shehran.rawcull.device.robots.LibraryRobot
import dev.shehran.rawcull.device.robots.awaitNode
import dev.shehran.rawcull.importer.ImportCollection
import dev.shehran.rawcull.library.LibraryScope
import dev.shehran.rawcull.qa.FixtureDocumentsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * R8.5, the R8.1 regression: after an import, *Cull in Library* and *Open collection* land on the
 * imported photos, and the grid survives its prefetch window changing under it.
 */
@RunWith(AndroidJUnit4::class)
class ImportLandingTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = deviceRules(compose)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val card get() = FixtureDocumentsProvider.dir(context, "card")
    private val imports by lazy { ImportRobot(compose) }
    private val library by lazy { LibraryRobot(compose) }

    private fun importGroups(count: Int, collection: String? = null): Long {
        Samples.copyGroups(count, card)
        FixtureDocumentsProvider.dir(context, "dest")
        imports.openTab().addDestination(FixtureDocumentsProvider.treeUri(context, "dest"))
        if (collection != null) {
            compose.runOnUiThread { imports.viewModel.chooseCollection(ImportCollection.New(collection)) }
        }
        imports.pickSource(FixtureDocumentsProvider.treeUri(context, "card")).awaitReady()
        compose.waitUntil(10_000) { collection == null || imports.viewModel.uiState.value.effectiveCollection is ImportCollection.New }
        val id = imports.start()
        // The shell has opened the progress screen by itself; its button shows once the job is done.
        compose.awaitNode(hasTestTag(ImportRobot.CULL_IN_LIBRARY), timeoutMs = 180_000)
        assertEquals(id, imports.awaitJob(id).id)
        return id
    }

    @Smoke
    @Test
    fun cullInLibraryShowsTheImportedPhotos() {
        val jobId = importGroups(3)

        imports.tap(ImportRobot.CULL_IN_LIBRARY)

        library.awaitGroups(3, timeoutMs = 60_000)
        val state = library.viewModel.uiState.value
        assertEquals(LibraryScope.LatestImport(jobId), state.scope)
        // The crash came from the grid prefetch some seconds in; sit through it, then use the grid.
        Thread.sleep(PREFETCH_GRACE_MS)
        library.clickGroup(0)
        assertNotNull(library.viewModel.uiState.value.selectedPhotoId)
        assertFalse("activity died", compose.activity.isFinishing)
    }

    @Test
    fun openCollectionShowsTheCollection() {
        val jobId = importGroups(2, collection = "Keepers")
        assertTrue(imports.awaitJob(jobId).collectionId != null)

        imports.tap(ImportRobot.OPEN_COLLECTION)

        library.awaitGroups(2, timeoutMs = 60_000)
        val scope = library.viewModel.uiState.value.scope
        assertTrue("scope is a collection: $scope", scope is LibraryScope.Collection)
        Thread.sleep(PREFETCH_GRACE_MS)
        library.clickGroup(1)
        assertFalse("activity died", compose.activity.isFinishing)
    }

    private companion object {
        const val PREFETCH_GRACE_MS = 4_000L
    }
}
