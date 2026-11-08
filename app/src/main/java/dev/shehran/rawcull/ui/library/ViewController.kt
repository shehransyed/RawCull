package dev.shehran.rawcull.ui.library

import dev.shehran.rawcull.data.model.FilterOptions
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.repository.SettingsRepository
import dev.shehran.rawcull.data.scan.ScanCoordinator
import dev.shehran.rawcull.library.LibraryFilter
import dev.shehran.rawcull.library.LibraryView
import dev.shehran.rawcull.ui.SelectionLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Turns the scope's rows into what's on screen: filters, sort, sections and counts, and the
 * current photo carried over by id. Also the visible order the rest of the Library steps through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ViewController(
    private val store: LibraryStore,
    private val settings: SettingsRepository,
    private val scanCoordinator: ScanCoordinator,
    private val histogram: HistogramLoader,
) {
    /** The scope's rows, unfiltered. */
    val rows = MutableStateFlow<List<PhotoAsset>>(emptyList())
    val filter = MutableStateFlow(FilterOptions())
    val filtersEnabled = MutableStateFlow(true)

    @Volatile
    var visibleIds: List<String> = emptyList()
        private set
    var visibleIdSet: Set<String> = emptySet()
        private set

    /** The photo to select once it's visible: set when a session is restored. */
    var pendingSelectionId: String? = null

    private var saveFilterJob: Job? = null
    private var saveSelectionJob: Job? = null

    /** The filter as it applies now: Ctrl+L turns it off without forgetting it. */
    val activeFilter: FilterOptions get() = if (filtersEnabled.value) filter.value else filter.value.cleared()

    fun start() {
        store.scope.launch {
            // mapLatest drops a run the next change has made pointless.
            combine(rows, filter, filtersEnabled, ::FilterRun)
                .mapLatest { run -> withContext(Dispatchers.Default) { computeView(run) } }
                .collect(::publish)
        }
    }

    /** A different Location: its own saved filters, and nothing of the old one visible. */
    suspend fun switchTo(location: LibraryLocation) {
        saveFilterJob?.cancel()
        filter.value = settings.filters(location.id)
        filtersEnabled.value = settings.filtersEnabled(location.id)
        rows.value = emptyList()
        visibleIds = emptyList()
        visibleIdSet = emptySet()
    }

    private class FilterRun(val rows: List<PhotoAsset>, val filter: FilterOptions, val enabled: Boolean)

    private class ComputedView(
        val source: List<PhotoAsset>,
        val view: LibraryView,
        val ids: List<String>,
        val idSet: Set<String>,
    )

    private fun computeView(run: FilterRun): ComputedView {
        val started = System.nanoTime()
        val active = if (run.enabled) run.filter else run.filter.cleared()
        val spansFolders = run.rows.asSequence().map { it.relativeDir }.distinct().take(2).count() > 1
        val view = LibraryFilter.apply(run.rows, active, run.filter.sortOrder, spansFolders)
        val ids = view.rows.map { it.id }
        val idSet = ids.toHashSet()
        if (ids != visibleIds) scanCoordinator.setVisibleOrder(ids) // O9
        store.log("filter rows=${run.rows.size} visible=${ids.size} ms=${(System.nanoTime() - started) / NANOS_PER_MILLI}")
        return ComputedView(run.rows, view, ids, idSet)
    }

    /** Publishes a new visible list, carrying the selection over by id. */
    private fun publish(computed: ComputedView) {
        val current = store.value
        val nextIds = computed.ids
        val restored = pendingSelectionId?.takeIf { it in computed.idSet }
        if (nextIds.isNotEmpty()) pendingSelectionId = null
        val selectedId = restored ?: current.selectedPhotoId?.takeIf { it in computed.idSet }
            ?: SelectionLogic.reconcile(visibleIds, nextIds, current.selectedPhotoId)
        visibleIds = nextIds
        visibleIdSet = computed.idSet

        store.state.value = current.copy(
            photos = computed.source,
            filteredPhotos = computed.view.rows,
            sections = computed.view.sections,
            counts = computed.view.counts,
            selectedPhotoId = selectedId,
            comparePhotoId = SelectionLogic.reconcileCompare(nextIds, current.comparePhotoId, selectedId),
            // What the filter hides can't stay selected: an edit shouldn't land on photos out of sight.
            selectedPhotoIds = current.selectedPhotoIds.takeIf { computed.idSet.containsAll(it) }
                ?: current.selectedPhotoIds.filterTo(HashSet()) { it in computed.idSet },
        )
        if (selectedId != current.selectedPhotoId) onSelectionChanged()
        histogram.refresh()
    }

    private fun onSelectionChanged() {
        val id = store.value.selectedPhotoId
        id?.let(scanCoordinator::prioritize)
        // Culling moves through photos quickly, and only where it stops matters on the next launch.
        saveSelectionJob?.cancel()
        saveSelectionJob = store.scope.launch {
            delay(SAVE_SELECTION_DELAY_MS)
            settings.setSelectedAsset(id)
        }
    }

    fun selectPhoto(id: String) {
        store.update { it.copy(selectedPhotoId = id) }
        onSelectionChanged()
        histogram.refresh()
    }

    fun updateFilter(filterModifier: (FilterOptions) -> FilterOptions) {
        val next = filterModifier(filter.value)
        filter.value = next
        store.update { it.copy(filterOptions = next) }
        val location = store.value.activeLocation ?: return
        // Typing in the search field shouldn't mean a disk write per letter.
        saveFilterJob?.cancel()
        saveFilterJob = store.scope.launch {
            delay(SAVE_FILTER_DELAY_MS)
            settings.setFilters(location.id, next)
        }
    }

    /** Ctrl+L: everything off and back on, without forgetting what was set. */
    fun toggleFilters() {
        val enabled = !filtersEnabled.value
        filtersEnabled.value = enabled
        store.update { it.copy(filtersEnabled = enabled) }
        val location = store.value.activeLocation ?: return
        store.scope.launch { settings.setFiltersEnabled(location.id, enabled) }
    }

    private companion object {
        const val SAVE_SELECTION_DELAY_MS = 500L
        const val SAVE_FILTER_DELAY_MS = 400L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
