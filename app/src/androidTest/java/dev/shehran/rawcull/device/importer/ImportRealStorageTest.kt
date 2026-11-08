package dev.shehran.rawcull.device.importer

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.shehran.rawcull.MainActivity
import dev.shehran.rawcull.data.model.StructureMode
import dev.shehran.rawcull.device.Samples
import dev.shehran.rawcull.device.Shell
import dev.shehran.rawcull.device.SharedFolder
import dev.shehran.rawcull.device.deviceRules
import dev.shehran.rawcull.device.robots.ImportRobot
import dev.shehran.rawcull.qa.FixtureDocumentsProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * R8.5, tier (b): one smoke import of the real samples into `Download/RawCullQA`, behind the real
 * ExternalStorageProvider. Needs the shared-folder grant (`SharedFolderGrantTest`); skips without it.
 */
@RunWith(AndroidJUnit4::class)
class ImportRealStorageTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = deviceRules(compose)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val robot by lazy { ImportRobot(compose) }

    @Before
    fun clean() {
        SharedFolder.assumeGranted(context)
        SharedFolder.clear()
    }

    @After
    fun tidy() {
        if (SharedFolder.isGranted(context)) SharedFolder.clear()
    }

    @Test
    fun importsSamplesIntoSharedStorage() {
        val groups = Samples.copyGroups(2, FixtureDocumentsProvider.dir(context, "card"))
        robot.openTab().addDestination(SharedFolder.treeUri).structure(StructureMode.FLAT)

        val job = robot.assertCompleted(robot.importAll(FixtureDocumentsProvider.treeUri(context, "card")))

        val expected = groups.flatten().map { "${it.size} ${it.name}" }.sorted()
        val onDisk = Shell.text("cd ${Shell.quote(SharedFolder.PATH)} && for f in *; do [ -f \"\$f\" ] && stat -c '%s %n' \"\$f\"; done")
            .lines().filter { it.isNotBlank() }.sorted()
        assertEquals(expected, onDisk)
        assertEquals(0, job.failedFiles)
    }
}
