package dev.shehran.rawcull.device.importer

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.shehran.rawcull.MainActivity
import dev.shehran.rawcull.data.model.StructureMode
import dev.shehran.rawcull.device.ImportFixtures
import dev.shehran.rawcull.device.Samples
import dev.shehran.rawcull.device.deviceRules
import dev.shehran.rawcull.device.robots.ImportRobot
import dev.shehran.rawcull.qa.FixtureDocumentsProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** R8.5: what the preview finds and where the path template puts it (fixture trees, real samples). */
@RunWith(AndroidJUnit4::class)
class ImportPlanningTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = deviceRules(compose)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val card get() = FixtureDocumentsProvider.dir(context, "card")
    private val dest get() = FixtureDocumentsProvider.dir(context, "dest")
    private val cardUri get() = FixtureDocumentsProvider.treeUri(context, "card")
    private val robot by lazy { ImportRobot(compose) }

    private fun prepare() = robot.also { dest.mkdirs() }.openTab().addDestination(FixtureDocumentsProvider.treeUri(context, "dest"))

    @Test
    fun previewCountsGroupsAndPairsSidecars() {
        val groups = Samples.copyGroups(3, card)
        val stem = groups[0][0].stem
        ImportFixtures.sidecar(File(card, "$stem.xmp"), rating = 3)

        prepare().pickSource(cardUri).awaitReady()

        val state = robot.viewModel.uiState.value
        assertEquals("one candidate per RAW+JPEG group", 3, state.candidates.size)
        val files = groups.flatten().size + 1
        assertEquals(files, state.candidates.sumOf { it.files.size })
        assertEquals(files, state.summary.files)
        assertEquals(3, state.summary.selectedPhotos)
        val paired = state.candidates.single { it.stem.equals(stem, ignoreCase = true) }
        assertTrue("sidecar belongs to its group", paired.files.any { it.role.isSidecar })
        assertEquals(groups[0].size + 1, paired.files.size)
    }

    @Test
    fun dateFoldersAndUndated() {
        val groups = Samples.copyGroups(2, card)
        ImportFixtures.undatedJpeg(File(card, "NODATE_0001.JPG"))

        prepare()
        robot.assertCompleted(robot.importAll(cardUri))

        val landed = ImportFixtures.relativeFiles(dest)
        assertTrue("undated file in Undated/: $landed", "Undated/NODATE_0001.JPG" in landed)
        val dated = Regex("""\d{4}/\d{4}-\d{2}-\d{2}/[^/]+""")
        val others = landed - "Undated/NODATE_0001.JPG"
        assertEquals(groups.flatten().size, others.size)
        assertTrue("sample files in date folders: $others", others.all { dated.matches(it) })
    }

    @Test
    fun projectFolderTakesEveryFile() {
        val groups = Samples.copyGroups(2, card)
        prepare().structure(StructureMode.PROJECT, "Wedding")
        robot.assertCompleted(robot.importAll(cardUri))

        val landed = ImportFixtures.relativeFiles(dest)
        assertEquals(groups.flatten().size, landed.size)
        assertTrue("all under Wedding/: $landed", landed.all { it.startsWith("Wedding/") && it.count { c -> c == '/' } == 1 })
    }

    @Test
    fun flatPutsFilesInTheRoot() {
        val groups = Samples.copyGroups(2, card)
        prepare().structure(StructureMode.FLAT)
        robot.assertCompleted(robot.importAll(cardUri))

        val landed = ImportFixtures.relativeFiles(dest)
        assertEquals(groups.flatten().size, landed.size)
        assertTrue("no folders: $landed", landed.none { '/' in it })
    }
}
