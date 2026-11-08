package dev.shehran.rawcull.device.library

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.shehran.rawcull.MainActivity
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.device.deviceRules
import dev.shehran.rawcull.device.robots.LibraryRobot
import dev.shehran.rawcull.util.xmp.XmpDocument
import dev.shehran.rawcull.util.xmp.XmpProps
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** R8.6: the culling keys from `LibraryKeymap`, undo/redo, auto-advance, batches and the sidecars they write. */
@RunWith(AndroidJUnit4::class)
class LibraryCullingTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = deviceRules(compose)

    private val library by lazy { LibraryRobot(compose) }

    @Test
    fun flagRatingAndLabelKeys() {
        library.seed("cull", 3)
        library.clickGroup(0)
        val name = checkNotNull(library.current()).fileName

        library.press(Key.P).await { library.photo(name).cullStatus == CullStatus.PICK }
        library.press(Key.X).await { library.photo(name).cullStatus == CullStatus.REJECT }
        library.press(Key.U).await { library.photo(name).cullStatus == CullStatus.UNFLAGGED }
        library.press(Key.Three).await { library.photo(name).rating == 3 }
        library.press(Key.RightBracket).await { library.photo(name).rating == 4 }
        library.press(Key.Zero).await { library.photo(name).rating == 0 }
        library.press(Key.Six).await { library.photo(name).colorLabel == ColorLabel.RED }
        library.press(Key.Six).await { library.photo(name).colorLabel == ColorLabel.NONE }
        library.press(Key.Eight).await { library.photo(name).colorLabel == ColorLabel.GREEN }
    }

    @Test
    fun undoAndRedoByKey() {
        library.seed("undo", 2)
        library.clickGroup(0)
        val name = checkNotNull(library.current()).fileName

        library.press(Key.P).await { library.photo(name).cullStatus == CullStatus.PICK }
        library.pressWith(Key.Z, ctrl = true).await { library.photo(name).cullStatus == CullStatus.UNFLAGGED }
        assertTrue(library.state().canRedo)
        library.pressWith(Key.Y, ctrl = true).await { library.photo(name).cullStatus == CullStatus.PICK }
        library.pressWith(Key.Z, ctrl = true).pressWith(Key.Z, ctrl = true, shift = true)
            .await { library.photo(name).cullStatus == CullStatus.PICK }
    }

    @Test
    fun autoAdvanceMovesOnAfterAFlag() {
        library.seed("advance", 3)
        library.clickGroup(0)
        val first = checkNotNull(library.current()).fileName
        compose.runOnUiThread { library.viewModel.setAutoAdvance(true) }
        library.await { library.state().autoAdvance }

        library.press(Key.P).await { library.photo(first).cullStatus == CullStatus.PICK }

        library.await { library.current()?.fileName != first }
        assertEquals("LIB_0002.JPG", library.current()?.fileName)
    }

    @Test
    fun batchEditOnASelectionAndItsUndo() {
        library.seed("batch", 4)
        library.clickGroup(0)

        library.pressWith(Key.A, ctrl = true).await { library.state().selectedPhotoIds.size == 4 }
        library.press(Key.Four).await { library.state().photos.all { it.rating == 4 } }
        library.press(Key.P).await { library.state().photos.all { it.cullStatus == CullStatus.PICK } }
        library.pressWith(Key.Z, ctrl = true).await { library.state().photos.all { it.cullStatus == CullStatus.UNFLAGGED } }
        assertTrue("rating edit stays", library.state().photos.all { it.rating == 4 })
        library.pressWith(Key.D, ctrl = true).await { library.state().selectedPhotoIds.isEmpty() }
    }

    @Test
    fun sidecarHoldsTheEditAndKeepsForeignContent() {
        val dir = library.seed("sidecar", 2) { d ->
            File(d, "LIB_0001.xmp").writeText(
                """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:qa="http://example.com/qa/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/" qa:Keep="yes">
<dc:creator><rdf:Seq><rdf:li>Kept Author</rdf:li></rdf:Seq></dc:creator></rdf:Description></rdf:RDF></x:xmpmeta>""",
            )
        }
        library.select("LIB_0001.JPG")

        library.press(Key.P).press(Key.Three)
        val sidecar = library.awaitSidecar(dir, "rawcull:Pick", "Rating")
        assertEquals("LIB_0001.xmp", sidecar)

        val bytes = File(dir, sidecar).readBytes()
        val doc = XmpDocument.parse(bytes).getOrThrow()
        assertEquals("3", doc.valueOf(XmpProps.RATING))
        val text = bytes.decodeToString()
        assertTrue("foreign attribute kept: $text", text.contains("qa:Keep") && text.contains("http://example.com/qa/1.0/"))
        assertTrue("foreign element kept: $text", text.contains("Kept Author"))
        assertTrue("Pick written: $text", Regex("""rawcull:Pick\s*(=\s*"1"|>1<)""").containsMatchIn(text))
    }
}
