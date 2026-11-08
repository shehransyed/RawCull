package dev.shehran.rawcull.device.importer

import android.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.shehran.rawcull.MainActivity
import dev.shehran.rawcull.data.importer.CardVolume
import dev.shehran.rawcull.data.model.ImportJobStatus
import dev.shehran.rawcull.data.model.StructureMode
import dev.shehran.rawcull.device.ImportFixtures
import dev.shehran.rawcull.device.Samples
import dev.shehran.rawcull.device.deviceRules
import dev.shehran.rawcull.device.robots.ImportRobot
import dev.shehran.rawcull.importer.ImportCollection
import dev.shehran.rawcull.qa.FixtureDocumentsProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** R8.5: the copy rules: verify, duplicates, never overwrite, delete from card, collections. */
@RunWith(AndroidJUnit4::class)
class ImportCopyTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = deviceRules(compose)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val card get() = FixtureDocumentsProvider.dir(context, "card")
    private val dest get() = FixtureDocumentsProvider.dir(context, "dest")
    private val cardUri get() = FixtureDocumentsProvider.treeUri(context, "card")
    private val robot by lazy { ImportRobot(compose) }

    /** Flat destination: every file lands in the root, so tests can compare by name. */
    private fun prepare() = robot.also { dest.mkdirs() }
        .openTab().addDestination(FixtureDocumentsProvider.treeUri(context, "dest")).structure(StructureMode.FLAT)

    @Test
    fun fullVerifyCopiesEveryByte() {
        val groups = Samples.copyGroups(3, card)
        ImportFixtures.sidecar(File(card, "${groups[0][0].stem}.xmp"), rating = 4)
        prepare()

        val job = robot.assertCompleted(robot.importAll(cardUri))

        val expected = groups.flatten().size + 1
        assertEquals(expected, job.totalFiles)
        assertEquals(expected, job.doneFiles)
        assertEquals(0, job.failedFiles)
        val landed = ImportFixtures.relativeFiles(dest)
        assertEquals(expected, landed.size)
        landed.forEach { name ->
            assertEquals("bytes of $name", ImportFixtures.sha256(File(card, name)), ImportFixtures.sha256(File(dest, name)))
        }
        // The duplicate index holds image files; sidecars aren't hashed.
        assertEquals(groups.flatten().size.toLong(), robot.count("SELECT COUNT(*) FROM imported_files"))
        assertEquals("one library row per group", 3L, robot.count("SELECT COUNT(*) FROM photo_assets WHERE lastImportJobId = ${job.id}"))
        assertEquals("sidecar rating read into the row", 1L, robot.count("SELECT COUNT(*) FROM photo_assets WHERE rating = 4"))
    }

    @Test
    fun reimportRecognisesAndSkipsDuplicates() {
        val groups = Samples.copyGroups(2, card)
        prepare()
        robot.assertCompleted(robot.importAll(cardUri))
        val before = ImportFixtures.relativeFiles(dest)

        // Second pass over the same card: everything is known, nothing is ticked.
        compose.runOnUiThread { robot.viewModel.newImport() }
        robot.pickSource(cardUri).awaitReady()
        val state = robot.viewModel.uiState.value
        assertTrue(state.candidates.all { c -> c.files.all { it.alreadyImported } })
        assertEquals(0, state.summary.selectedPhotos)
        assertFalse(state.canStart)

        // Ticking them by hand still copies nothing: the planner skips known files.
        compose.runOnUiThread {
            robot.viewModel.setHideImported(false)
            state.candidates.forEach(robot.viewModel::toggleSelected)
        }
        compose.waitForIdle()
        val job = robot.awaitJob(robot.start())
        assertEquals(ImportJobStatus.COMPLETED, job.status)
        assertEquals(0, job.totalFiles)
        assertEquals(groups.flatten().size, job.skippedFiles)
        assertEquals(before, ImportFixtures.relativeFiles(dest))
        assertEquals(before.size.toLong(), robot.count("SELECT COUNT(*) FROM imported_files"))
    }

    @Test
    fun neverOverwritesAnExistingFile() {
        ImportFixtures.undatedJpeg(File(card, "CLASH_0001.JPG"), shade = Color.RED)
        val existing = File(dest, "CLASH_0001.JPG").apply { writeText("not the same bytes at all, belongs to the library") }
        val existingHash = ImportFixtures.sha256(existing)
        prepare()

        robot.assertCompleted(robot.importAll(cardUri))

        assertEquals("existing file untouched", existingHash, ImportFixtures.sha256(existing))
        val suffixed = ImportFixtures.relativeFiles(dest).single { it != "CLASH_0001.JPG" }
        assertEquals("CLASH_0001-1.JPG", suffixed)
        assertEquals(ImportFixtures.sha256(File(card, "CLASH_0001.JPG")), ImportFixtures.sha256(File(dest, suffixed)))
    }

    @Test
    fun deleteFromCardTakesOnlyVerifiedNewGroups() {
        val groups = Samples.copyGroups(2, card)
        prepare()
        // Group A goes in first from a plain folder, so on the card it is a known duplicate.
        val a = groups[0].map { it.name }.toSet()
        val b = groups[1].map { it.name }.toSet()
        val solo = FixtureDocumentsProvider.dir(context, "solo")
        a.forEach { File(card, it).copyTo(File(solo, it)) }
        assertTrue(robot.assertCompleted(robot.importAll(FixtureDocumentsProvider.treeUri(context, "solo"))).totalFiles > 0)

        runBlocking { robot.container.settingsRepository.setDeleteFromCardAfterImport(true) }
        compose.runOnUiThread { robot.viewModel.newImport() }
        compose.runOnUiThread {
            robot.viewModel.onCardPicked(CardVolume("QA-CARD", "QA card", isUsb = false, freeBytes = null, totalBytes = null), cardUri)
        }
        robot.awaitReady()
        compose.waitUntil(10_000) { robot.viewModel.uiState.value.deleteFromCardSetting }
        val job = robot.assertCompleted(robot.awaitJob(robot.start()))

        assertTrue("job deletes from the card", job.deleteSourceAfter)
        val onCard = card.list()!!.toSet()
        assertTrue("verified group B left the card: $onCard", onCard.none { it in b })
        assertTrue("duplicate group A stays: $onCard", a.all { it in onCard })
        assertEquals(b.size.toLong(), robot.count("SELECT COUNT(*) FROM import_job_items WHERE jobId = ${job.id} AND sourceDeleted = 1"))
    }

    @Test
    fun cardIsUntouchedWithoutDeleteSetting() {
        val groups = Samples.copyGroups(2, card)
        prepare()
        robot.assertCompleted(robot.importAll(cardUri))
        assertEquals(groups.flatten().map { it.name }.toSet(), card.list()!!.toSet())
    }

    @Test
    fun addToCollectionFilesEachGroup() {
        Samples.copyGroups(3, card)
        prepare()
        compose.runOnUiThread { robot.viewModel.chooseCollection(ImportCollection.New("Keepers")) }
        robot.pickSource(cardUri).awaitReady()
        compose.waitUntil(10_000) { robot.viewModel.uiState.value.effectiveCollection is ImportCollection.New }

        val job = robot.assertCompleted(robot.awaitJob(robot.start()))

        assertTrue(job.collectionId != null)
        assertEquals(3L, robot.count("SELECT COUNT(*) FROM collection_items WHERE collectionId = ${job.collectionId}"))
        assertEquals(1L, robot.count("SELECT COUNT(*) FROM collections WHERE name = 'Keepers'"))
    }
}
