package dev.shehran.rawcull.ui.importer

import dev.shehran.rawcull.data.model.DEMO_ROOT_URI
import dev.shehran.rawcull.data.model.ImportItemRole
import dev.shehran.rawcull.data.model.ImportPreset
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.importer.CandidateFile
import dev.shehran.rawcull.importer.ImportCandidate
import dev.shehran.rawcull.importer.ImportSessionState
import dev.shehran.rawcull.importer.ImportSource
import dev.shehran.rawcull.importer.PreviewSummary
import dev.shehran.rawcull.ui.library.LocationUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportUiStateTest {
    private val location = LibraryLocation(id = 1, treeUri = "t", displayName = "Photos", addedAt = 0, lastOpenedAt = 0)
    private val preset = ImportPreset(id = 1, name = ImportPreset.STANDARD_NAME)

    private fun candidate(id: String, date: Long?) = ImportCandidate(
        id = id,
        stemGroup = id,
        stem = id,
        files = listOf(CandidateFile("u", "$id.ARW", 1, ImportItemRole.PRIMARY, PhotoFormat.ARW)),
        captureDate = date,
    )

    private val ready = ImportUiState(
        session = ImportSessionState.Ready(listOf(candidate("A", 5L))),
        locations = listOf(LocationUi(location, isAvailable = true)),
        draft = preset.copy(locationId = 1),
        presets = listOf(preset),
        selectedPresetId = 1,
    )

    @Test
    fun `Next needs photos and a connected destination`() {
        assertTrue(ready.canContinue)
        assertFalse(ready.copy(session = ImportSessionState.Empty).canContinue)
        assertFalse(ready.copy(draft = preset).canContinue)
        assertFalse(ready.copy(locations = listOf(LocationUi(location, isAvailable = false))).canContinue)
    }

    @Test
    fun `Next is available while dates are still being read`() {
        val reading = ready.copy(session = ImportSessionState.Reading(0, 1, listOf(candidate("A", null))))
        assertTrue(reading.canContinue)
    }

    @Test
    fun `the demo library is no destination`() {
        val demo = location.copy(id = 2, treeUri = DEMO_ROOT_URI)
        assertEquals(listOf(location), ready.copy(locations = ready.locations + LocationUi(demo, true)).destinationLocations.map { it.location })
    }

    @Test
    fun `a suggested location on a preset without one isn't an edit`() {
        assertFalse(ready.isDraftModified)
    }

    @Test
    fun `changing the folders is an edit`() {
        assertTrue(ready.copy(draft = ready.draft.copy(projectName = "x")).isDraftModified)
    }

    @Test
    fun `moving a preset off its own location is an edit`() {
        val own = preset.copy(locationId = 7)
        assertTrue(ready.copy(presets = listOf(own)).isDraftModified)
    }

    @Test
    fun `start needs something selected and enough space`() {
        val selected = ready.copy(summary = PreviewSummary(selectedPhotos = 1, bytes = 100))
        assertTrue(selected.canStart)
        assertFalse(selected.copy(summary = PreviewSummary()).canStart)
        assertFalse(selected.copy(destinationFreeBytes = 50).canStart)
        assertFalse(selected.copy(session = ImportSessionState.SourceLost).canStart)
        assertFalse(selected.copy(isStarting = true).canStart)
    }

    @Test
    fun `the delete notice is only for detected cards`() {
        val card = ready.copy(deleteFromCardSetting = true, source = ImportSource("t", "Card", "ABCD"))
        assertTrue(card.deleteFromCard)
        assertFalse(card.copy(source = ImportSource("t", "Folder")).deleteFromCard)
    }

    @Test
    fun `the summary path follows what's being imported, not what's hidden`() {
        val hidden = candidate("OLD", 1L).let { c ->
            c.copy(files = c.files.map { it.copy(alreadyImported = true) })
        }
        val state = ready.copy(session = ImportSessionState.Ready(listOf(hidden, candidate("NEW", 9L))))

        assertEquals(9L, state.firstImportDate)
        assertEquals(1L, state.firstPhotoDate)
    }
}
