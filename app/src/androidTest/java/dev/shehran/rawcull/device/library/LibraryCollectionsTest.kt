package dev.shehran.rawcull.device.library

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.shehran.rawcull.MainActivity
import dev.shehran.rawcull.RawCullApp
import dev.shehran.rawcull.data.export.ExportOptions
import dev.shehran.rawcull.device.deviceRules
import dev.shehran.rawcull.device.robots.LibraryRobot
import dev.shehran.rawcull.library.ExportContent
import dev.shehran.rawcull.qa.FixtureDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** R8.6: collections (create, B with a target, custom order) and the ordered export built on them. */
@RunWith(AndroidJUnit4::class)
class LibraryCollectionsTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = deviceRules(compose)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val library by lazy { LibraryRobot(compose) }
    private val container get() = (context.applicationContext as RawCullApp).container

    private fun createCollection(name: String): Long {
        compose.runOnUiThread { library.viewModel.createCollection(name, parentId = null) }
        library.await { library.state().collections.any { it.name == name } }
        return library.state().collections.first { it.name == name }.id
    }

    private fun members(id: Long) = runBlocking { container.collectionRepository.memberIds(id) }

    @Test
    fun bAddsAndRemovesFromTheTargetCollection() {
        library.seed("coll", 3)
        val id = createCollection("Keepers")
        compose.runOnUiThread { library.viewModel.setTargetCollection(id) }
        library.await { library.state().targetCollectionId == id }
        library.select("LIB_0002.JPG")

        library.press(Key.B)
        library.await { members(id).size == 1 }
        assertEquals(library.photo("LIB_0002.JPG").id, members(id).single())

        library.press(Key.B)
        library.await { members(id).isEmpty() }
    }

    @Test
    fun collectionsKeepTheirOrderAndMembersLeaveWithDeletion() {
        library.seed("order", 4)
        val id = createCollection("Sequence")
        val ids = listOf("LIB_0004.JPG", "LIB_0002.JPG", "LIB_0003.JPG").map { library.photo(it).id }
        runBlocking {
            container.collectionRepository.add(id, ids)
            container.collectionRepository.reorder(id, ids.reversed())
        }
        assertEquals(ids.reversed(), members(id))

        compose.runOnUiThread { library.viewModel.deleteCollection(id) }
        library.await { library.state().collections.none { it.id == id } }
        assertEquals(emptyList<String>(), members(id))
    }

    @Test
    fun exportNumbersFilesInOrderAndStepsCaptureTimes() {
        library.seed("exp", 4)
        val order = listOf("LIB_0003.JPG", "LIB_0001.JPG", "LIB_0004.JPG").map { library.photo(it) }
        val out = FixtureDocumentsProvider.dir(context, "out")

        val summary = runBlocking {
            container.exportEngine.run(
                FixtureDocumentsProvider.treeUri(context, "out").toString(),
                order,
                ExportOptions(ExportContent.ORIGINALS, keepOrder = true),
            )
        }

        assertEquals(3, summary.exported)
        assertTrue("nothing failed: ${summary.failed}", summary.failed.isEmpty())
        val landed = out.walkTopDown().filter { it.isFile && it.name.endsWith(".JPG") }.sortedBy { it.name }.toList()
        assertEquals(listOf("001_LIB_0003.JPG", "002_LIB_0001.JPG", "003_LIB_0004.JPG"), landed.map { it.name })
        val times = landed.map { ExifInterface(it).getAttribute(ExifInterface.TAG_DATETIME) }
        assertTrue("capture times rewritten in order: $times", times.none { it == null } && times == times.sortedBy { it })
        assertEquals("library originals untouched", 4, FixtureDocumentsProvider.dir(context, "exp").listFiles { f -> f.name.endsWith(".JPG") }!!.size)
    }
}
