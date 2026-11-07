package dev.shehran.rawcull.ui.importer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shehran.rawcull.data.model.ImportItemRole
import dev.shehran.rawcull.importer.DayTick
import dev.shehran.rawcull.importer.ImportCandidate
import dev.shehran.rawcull.importer.ImportDestination
import dev.shehran.rawcull.importer.ImportPreview
import dev.shehran.rawcull.importer.ImportSessionState
import dev.shehran.rawcull.importer.PreviewDay
import dev.shehran.rawcull.ui.components.ZoomableLoupeView
import dev.shehran.rawcull.ui.components.badgeText
import dev.shehran.rawcull.ui.shell.LocalWindowLayout
import dev.shehran.rawcull.ui.theme.AmberRating
import dev.shehran.rawcull.ui.theme.EmeraldPick
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.TextMuted
import dev.shehran.rawcull.ui.theme.TextSecondary
import dev.shehran.rawcull.util.RawPreviewLoader

private const val THUMBNAIL_SIZE = 320
private const val DIMMED_ALPHA = 0.45f

private sealed interface PreviewRow {
    val key: String

    data class Header(val day: PreviewDay) : PreviewRow {
        override val key: String get() = "day_${day.key}"
    }

    data class Tile(val candidate: ImportCandidate) : PreviewRow {
        override val key: String get() = candidate.id
    }
}

/** Step 2 of the wizard: what to bring in. */
@Composable
fun ImportPreviewScreen(
    viewModel: ImportViewModel,
    onBack: () -> Unit,
    onBackToSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val layout = LocalWindowLayout.current

    LaunchedEffect(uiState.draft.locationId) { viewModel.refreshFreeSpace() }

    // Asked at the first import. The answer doesn't matter: an import works without notifications.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.startImport()
    }
    val context = LocalContext.current
    val onStart = {
        val needsAsking = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsking) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.startImport()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }

    Box(modifier = modifier.fillMaxSize().testTag("import_preview_screen")) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            if (!layout.isHeightCompact) WizardHeader(step = 2)
            PreviewToolbar(uiState, viewModel)
            ReadingProgress(uiState.session)
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                PreviewGrid(uiState, viewModel, Modifier.weight(1f).fillMaxHeight())
                if (layout.showsFolderPane) {
                    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.width(280.dp).fillMaxHeight()) {
                        CandidateInfo(uiState.inspected, Modifier.padding(16.dp))
                    }
                }
            }
            SummaryBar(uiState, onStart, onBack)
        }

        uiState.viewerCandidate?.let { candidate ->
            PreviewViewer(uiState, candidate, viewModel)
        }

        if (uiState.session == ImportSessionState.SourceLost) {
            CardDisconnectedOverlay(onBackToSource)
        }

        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun PreviewToolbar(uiState: ImportUiState, viewModel: ImportViewModel) {
    val summary = uiState.summary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { viewModel.selectAll(true) }, modifier = Modifier.testTag("preview_select_all")) { Text("All") }
        TextButton(onClick = { viewModel.selectAll(false) }, modifier = Modifier.testTag("preview_select_none")) { Text("None") }
        FilterChip(
            selected = uiState.options.includeRaw,
            onClick = { viewModel.setIncludeRaw(!uiState.options.includeRaw) },
            label = { Text("RAW (${summary.rawCount})") },
            modifier = Modifier.testTag("preview_raw_toggle"),
        )
        FilterChip(
            selected = uiState.options.includeRaster,
            onClick = { viewModel.setIncludeRaster(!uiState.options.includeRaster) },
            label = { Text("JPG/HEIF (${summary.rasterCount})") },
            modifier = Modifier.testTag("preview_raster_toggle"),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hide already imported", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = uiState.options.hideImported,
                onCheckedChange = viewModel::setHideImported,
                modifier = Modifier.testTag("preview_hide_imported"),
            )
        }
    }
}

@Composable
private fun ReadingProgress(session: ImportSessionState) {
    if (session is ImportSessionState.Reading) {
        Text(
            text = "Reading dates ${session.done} / ${session.total}",
            color = TextMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun PreviewGrid(uiState: ImportUiState, viewModel: ImportViewModel, modifier: Modifier = Modifier) {
    val rows = remember(uiState.days, uiState.collapsedDays) {
        uiState.days.flatMap { day ->
            val tiles = if (day.key in uiState.collapsedDays) emptyList() else day.candidates.map { PreviewRow.Tile(it) }
            listOf(PreviewRow.Header(day)) + tiles
        }
    }
    val gridState = rememberLazyGridState()

    if (rows.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Nothing to show with these filters.", color = TextMuted)
        }
        return
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 120.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.testTag("import_preview_grid"),
    ) {
        items(
            items = rows,
            key = { it.key },
            span = { row -> GridItemSpan(if (row is PreviewRow.Header) maxLineSpan else 1) },
            contentType = { it::class },
        ) { row ->
            when (row) {
                is PreviewRow.Header -> DayHeader(
                    day = row.day,
                    tick = ImportPreview.dayTick(row.day, uiState.options, uiState.overrides),
                    isCollapsed = row.day.key in uiState.collapsedDays,
                    onToggleCollapsed = { viewModel.toggleDayCollapsed(row.day.key) },
                    onTick = { selected -> viewModel.setDaySelected(row.day, selected) },
                )
                is PreviewRow.Tile -> {
                    val windowInfo = LocalWindowInfo.current
                    PreviewTile(
                        candidate = row.candidate,
                        selected = ImportPreview.isSelected(row.candidate, uiState.options, uiState.overrides),
                        imported = ImportPreview.isImported(row.candidate, uiState.options),
                        onOpen = {
                            if (windowInfo.keyboardModifiers.isShiftPressed) {
                                viewModel.extendSelection(row.candidate.id)
                            } else {
                                viewModel.openViewer(row.candidate.id)
                            }
                        },
                        onLongPress = { viewModel.extendSelection(row.candidate.id) },
                        onToggle = { viewModel.toggleSelected(row.candidate) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DayHeader(
    day: PreviewDay,
    tick: DayTick,
    isCollapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onTick: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val title = remember(day.key) {
        day.date?.takeIf { day.key != ImportPreview.UNDATED_KEY }?.let {
            DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL)
        } ?: "No date"
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleCollapsed).testTag("preview_day_${day.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(
            state = when (tick) {
                DayTick.ALL -> ToggleableState.On
                DayTick.SOME -> ToggleableState.Indeterminate
                DayTick.NONE -> ToggleableState.Off
            },
            onClick = { onTick(tick != DayTick.ALL) },
            modifier = Modifier.testTag("preview_day_tick_${day.key}"),
        )
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Text("${day.candidates.size} photos", color = TextMuted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = if (isCollapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
            contentDescription = if (isCollapsed) "Expand day" else "Collapse day",
            tint = TextSecondary,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PreviewTile(
    candidate: ImportCandidate,
    selected: Boolean,
    imported: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val asset = remember(candidate.id) { candidate.toPreviewAsset() }
    val bitmap by produceState<Bitmap?>(null, candidate.id) {
        value = RawPreviewLoader.loadThumbnail(
            context,
            Uri.parse(asset.fileUri),
            asset.previewCacheKey,
            asset.format,
            maxDimension = THUMBNAIL_SIZE,
            companionUri = asset.companionFileUri?.let(Uri::parse),
        )
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .testTag("preview_tile"),
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = candidate.files.first().name,
                contentScale = ContentScale.Crop,
                alpha = if (selected) 1f else DIMMED_ALPHA,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(uncheckedColor = Color.White),
            modifier = Modifier.align(Alignment.TopStart).testTag("preview_tile_check"),
        )
        Row(modifier = Modifier.align(Alignment.BottomStart).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TileBadge(asset.badgeText, Color(0xCC000000), Color.White)
            if (imported) TileBadge("Imported", EmeraldPick, Color.Black)
        }
    }
}

@Composable
private fun TileBadge(text: String, background: Color, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun SummaryBar(uiState: ImportUiState, onStart: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val summary = uiState.summary
    val size = Formatter.formatShortFileSize(context, summary.bytes)
    val path = ImportDestination.previewPath(uiState.destination, uiState.draft, uiState.firstPhotoDate)

    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Text(
            text = "Importing ${summary.selectedPhotos} of ${summary.totalPhotos} photos · ${summary.files} files · $size → $path",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("import_summary"),
        )
        if (uiState.isShortOfSpace) {
            val free = Formatter.formatShortFileSize(context, uiState.destinationFreeBytes ?: 0L)
            Text(
                text = "Not enough space: this needs $size and ${uiState.destination?.displayName} has $free free.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("import_space_warning"),
            )
        }
        if (uiState.deleteFromCard) {
            Text(
                text = "Verified files will be deleted from the card.",
                color = AmberRating,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("import_delete_notice"),
            )
        }
        WizardActionBar(
            nextLabel = "Start import",
            nextEnabled = uiState.canStart,
            onNext = onStart,
            nextTag = "import_start_button",
            onBack = onBack,
        )
    }
}

@Composable
private fun CandidateInfo(candidate: ImportCandidate?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (candidate == null) {
            Text("Open a photo to see its details.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Text(candidate.files.first().name, color = Color.White, fontWeight = FontWeight.Bold)
        candidate.folderDate?.let {
            Text(
                DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        candidate.cameraName()?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
        candidate.files.forEach { file ->
            Text(
                text = "${file.name} · ${Formatter.formatShortFileSize(context, file.sizeBytes)}",
                color = if (file.role == ImportItemRole.PRIMARY) Color.White else TextMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Full-screen look at one photo, with the same tick as its tile. */
@Composable
private fun PreviewViewer(uiState: ImportUiState, candidate: ImportCandidate, viewModel: ImportViewModel) {
    val context = LocalContext.current
    val asset = remember(candidate.id) { candidate.toPreviewAsset() }
    val selected = ImportPreview.isSelected(candidate, uiState.options, uiState.overrides)
    val focus = remember { FocusRequester() }

    BackHandler(onBack = viewModel::closeViewer)
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Spacebar -> viewModel.toggleSelected(candidate)
                    Key.DirectionLeft -> viewModel.stepViewer(-1)
                    Key.DirectionRight -> viewModel.stepViewer(1)
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .testTag("import_viewer"),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = viewModel::closeViewer, modifier = Modifier.testTag("import_viewer_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to photos", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(candidate.files.first().name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = listOfNotNull(
                        Formatter.formatShortFileSize(context, candidate.files.sumOf { it.sizeBytes }),
                        candidate.folderDate?.let { DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME) },
                        candidate.cameraName(),
                    ).joinToString(" · "),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("Include", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = selected,
                onCheckedChange = { viewModel.toggleSelected(candidate) },
                modifier = Modifier.padding(end = 8.dp).testTag("import_viewer_include"),
            )
        }
        ZoomableLoupeView(
            photo = asset,
            onNext = { viewModel.stepViewer(1) },
            onPrevious = { viewModel.stepViewer(-1) },
            onSyncChipClick = {},
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun CardDisconnectedOverlay(onBackToSource: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6000000))
            .clickable(onClick = {})
            .testTag("card_disconnected_overlay"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Card disconnected — reconnect it to continue", color = Color.White, fontWeight = FontWeight.Bold)
            OutlinedButton(
                onClick = onBackToSource,
                modifier = Modifier.heightIn(min = 48.dp).testTag("back_to_source_button"),
            ) { Text("Back to source", color = PrimaryCyan) }
        }
    }
}
