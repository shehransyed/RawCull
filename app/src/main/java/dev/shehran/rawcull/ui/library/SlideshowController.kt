package dev.shehran.rawcull.ui.library

import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.repository.SettingsRepository
import dev.shehran.rawcull.library.Slideshow
import dev.shehran.rawcull.library.SlideshowClock
import dev.shehran.rawcull.library.SlideshowPlaylist
import kotlinx.coroutines.launch

/**
 * Runs the slideshow (R6 D4-D6) on top of the pure [Slideshow]: takes the playlist snapshot, keeps the
 * selection on the shown photo, and publishes [SlideshowUi]. The clock is driven from the UI, which
 * waits [msUntilAdvance] and then calls [tick].
 */
internal class SlideshowController(
    private val store: LibraryStore,
    private val view: ViewController,
    private val selection: SelectionController,
    private val settings: SettingsRepository,
    private val clock: SlideshowClock,
) {
    private var show: Slideshow? = null
    private var version = 0

    private var knownPhotos: List<PhotoAsset> = emptyList()
    private var knownIds: Set<String> = emptySet()

    private fun isAvailable(id: String): Boolean {
        val photos = store.value.photos
        if (photos !== knownPhotos) {
            knownPhotos = photos
            knownIds = photos.mapTo(HashSet(photos.size)) { it.id }
        }
        return id in knownIds
    }

    /** Plays the selection (two or more photos) or the current view, from the current photo. */
    fun start() {
        val state = store.value
        if (show != null || state.activeLocation == null) return
        val playlist = SlideshowPlaylist.snapshot(
            viewIds = state.filteredPhotos.map { it.id },
            selectedIds = state.selectedPhotoIds,
            currentId = state.selectedPhotoId,
        )
        if (playlist == null) {
            store.showMessage("No photos to play")
            return
        }
        val prefs = state.viewing
        val next = Slideshow(playlist, clock, ::isAvailable, prefs.slideshowIntervalS * MS_PER_S, prefs.slideshowLoop)
        if (next.currentId == null) {
            store.showMessage("No photos to play")
            return
        }
        show = next
        // Culling then edits the shown photo, not a leftover multi-selection.
        selection.clearSelection()
        publish(enterFullWindow = true)
        syncSelection()
    }

    /** Ends the show, back in the view it started from, with the last shown photo selected. */
    fun end() {
        if (show == null) return
        syncSelection()
        show = null
        store.update { it.copy(slideshow = null, fullWindow = false) }
    }

    /** The shown photo's fit preview (or its "No preview" state) is on screen: its time starts. */
    fun photoShown(id: String) {
        val current = show ?: return
        if (current.currentId != id) return
        current.photoShown()
        publish()
    }

    fun msUntilAdvance(): Long? = show?.msUntilAdvance()

    /** Moves on if the time is up. */
    fun tick() {
        val current = show ?: return
        val moved = current.refresh() or current.tick()
        if (moved) syncSelection()
        publish()
    }

    fun next() = step { it.next() }

    fun previous() = step { it.previous() }

    private fun step(move: (Slideshow) -> Boolean) {
        val current = show ?: return
        if (move(current)) syncSelection()
        publish()
    }

    fun pause() {
        show?.pause()
        publish()
    }

    fun togglePause() {
        val current = show ?: return
        current.togglePause()
        publish(refit = !current.isPaused)
    }

    fun setInterval(seconds: Int) {
        show?.setInterval(seconds * MS_PER_S)
        store.scope.launch { settings.updateViewingPrefs { it.copy(slideshowIntervalS = seconds) } }
        publish()
    }

    fun setLoop(on: Boolean) {
        show?.setLoop(on)
        store.scope.launch { settings.updateViewingPrefs { it.copy(slideshowLoop = on) } }
        publish()
    }

    /** The shown photo is also the selection, so edits, the inspector and the filmstrip follow it. */
    private fun syncSelection() {
        val id = show?.currentId ?: return
        if (id in view.visibleIdSet) view.selectPhoto(id)
    }

    private fun publish(enterFullWindow: Boolean = false, refit: Boolean = false) {
        val current = show ?: return
        version++
        store.update {
            val previous = it.slideshow
            it.copy(
                fullWindow = it.fullWindow || enterFullWindow,
                slideshow = SlideshowUi(
                    playlist = previous?.playlist ?: current.playlistIds,
                    currentId = current.currentId,
                    position = current.currentIndex,
                    isPaused = current.isPaused,
                    hasEnded = current.hasEnded,
                    refit = if (refit) (previous?.refit ?: 0) + 1 else previous?.refit ?: 0,
                    version = version,
                ),
            )
        }
    }

    private companion object {
        const val MS_PER_S = 1000L
    }
}
