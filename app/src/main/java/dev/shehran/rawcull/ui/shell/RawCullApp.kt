package dev.shehran.rawcull.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.shehran.rawcull.data.model.ImportJob
import dev.shehran.rawcull.ui.importer.ImportSourceScreen
import dev.shehran.rawcull.ui.importer.ImportViewModel
import dev.shehran.rawcull.ui.library.LibraryScreen
import dev.shehran.rawcull.ui.library.LibraryViewModel

val LocalWindowLayout = staticCompositionLocalOf { WindowLayout(WidthClass.COMPACT, isHeightCompact = false) }

private data class TabItem(val tab: TopLevelTab, val label: String, val tag: String)

private val TAB_ITEMS = listOf(
    TabItem(TopLevelTab.IMPORT, "Import", "nav_import"),
    TabItem(TopLevelTab.LIBRARY, "Library", "nav_library"),
    TabItem(TopLevelTab.SETTINGS, "Settings", "nav_settings"),
)

/** The app shell: top-level navigation that follows the window size, and one back stack per tab. */
@Composable
fun RawCullApp(
    libraryViewModel: LibraryViewModel,
    modifier: Modifier = Modifier,
    shellViewModel: ShellViewModel = viewModel(factory = ShellViewModel.Factory),
    importViewModel: ImportViewModel = viewModel(factory = ImportViewModel.Factory),
) {
    val layout = rememberWindowLayout()
    val stacks = rememberSaveable(saver = TabStacks.Saver) { TabStacks() }
    val activeJob by shellViewModel.activeJob.collectAsStateWithLifecycle()
    var immersive by remember { mutableStateOf(false) }
    val suiteType = when {
        immersive -> NavigationSuiteType.None
        layout.usesBottomBar -> NavigationSuiteType.NavigationBar
        layout.usesWideRail -> NavigationSuiteType.WideNavigationRailExpanded
        else -> NavigationSuiteType.NavigationRail
    }

    CompositionLocalProvider(LocalWindowLayout provides layout) {
        NavigationSuiteScaffold(
            modifier = modifier.fillMaxSize(),
            navigationSuiteType = suiteType,
            containerColor = MaterialTheme.colorScheme.background,
            navigationItems = {
                TAB_ITEMS.forEach { item ->
                    NavigationSuiteItem(
                        selected = stacks.selected == item.tab,
                        onClick = { stacks.select(item.tab) },
                        icon = { Icon(item.tab.icon, contentDescription = null) },
                        label = { Text(item.label) },
                        badge = if (item.tab == TopLevelTab.IMPORT && activeJob != null) {
                            { Badge() }
                        } else {
                            null
                        },
                        modifier = Modifier.testTag(item.tag),
                        navigationSuiteType = suiteType,
                    )
                }
            },
        ) {
            AppNavDisplay(stacks, activeJob, libraryViewModel, importViewModel, onImmersiveChange = { immersive = it })
        }
    }
}

private val TopLevelTab.icon
    get() = when (this) {
        TopLevelTab.IMPORT -> Icons.Default.SdCard
        TopLevelTab.LIBRARY -> Icons.Default.PhotoLibrary
        TopLevelTab.SETTINGS -> Icons.Default.Settings
    }

@Composable
private fun AppNavDisplay(
    stacks: TabStacks,
    activeJob: ImportJob?,
    libraryViewModel: LibraryViewModel,
    importViewModel: ImportViewModel,
    onImmersiveChange: (Boolean) -> Unit,
) {
    val entryProvider: (AppKey) -> NavEntry<AppKey> = { key ->
        NavEntry(key) {
            when (key) {
                AppKey.Library -> Column(Modifier.fillMaxSize()) {
                    activeJob?.let { job ->
                        ImportRunningRow(job, onClick = { stacks.push(AppKey.ImportProgress(job.id), TopLevelTab.IMPORT) })
                    }
                    LibraryScreen(
                        viewModel = libraryViewModel,
                        onImportFromCard = { stacks.select(TopLevelTab.IMPORT) },
                        onImmersiveChange = onImmersiveChange,
                        modifier = Modifier.weight(1f),
                    )
                }
                AppKey.ImportSource -> ImportSourceScreen(
                    viewModel = importViewModel,
                    onNext = { stacks.push(AppKey.ImportPreview, TopLevelTab.IMPORT) },
                )
                AppKey.ImportPreview, is AppKey.ImportProgress -> ComingNext("Import")
                AppKey.Settings -> ComingNext("Settings")
            }
        }
    }

    // Decorated per tab, so that a tab keeps its screens' state while another one is showing.
    val entriesByTab = TopLevelTab.entries.associateWith { tab ->
        rememberDecoratedNavEntries(
            backStack = stacks.stack(tab),
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider,
        )
    }

    // NavDisplay only handles back above a stack's root; the root of a tab goes to Library.
    BackHandler(enabled = stacks.selected != TopLevelTab.LIBRARY && stacks.current.size == 1) { stacks.back() }

    NavDisplay(
        entries = entriesByTab.getValue(stacks.selected),
        onBack = { stacks.back() },
    )
}

/** Shown above the Library while an import runs; tapping it opens the progress screen. */
@Composable
private fun ImportRunningRow(job: ImportJob, onClick: () -> Unit) {
    val total = job.totalBytes.coerceAtLeast(1L)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("import_running_row"),
    ) {
        Text(
            text = "Importing ${job.doneFiles} of ${job.totalFiles} files",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
        LinearProgressIndicator(
            progress = { job.doneBytes.toFloat() / total },
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
    }
}

@Composable
private fun ComingNext(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
