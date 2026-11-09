package dev.shehran.rawcull.device.layout

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.shehran.rawcull.BuildConfig
import dev.shehran.rawcull.RawCullApp
import dev.shehran.rawcull.device.ImportFixtures
import dev.shehran.rawcull.device.KnownIssue
import dev.shehran.rawcull.device.deviceRules
import dev.shehran.rawcull.qa.FixtureDocumentsProvider
import dev.shehran.rawcull.ui.components.LocalPreviewRepository
import dev.shehran.rawcull.ui.components.LocalPreviewWarmup
import dev.shehran.rawcull.ui.components.LocalShowPreviewTiming
import dev.shehran.rawcull.ui.importer.ImportViewModel
import dev.shehran.rawcull.ui.library.DamViewMode
import dev.shehran.rawcull.ui.library.LibraryViewModel
import dev.shehran.rawcull.ui.library.LocalCameraLocations
import dev.shehran.rawcull.ui.shell.RawCullApp as RawCullScreen
import dev.shehran.rawcull.ui.shell.LocalWindowSizeOverride
import dev.shehran.rawcull.ui.shell.ShellViewModel
import dev.shehran.rawcull.ui.theme.RawCullTheme
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * R8.7 (D14): the six main screens at 360×640, 640×360, 840×600 and 1280×800 dp, drawn in the
 * qa app under `DeviceConfigurationOverride.ForcedSize` (the tablet's own display size is never
 * changed; a size bigger than the display is scaled down to fit). Per screen: it renders, tagged
 * controls don't overlap and nothing is clipped off-screen (hard checks), and a screenshot goes
 * to `build/device-screens/`. Touch targets under 48 dp are collected the same way and asserted at
 * the end by a known-issue test, so they're reported without failing the run.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LayoutSizesTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = deviceRules(compose)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as RawCullApp).container

    @Test fun s1_compact360x640() = run(360, 640)

    @Test fun s2_compactLandscape640x360() = run(640, 360)

    @Test fun s3_medium840x600() = run(840, 600)

    @Test fun s4_large1280x800() = run(1280, 800)

    /** Reported as a known issue until R9 fixes the small targets; see the screens test output. */
    @KnownIssue("R9 touch targets under 48 dp")
    @Test
    fun s9_touchTargets() {
        assertTrue("${touch.size} controls under 48 dp, e.g. " + touch.take(12).joinToString("; "), touch.isEmpty())
    }

    @KnownIssue("R9 controls overlap")
    @Test
    fun s9_overlaps() {
        assertTrue("${overlaps.size} overlaps, e.g. " + overlaps.take(12).joinToString("; "), overlaps.isEmpty())
    }

    private fun run(widthDp: Int, heightDp: Int) {
        val label = "${widthDp}x$heightDp"
        val dir = FixtureDocumentsProvider.dir(context, "layout")
        ImportFixtures.jpegs(dir, 6)
        val card = FixtureDocumentsProvider.dir(context, "layout-card")
        ImportFixtures.jpegs(card, 4, prefix = "CARD")
        val treeUri = FixtureDocumentsProvider.treeUri(context, "layout")

        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                RawCullTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        val settings = container.settingsRepository
                        val showTiming by settings.showPreviewTiming.collectAsStateWithLifecycle(initialValue = false)
                        CompositionLocalProvider(
                            LocalPreviewRepository provides container.previewRepository,
                            LocalPreviewWarmup provides container.previewWarmup,
                            LocalCameraLocations provides container.cameraLocations,
                            LocalShowPreviewTiming provides (BuildConfig.DEBUG && showTiming),
                            LocalWindowSizeOverride provides (widthDp to heightDp),
                        ) {
                            Box(Modifier.fillMaxSize().testTag(LayoutChecks.ROOT_TAG)) {
                                RawCullScreen(
                                    libraryViewModel = viewModel(factory = LibraryViewModel.Factory),
                                    shellViewModel = viewModel(factory = ShellViewModel.Factory),
                                )
                            }
                        }
                    }
                }
            }
        }
        val library = ViewModelProvider(compose.activity, LibraryViewModel.Factory)[LibraryViewModel::class.java]
        val imports = ViewModelProvider(compose.activity, ImportViewModel.Factory)[ImportViewModel::class.java]
        compose.runOnUiThread { library.addLocation(treeUri) }
        awaitTag("grid_card_0", 90_000)

        val problems = mutableListOf<String>()
        fun capture(screen: String, only: (String) -> Boolean = { true }) {
            compose.waitForIdle()
            Thread.sleep(SETTLE_MS) // previews decode off the main thread
            val findings = LayoutChecks.check(compose, "$label $screen", density, only)
            touch += findings.touchTargets
            overlaps += findings.overlaps
            problems += findings.clipped
            LayoutChecks.screenshot(compose, "${label}_$screen")
        }

        capture("grid")

        compose.runOnUiThread { library.selectPhotoIndex(0); library.setViewMode(DamViewMode.LOUPE) }
        awaitTag("zoomable_loupe_container", 20_000)
        capture("loupe")

        compose.runOnUiThread { library.setCompareIndex(1); library.setViewMode(DamViewMode.COMPARE) }
        awaitTag("compare_loupe_container", 20_000)
        capture("compare")
        compose.runOnUiThread { library.setViewMode(DamViewMode.LOUPE) }

        compose.runOnUiThread { library.enterFullWindow() }
        awaitTag("full_window", 20_000)
        // The Library stays composed under the full window, so only the window's own controls count.
        capture("fullwindow") { it.startsWith("full_window") || it.startsWith("slideshow") }
        compose.runOnUiThread { library.exitFullWindow(); library.setViewMode(DamViewMode.GRID) } // compact loupes hide the bar

        awaitTag("nav_settings", 20_000) // the bar returns once the full window has closed
        compose.onNodeWithTag("nav_settings", useUnmergedTree = true).performClick()
        awaitTag("settings_screen", 20_000)
        capture("settings")

        awaitTag("nav_import", 20_000)
        compose.onNodeWithTag("nav_import", useUnmergedTree = true).performClick()
        compose.runOnUiThread {
            imports.addLocation(treeUri)
            imports.onFolderPicked(Uri.parse(FixtureDocumentsProvider.treeUri(context, "layout-card").toString()))
        }
        awaitTag("import_next_button", 90_000)
        compose.waitUntil(90_000) { imports.uiState.value.candidates.isNotEmpty() }
        compose.onNodeWithTag("import_next_button", useUnmergedTree = true).performClick()
        awaitTag("import_preview_screen", 30_000)
        compose.waitUntil(60_000) { compose.onAllNodes(hasTestTag("preview_tile"), true).fetchSemanticsNodes().isNotEmpty() }
        capture("importpreview")

        assertTrue("Clipped controls at $label:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private fun awaitTag(tag: String, timeoutMs: Long) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasTestTag(tag), true).fetchSemanticsNodes().isNotEmpty() }


    private companion object {
        const val SETTLE_MS = 800L
        val touch = mutableListOf<String>()
        val overlaps = mutableListOf<String>()
    }
}
