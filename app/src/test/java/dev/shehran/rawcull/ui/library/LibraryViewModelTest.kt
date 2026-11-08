package dev.shehran.rawcull.ui.library

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import dev.shehran.rawcull.data.preview.FakePreviewRepository
import dev.shehran.rawcull.data.repository.CollectionRepository
import dev.shehran.rawcull.data.repository.HistogramMode
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
import dev.shehran.rawcull.library.SlideshowClock
import dev.shehran.rawcull.util.xmp.XmpPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** The real ViewModel on a fake catalog and a fake trash. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryViewModelTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeCatalog : PhotoCatalog {
        val rows = MutableStateFlow<List<PhotoAsset>>(emptyList())
        val writes = CopyOnWriteArrayList<CullChange>() // Written on Main, read by the test

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
    private lateinit var settingsScope: CoroutineScope
    private lateinit var locations: LocationRepository
    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var viewModel: LibraryViewModel

    private val state get() = viewModel.uiState.value

    /** The slideshow's virtual time. */
    private var clockMs = 0L

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
        // A DataStore of its own: the app's is one per process, so it would carry one test's settings into the next.
        settingsScope = CoroutineScope(Dispatchers.IO + Job())
        settings = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = settingsScope) { tempFolder.newFile("settings.preferences_pb").also { it.delete() } },
        )
        locations = LocationRepository(
            locations = database.locationDao(),
            photos = dao,
            collections = CollectionRepository(database),
            access = AlwaysThere(),
            flushXmp = {},
            isGrantInUse = { false },
        )
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
            previews = FakePreviewRepository(),
            startupFinished = { awaitCancellation() }, // Restoring a session isn't what's tested here
            slideshowClock = SlideshowClock { clockMs },
            log = {},
        )
    }

    @After
    fun tearDown() {
        // Nothing of this test may still be using Main when the next one swaps it.
        runBlocking { viewModel.viewModelScope.coroutineContext.job.cancelAndJoin() }
        database.close()
        settingsScope.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    /** ViewModel calls run on Main, as the UI's do: from the test thread they would race its own updates. */
    private fun <T> onMain(block: LibraryViewModel.() -> T): T = runBlocking(main) { viewModel.block() }

    private fun waitUntil(condition: () -> Boolean) = runBlocking {
        withTimeout(TIMEOUT_MS) { while (!condition()) delay(5) }
    }

    /** Opens a fresh Location, so what one test remembers doesn't reach the next. */
    private fun open(vararg photos: PhotoAsset): LibraryLocation {
        val location = runBlocking { checkNotNull(locations.add("content://tree/${System.nanoTime()}")) }
        catalog.rows.value = photos.toList()
        onMain { selectLocation(location.id) }
        waitUntil { state.activeLocation?.id == location.id && state.filteredPhotos.size == photos.size }
        return location
    }

    private fun rating(id: String) = catalog.rows.value.first { it.id == id }.rating

    private fun status(id: String) = catalog.rows.value.first { it.id == id }.cullStatus

    private fun select(vararg ids: String) {
        ids.forEach { onMain { togglePhotoSelection(it) } }
        waitUntil { state.selectedPhotoIds == ids.toSet() }
    }

    @Test
    fun `an edit on a selection writes one batch and one undo step`() {
        open(photo("a"), photo("b"), photo("c"))
        select("a", "b")

        onMain { apply(CullEdit.Rating(4, toggle = false)) }
        waitUntil { catalog.writes.isNotEmpty() }

        assertEquals(1, catalog.writes.size)
        assertEquals(setOf("a", "b"), catalog.writes.single().ids.toSet())
        assertEquals(0, rating("c"))
        waitUntil { state.canUndo }

        onMain { undo() }
        waitUntil { rating("a") == 0 && rating("b") == 0 }
        assertFalse(state.canUndo)
    }

    @Test
    fun `without a selection the current photo is the target`() {
        open(photo("a"), photo("b"))
        val current = checkNotNull(state.selectedPhotoId)

        onMain { apply(CullEdit.Flag(CullStatus.PICK)) }
        waitUntil { catalog.writes.isNotEmpty() }

        assertEquals(listOf(current), catalog.writes.single().ids)
    }

    @Test
    fun `undo restores each photo's own state`() {
        open(photo("a", rating = 1), photo("b", rating = 2, status = CullStatus.PICK), photo("c", rating = 5))
        select("a", "b", "c")
        onMain { apply(CullEdit.Rating(4, toggle = false)) }
        waitUntil { rating("a") == 4 && rating("b") == 4 && rating("c") == 4 }

        onMain { undo() }
        waitUntil { rating("a") == 1 }

        assertEquals(2, rating("b"))
        assertEquals(5, rating("c"))
        assertEquals(CullStatus.PICK, status("b"))

        onMain { redo() }
        waitUntil { rating("a") == 4 && rating("c") == 4 }
    }

    @Test
    fun `an edit that changes nothing leaves no undo step`() {
        open(photo("a", rating = 5))
        onMain { apply(CullEdit.RatingStep(1)) }
        onMain { } // Anything apply launched on Main has started by now
        assertTrue(catalog.writes.isEmpty())
        assertFalse(state.canUndo)
    }

    @Test
    fun `auto-advance moves on without skipping when photos leave the filter`() {
        open(photo("a"), photo("b"), photo("c"))
        onMain { updateFilter { it.copy(flags = setOf(CullStatus.UNFLAGGED)) } }
        val order = state.filteredPhotos.map { it.id }
        val visited = ArrayList<String>()

        repeat(3) { round ->
            val current = checkNotNull(state.selectedPhotoId)
            visited += current
            onMain { apply(CullEdit.Flag(CullStatus.PICK), advance = true) }
            waitUntil { state.filteredPhotos.size == 2 - round }
        }

        assertEquals(order, visited)
    }

    @Test
    fun `advance stays on the last photo`() {
        open(photo("a"), photo("b"))
        onMain { lastPhoto() }
        val last = state.filteredPhotos.last().id
        waitUntil { state.selectedPhotoId == last }

        onMain { apply(CullEdit.Rating(3), advance = true) }
        waitUntil { catalog.writes.isNotEmpty() }

        assertEquals(last, state.selectedPhotoId)
    }

    @Test
    fun `a batch doesn't advance`() {
        open(photo("a"), photo("b"), photo("c"))
        val current = state.selectedPhotoId
        select("a", "b")

        onMain { apply(CullEdit.Rating(3, toggle = false), advance = true) }
        waitUntil { catalog.writes.isNotEmpty() }

        assertEquals(current, state.selectedPhotoId)
    }

    @Test
    fun `filters are remembered per Location`() {
        val first = open(photo("a", status = CullStatus.PICK), photo("b"))
        onMain { updateFilter { it.copy(flags = setOf(CullStatus.PICK)) } }
        waitUntil { state.filteredPhotos.size == 1 }
        waitUntil { runBlocking { settings.filters(first.id) }.flags == setOf(CullStatus.PICK) }

        open(photo("c"), photo("d"))
        assertEquals(FilterOptions(), state.filterOptions)

        onMain { selectLocation(first.id) }
        waitUntil { state.activeLocation?.id == first.id }
        assertEquals(setOf(CullStatus.PICK), state.filterOptions.flags)
    }

    @Test
    fun `filters switch off and on without being forgotten`() {
        open(photo("a", status = CullStatus.PICK), photo("b"))
        onMain { updateFilter { it.copy(flags = setOf(CullStatus.PICK)) } }
        waitUntil { state.filteredPhotos.size == 1 }

        onMain { toggleFilters() }
        waitUntil { state.filteredPhotos.size == 2 }
        assertEquals(setOf(CullStatus.PICK), state.filterOptions.flags)

        onMain { toggleFilters() }
        waitUntil { state.filteredPhotos.size == 1 }
    }

    @Test
    fun `a selection never holds what the filter hides`() {
        open(photo("a", status = CullStatus.PICK), photo("b"))
        select("a", "b")

        onMain { updateFilter { it.copy(flags = setOf(CullStatus.PICK)) } }

        waitUntil { state.selectedPhotoIds == setOf("a") }
    }

    @Test
    fun `switching Location forgets the undo history and the selection`() {
        open(photo("a"), photo("b"))
        select("a")
        onMain { apply(CullEdit.Rating(3, toggle = false)) }
        waitUntil { state.canUndo }

        open(photo("c"), photo("d"))

        assertFalse(state.canUndo)
        assertTrue(state.selectedPhotoIds.isEmpty())
    }

    @Test
    fun `esc steps back one level at a time`() {
        open(photo("a", dir = "x"), photo("b", dir = "y"))
        onMain { setScope(LibraryScope.Folder("x")) }
        onMain { setViewMode(DamViewMode.LOUPE) }
        select("a")

        onMain { stepBack() }
        waitUntil { !state.isSelecting }
        assertEquals(DamViewMode.LOUPE, state.viewMode)

        onMain { stepBack() }
        waitUntil { state.viewMode == DamViewMode.GRID }
        assertEquals(LibraryScope.Folder("x"), state.scope)

        onMain { stepBack() }
        waitUntil { state.scope == LibraryScope.All }
    }

    private fun requestDeletion(selectionOnly: Boolean = false) {
        onMain { requestDeleteRejects(selectionOnly) }
        waitUntil { state.deletionPrompt != null }
    }

    @Test
    fun `deleting rejects removes rows and their undo entries`() {
        open(photo("a", status = CullStatus.UNFLAGGED), photo("b"))
        select("a")
        onMain { apply(CullEdit.Flag(CullStatus.REJECT, toggle = false)) }
        waitUntil { status("a") == CullStatus.REJECT }
        // The catalog changed first; the request reads the ViewModel's own copy.
        waitUntil { state.rejectsCount == 1 }
        onMain { clearSelection() }

        requestDeletion()
        onMain { confirmDeletion() }
        waitUntil { state.trashRequest != null }
        onMain { onTrashRequestLaunched() }
        onMain { onTrashResult(approved = true, restoring = false) }

        waitUntil { catalog.rows.value.map { it.id } == listOf("b") }
        waitUntil { !state.canUndo }
        assertEquals(listOf("media/doc/a.ARW"), gateway.trashed.single())
        assertEquals(listOf("doc/a.xmp"), gateway.deleted)
    }

    @Test
    fun `cancelling the system dialog deletes nothing, sidecars included`() {
        open(photo("a", status = CullStatus.REJECT))
        requestDeletion()
        onMain { confirmDeletion() }
        waitUntil { state.trashRequest != null }

        onMain { onTrashResult(approved = false, restoring = false) }

        waitUntil { state.snackbarMessage == "Nothing was deleted" }
        assertTrue(gateway.deleted.isEmpty())
        assertEquals(1, catalog.rows.value.size)
    }

    @Test
    fun `a photo still there after the trash keeps its sidecar`() {
        open(photo("a", status = CullStatus.REJECT), photo("b", status = CullStatus.REJECT))
        gateway.stillThere = setOf("doc/a.ARW")
        requestDeletion()
        onMain { confirmDeletion() }
        waitUntil { state.trashRequest != null }

        onMain { onTrashResult(approved = true, restoring = false) }

        waitUntil { catalog.rows.value.map { it.id } == listOf("a") }
        assertEquals(listOf("doc/b.xmp"), gateway.deleted)
    }

    @Test
    fun `before Android 11 nothing asks the system`() {
        gateway.apiLevel = 29
        open(photo("a", status = CullStatus.REJECT))
        requestDeletion()
        assertTrue(checkNotNull(state.deletionPrompt).isAllPermanent)

        onMain { confirmDeletion() }

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
        onMain { confirmDeletion() }
        waitUntil { state.trashRequest != null }
        onMain { onTrashResult(approved = true, restoring = false) }
        waitUntil { state.snackbarAction == SnackbarAction.RESTORE }
        waitUntil { state.filteredPhotos.map { it.id } == listOf("b") && state.selectedPhotoId == "b" }

        onMain { apply(CullEdit.Rating(3)) }
        waitUntil { state.canUndo }
        onMain { undo() }

        waitUntil { state.snackbarMessage?.startsWith("Undid") == true }
        assertEquals(null, state.snackbarAction)
    }

    @Test
    fun `with no rejects nothing is asked`() {
        open(photo("a"))
        onMain { requestDeleteRejects() }
        waitUntil { state.snackbarMessage == "No rejected photos here" }
        assertEquals(null, state.deletionPrompt)
    }

    @Test
    fun `focus peaking starts off and toggles`() {
        open(photo("a"))
        assertFalse(state.peaking)
        onMain { togglePeaking() }
        assertTrue(state.peaking)
        onMain { togglePeaking() }
        assertFalse(state.peaking)
    }

    @Test
    fun `histogram channels, scale and overlay are saved choices`() {
        open(photo("a"))
        assertEquals(HistogramMode.RGB_LUMA, state.viewing.histogramMode)
        try {
            onMain { cycleHistogramMode() }
            waitUntil { state.viewing.histogramMode == HistogramMode.RGB }
            onMain { cycleHistogramMode() }
            waitUntil { state.viewing.histogramMode == HistogramMode.LUMA }
            onMain { cycleHistogramMode() }
            waitUntil { state.viewing.histogramMode == HistogramMode.RGB_LUMA }
            onMain { toggleHistogramLog() }
            waitUntil { state.viewing.histogramLog }
            onMain { toggleHistogramOverlay() }
            waitUntil { state.viewing.histogramOverlay }
        } finally {
            // Saved settings outlive this test.
            onMain { toggleHistogramLog() }
            onMain { toggleHistogramOverlay() }
            waitUntil { !state.viewing.histogramLog && !state.viewing.histogramOverlay }
        }
    }

    @Test
    fun `full window opens on the selected photo and leaves the view underneath alone`() {
        open(photo("a"), photo("b"))
        onMain { setViewMode(DamViewMode.GRID) }
        val shown = checkNotNull(state.selectedPhotoId)

        onMain { enterFullWindow() }

        assertTrue(state.fullWindow)
        assertEquals(shown, state.selectedPhotoId)
        assertEquals(DamViewMode.GRID, state.viewMode)
        onMain { exitFullWindow() }
        assertFalse(state.fullWindow)
        assertEquals(DamViewMode.GRID, state.viewMode)
    }

    @Test
    fun `full window from compare shows the selected photo and returns to compare`() {
        open(photo("a"), photo("b"))
        onMain { toggleCompareMode() }
        val shown = checkNotNull(state.selectedPhotoId)

        onMain { enterFullWindow() }
        assertEquals(shown, state.currentPhoto?.id)
        onMain { toggleFullWindow() }

        assertFalse(state.fullWindow)
        assertEquals(DamViewMode.COMPARE, state.viewMode)
    }

    @Test
    fun `full window needs a photo, and esc leaves it before anything else`() {
        onMain { enterFullWindow() }
        assertFalse(state.fullWindow)

        open(photo("a"), photo("b"))
        onMain { setViewMode(DamViewMode.LOUPE) }
        onMain { enterFullWindow() }
        onMain { stepBack() }

        assertFalse(state.fullWindow)
        assertEquals(DamViewMode.LOUPE, state.viewMode)
    }

    @Test
    fun `culling keys edit the shown photo in full window, and advance when asked`() {
        open(photo("fw1"), photo("fw2"))
        onMain { enterFullWindow() }
        val first = checkNotNull(state.selectedPhotoId)
        val second = state.filteredPhotos.first { it.id != first }.id

        onMain { apply(CullEdit.Rating(4)) }
        waitUntil { rating(first) == 4 }
        assertEquals(first, state.selectedPhotoId)

    }

    private fun photoIds() = state.filteredPhotos.map { it.id }

    /** What the loupe does once the shown photo is up, then time passing and the UI's wake-up. */
    private fun showFor(ms: Long) {
        val shown = checkNotNull(state.slideshow?.currentId)
        onMain { onSlideshowPhotoShown(shown) }
        clockMs += ms
        onMain { slideshowTick() }
    }

    @Test
    fun `a slideshow plays the view from the current photo, in full window`() {
        open(photo("a"), photo("b"), photo("c"))
        onMain { setViewMode(DamViewMode.GRID) }
        val ids = photoIds()
        onMain { selectPhotoIndex(1) }
        waitUntil { state.selectedPhotoId == ids[1] }

        onMain { startSlideshow() }

        val show = checkNotNull(state.slideshow)
        assertEquals(ids, show.playlist)
        assertEquals(ids[1], show.currentId)
        assertTrue(state.fullWindow)
        assertEquals(DamViewMode.GRID, state.viewMode)
    }

    @Test
    fun `two or more selected photos are the playlist, and the selection is spent`() {
        open(photo("a"), photo("b"), photo("c"))
        val ids = photoIds()
        select(ids[0], ids[2])

        onMain { startSlideshow() }

        assertEquals(listOf(ids[0], ids[2]), state.slideshow?.playlist)
        assertTrue(state.selectedPhotoIds.isEmpty())
        assertEquals(ids[0], state.selectedPhotoId)
    }

    @Test
    fun `nothing to play gives a message and no show`() {
        onMain { startSlideshow() }
        assertEquals(null, state.slideshow)
        assertFalse(state.fullWindow)

        open(photo("a"))
        onMain { updateFilter { it.copy(searchQuery = "no such file") } }
        waitUntil { state.filteredPhotos.isEmpty() }
        onMain { startSlideshow() }
        waitUntil { state.snackbarMessage == "No photos to play" }
        assertEquals(null, state.slideshow)
    }

    @Test
    fun `the clock moves on after the interval counted from when the photo is shown`() {
        open(photo("a"), photo("b"), photo("c"))
        val ids = photoIds()
        onMain { startSlideshow() }
        assertEquals(null, onMain { slideshowMsUntilAdvance() })

        clockMs = 20_000 // a slow load
        onMain { slideshowTick() }
        assertEquals(ids[0], state.slideshow?.currentId)

        onMain { onSlideshowPhotoShown(ids[0]) }
        assertEquals(5000L, onMain { slideshowMsUntilAdvance() })
        clockMs += 4999
        onMain { slideshowTick() }
        assertEquals(ids[0], state.slideshow?.currentId)
        clockMs += 1
        onMain { slideshowTick() }

        assertEquals(ids[1], state.slideshow?.currentId)
        assertEquals(ids[1], state.selectedPhotoId)
    }

    @Test
    fun `pausing holds the show, and resuming gives the photo a full interval`() {
        open(photo("a"), photo("b"))
        val ids = photoIds()
        onMain { startSlideshow() }
        onMain { onSlideshowPhotoShown(ids[0]) }

        onMain { toggleSlideshowPause() }
        assertTrue(checkNotNull(state.slideshow).isPaused)
        clockMs += 60_000
        onMain { slideshowTick() }
        assertEquals(ids[0], state.slideshow?.currentId)

        onMain { toggleSlideshowPause() }
        assertEquals(5000L, onMain { slideshowMsUntilAdvance() })
    }

    @Test
    fun `leaving the app and zooming both pause`() {
        open(photo("a"), photo("b"))
        onMain { startSlideshow() }
        onMain { onAppStop() }
        assertTrue(checkNotNull(state.slideshow).isPaused)

        onMain { toggleSlideshowPause() }
        assertFalse(checkNotNull(state.slideshow).isPaused)
        onMain { toggleZoom() }
        assertTrue(checkNotNull(state.slideshow).isPaused)
    }

    @Test
    fun `culling edits the shown photo and never advances`() {
        open(photo("a"), photo("b"))
        val ids = photoIds()
        onMain { setAutoAdvance(true) }
        waitUntil { state.autoAdvance }
        onMain { startSlideshow() }
        onMain { onSlideshowPhotoShown(ids[0]) }

        onMain { apply(CullEdit.Rating(3), advance = true) }
        waitUntil { rating(ids[0]) == 3 }

        assertEquals(ids[0], state.slideshow?.currentId)
        assertEquals(ids[0], state.selectedPhotoId)
        showFor(5000)
        assertEquals(ids[1], state.slideshow?.currentId)
        onMain { setAutoAdvance(false) }
    }

    @Test
    fun `arrows step and restart the interval, and jumping is ignored`() {
        open(photo("a"), photo("b"), photo("c"))
        val ids = photoIds()
        onMain { startSlideshow() }
        onMain { onSlideshowPhotoShown(ids[0]) }
        clockMs += 3000

        onMain { nextPhoto() }
        assertEquals(ids[1], state.slideshow?.currentId)
        assertEquals(null, onMain { slideshowMsUntilAdvance() })
        onMain { previousPhoto() }
        assertEquals(ids[0], state.slideshow?.currentId)

        onMain { lastPhoto() }
        assertEquals(ids[0], state.slideshow?.currentId)
    }

    @Test
    fun `ending the show returns to the view it started from with the last photo selected`() {
        open(photo("a"), photo("b"), photo("c"))
        val ids = photoIds()
        onMain { setViewMode(DamViewMode.LOUPE) }
        onMain { startSlideshow() }
        onMain { onSlideshowPhotoShown(ids[0]) }
        showFor(5000)
        assertEquals(ids[1], state.slideshow?.currentId)

        onMain { stepBack() }

        assertEquals(null, state.slideshow)
        assertFalse(state.fullWindow)
        assertEquals(DamViewMode.LOUPE, state.viewMode)
        assertEquals(ids[1], state.selectedPhotoId)
    }

    @Test
    fun `with loop off the show stops paused on the last photo`() {
        open(photo("a"), photo("b"))
        val ids = photoIds()
        // Saved settings reach the state a moment later, and outlive this test.
        onMain { setSlideshowLoop(false) }
        try {
            waitUntil { !state.viewing.slideshowLoop }
            onMain { startSlideshow() }
            onMain { onSlideshowPhotoShown(ids[0]) }
            showFor(5000)
            onMain { onSlideshowPhotoShown(ids[1]) }
            clockMs += 5000
            onMain { slideshowTick() }

            val show = checkNotNull(state.slideshow)
            assertTrue(show.hasEnded)
            assertTrue(show.isPaused)
            assertEquals(ids[1], show.currentId)
        } finally {
            onMain { setSlideshowLoop(true) }
            waitUntil { state.viewing.slideshowLoop }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val BASE_DATE = 1_700_000_000_000L
    }
}
