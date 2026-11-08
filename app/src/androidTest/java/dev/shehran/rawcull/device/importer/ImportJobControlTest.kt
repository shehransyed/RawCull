package dev.shehran.rawcull.device.importer

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.shehran.rawcull.MainActivity
import dev.shehran.rawcull.data.importer.ImportController
import dev.shehran.rawcull.data.importer.ImportEngine
import dev.shehran.rawcull.data.importer.ImportRequest
import dev.shehran.rawcull.data.importer.ImportScheduler
import dev.shehran.rawcull.data.importer.ImportStart
import dev.shehran.rawcull.data.model.ImportJobStatus
import dev.shehran.rawcull.data.model.StructureMode
import dev.shehran.rawcull.device.ImportFixtures
import dev.shehran.rawcull.device.Samples
import dev.shehran.rawcull.device.deviceRules
import dev.shehran.rawcull.device.robots.ImportRobot
import dev.shehran.rawcull.importer.DocumentIo
import dev.shehran.rawcull.importer.ImportDestination
import dev.shehran.rawcull.importer.ImportPreview
import dev.shehran.rawcull.importer.ImportSelection
import dev.shehran.rawcull.qa.FixtureDocumentsProvider
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * R8.5: stopping an import part-way, then cancelling or resuming it. The test drives the real
 * engine and controller itself (no WorkManager), with storage that stops the run on its Nth write,
 * so the stop point is exact instead of a race.
 */
@RunWith(AndroidJUnit4::class)
class ImportJobControlTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = deviceRules(compose)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val card get() = FixtureDocumentsProvider.dir(context, "card")
    private val dest get() = FixtureDocumentsProvider.dir(context, "dest")
    private val robot by lazy { ImportRobot(compose) }

    /** Real storage, except that the run is cancelled when the [stopAt]th file is opened for writing. */
    private class StoppingIo(private val real: DocumentIo, private val stopAt: Int) : DocumentIo by real {
        private val opened = AtomicInteger()

        override fun openOutput(uri: String): OutputStream {
            if (opened.incrementAndGet() >= stopAt) throw CancellationException("test stop")
            return real.openOutput(uri)
        }
    }

    private object NoScheduler : ImportScheduler {
        override fun enqueue(jobId: Long) = Unit
        override fun cancel() = Unit
    }

    private fun engine(io: DocumentIo): ImportEngine = with(robot.container) {
        ImportEngine(
            jobs = database.importJobDao(),
            importedFiles = database.importedFileDao(),
            locations = database.locationDao(),
            photos = photoDao,
            io = io,
            collections = collectionRepository,
        )
    }

    private fun controller(engine: ImportEngine): ImportController = with(robot.container) {
        ImportController(database.importJobDao(), engine, documentIo, NoScheduler, collectionRepository)
    }

    /** Scans the card and queues a job over it without running it; returns the job id. */
    private fun queueJob(controller: ImportController): Long {
        dest.mkdirs()
        robot.openTab().addDestination(FixtureDocumentsProvider.treeUri(context, "dest")).structure(StructureMode.FLAT)
        robot.pickSource(FixtureDocumentsProvider.treeUri(context, "card")).awaitReady()
        val s = robot.viewModel.uiState.value
        val request = ImportRequest(
            source = checkNotNull(s.source),
            location = checkNotNull(s.destination),
            candidates = s.candidates,
            selection = ImportSelection(ImportPreview.selectedIds(s.candidates, s.options, s.overrides)),
            template = { date -> ImportDestination.relativeDir(s.draft, date) },
            verifyMode = dev.shehran.rawcull.data.model.VerifyMode.FULL,
            deleteFromCard = false,
        )
        val started = runBlocking { controller.start(request) }
        return (started as ImportStart.Started).jobId
    }

    /** Runs [engine] until the storage stops it; the engine marks the job PAUSED and rethrows. */
    private fun runUntilStopped(engine: ImportEngine, id: Long): ImportJobStatus? {
        try {
            runBlocking { engine.run(id) }
        } catch (e: CancellationException) {
            // expected
        }
        return runBlocking { robot.container.database.importJobDao().getJob(id) }?.status
    }

    private fun doneItems(jobId: Long) = robot.count("SELECT COUNT(*) FROM import_job_items WHERE jobId = $jobId AND state = 'DONE'")

    @Test
    fun stoppedImportPausesAndKeepsFinishedFiles() {
        Samples.copyGroups(3, card)
        val real = robot.container.documentIo
        val controller = controller(engine(StoppingIo(real, stopAt = 3)))
        val id = queueJob(controller)

        assertEquals(ImportJobStatus.PAUSED, runUntilStopped(engine(StoppingIo(real, stopAt = 3)), id))
        val done = doneItems(id)
        assertTrue("some files finished before the stop: $done", done in 1 until robot.count("SELECT COUNT(*) FROM import_job_items WHERE jobId = $id"))
        assertEquals("only finished files are on disk", done.toInt(), ImportFixtures.relativeFiles(dest).size)
    }

    @Test
    fun cancelKeepsFinishedFilesAndRemovesThePartialOne() {
        Samples.copyGroups(3, card)
        val real = robot.container.documentIo
        val controller = controller(engine(real))
        val id = queueJob(controller)
        runUntilStopped(engine(StoppingIo(real, stopAt = 3)), id)
        val done = doneItems(id)

        runBlocking { controller.cancel(id) }

        assertEquals(ImportJobStatus.CANCELLED, runBlocking { robot.container.database.importJobDao().getJob(id) }?.status)
        assertEquals("finished files stay, nothing partial", done.toInt(), ImportFixtures.relativeFiles(dest).size)
        assertEquals(done, doneItems(id))
    }

    @Test
    fun resumeFinishesTheJobWithoutDuplicates() {
        val groups = Samples.copyGroups(3, card)
        val real = robot.container.documentIo
        val controller = controller(engine(real))
        val id = queueJob(controller)
        assertEquals(ImportJobStatus.PAUSED, runUntilStopped(engine(StoppingIo(real, stopAt = 3)), id))

        assertTrue(runBlocking { controller.resume(id) } is ImportStart.Started)
        val status = runBlocking { engine(real).run(id) }

        assertEquals(ImportJobStatus.COMPLETED, status)
        val landed = ImportFixtures.relativeFiles(dest)
        assertEquals(groups.flatten().map { it.name }.sorted(), landed)
        landed.forEach { assertEquals(ImportFixtures.sha256(File(card, it)), ImportFixtures.sha256(File(dest, it))) }
        assertEquals(landed.size.toLong(), robot.count("SELECT COUNT(*) FROM imported_files"))
        assertEquals(3L, robot.count("SELECT COUNT(*) FROM photo_assets WHERE lastImportJobId = $id"))
    }
}
