package dev.shehran.rawcull.ui.library

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.shehran.rawcull.data.model.PhotoAsset
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.preview.PreviewKeys
import dev.shehran.rawcull.ui.components.LoupePrefetch
import dev.shehran.rawcull.ui.components.ZoomableLoupeView
import dev.shehran.rawcull.ui.shell.LocalWindowLayout
import dev.shehran.rawcull.ui.shell.WidthClass
import dev.shehran.rawcull.ui.theme.FullWindowBackground
import dev.shehran.rawcull.ui.theme.ScrimStrong
import kotlinx.coroutines.delay

/** How long the controls stay up without input (R6 D3). */
private const val CONTROLS_HIDE_MS = 3000L

/** The slideshow crossfade (R6 D5). */
private const val CROSSFADE_MS = 300

/**
 * R6 D2/D3: the current photo fills the window, on black, with the system bars hidden. It is the loupe's own
 * view, so zoom, swipes and culling keys behave the same; a tap shows or hides a small controls overlay.
 * Keys are handled by the Library screen underneath, which keeps the keyboard focus.
 */
@Composable
fun FullWindow(
    uiState: LibraryUiState,
    viewModel: LibraryViewModel,
    modifier: Modifier = Modifier,
) {
    HideSystemBars()
    val photo = uiState.shownPhoto
    val show = uiState.slideshow
    KeepScreenOn(on = show != null && !show.isPaused && !show.hasEnded)
    val layout = LocalWindowLayout.current
    var controlsVisible by rememberSaveable { mutableStateOf(false) }
    var inputCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(controlsVisible, inputCount, photo?.id) {
        if (controlsVisible) {
            delay(CONTROLS_HIDE_MS)
            controlsVisible = false
        }
    }

    var size by remember { mutableStateOf(IntSize.Zero) }
    // A show prefetches along its own playlist, which can be a selection rather than the view.
    val byId = remember(uiState.photos) { uiState.photos.associateBy { it.id } }
    val photos = if (show != null) {
        remember(show.playlist, byId) { show.playlist.mapNotNull(byId::get) }
    } else {
        uiState.filteredPhotos
    }
    val index = if (show != null) photos.indexOfFirst { it.id == show.currentId } else uiState.selectedIndex
    // Each photo's time runs from when it is on screen; the UI waits and the controller moves on.
    LaunchedEffect(show?.version) {
        if (show != null) {
            val wait = viewModel.slideshowMsUntilAdvance() ?: return@LaunchedEffect
            delay(wait)
            viewModel.slideshowTick()
        }
    }
    LoupePrefetch(
        current = index,
        count = photos.size,
        compact = layout.width == WidthClass.COMPACT,
        widthPx = size.width,
        heightPx = size.height,
    ) { PreviewKeys.source(photos[it]) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FullWindowBackground)
            // Sees every touch without taking it, so the controls' timer restarts on any input.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        inputCount++
                    }
                }
            }
            .onSizeChanged { size = it }
            .testTag("full_window"),
    ) {
        val loupe = @Composable { shown: PhotoAsset? ->
            ZoomableLoupeView(
                photo = shown,
                onNext = viewModel::nextPhoto,
                onPrevious = viewModel::previousPhoto,
                onSyncChipClick = viewModel::showSyncDetails,
                zoomRequest = uiState.zoomRequest,
                autoAdvance = uiState.advancesAfterEdits,
                swipeToFlag = uiState.swipeToFlag,
                onSwipe = viewModel::onSwipe,
                onTap = { controlsVisible = !controlsVisible },
                onShown = viewModel::onSlideshowPhotoShown,
                onUserZoom = viewModel::pauseSlideshow,
                refitRequest = show?.refit ?: 0,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (show != null && uiState.viewing.slideshowCrossfade) {
            // Both photos are composed for the length of the fade; the decoded bitmaps are hardware ones.
            Crossfade(targetState = photo?.id, animationSpec = tween(CROSSFADE_MS), label = "slideshow") { id ->
                loupe(id?.let(byId::get))
            }
        } else {
            loupe(photo)
        }

        if (show?.hasEnded == true) {
            Text(
                text = "End of slideshow",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 72.dp)
                    .background(ScrimStrong)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("slideshow_end"),
            )
        }

        EditBadge(
            text = rememberEditBadgeText(photo),
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 12.dp),
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            FullWindowControls(
                uiState = uiState,
                onPrevious = viewModel::previousPhoto,
                onNext = viewModel::nextPhoto,
                onExit = viewModel::exitFullWindow,
                onPlay = viewModel::startSlideshow,
                slideshow = SlideshowActions(
                    onTogglePause = viewModel::toggleSlideshowPause,
                    onSetInterval = viewModel::setSlideshowInterval,
                    onSetLoop = viewModel::setSlideshowLoop,
                ),
            )
        }
    }
}

@Composable
private fun FullWindowControls(
    uiState: LibraryUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
    onPlay: () -> Unit,
    slideshow: SlideshowActions,
) {
    val photo = uiState.shownPhoto
    Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("full_window_controls")) {
        Row(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().background(ScrimStrong).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onExit, modifier = Modifier.testTag("full_window_exit_button")) {
                Icon(Icons.Default.FullscreenExit, contentDescription = "Exit full window", tint = Color.White)
            }
            Text(
                text = photo?.fileName.orEmpty(),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (photo != null) {
                Text(text = summary(photo.cullStatus, photo.rating, photo.colorLabel), color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val show = uiState.slideshow
            if (show != null) SlideshowBar(show, uiState.viewing, slideshow)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                IconButton(onClick = onPrevious, modifier = Modifier.background(ScrimStrong).testTag("full_window_previous")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous photo", tint = Color.White)
                }
                if (show == null) {
                    IconButton(onClick = onPlay, modifier = Modifier.background(ScrimStrong).testTag("full_window_play")) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Start slideshow", tint = Color.White)
                    }
                }
                IconButton(onClick = onNext, modifier = Modifier.background(ScrimStrong).testTag("full_window_next")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next photo", tint = Color.White)
                }
            }
        }
    }
}

private fun summary(status: CullStatus, rating: Int, label: ColorLabel): String = buildList {
    when (status) {
        CullStatus.PICK -> add("Pick")
        CullStatus.REJECT -> add("Reject")
        CullStatus.UNFLAGGED -> Unit
    }
    if (rating > 0) add("$rating ★")
    if (label != ColorLabel.NONE) add(label.name.lowercase().replaceFirstChar { it.uppercase() })
}.joinToString(" · ")

/** The screen stays on while a show plays, and only then. */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/** Hides the status and navigation bars while on screen; a swipe from the edge shows them briefly. They return on exit. */
@Composable
private fun HideSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
