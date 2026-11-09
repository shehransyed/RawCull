package dev.shehran.rawcull.device.deletion

import android.media.MediaScannerConnection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.shehran.rawcull.MainActivity
import dev.shehran.rawcull.RawCullApp
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.device.Samples
import dev.shehran.rawcull.device.SharedFolder
import dev.shehran.rawcull.device.Shell
import dev.shehran.rawcull.device.SystemDialog
import dev.shehran.rawcull.device.deviceRules
import dev.shehran.rawcull.device.robots.LibraryRobot
import dev.shehran.rawcull.library.DeletionPlan
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * R8.7, tier (b): rejects deleted through MediaStore's trash request on real storage
 * (`Download/RawCullQA`), with UiAutomator on the system dialog. A group's files go together, the
 * sidecar goes for good, Restore brings the trash back, and cancelling deletes nothing. Needs the
 * shared-folder grant and samples; skips without them.
 */
@RunWith(AndroidJUnit4::class)
class RejectDeletionTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = deviceRules(compose)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val library by lazy { LibraryRobot(compose) }
    private val container get() = (context.applicationContext as RawCullApp).container

    @Before
    fun clean() {
        SharedFolder.assumeGranted(context)
        SharedFolder.clear()
    }

    @After
    fun tidy() {
        if (SharedFolder.isGranted(context)) SharedFolder.clear()
    }

    /** One RAW alone and, if the samples have one, a RAW+JPEG pair. */
    private fun chooseGroups(): List<List<Samples.Sample>> {
        val all = Samples.groups()
        return listOfNotNull(all.firstOrNull { it.size == 1 }, all.firstOrNull { it.size > 1 })
    }

    private fun scan(names: List<String>) {
        val latch = CountDownLatch(names.size)
        MediaScannerConnection.scanFile(context, names.map { "${SharedFolder.PATH}/$it" }.toTypedArray(), null) { _, _ -> latch.countDown() }
        latch.await(30, TimeUnit.SECONDS)
    }

    /** MediaStore indexes shell-copied files a moment after the scan; trashing needs them there. */
    private fun awaitIndexed(names: List<String>) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val known = Shell.text("content query --uri content://media/external/file --projection _display_name --where \"relative_path='Download/RawCullQA/'\"")
            if (names.all { it in known }) return
            Thread.sleep(500)
        }
    }

    private fun listing(): List<String> =
        Shell.text("ls -1A ${Shell.quote(SharedFolder.PATH)}").lines().filter { it.isNotBlank() }

    private fun visibleFiles(): Set<String> = listing().filterNot { it.startsWith(".") }.toSet()

    private fun trashed(): List<String> = listing().filter { it.startsWith(".trashed") }

    private fun count(sql: String): Long = container.database.openHelper.readableDatabase.query(sql).use {
        it.moveToFirst()
        it.getLong(0)
    }

    /** Copies the groups in, opens the folder as a Location and rejects every photo. */
    private fun seedRejects(): Pair<List<Samples.Sample>, Int> {
        val groups = chooseGroups()
        assumeTrue("Need at least one sample group", groups.isNotEmpty())
        val files = groups.flatten()
        SharedFolder.copy(files)
        scan(files.map { it.name })
        awaitIndexed(files.map { it.name })
        library.openTab().addLocation(SharedFolder.treeUri).awaitGroups(groups.size, timeoutMs = 120_000)
        for (name in library.state().filteredPhotos.map { it.fileName }) {
            library.select(name) // the first click opens the loupe, so later cards aren't on screen
            library.press(Key.X).await { library.photo(name).cullStatus == CullStatus.REJECT }
        }
        library.await(30_000) { listing().count { it.endsWith(".xmp") } == groups.size } // the rejects wrote sidecars
        return files to groups.size
    }

    private fun requestAndConfirm(): DeletionPlan {
        compose.runOnUiThread { library.viewModel.requestDeleteRejects(selectionOnly = false) }
        library.await(30_000) { library.state().deletionPrompt != null }
        val plan = checkNotNull(library.state().deletionPrompt)
        compose.runOnUiThread { library.viewModel.confirmDeletion() }
        // Compose only runs its effects while the test waits, so let the screen launch the dialog.
        library.await(20_000) { library.state().trashRequest == null }
        return plan
    }

    @Test
    fun allowedTrashMovesWholeGroupsAndDeletesSidecarsForGood() {
        val (files, groupCount) = seedRejects()
        val imported = count("SELECT COUNT(*) FROM imported_files")

        val plan = requestAndConfirm()
        assertEquals(groupCount, plan.photoCount)
        assertEquals("every RAW and JPEG is trashable", files.size, plan.toTrash.size)
        assertTrue("sidecars are permanent", plan.toDelete.isNotEmpty())

        assertTrue("the system dialog appeared", SystemDialog.allow())
        library.await(60_000) { count("SELECT COUNT(*) FROM photo_assets") == 0L }

        assertEquals("nothing left in the folder", emptySet<String>(), visibleFiles())
        assertEquals("every file went to the trash", files.size, trashed().size)
        assertEquals("deleted rejects stay in imported_files", imported, count("SELECT COUNT(*) FROM imported_files"))
    }

    @Test
    fun restoreBringsTheTrashBack() {
        val (files, _) = seedRejects()
        requestAndConfirm()
        assertTrue("the system dialog appeared", SystemDialog.allow())
        library.await(60_000) { library.state().snackbarAction != null }

        compose.runOnUiThread { library.viewModel.restoreTrashed() }
        library.await(20_000) { library.state().trashRequest == null }
        assertTrue("the restore dialog appeared", SystemDialog.allow())
        library.await(60_000) { visibleFiles().containsAll(files.map { it.name }) }

        assertEquals(emptyList<String>(), trashed())
    }

    @Test
    fun cancellingTheSystemDialogDeletesNothing() {
        val (files, _) = seedRejects()
        val before = visibleFiles()
        val rows = count("SELECT COUNT(*) FROM photo_assets")

        requestAndConfirm()
        assertTrue("the system dialog appeared", SystemDialog.deny())
        library.await(20_000) { library.state().snackbarMessage != null || true }
        Thread.sleep(2_000)

        assertEquals(before, visibleFiles())
        assertTrue(visibleFiles().containsAll(files.map { it.name }))
        assertEquals(rows, count("SELECT COUNT(*) FROM photo_assets"))
    }
}
