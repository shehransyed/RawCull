package dev.shehran.rawcull.ui.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.shehran.rawcull.data.model.GeoPoint
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.scan.CameraLocations
import dev.shehran.rawcull.library.CullEdit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Location card's states (R8 D6). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocationCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val edits = ArrayList<CullEdit>()
    private val place = GeoPoint(40.5, -79.0)

    private fun photo(point: GeoPoint? = null, gpsInFile: Boolean = false) = PhotoAsset(
        id = "a",
        rootUri = "root",
        fileName = "a.ARW",
        fileUri = "content://a",
        format = PhotoFormat.ARW,
        fileSizeBytes = 1L,
        dateTaken = 0L,
        gpsLatitude = point?.latitude,
        gpsLongitude = point?.longitude,
        gpsInFile = gpsInFile,
    )

    private fun show(photo: PhotoAsset, selectedCount: Int = 1, cameras: CameraLocations = CameraLocations.None) {
        compose.setContent {
            CompositionLocalProvider(LocalCameraLocations provides cameras) {
                LocationCard(photo = photo, selectedCount = selectedCount, onEdit = { edits += it })
            }
        }
    }

    @Test
    fun `no location offers Add location and Paste, and the sheet opens on its field`() {
        show(photo())
        compose.onNodeWithTag("location_value").assertTextContains("No location")
        compose.onNodeWithTag("location_add").assertIsDisplayed().assertTextContains("Add location")
        compose.onNodeWithTag("location_paste").assertIsDisplayed()
        compose.onNodeWithTag("location_edit").assertDoesNotExist()
        compose.onNodeWithTag("location_copy").assertDoesNotExist()
        compose.onNodeWithTag("location_clear").assertDoesNotExist()

        compose.onNodeWithTag("location_add").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("location_input").assertIsFocused()
    }

    @Test
    fun `a sidecar location offers Edit, Copy, Maps and Clear`() {
        show(photo(place))
        compose.onNodeWithTag("location_source").assertTextContains("Sidecar")
        compose.onNodeWithTag("location_edit").assertTextContains("Edit")
        compose.onNodeWithTag("location_add").assertDoesNotExist()
        compose.onNodeWithTag("location_copy").assertIsDisplayed()
        compose.onNodeWithTag("location_maps").assertIsDisplayed()
        compose.onNodeWithTag("location_clear").performClick()
        assertEquals(listOf<CullEdit>(CullEdit.ClearLocation), edits)
    }

    @Test
    fun `a camera location can be edited but not cleared`() {
        show(photo(place, gpsInFile = true), cameras = CameraLocations { place })
        compose.waitForIdle()
        compose.onNodeWithTag("location_source").assertTextContains("From the camera")
        compose.onNodeWithTag("location_edit").assertIsDisplayed()
        compose.onNodeWithTag("location_clear").assertIsNotEnabled()
    }

    @Test
    fun `several selected name the count`() {
        show(photo(), selectedCount = 3)
        compose.onNodeWithText("Add location to 3 photos").assertIsDisplayed()
    }

    @Test
    fun `labels per state`() {
        assertEquals("Add location", locationActionLabel(hasLocation = false, selectedCount = 1))
        assertEquals("Edit", locationActionLabel(hasLocation = true, selectedCount = 0))
        assertEquals("Add location to 4 photos", locationActionLabel(hasLocation = false, selectedCount = 4))
        assertEquals("Edit location of 4 photos", locationActionLabel(hasLocation = true, selectedCount = 4))
    }
}
