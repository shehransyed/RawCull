package dev.shehran.rawcull.ui.library

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import dev.shehran.rawcull.data.preview.PreviewKeys
import dev.shehran.rawcull.ui.shell.WidthClass
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.library.LibraryKeyAction
import dev.shehran.rawcull.library.LibraryScope
import dev.shehran.rawcull.ui.components.CompareLoupeView
import dev.shehran.rawcull.ui.components.CullingActionBar
import dev.shehran.rawcull.ui.components.CullingHistory
import dev.shehran.rawcull.ui.components.CullingSideBar
import dev.shehran.rawcull.ui.components.FilmstripBar
import dev.shehran.rawcull.ui.components.GridViewGallery
import dev.shehran.rawcull.ui.components.LoupePrefetch
import dev.shehran.rawcull.ui.components.StorageChangeEffect
import dev.shehran.rawcull.ui.components.SyncDetailsDialog
import dev.shehran.rawcull.ui.components.TagManagerDialog
import dev.shehran.rawcull.ui.components.ZoomableLoupeView
import dev.shehran.rawcull.ui.shell.LocalWindowLayout
import dev.shehran.rawcull.ui.shell.WindowLayout
import dev.shehran.rawcull.ui.theme.Dimens
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider
import dev.shehran.rawcull.library.PeakingKernel
import dev.shehran.rawcull.ui.components.LocalPeaking
import dev.shehran.rawcull.ui.components.PeakingStyle
import dev.shehran.rawcull.ui.theme.RawCullTheme
import dev.shehran.rawcull.ui.components.WarmupBar

/**
 * The whole archive: folder pane, content and inspector, as far as the window has room for them.
 *
 * @param onImmersiveChange true while the photo should own the screen, so the shell hides its navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onImportFromCard: () -> Unit,
    onImmersiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val layout = LocalWindowLayout.current
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutines = rememberCoroutineScope()

    // Null means "whatever suits this window": rotating shouldn't override an explicit choice.
    var folderPaneChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var inspectorChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var showInspectorSheet by rememberSaveable { mutableStateOf(false) }
    var relinkTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var chromeHidden by rememberSaveable { mutableStateOf(false) }
    var filterBarChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
    var focusSearch by remember { mutableStateOf(false) }
    var textFieldFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val folderPaneOpen = layout.showsFolderPane && (folderPaneChoice ?: true)
    val inspectorOpen = layout.showsSideInspector && (inspectorChoice ?: layout.opensInspectorByDefault)

    val addLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::addLocation)
    }
    val relinkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val target = relinkTargetId
        relinkTargetId = null
        if (uri != null && target != null) viewModel.relinkLocation(target, uri)
    }
    val relink = { id: Long ->
        relinkTargetId = id
        relinkLauncher.launch(null)
    }

    // Saved, so the result still lands right if the activity is recreated behind the system dialog.
    var trashRestoring by rememberSaveable { mutableStateOf(false) }
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onTrashResult(approved = result.resultCode == Activity.RESULT_OK, restoring = trashRestoring)
    }
    LaunchedEffect(uiState.trashRequest) {
        uiState.trashRequest?.let { request ->
            trashRestoring = request.restoring
            viewModel.onTrashRequestLaunched()
            trashLauncher.launch(IntentSenderRequest.Builder(request.sender).build())
        }
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshSources()
        onPauseOrDispose { }
    }
    LifecycleStartEffect(Unit) {
        viewModel.onAppStart()
        onStopOrDispose { viewModel.onAppStop() }
    }
    StorageChangeEffect(onChanged = viewModel::refreshSources)
    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { message ->
            val action = uiState.snackbarAction
            // Clearing first would change this effect's key and cancel the snackbar.
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = action?.label,
            )
            viewModel.onSnackbarShown()
            if (result == SnackbarResult.ActionPerformed) {
                when (action) {
                    SnackbarAction.UNDO -> viewModel.undo()
                    SnackbarAction.RESTORE -> viewModel.restoreTrashed()
                    null -> Unit
                }
            }
        }
    }

    val keyFocus = remember { FocusRequester() }
    val viewingPhoto = uiState.activeLocation != null && uiState.viewMode != DamViewMode.GRID
    val immersive = viewingPhoto && (layout.usesBottomBar || layout.isHeightCompact)
    // R6 D2: full window hides the shell's navigation at every width. It needs a photo to show.
    val fullWindow = uiState.fullWindow && uiState.activeLocation != null && uiState.shownPhoto != null
    LaunchedEffect(uiState.fullWindow, uiState.isRestoring, uiState.shownPhoto == null) {
        if (uiState.fullWindow && !uiState.isRestoring && uiState.shownPhoto == null) viewModel.exitFullWindow()
    }
    LaunchedEffect(fullWindow) { if (fullWindow) keyFocus.requestFocus() }
    LaunchedEffect(immersive || fullWindow) { onImmersiveChange(immersive || fullWindow) }
    DisposableEffect(Unit) { onDispose { onImmersiveChange(false) } }

    // Innermost state first: each back press undoes one thing.
    BackHandler(enabled = viewingPhoto && !drawerState.isOpen) { viewModel.setViewMode(DamViewMode.GRID) }
    BackHandler(enabled = uiState.isSelecting && !drawerState.isOpen) { viewModel.clearSelection() }
    BackHandler(enabled = drawerState.isOpen) { coroutines.launch { drawerState.close() } }
    BackHandler(enabled = fullWindow && !drawerState.isOpen) { viewModel.exitFullWindow() }

    val folderActions = FolderPaneActions(
        onSelectLocation = {
            viewModel.selectLocation(it)
            coroutines.launch { drawerState.close() }
        },
        onRelinkLocation = relink,
        onAddLocation = { addLauncher.launch(null) },
        onSelectScope = {
            viewModel.setScope(it)
            coroutines.launch { drawerState.close() }
        },
        onShowLatestImport = {
            viewModel.showLatestImport()
            coroutines.launch { drawerState.close() }
        },
        onToggleFolder = viewModel::toggleFolder,
    )

    // Dialogs and sheets are windows of their own, so focus has to be taken back when they close.
    val filterSheetOpen = showFilterSheet && !layout.showsFilterBar
    val overlayOpen = showHelp || showInspectorSheet || filterSheetOpen || uiState.deletionPrompt != null ||
        uiState.showTagDialog || uiState.syncDetailsPhoto != null
    LaunchedEffect(overlayOpen) {
        if (!overlayOpen) {
            textFieldFocused = false
            keyFocus.requestFocus()
        }
    }

    val filterBarVisible = filterBarChoice ?: !layout.isHeightCompact
    val keyActions = LibraryKeyHandlers(
        onTogglePanes = {
            val open = folderPaneOpen || inspectorOpen
            folderPaneChoice = !open
            inspectorChoice = !open
        },
        onToggleChrome = { chromeHidden = !chromeHidden },
        onToggleFilterBar = { filterBarChoice = !filterBarVisible },
        onFocusSearch = {
            if (layout.showsFilterBar) filterBarChoice = true else showFilterSheet = true
            focusSearch = true
        },
        onShowHelp = { showHelp = true },
        onEscape = {
            if (textFieldFocused) {
                focusManager.clearFocus()
                keyFocus.requestFocus()
            } else {
                viewModel.stepBack()
            }
        },
    )

    val filterActions = FilterActions(
        onUpdate = viewModel::updateFilter,
        onClear = viewModel::clearFilters,
        onToggleEnabled = viewModel::toggleFilters,
        onDeleteRejects = { viewModel.requestDeleteRejects() },
        onSearchFocusChanged = { textFieldFocused = it },
        onSearchFocused = { focusSearch = false },
    )

    // R6 D7: one switch for every loupe on screen (loupe, compare, full window, slideshow).
    val peakingColor = RawCullTheme.colors.peaking(uiState.viewing.peakingColor)
    val peaking = if (uiState.peaking) PeakingStyle(peakingColor, PeakingKernel.threshold(uiState.viewing.peakingSensitivity)) else null
    CompositionLocalProvider(LocalPeaking provides peaking) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .focusRequester(keyFocus)
                .focusable()
                // Only this screen listens, so typing anywhere else in the app is left alone.
                .onPreviewKeyEvent { event ->
                    viewModel.onKeyboardModifiers(event.isCtrlPressed || event.isMetaPressed, event.isShiftPressed)
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val capsLock = event.isCapsLockOn
                    viewModel.onCapsLock(capsLock)
                    val action = event.toLibraryAction(capsLock, textFieldFocused, slideshowActive = uiState.slideshow != null) ?: return@onPreviewKeyEvent false
                    onLibraryKey(action, viewModel, keyActions)
                    true
                }
                .testTag("library_screen"),
        ) {
            when {
                // Avoids flashing the empty state while the last Location is being checked.
                uiState.isRestoring -> Unit
                uiState.activeLocation == null -> LibraryEmptyState(
                    onAddLocation = { addLauncher.launch(null) },
                    onImportFromCard = onImportFromCard,
                    onTryDemo = viewModel::openDemoLibrary,
                )
                else -> ModalNavigationDrawer(
                    // A closed sheet waits just left of this area, which is where the rail is.
                    modifier = Modifier.clipToBounds(),
                    drawerState = drawerState,
                    gesturesEnabled = !layout.showsFolderPane && (drawerState.isOpen || !viewingPhoto),
                    drawerContent = {
                        if (!layout.showsFolderPane) {
                            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
                                FolderPane(uiState, folderActions, Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
                            }
                        }
                    },
                ) {
                    LibraryContent(
                        uiState = uiState,
                        layout = layout,
                        viewModel = viewModel,
                        immersive = immersive,
                        folderPaneOpen = folderPaneOpen,
                        inspectorOpen = inspectorOpen,
                        chromeHidden = chromeHidden,
                        filterBarVisible = filterBarVisible,
                        onToggleFilterBar = keyActions.onToggleFilterBar,
                        onShowShortcuts = keyActions.onShowHelp,
                        filterActions = filterActions,
                        focusSearch = focusSearch,
                        onOpenFilterSheet = { showFilterSheet = true },
                        folderActions = folderActions,
                        onToggleFolders = {
                            if (layout.showsFolderPane) {
                                folderPaneChoice = !folderPaneOpen
                            } else {
                                coroutines.launch { drawerState.open() }
                            }
                        },
                        onToggleInspector = { inspectorChoice = !inspectorOpen },
                        onOpenInspectorSheet = { showInspectorSheet = true },
                        onRelink = { uiState.activeLocation?.let { relink(it.id) } },
                    )
                }
            }

            if (fullWindow) {
                FullWindow(uiState = uiState, viewModel = viewModel)
            }

            SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    if (showInspectorSheet) {
        ModalBottomSheet(
            onDismissRequest = { showInspectorSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            InspectorPanel(
                uiState,
                culling = null,
                onEditTags = viewModel::openTagDialog,
                modifier = Modifier.dismissOnEscape { showInspectorSheet = false },
            )
        }
    }
    if (showHelp) ShortcutHelpSheet(onDismiss = { showHelp = false })
    uiState.deletionPrompt?.let { plan ->
        DeleteRejectsDialog(plan, onConfirm = viewModel::confirmDeletion, onDismiss = viewModel::cancelDeletion)
    }
    if (filterSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            FilterSheetContent(
                uiState = uiState,
                actions = filterActions,
                focusSearch = focusSearch,
                modifier = Modifier.dismissOnEscape(takeFocus = !focusSearch) { showFilterSheet = false },
            )
        }
    }
    if (uiState.showTagDialog) {
        TagManagerDialog(
            currentTags = uiState.tagDialogTags,
            onAddTag = viewModel::addTagToCurrent,
            onRemoveTag = viewModel::removeTagFromCurrent,
            onDismiss = viewModel::dismissTagDialog,
        )
    }
    uiState.syncDetailsPhoto?.let { photo ->
        SyncDetailsDialog(
            photo = photo,
            onRetry = { viewModel.retrySidecar(photo.id) },
            onReplaceSidecar = { viewModel.replaceSidecar(photo.id) },
            onDismiss = viewModel::dismissSyncDetails,
        )
    }
}

@Composable
private fun LibraryContent(
    uiState: LibraryUiState,
    layout: WindowLayout,
    viewModel: LibraryViewModel,
    immersive: Boolean,
    folderPaneOpen: Boolean,
    inspectorOpen: Boolean,
    chromeHidden: Boolean,
    filterBarVisible: Boolean,
    onToggleFilterBar: () -> Unit,
    onShowShortcuts: () -> Unit,
    filterActions: FilterActions,
    focusSearch: Boolean,
    onOpenFilterSheet: () -> Unit,
    folderActions: FolderPaneActions,
    onToggleFolders: () -> Unit,
    onToggleInspector: () -> Unit,
    onOpenInspectorSheet: () -> Unit,
    onRelink: () -> Unit,
) {
    val culling = CullingActions(
        onTogglePick = viewModel::togglePick,
        onToggleReject = viewModel::toggleReject,
        onSetRating = viewModel::setRating,
        onSetColorLabel = viewModel::setColorLabel,
        history = CullingHistory(
            canUndo = uiState.canUndo,
            canRedo = uiState.canRedo,
            autoAdvance = uiState.advancesAfterEdits,
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onToggleAutoAdvance = { viewModel.setAutoAdvance(!uiState.autoAdvance) },
        ),
    )
    // On a phone on its side the photo gets the whole screen, so the bar moves out of its way.
    val fullScreenPhoto = immersive && layout.isHeightCompact
    val showPanes = !fullScreenPhoto && !chromeHidden
    val insets = if (fullScreenPhoto) {
        WindowInsets.safeDrawing
    } else {
        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    }

    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(insets)) {
        if (showPanes) {
            LibraryTopBar(
                selectionRow = if (uiState.isSelecting) {
                    {
                        SelectionTopBar(
                            count = uiState.selectedPhotoIds.size,
                            onClose = viewModel::clearSelection,
                            onSelectAll = viewModel::selectAll,
                            onEdit = { viewModel.apply(it) },
                            onEditTags = viewModel::openTagDialog,
                            onDeleteRejects = { viewModel.requestDeleteRejects(selectionOnly = true) },
                        )
                    }
                } else {
                    null
                },
                uiState = uiState,
                filterBarVisible = filterBarVisible,
                filterActions = filterActions,
                focusSearch = focusSearch,
                onOpenFilterSheet = onOpenFilterSheet,
                actions = LibraryTopBarActions(
                    onToggleFolders = onToggleFolders,
                    onToggleInspector = onToggleInspector,
                    onSetViewMode = viewModel::setViewMode,
                    onToggleFilterBar = onToggleFilterBar,
                    onToggleSyncErrors = viewModel::toggleSyncErrorFilter,
                    onShowShortcuts = onShowShortcuts,
                    onFullWindow = viewModel::enterFullWindow,
                    onSlideshow = viewModel::startSlideshow,
                    onDeleteRejects = { viewModel.requestDeleteRejects() },
                    onRescan = viewModel::rescan,
                    onRetryLocation = viewModel::retryLocation,
                    onRelinkLocation = onRelink,
                ),
            )
            WarmupBar()
        }

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (folderPaneOpen && showPanes) {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.width(Dimens.FolderPaneWidth).fillMaxHeight()) {
                    FolderPane(uiState, folderActions)
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                LibraryMainView(
                    uiState = uiState,
                    layout = layout,
                    viewModel = viewModel,
                    culling = culling,
                    cullingInInspector = inspectorOpen && !layout.cullingBarAtSide,
                    fullScreenPhoto = fullScreenPhoto,
                    chromeHidden = chromeHidden,
                    onOpenInspectorSheet = onOpenInspectorSheet,
                )
            }

            if (inspectorOpen && showPanes) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.width(Dimens.InspectorWidth).fillMaxHeight().testTag("right_inspector_sidebar"),
                ) {
                    InspectorPanel(
                        uiState = uiState,
                        culling = culling.takeIf { !layout.cullingBarAtSide },
                        onEditTags = viewModel::openTagDialog,
                    )
                }
            }
        }

        if (uiState.viewMode != DamViewMode.GRID && layout.showsFilmstrip && !chromeHidden) {
            FilmstripBar(
                photos = uiState.filteredPhotos,
                selectedPhotoId = uiState.selectedPhotoId,
                selectedPhotoIds = uiState.selectedPhotoIds,
                isSelecting = uiState.isSelecting,
                onSelectIndex = viewModel::selectPhotoIndex,
                onToggleSelectPhoto = viewModel::togglePhotoSelection,
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryMainView(
    uiState: LibraryUiState,
    layout: WindowLayout,
    viewModel: LibraryViewModel,
    culling: CullingActions,
    cullingInInspector: Boolean,
    fullScreenPhoto: Boolean,
    chromeHidden: Boolean,
    onOpenInspectorSheet: () -> Unit,
) {
    val photo = uiState.currentPhoto
    when (uiState.viewMode) {
        DamViewMode.GRID -> PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::onPullToRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            GridViewGallery(
                photos = uiState.filteredPhotos,
                sections = uiState.sections,
                selectedPhotoId = uiState.selectedPhotoId,
                selectedIds = uiState.selectedPhotoIds,
                onPhotoClick = viewModel::clickPhoto,
                onDragSelectStart = viewModel::beginDragSelection,
                onDragSelectTo = viewModel::extendDragSelection,
                onColumnsChanged = viewModel::setGridColumns,
                onClearFilters = viewModel::clearFilters.takeIf { uiState.totalCount > 0 },
                modifier = Modifier.fillMaxSize(),
            )
        }
        DamViewMode.COMPARE -> CompareLoupeView(
            photoA = photo,
            photoB = uiState.comparePhoto,
            onPickA = { viewModel.setCullStatus(CullStatus.PICK) },
            onPickB = { swapThenCull(uiState, viewModel, CullStatus.PICK) },
            onRejectA = { viewModel.setCullStatus(CullStatus.REJECT) },
            onRejectB = { swapThenCull(uiState, viewModel, CullStatus.REJECT) },
            zoomRequest = uiState.zoomRequest,
            swipeToFlag = uiState.swipeToFlag,
            onSwipe = viewModel::onSwipe,
            modifier = Modifier.fillMaxSize(),
        )
        DamViewMode.LOUPE -> {
            var loupeSize by remember { mutableStateOf(IntSize.Zero) }
            val photos = uiState.filteredPhotos
            val currentIndex = remember(photos, uiState.selectedPhotoId) { photos.indexOfFirst { it.id == uiState.selectedPhotoId } }
            LoupePrefetch(
                current = currentIndex,
                count = photos.size,
                compact = layout.width == WidthClass.COMPACT,
                widthPx = loupeSize.width,
                heightPx = loupeSize.height,
            ) { PreviewKeys.source(photos[it]) }
            val loupe = @Composable { loupeModifier: Modifier ->
                Box(modifier = loupeModifier.onSizeChanged { loupeSize = it }) {
                    ZoomableLoupeView(
                        photo = photo,
                        onNext = viewModel::nextPhoto,
                        onPrevious = viewModel::previousPhoto,
                        onSyncChipClick = viewModel::showSyncDetails,
                        zoomRequest = uiState.zoomRequest,
                        autoAdvance = uiState.advancesAfterEdits,
                        swipeToFlag = uiState.swipeToFlag,
                        onSwipe = viewModel::onSwipe,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (fullScreenPhoto) {
                        IconButton(
                            onClick = { viewModel.setViewMode(DamViewMode.GRID) },
                            // Bottom corner: the loupe's own badges fill the top of the photo.
                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).testTag("loupe_back_button"),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to grid", tint = Color.White)
                        }
                    }
                    Row(modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                        PeakingToggle(on = uiState.peaking, onClick = viewModel::togglePeaking)
                        IconButton(onClick = viewModel::startSlideshow, modifier = Modifier.testTag("loupe_slideshow_button")) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Slideshow", tint = Color.White)
                        }
                        IconButton(
                            onClick = viewModel::enterFullWindow,
                            modifier = Modifier.testTag("loupe_full_window_button"),
                        ) {
                            Icon(Icons.Default.Fullscreen, contentDescription = "Full window", tint = Color.White)
                        }
                        if (!layout.showsSideInspector) {
                            IconButton(onClick = onOpenInspectorSheet, modifier = Modifier.testTag("mobile_exif_button")) {
                                Icon(Icons.Default.Info, contentDescription = "Photo details", tint = Color.White)
                            }
                        }
                    }
                }
            }

            if (chromeHidden) {
                loupe(Modifier.fillMaxSize())
            } else if (layout.cullingBarAtSide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    loupe(Modifier.weight(1f).fillMaxHeight())
                    CullingSideBar(
                        cullStatus = photo?.cullStatus ?: CullStatus.UNFLAGGED,
                        rating = photo?.rating ?: 0,
                        colorLabel = photo?.colorLabel ?: ColorLabel.NONE,
                        onTogglePick = culling.onTogglePick,
                        onToggleReject = culling.onToggleReject,
                        onSetRating = culling.onSetRating,
                        onSetColorLabel = culling.onSetColorLabel,
                        history = culling.history,
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    loupe(Modifier.weight(1f).fillMaxWidth())
                    if (!cullingInInspector) {
                        CullingActionBar(
                            cullStatus = photo?.cullStatus ?: CullStatus.UNFLAGGED,
                            rating = photo?.rating ?: 0,
                            colorLabel = photo?.colorLabel ?: ColorLabel.NONE,
                            onTogglePick = culling.onTogglePick,
                            onToggleReject = culling.onToggleReject,
                            onSetRating = culling.onSetRating,
                            onSetColorLabel = culling.onSetColorLabel,
                            history = culling.history,
                            dense = true,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The action buttons of the second compare pane act on that photo, so it becomes the selection first. */
private fun swapThenCull(uiState: LibraryUiState, viewModel: LibraryViewModel, status: CullStatus) {
    if (uiState.comparePhoto == null) return
    viewModel.setCompareIndex(uiState.selectedIndex)
    viewModel.selectPhotoIndex(uiState.compareIndex)
    viewModel.setCullStatus(status)
}

@Composable
private fun LibraryEmptyState(
    onAddLocation: () -> Unit,
    onImportFromCard: () -> Unit,
    onTryDemo: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .testTag("library_empty_state"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
            Text("Your library is empty", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                text = "Point RawCull at the folder that holds your photos, or import a card into a new one.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onAddLocation,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("empty_add_location_button"),
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add library location", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onImportFromCard,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("empty_import_button"),
            ) {
                Icon(Icons.Default.SdCard, contentDescription = null, tint = PrimaryCyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Import from card", color = PrimaryCyan, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onTryDemo, modifier = Modifier.testTag("empty_demo_button")) {
                Text("Try demo library", color = TextSecondary)
            }
        }
    }
}

/** What keys do that lives on this screen rather than in the ViewModel. */
private class LibraryKeyHandlers(
    val onTogglePanes: () -> Unit,
    val onToggleChrome: () -> Unit,
    val onToggleFilterBar: () -> Unit,
    val onFocusSearch: () -> Unit,
    val onShowHelp: () -> Unit,
    val onEscape: () -> Unit,
)

private fun onLibraryKey(action: LibraryKeyAction, viewModel: LibraryViewModel, screen: LibraryKeyHandlers) {
    when (action) {
        is LibraryKeyAction.Cull -> viewModel.apply(action.edit, action.advance)
        LibraryKeyAction.Previous -> viewModel.previousPhoto()
        LibraryKeyAction.Next -> viewModel.nextPhoto()
        LibraryKeyAction.RowUp -> viewModel.moveRow(-1)
        LibraryKeyAction.RowDown -> viewModel.moveRow(1)
        LibraryKeyAction.First -> viewModel.firstPhoto()
        LibraryKeyAction.Last -> viewModel.lastPhoto()
        LibraryKeyAction.ShowGrid -> viewModel.setViewMode(DamViewMode.GRID)
        LibraryKeyAction.ShowLoupe -> viewModel.setViewMode(DamViewMode.LOUPE)
        LibraryKeyAction.ToggleCompare -> viewModel.toggleCompareMode()
        LibraryKeyAction.ToggleZoom -> viewModel.toggleZoom()
        LibraryKeyAction.TogglePanes -> screen.onTogglePanes()
        LibraryKeyAction.ToggleChrome -> screen.onToggleChrome()
        LibraryKeyAction.ToggleFullWindow -> viewModel.toggleFullWindow()
        LibraryKeyAction.ToggleSlideshow -> viewModel.toggleSlideshow()
        LibraryKeyAction.TogglePeaking -> viewModel.togglePeaking()
        LibraryKeyAction.SlideshowPause -> viewModel.toggleSlideshowPause()
        LibraryKeyAction.Undo -> viewModel.undo()
        LibraryKeyAction.Redo -> viewModel.redo()
        LibraryKeyAction.SelectAll -> viewModel.selectAll()
        LibraryKeyAction.SelectNone -> viewModel.clearSelection()
        LibraryKeyAction.FocusSearch -> screen.onFocusSearch()
        LibraryKeyAction.ToggleFilterBar -> screen.onToggleFilterBar()
        LibraryKeyAction.ToggleFilters -> viewModel.toggleFilters()
        LibraryKeyAction.EditTags -> viewModel.openTagDialog()
        LibraryKeyAction.DeleteRejects -> viewModel.requestDeleteRejects()
        LibraryKeyAction.ShowHelp -> screen.onShowHelp()
        LibraryKeyAction.Escape -> screen.onEscape()
    }
}
