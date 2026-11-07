package dev.shehran.rawcull.ui.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.library.LibraryScope
import dev.shehran.rawcull.ui.LibraryKeyAction
import dev.shehran.rawcull.ui.LibraryKeys
import dev.shehran.rawcull.ui.components.CompareLoupeView
import dev.shehran.rawcull.ui.components.CullingActionBar
import dev.shehran.rawcull.ui.components.CullingSideBar
import dev.shehran.rawcull.ui.components.FilmstripBar
import dev.shehran.rawcull.ui.components.GridViewGallery
import dev.shehran.rawcull.ui.components.StorageChangeEffect
import dev.shehran.rawcull.ui.components.SyncDetailsDialog
import dev.shehran.rawcull.ui.components.TagManagerDialog
import dev.shehran.rawcull.ui.components.ZoomableLoupeView
import dev.shehran.rawcull.ui.shell.LocalWindowLayout
import dev.shehran.rawcull.ui.shell.WindowLayout
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private val FolderPaneWidth = 280.dp
private val InspectorWidth = 320.dp

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
    val folderPaneOpen = layout.showsFolderPane && (folderPaneChoice ?: true)
    val inspectorOpen = layout.showsSideInspector && (inspectorChoice ?: layout.showsFolderPane)

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

    LifecycleResumeEffect(Unit) {
        viewModel.refreshSources()
        onPauseOrDispose { }
    }
    LifecycleStartEffect(Unit) {
        viewModel.onAppStart()
        onStopOrDispose { }
    }
    StorageChangeEffect(onChanged = viewModel::refreshSources)
    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { message ->
            // Clearing first would change this effect's key and cancel the snackbar.
            snackbarHostState.showSnackbar(message)
            viewModel.onSnackbarShown()
        }
    }

    val viewingPhoto = uiState.activeLocation != null && uiState.viewMode != DamViewMode.GRID
    val immersive = viewingPhoto && (layout.usesBottomBar || layout.isHeightCompact)
    LaunchedEffect(immersive) { onImmersiveChange(immersive) }
    DisposableEffect(Unit) { onDispose { onImmersiveChange(false) } }

    // Innermost state first: each back press undoes one thing.
    BackHandler(enabled = viewingPhoto && !drawerState.isOpen) { viewModel.setViewMode(DamViewMode.GRID) }
    BackHandler(enabled = drawerState.isOpen) { coroutines.launch { drawerState.close() } }

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

    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { keyFocus.requestFocus() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .focusRequester(keyFocus)
            .focusable()
            // Only this screen listens, so typing anywhere else in the app is left alone.
            .onPreviewKeyEvent { event ->
                val action = LibraryKeys.actionFor(event.key)
                if (event.type == KeyEventType.KeyDown && action != null) {
                    onLibraryKey(action, viewModel)
                    true
                } else {
                    false
                }
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

        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (showInspectorSheet) {
        ModalBottomSheet(
            onDismissRequest = { showInspectorSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            InspectorPanel(uiState, culling = null, onEditTags = viewModel::openTagDialog)
        }
    }
    if (uiState.showTagDialog) {
        TagManagerDialog(
            currentTags = uiState.currentPhoto?.tags.orEmpty(),
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
    )
    // On a phone on its side the photo gets the whole screen, so the bar moves out of its way.
    val fullScreenPhoto = immersive && layout.isHeightCompact
    val insets = if (fullScreenPhoto) {
        WindowInsets.safeDrawing
    } else {
        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    }

    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(insets)) {
        if (!fullScreenPhoto) {
            LibraryTopBar(
                uiState = uiState,
                actions = LibraryTopBarActions(
                    onToggleFolders = onToggleFolders,
                    onToggleInspector = onToggleInspector,
                    onSetViewMode = viewModel::setViewMode,
                    onUpdateFilter = viewModel::updateFilter,
                    onToggleSyncErrors = viewModel::toggleSyncErrorFilter,
                    onRescan = viewModel::rescan,
                    onToggleMultiSelect = viewModel::toggleMultiSelectMode,
                    onRetryLocation = viewModel::retryLocation,
                    onRelinkLocation = onRelink,
                ),
            )
        }

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (folderPaneOpen) {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.width(FolderPaneWidth).fillMaxHeight()) {
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
                    onOpenInspectorSheet = onOpenInspectorSheet,
                )
            }

            if (inspectorOpen && !fullScreenPhoto) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.width(InspectorWidth).fillMaxHeight().testTag("right_inspector_sidebar"),
                ) {
                    InspectorPanel(
                        uiState = uiState,
                        culling = culling.takeIf { !layout.cullingBarAtSide },
                        onEditTags = viewModel::openTagDialog,
                    )
                }
            }
        }

        if (uiState.viewMode != DamViewMode.GRID && layout.showsFilmstrip) {
            FilmstripBar(
                photos = uiState.filteredPhotos,
                selectedPhotoId = uiState.selectedPhotoId,
                selectedPhotoIds = uiState.selectedPhotoIds,
                isMultiSelectMode = uiState.isMultiSelectMode,
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
                selectedPhotoId = uiState.selectedPhotoId,
                selectedIds = uiState.selectedPhotoIds,
                isMultiSelectMode = uiState.isMultiSelectMode,
                onSelectIndex = {
                    viewModel.selectPhotoIndex(it)
                    viewModel.setViewMode(DamViewMode.LOUPE)
                },
                onToggleSelectId = viewModel::togglePhotoSelection,
                onBatchPick = viewModel::batchPickSelected,
                onBatchReject = viewModel::batchRejectSelected,
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
            modifier = Modifier.fillMaxSize(),
        )
        DamViewMode.LOUPE -> {
            val loupe = @Composable { loupeModifier: Modifier ->
                Box(modifier = loupeModifier) {
                    ZoomableLoupeView(
                        photo = photo,
                        onNext = viewModel::nextPhoto,
                        onPrevious = viewModel::previousPhoto,
                        onSyncChipClick = viewModel::showSyncDetails,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (fullScreenPhoto) {
                        IconButton(
                            onClick = { viewModel.setViewMode(DamViewMode.GRID) },
                            modifier = Modifier.align(Alignment.TopStart).testTag("loupe_back_button"),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to grid", tint = Color.White)
                        }
                    }
                    if (!layout.showsSideInspector) {
                        IconButton(
                            onClick = onOpenInspectorSheet,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).testTag("mobile_exif_button"),
                        ) {
                            Icon(Icons.Default.Info, contentDescription = "Photo details", tint = Color.White)
                        }
                    }
                }
            }

            if (layout.cullingBarAtSide) {
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

private fun onLibraryKey(action: LibraryKeyAction, viewModel: LibraryViewModel) {
    when (action) {
        LibraryKeyAction.TogglePick -> viewModel.togglePick()
        LibraryKeyAction.ToggleReject -> viewModel.toggleReject()
        LibraryKeyAction.Unflag -> viewModel.setCullStatus(CullStatus.UNFLAGGED)
        is LibraryKeyAction.Rate -> viewModel.setRating(action.stars)
        LibraryKeyAction.Previous -> viewModel.previousPhoto()
        LibraryKeyAction.Next -> viewModel.nextPhoto()
        LibraryKeyAction.ToggleCompare -> viewModel.toggleCompareMode()
        LibraryKeyAction.ShowAllPhotos -> viewModel.setScope(LibraryScope.All)
    }
}
