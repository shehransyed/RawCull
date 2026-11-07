package dev.shehran.rawcull.ui.library

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.shehran.rawcull.data.deletion.DeletionGateway
import dev.shehran.rawcull.data.local.AppDatabase
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.FilterOptions
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.repository.LocationAccess
import dev.shehran.rawcull.data.repository.LocationRepository
import dev.shehran.rawcull.data.repository.PhotoCatalog
import dev.shehran.rawcull.data.repository.SettingsRepository
import dev.shehran.rawcull.data.scan.ScanCoordinator
import dev.shehran.rawcull.data.xmp.LocalSidecarStore
import dev.shehran.rawcull.data.xmp.SidecarStores
import dev.shehran.rawcull.data.xmp.XMP_TOOLKIT
import dev.shehran.rawcull.data.xmp.XmpWriteQueue
import dev.shehran.rawcull.library.CullChange
import dev.shehran.rawcull.library.CullEdit
import dev.shehran.rawcull.library.LibraryScope
import dev.shehran.rawcull.util.xmp.XmpPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real ViewModel on a fake catalog and a fake trash. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryViewModelTest {
    private class FakeCatalog : PhotoCatalog {
        val rows = MutableStateFlow<List<PhotoAsset>>(emptyList())
        val writes = ArrayList<CullChange>()

        override fun observeScope(rootUri: String, scope: LibraryScope): Flow<List<PhotoAsset>> = rows
        override fun observeFolderCounts(rootUri: String): Flow<Map<String, Int>> = flowOf(emptyMap())
        override fun observeImportPhotoCount(jobId: Long): Flow<Int> = flowOf(0)
        override suspend fun loadSampleShoot() = Unit

        override suspend fun applyChange(change: CullChange) {
            writes += change
            rows.update { list ->
                list.map { row ->
                    change.after[row.id]?.let {
                        row.copy(rating = it.rating, cullStatus = it.cullStatus, colorLabel = it.colorLabel, tags = it.tags)
                    } ?: row
                }
            }
        }

        override suspend fun photosById(ids: List<String>) = rows.value.filter { it.id in ids }

        override suspend fun removeRows(ids: List<String>) {
            rows.update { list -> list.filterNot { it.id in ids } }
        }
    }

    private inner class FakeGateway : DeletionGateway {
        override var apiLevel = 36
        val deleted = ArrayList<String>()
        val trashed = ArrayList<List<String>>()
        var stillThere = emptySet<String>()

        override fun mediaUri(documentUri: String) = "media/$documentUri".takeIf { documentUri.endsWith(".ARW") }

        override fun trashRequest(mediaUris: List<String>, trashed: Boolean): IntentSender {
            this.trashed += mediaUris
            return PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE).intentSender
        }

        override fun documentExists(documentUri: String) = documentUri in stillThere

        override fun deleteDocument(documentUri: String): Boolean {
            deleted += documentUri
            return true
        }
    }

    private class AlwaysThere : LocationAccess {
        override fun takePersistable(treeUri: String) = true
        override fun release(treeUri: String) = Unit
        override fun isAvailable(treeUri: String) = true
        override fun displayName(treeUri: String) = treeUri.substringAfterLast('/')
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val catalog = FakeCatalog()
    private val gateway = FakeGateway()
    private lateinit var database: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var locations: LocationRepository
    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var viewModel: LibraryViewModel

    private val state get() = viewModel.uiState.value

    private fun photo(id: String, rating: Int = 0, status: CullStatus = CullStatus.UNFLAGGED, dir: String = "") = PhotoAsset(
        id = id,
        rootUri = "tree",
        relativeDir = dir,
        fileName = "$id.ARW",
        fileUri = "doc/$id.ARW",
        format = PhotoFormat.ARW,
        fileSizeBytes = 100,
        dateTaken = BASE_DATE + id.hashCode(),
        rating = rating,
        cullStatus = status,
        xmpFileUri = "doc/$id.xmp",
    )

    @Before
    fun setUp() {
        main = newSingleThreadContext("test-main")
        Dispatchers.setMain(main)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dao = database.rawPhotoDao()
        settings = SettingsRepository(context)
        locations = LocationRepository(
            locations = database.locationDao(),
            photos = dao,
            access = AlwaysThere(),
            flushXmp = {},
            isGrantInUse = { false },
        )
        // Settings outlive a test and every database starts its ids at 1, so a new Location 1 would
        // inherit what an earlier test saved for its own. The seed row moves each test's ids on.
        idBase += ID_STEP
        runBlocking {
            database.locationDao().insert(
                LibraryLocation(id = idBase, treeUri = "seed", displayName = "seed", addedAt = 0, lastOpenedAt = 0),
            )
        }
        val store = LocalSidecarStore()
        val stores = SidecarStores(store, store)
        val appScope = CoroutineScope(Job())
        val queue = XmpWriteQueue(appScope, dao, stores, { XmpPrefs() }, XMP_TOOLKIT)
        // A cancelled scope, so that no real scan starts on the fake tree.
        val noScans = CoroutineScope(Job().apply { cancel() })
        viewModel = LibraryViewModel(
            appContext = context,
            repository = catalog,
            settings = settings,
            locations = locations,
            jobs = database.importJobDao(),
            scanCoordinator = ScanCoordinator(context, database, dao, noScans, stores, queue),
            writeQueue = queue,
            deletions = gateway,
            startupFinished = { awaitCancellation() }, // Restoring a session isn't what's tested here
            log = {},
        )
    }

    @After
    fun tearDown() {
        // Nothing of this test may still be using Main when the next one swaps it.
        runBlocking { viewModel.viewModelScope.coroutineContext.job.cancelAndJoin() }
        database.close()
        Dispatchers.resetMain()
        main.close()
    }

    private fun waitUntil(condition: () -> Boolean) = runBlocking {
        withTimeout(TIMEOUT_MS) { while (!condition()) delay(5) }
    }

    /** Opens a fresh Location, so what one test remembers doesn't reach the next. */
    private fun open(vararg photos: PhotoAsset): LibraryLocation {
        val location = runBlocking { checkNotNull(locations.add("content://tree/${System.nanoTime()}")) }
        catalog.rows.value = photos.toList()
        viewModel.selectLocation(location.id)
        waitUntil { state.activeLocation?.id == location.id && state.filteredPhotos.size == photos.size }
        return location
    }

    private fun rating(id: String) = catalog.rows.value.first { it.id == id }.rating

    private fun status(id: String) = catalog.rows.value.first { it.id == id }.cullStatus

    private fun select(vararg ids: String) {
        ids.forEach { viewModel.togglePhotoSelection(it) }
        waitUntil { state.selectedPhotoIds == ids.toSet() }
    }

    @Test
    fun `an edit on a selection writes one batch and one undo step`() {
        open(photo("a"), photo("b"), photo("c"))
        select("a", "b")

        viewModel.apply(CullEdit.Rating(4, toggle = false))
        waitUntil { catalog.writes.isNotEmpty() }

        assertEquals(1, catalog.writes.size)
        assertEquals(setOf("a", "b"), catalog.writes.single().ids.toSet())
        assertEquals(0, rating("c"))
        waitUntil { state.canUndo }

        viewModel.undo()
        waitUntil { rating("a") == 0 && rating("b") == 0 }
        assertFalse(state.canUndo)
    }

    @Test
    fun `without a selection the current photo is the target`() {
        open(photo("a"), photo("b"))
        val current = checkNotNull(state.selectedPhotoId)

        viewModel.apply(CullEdit.Flag(CullStatus.PICK))
        waitUntil { catalog.writes.isNotEmpty() }

        assertEquals(listOf(current), catalog.writes.single().ids)
    }

    @Test
    fun `undo restores each photo's own state`() {
        open(photo("a", rating = 1), photo("b", rating = 2, status = CullStatus.PICK), photo("c", rating = 5))
        select("a", "b", "c")
        viewModel.apply(CullEdit.Rating(4, toggle = false))
        waitUntil { rating("a") == 4 && rating("b") == 4 && rating("c") == 4 }

        viewModel.undo()
        waitUntil { rating("a") == 1 }

        assertEquals(2, rating("b"))
        assertEquals(5, rating("c"))
        assertEquals(CullStatus.PICK, status("b"))

        viewModel.redo()
        waitUntil { rating("a") == 4 && rating("c") == 4 }
    }

    @Test
    fun `an edit that changes nothing leaves no undo step`() {
        open(photo("a", rating = 5))
        viewModel.apply(CullEdit.RatingStep(1))
        runBlocking { delay(50) }
        assertTrue(catalog.writes.isEmpty())
        assertFalse(state.canUndo)
    }

    @Test
    fun `auto-advance moves on without skipping when photos leave the filter`() {
        open(photo("a"), photo("b"), photo("c"))
        viewModel.updateFilter { it.copy(flags = setOf(CullStatus.UNFLAGGED)) }
        val order = state.filteredPhotos.map { it.id }
        val visited = ArrayList<String>()

        repeat(3) { round ->
            val current = checkNotNull(state.selectedPhotoId)
            visited += current
            viewModel.apply(CullEdit.Flag(CullStatus.PICK), advance = true)
            waitUntil { state.filteredPhotos.size == 2 - round }
        }

        assertEquals(order, visited)
    }

    @Test
    fun `advance stays on the last photo`() {
        open(photo("a"), photo("b"))
        viewModel.lastPhoto()
        val last = state.filteredPhotos.last().id
        waitUntil { state.selectedPhotoId == last }

        viewModel.apply(CullEdit.Rating(3), advance = true)
        waitUntil { catalog.writes.isNotEmpty() }

        assertEquals(last, state.selectedPhotoId)
    }

    @Test
    fun `a batch doesn't advance`() {
        open(photo("a"), photo("b"), photo("c"))
        val current = state.selectedPhotoId
        select("a", "b")

        viewModel.apply(CullEdit.Rating(3, toggle = false), advance = true)
        waitUntil { catalog.writes.isNotEmpty() }

        assertEquals(current, state.selectedPhotoId)
    }

    @Test
    fun `filters are remembered per Location`() {
        val first = open(photo("a", status = CullStatus.PICK), photo("b"))
        viewModel.updateFilter { it.copy(flags = setOf(CullStatus.PICK)) }
        waitUntil { state.filteredPhotos.size == 1 }
        waitUntil { runBlocking { settings.filters(first.id) }.flags == setOf(CullStatus.PICK) }

        open(photo("c"), photo("d"))
        assertEquals(FilterOptions(), state.filterOptions)

        viewModel.selectLocation(first.id)
        waitUntil { state.activeLocation?.id == first.id }
        assertEquals(setOf(CullStatus.PICK), state.filterOptions.flags)
    }

    @Test
    fun `filters switch off and on without being forgotten`() {
        open(photo("a", status = CullStatus.PICK), photo("b"))
        viewModel.updateFilter { it.copy(flags = setOf(CullStatus.PICK)) }
        waitUntil { state.filteredPhotos.size == 1 }

        viewModel.toggleFilters()
        waitUntil { state.filteredPhotos.size == 2 }
        assertEquals(setOf(CullStatus.PICK), state.filterOptions.flags)

        viewModel.toggleFilters()
        waitUntil { state.filteredPhotos.size == 1 }
    }

    @Test
    fun `a selection never holds what the filter hides`() {
        open(photo("a", status = CullStatus.PICK), photo("b"))
        select("a", "b")

        viewModel.updateFilter { it.copy(flags = setOf(CullStatus.PICK)) }

        waitUntil { state.selectedPhotoIds == setOf("a") }
    }

    @Test
    fun `switching Location forgets the undo history and the selection`() {
        open(photo("a"), photo("b"))
        select("a")
        viewModel.apply(CullEdit.Rating(3, toggle = false))
        waitUntil { state.canUndo }

        open(photo("c"), photo("d"))

        assertFalse(state.canUndo)
        assertTrue(state.selectedPhotoIds.isEmpty())
    }

    @Test
    fun `esc steps back one level at a time`() {
        open(photo("a", dir = "x"), photo("b", dir = "y"))
        viewModel.setScope(LibraryScope.Folder("x"))
        viewModel.setViewMode(DamViewMode.LOUPE)
        select("a")

        viewModel.stepBack()
        waitUntil { !state.isSelecting }
        assertEquals(DamViewMode.LOUPE, state.viewMode)

        viewModel.stepBack()
        waitUntil { state.viewMode == DamViewMode.GRID }
        assertEquals(LibraryScope.Folder("x"), state.scope)

        viewModel.stepBack()
        waitUntil { state.scope == LibraryScope.All }
    }

    private fun requestDeletion(selectionOnly: Boolean = false) {
        viewModel.requestDeleteRejects(selectionOnly)
        waitUntil { state.deletionPrompt != null }
    }

    @Test
    fun `deleting rejects removes rows and their undo entries`() {
        open(photo("a", status = CullStatus.UNFLAGGED), photo("b"))
        select("a")
        viewModel.apply(CullEdit.Flag(CullStatus.REJECT, toggle = false))
        waitUntil { status("a") == CullStatus.REJECT }
        viewModel.clearSelection()

        requestDeletion()
        viewModel.confirmDeletion()
        waitUntil { state.trashRequest != null }
        viewModel.onTrashRequestLaunched()
        viewModel.onTrashResult(approved = true, restoring = false)

        waitUntil { catalog.rows.value.map { it.id } == listOf("b") }
        waitUntil { !state.canUndo }
        assertEquals(listOf("media/doc/a.ARW"), gateway.trashed.single())
        assertEquals(listOf("doc/a.xmp"), gateway.deleted)
    }

    @Test
    fun `cancelling the system dialog deletes nothing, sidecars included`() {
        open(photo("a", status = CullStatus.REJECT))
        requestDeletion()
        viewModel.confirmDeletion()
        waitUntil { state.trashRequest != null }

        viewModel.onTrashResult(approved = false, restoring = false)

        waitUntil { state.snackbarMessage == "Nothing was deleted" }
        assertTrue(gateway.deleted.isEmpty())
        assertEquals(1, catalog.rows.value.size)
    }

    @Test
    fun `a photo still there after the trash keeps its sidecar`() {
        open(photo("a", status = CullStatus.REJECT), photo("b", status = CullStatus.REJECT))
        gateway.stillThere = setOf("doc/a.ARW")
        requestDeletion()
        viewModel.confirmDeletion()
        waitUntil { state.trashRequest != null }

        viewModel.onTrashResult(approved = true, restoring = false)

        waitUntil { catalog.rows.value.map { it.id } == listOf("a") }
        assertEquals(listOf("doc/b.xmp"), gateway.deleted)
    }

    @Test
    fun `before Android 11 nothing asks the system`() {
        gateway.apiLevel = 29
        open(photo("a", status = CullStatus.REJECT))
        requestDeletion()
        assertTrue(checkNotNull(state.deletionPrompt).isAllPermanent)

        viewModel.confirmDeletion()

        waitUntil { catalog.rows.value.isEmpty() }
        assertTrue(gateway.trashed.isEmpty())
        assertEquals(listOf("doc/a.ARW", "doc/a.xmp"), gateway.deleted)
        assertEquals(null, state.snackbarAction)
    }

    @Test
    fun `only the rejects of a selection go`() {
        open(photo("a", status = CullStatus.REJECT), photo("b", status = CullStatus.REJECT), photo("c"))
        select("a", "c")

        requestDeletion(selectionOnly = true)

        assertEquals(listOf("a"), checkNotNull(state.deletionPrompt).photoIds)
    }

    @Test
    fun `a later message doesn't inherit Restore`() {
        open(photo("a", status = CullStatus.REJECT), photo("b"))
        requestDeletion()
        viewModel.confirmDeletion()
        waitUntil { state.trashRequest != null }
        viewModel.onTrashResult(approved = true, restoring = false)
        waitUntil { state.snackbarAction == SnackbarAction.RESTORE }

        viewModel.apply(CullEdit.Rating(3))
        waitUntil { state.canUndo }
        viewModel.undo()

        waitUntil { state.snackbarMessage?.startsWith("Undid") == true }
        assertEquals(null, state.snackbarAction)
    }

    @Test
    fun `with no rejects nothing is asked`() {
        open(photo("a"))
        viewModel.requestDeleteRejects()
        waitUntil { state.snackbarMessage == "No rejected photos here" }
        assertEquals(null, state.deletionPrompt)
    }

    private companion object {
        var idBase = 0L
        const val ID_STEP = 100L
        const val TIMEOUT_MS = 10_000L
        const val BASE_DATE = 1_700_000_000_000L
    }
}
