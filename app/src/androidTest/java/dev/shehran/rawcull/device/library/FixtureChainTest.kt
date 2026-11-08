package dev.shehran.rawcull.device.library

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.shehran.rawcull.MainActivity
import dev.shehran.rawcull.device.QaResetRule
import dev.shehran.rawcull.device.Samples
import dev.shehran.rawcull.device.Smoke
import dev.shehran.rawcull.device.robots.LibraryRobot
import dev.shehran.rawcull.qa.FixtureDocumentsProvider
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** R8.3: the harness end to end: samples → fixture tree → Location → grid → key → sidecar on the provider. */
@Smoke
@RunWith(AndroidJUnit4::class)
class FixtureChainTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(QaResetRule()).around(compose)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun pickOnAFixtureTreeWritesTheSidecar() {
        val dir = FixtureDocumentsProvider.dir(context, TREE)
        val groups = Samples.copyGroups(3, dir)

        val library = LibraryRobot(compose)
            .openTab()
            .addLocation(FixtureDocumentsProvider.treeUri(context, TREE))
            .awaitGroups(3, timeoutMs = 60_000)
            .clickGroup(0)
            .press(Key.P)

        val picked = library.viewModel.uiState.value.selectedPhotoId
        // Element or attribute form, whichever XmpDocument writes.
        val sidecar = library.awaitSidecar(dir, "xmp:Rating", "rawcull:Pick")
        val text = java.io.File(dir, sidecar).readText()
        assertTrue("Pick not 1 in $sidecar: $text", Regex("""rawcull:Pick\s*(=\s*"1"|>1<)""").containsMatchIn(text))
        assertTrue("$sidecar isn't a copied group", groups.any { g -> g.any { sidecar == "${it.stem}.xmp" } })
        assertTrue("No current photo", picked != null)
    }

    private companion object {
        const val TREE = "chain"
    }
}
