package dev.shehran.rawcull.device.library

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.shehran.rawcull.MainActivity
import dev.shehran.rawcull.device.deviceRules
import dev.shehran.rawcull.device.robots.LibraryRobot
import dev.shehran.rawcull.library.CullEdit
import dev.shehran.rawcull.library.GeoInput
import dev.shehran.rawcull.library.GeoParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** R8.6: GPS (paste a Maps link, batch apply, undo, clear) and the viewing toggles. */
@RunWith(AndroidJUnit4::class)
class LibraryGpsAndViewingTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = deviceRules(compose)

    private val library by lazy { LibraryRobot(compose) }

    private fun located() = library.state().photos.count { it.gpsLatitude != null }

    @Test
    fun pastedMapsLinkAppliesToABatchAndUndoes() {
        val dir = library.seed("gps", 3)
        val parsed = GeoInput.parse("https://www.google.com/maps?q=48.8584,2.2945")
        assertTrue("link parses: $parsed", parsed is GeoParse.Ok)
        val point = (parsed as GeoParse.Ok).point
        library.clickGroup(0)
        library.pressWith(Key.A, ctrl = true).await { library.state().selectedPhotoIds.size == 3 }

        compose.runOnUiThread { library.viewModel.apply(CullEdit.SetLocation(point)) }

        library.await { located() == 3 }
        assertEquals(48.8584, library.photo("LIB_0002.JPG").gpsLatitude!!, 1e-6)
        library.awaitSidecar(dir, "GPSLatitude")

        library.pressWith(Key.Z, ctrl = true).await { located() == 0 }
        library.pressWith(Key.Y, ctrl = true).await { located() == 3 }
        compose.runOnUiThread { library.viewModel.apply(CullEdit.ClearLocation) }
        library.await { located() == 0 }
    }

    @Test
    fun cameraLocationShowsAfterAnImport() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        dev.shehran.rawcull.device.Samples.copyGroups(3, dev.shehran.rawcull.qa.FixtureDocumentsProvider.dir(context, "camcard"))
        dev.shehran.rawcull.qa.FixtureDocumentsProvider.dir(context, "camdest")
        val imports = dev.shehran.rawcull.device.robots.ImportRobot(compose)
        imports.openTab().addDestination(dev.shehran.rawcull.qa.FixtureDocumentsProvider.treeUri(context, "camdest"))
        imports.assertCompleted(imports.importAll(dev.shehran.rawcull.qa.FixtureDocumentsProvider.treeUri(context, "camcard")))

        val withGps = imports.count("SELECT COUNT(*) FROM photo_assets WHERE gpsInFile = 1")
        org.junit.Assume.assumeTrue("no sample has GPS in its EXIF", withGps > 0)
        assertEquals(
            "every row with GPS in the file shows it",
            0L,
            imports.count("SELECT COUNT(*) FROM photo_assets WHERE gpsInFile = 1 AND (gpsLatitude IS NULL OR gpsLongitude IS NULL)"),
        )
    }

    @Test
    fun badLinksAreRefusedWithAReason() {
        assertTrue(GeoInput.parse("") is GeoParse.Invalid)
        assertTrue(GeoInput.parse("not a place") is GeoParse.Invalid)
        assertTrue(GeoInput.parse("https://maps.app.goo.gl/abc") is GeoParse.Invalid)
    }

    @Test
    fun viewingTogglesOpenAndCloseWithoutErrors() {
        library.seed("view", 2)
        library.clickGroup(0)

        library.press(Key.E).await { library.state().viewMode.name == "LOUPE" }
        library.press(Key.O).await { library.state().peaking }
        library.press(Key.O).await { !library.state().peaking }
        val overlay = library.state().viewing.histogramOverlay
        library.press(Key.H).await { library.state().viewing.histogramOverlay != overlay }
        library.press(Key.H).await { library.state().viewing.histogramOverlay == overlay }

        library.press(Key.F).await { library.state().fullWindow }
        library.press(Key.Escape).await { !library.state().fullWindow }
        library.press(Key.F).await { library.state().fullWindow }
        library.press(Key.F).await { !library.state().fullWindow }

        library.pressWith(Key.Enter, ctrl = true).await { library.state().slideshow != null }
        library.pressWith(Key.Enter, ctrl = true).await { library.state().slideshow == null }
        library.press(Key.G).await { library.state().viewMode.name == "GRID" }
        assertFalse("activity alive", compose.activity.isFinishing)
        assertNotNull(library.current())
        assertNull(library.state().slideshow)
    }
}
