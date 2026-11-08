package dev.shehran.rawcull.ui.library

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FullscreenExit
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
    val photo = uiState.currentPhoto
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
    val photos = uiState.filteredPhotos
    val index = uiState.selectedIndex
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
        ZoomableLoupeView(
            photo = photo,
            onNext = viewModel::nextPhoto,
            onPrevious = viewModel::previousPhoto,
            onSyncChipClick = viewModel::showSyncDetails,
            zoomRequest = uiState.zoomRequest,
            autoAdvance = uiState.advancesAfterEdits,
            swipeToFlag = uiState.swipeToFlag,
            onSwipe = viewModel::onSwipe,
            onTap = { controlsVisible = !controlsVisible },
            modifier = Modifier.fillMaxSize(),
        )

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
) {
    val photo = uiState.currentPhoto
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
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            IconButton(onClick = onPrevious, modifier = Modifier.background(ScrimStrong).testTag("full_window_previous")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous photo", tint = Color.White)
            }
            IconButton(onClick = onNext, modifier = Modifier.background(ScrimStrong).testTag("full_window_next")) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next photo", tint = Color.White)
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
