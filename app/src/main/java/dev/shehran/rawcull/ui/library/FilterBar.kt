package dev.shehran.rawcull.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.FilterOptions
import dev.shehran.rawcull.data.model.PairMode
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.RatingOperator
import dev.shehran.rawcull.data.model.SortOrder
import dev.shehran.rawcull.ui.components.pairChipLabel
import dev.shehran.rawcull.ui.theme.DarkroomSurfaceVariant
import dev.shehran.rawcull.ui.theme.Dimens
import dev.shehran.rawcull.ui.theme.DotOutlineWhite
import dev.shehran.rawcull.ui.theme.FormatCyan
import dev.shehran.rawcull.ui.theme.FormatOrange
import dev.shehran.rawcull.ui.theme.HairlineWhite
import dev.shehran.rawcull.ui.theme.OutlineWhite
import dev.shehran.rawcull.ui.theme.PairedMint
import dev.shehran.rawcull.ui.theme.PrimaryCyan
import dev.shehran.rawcull.ui.theme.RawCullTheme
import dev.shehran.rawcull.ui.theme.StarEmpty
import dev.shehran.rawcull.ui.theme.TextChip
import dev.shehran.rawcull.ui.theme.TextMuted
import dev.shehran.rawcull.ui.theme.TextSecondary

/** Everything the filter controls can ask for. */
class FilterActions(
    val onUpdate: ((FilterOptions) -> FilterOptions) -> Unit,
    val onClear: () -> Unit,
    val onToggleEnabled: () -> Unit,
    val onSearchFocusChanged: (Boolean) -> Unit,
    val onSearchFocused: () -> Unit,
    val onDeleteRejects: () -> Unit,
)

/** The full bar, for windows wide enough to hold it. It wraps, so that no control ends up out of reach. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterBar(
    uiState: LibraryUiState,
    actions: FilterActions,
    focusSearch: Boolean,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .testTag("filter_bar"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        FlagChips(uiState, actions)
        ChipDivider()
        RatingControl(uiState.filterOptions, actions)
        ChipDivider()
        ColorSwatches(uiState, actions)
        ChipDivider()
        TagsMenu(uiState, actions)
        FormatChips(uiState, actions)
        ChipDivider()
        SearchField(uiState.filterOptions.searchQuery, actions, focusSearch, Modifier.width(SEARCH_WIDTH))
        SortMenu(uiState.filterOptions.sortOrder, actions)
        EnabledToggle(uiState.filtersEnabled, actions)
        if (uiState.filterOptions.isActive) ClearButton(actions)
        DeleteRejectsButton(uiState, actions)
    }
}

/** What narrow windows show instead: the active filters, and the way into the sheet. */
@Composable
fun ActiveFilterRow(
    uiState: LibraryUiState,
    actions: FilterActions,
    onOpenSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filter = uiState.filterOptions
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp)
            .testTag("active_filter_row"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onOpenSheet, modifier = Modifier.testTag("filter_sheet_button")) {
            Icon(Icons.Default.FilterList, contentDescription = null, tint = PrimaryCyan, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Filters", color = PrimaryCyan, fontSize = 12.sp)
        }
        filter.flags.sortedBy { it.ordinal }.forEach { status ->
            DamFilterChip(flagName(status), true, flagColor(status)) {
                actions.onUpdate { it.copy(flags = it.flags - status) }
            }
        }
        if (filter.filtersRating) {
            DamFilterChip("★ ${filter.ratingOperator.symbol} ${filter.rating}", true, RawCullTheme.colors.star) {
                actions.onUpdate { it.copy(ratingOperator = RatingOperator.AT_LEAST, rating = 0) }
            }
        }
        filter.colors.sortedBy { it.ordinal }.forEach { label ->
            DamFilterChip(colorName(label), true) { actions.onUpdate { it.copy(colors = it.colors - label) } }
        }
        filter.tags.sorted().forEach { tag ->
            DamFilterChip(tag, true) { actions.onUpdate { it.copy(tags = it.tags - tag) } }
        }
        filter.formatFilter?.let { format ->
            DamFilterChip(format.extension, true, formatChipColor(format)) { actions.onUpdate { it.copy(formatFilter = null) } }
        }
        if (filter.pairFilter != null) {
            DamFilterChip("Pairs", true, PairedMint) { actions.onUpdate { it.copy(pairFilter = null) } }
        }
        if (filter.searchQuery.isNotBlank()) {
            DamFilterChip("“${filter.searchQuery}”", true) { actions.onUpdate { it.copy(searchQuery = "") } }
        }
        if (!uiState.filtersEnabled) DamFilterChip("Filters off", true, onClick = actions.onToggleEnabled)
        DeleteRejectsButton(uiState, actions)
    }
}

/** The same controls as the bar, laid out for a sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterSheetContent(
    uiState: LibraryUiState,
    actions: FilterActions,
    focusSearch: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
            .testTag("filter_sheet"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SearchField(uiState.filterOptions.searchQuery, actions, focusSearch, Modifier.fillMaxWidth())
        SheetSection("Flags") { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FlagChips(uiState, actions) } }
        SheetSection("Rating") { RatingControl(uiState.filterOptions, actions) }
        SheetSection("Colours") { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ColorSwatches(uiState, actions) } }
        SheetSection("Tags and formats") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                TagsMenu(uiState, actions)
                FormatChips(uiState, actions)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SortMenu(uiState.filterOptions.sortOrder, actions)
            Spacer(Modifier.weight(1f))
            EnabledToggle(uiState.filtersEnabled, actions)
            if (uiState.filterOptions.isActive) ClearButton(actions)
        }
    }
}

@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        content()
    }
}

@Composable
private fun FlagChips(uiState: LibraryUiState, actions: FilterActions) {
    val selected = uiState.filterOptions.flags
    listOf(CullStatus.PICK, CullStatus.UNFLAGGED, CullStatus.REJECT).forEach { status ->
        DamFilterChip(
            label = "${flagName(status)} (${uiState.counts.flags[status] ?: 0})",
            selected = status in selected,
            activeColor = flagColor(status),
            tag = "filter_flag_${status.name.lowercase()}",
        ) {
            actions.onUpdate { filter ->
                filter.copy(flags = if (status in filter.flags) filter.flags - status else filter.flags + status)
            }
        }
    }
}

private fun flagName(status: CullStatus) = when (status) {
    CullStatus.PICK -> "Picks"
    CullStatus.UNFLAGGED -> "Unflagged"
    CullStatus.REJECT -> "Rejects"
}

@Composable
private fun flagColor(status: CullStatus): Color = when (status) {
    CullStatus.PICK -> RawCullTheme.colors.pick
    CullStatus.UNFLAGGED -> PrimaryCyan
    CullStatus.REJECT -> RawCullTheme.colors.reject
}

@Composable
private fun RatingControl(filter: FilterOptions, actions: FilterActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val entries = RatingOperator.entries
        DamFilterChip(filter.ratingOperator.symbol, filter.filtersRating, RawCullTheme.colors.star, tag = "filter_rating_operator") {
            actions.onUpdate { it.copy(ratingOperator = entries[(it.ratingOperator.ordinal + 1) % entries.size]) }
        }
        for (star in 1..MAX_RATING) {
            val filled = star <= filter.rating
            Box(
                modifier = Modifier
                    .size(STAR_TARGET_WIDTH, Dimens.TouchTarget)
                    .clickable {
                        actions.onUpdate { it.copy(rating = if (it.rating == star) 0 else star) }
                    }
                    .testTag("filter_rating_$star"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (filled) Icons.Default.Star else Icons.Outlined.Star,
                    contentDescription = "$star stars",
                    tint = if (filled) RawCullTheme.colors.star else StarEmpty,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ColorSwatches(uiState: LibraryUiState, actions: FilterActions) {
    val selected = uiState.filterOptions.colors
    SWATCHES.forEach { label ->
        val active = label in selected
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable {
                    actions.onUpdate { filter ->
                        filter.copy(colors = if (label in filter.colors) filter.colors - label else filter.colors + label)
                    }
                }
                .minimumInteractiveComponentSize()
                .padding(horizontal = 4.dp)
                .testTag("filter_color_${label.name.lowercase()}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(RawCullTheme.colors.label(label))
                    .border(if (active) 2.dp else 1.dp, if (active) Color.White else DotOutlineWhite, CircleShape),
            )
            Spacer(Modifier.width(3.dp))
            Text("${uiState.counts.colors[label] ?: 0}", color = if (active) Color.White else TextMuted, fontSize = 11.sp)
        }
    }
}

private fun colorName(label: ColorLabel) = if (label == ColorLabel.NONE) "No label" else label.labelName.substringBefore(' ')

@Composable
private fun TagsMenu(uiState: LibraryUiState, actions: FilterActions) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val chosen = uiState.filterOptions.tags
    Box {
        DamFilterChip(
            label = if (chosen.isEmpty()) "Tags" else "Tags (${chosen.size})",
            selected = chosen.isNotEmpty(),
            tag = "filter_tags_button",
        ) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val tags = uiState.counts.tags.entries
                .filter { it.key.contains(query.trim(), ignoreCase = true) }
                .sortedBy { it.key.lowercase() }
            PlainField(
                value = query,
                placeholder = "Find a tag",
                onValueChange = { query = it },
                modifier = Modifier.padding(horizontal = 12.dp).width(SEARCH_WIDTH),
            )
            if (tags.isEmpty()) {
                DropdownMenuItem(text = { Text("No tags", color = TextMuted) }, enabled = false, onClick = {})
            }
            tags.forEach { (tag, count) ->
                DropdownMenuItem(
                    text = { Text("$tag ($count)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Checkbox(checked = tag in chosen, onCheckedChange = null) },
                    onClick = {
                        actions.onUpdate { filter ->
                            filter.copy(tags = if (tag in filter.tags) filter.tags - tag else filter.tags + tag)
                        }
                    },
                    modifier = Modifier.testTag("filter_tag_$tag"),
                )
            }
        }
    }
}

@Composable
private fun FormatChips(uiState: LibraryUiState, actions: FilterActions) {
    val filter = uiState.filterOptions
    pairChipLabel(uiState.counts.companionFormats)?.let { label ->
        DamFilterChip("$label (${uiState.pairedCount})", filter.pairFilter == PairMode.RAW_AND_RASTER, PairedMint) {
            actions.onUpdate {
                it.copy(pairFilter = if (it.pairFilter == PairMode.RAW_AND_RASTER) null else PairMode.RAW_AND_RASTER)
            }
        }
    }
    uiState.counts.formats.sortedBy { it.ordinal }.forEach { format ->
        DamFilterChip(format.extension, filter.formatFilter == format, formatChipColor(format)) {
            actions.onUpdate { it.copy(formatFilter = if (it.formatFilter == format) null else format) }
        }
    }
}

@Composable
private fun SearchField(value: String, actions: FilterActions, focusNow: Boolean, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusNow) {
        if (focusNow) {
            focus.requestFocus()
            actions.onSearchFocused()
        }
    }
    // Leaving composition doesn't always report a focus change, and a stale true would mute every key.
    DisposableEffect(Unit) { onDispose { actions.onSearchFocusChanged(false) } }
    PlainField(
        value = value,
        placeholder = "Search",
        leadingIcon = true,
        onValueChange = { text -> actions.onUpdate { it.copy(searchQuery = text) } },
        modifier = modifier
            .focusRequester(focus)
            .onFocusChanged { actions.onSearchFocusChanged(it.isFocused) }
            .testTag("filter_search_field"),
    )
}

@Composable
private fun PlainField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
        cursorBrush = SolidColor(PrimaryCyan),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = modifier.height(FIELD_HEIGHT),
        decorationBox = { field ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkroomSurfaceVariant)
                    .border(1.dp, HairlineWhite, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = TextMuted, fontSize = 13.sp)
                    field()
                }
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(FIELD_HEIGHT)) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(14.dp))
                    }
                }
            }
        },
    )
}

@Composable
private fun SortMenu(sort: SortOrder, actions: FilterActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("filter_sort_button")) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, tint = TextChip, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(sort.label, color = TextChip, fontSize = 12.sp, maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label) },
                    leadingIcon = {
                        if (order == sort) Icon(Icons.Default.Check, contentDescription = null, tint = PrimaryCyan)
                    },
                    onClick = {
                        expanded = false
                        actions.onUpdate { it.copy(sortOrder = order) }
                    },
                    modifier = Modifier.testTag("filter_sort_${order.name.lowercase()}"),
                )
            }
        }
    }
}

@Composable
private fun EnabledToggle(enabled: Boolean, actions: FilterActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Filters", color = TextChip, fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Switch(
            checked = enabled,
            onCheckedChange = { actions.onToggleEnabled() },
            modifier = Modifier.testTag("filters_enabled_switch"),
        )
    }
}

@Composable
private fun ClearButton(actions: FilterActions) {
    TextButton(onClick = actions.onClear, modifier = Modifier.testTag("filter_clear_button")) {
        Text("Clear", color = PrimaryCyan, fontSize = 12.sp)
    }
}

/** Only offered when the view is nothing but rejects, which is when the question comes up. */
@Composable
private fun DeleteRejectsButton(uiState: LibraryUiState, actions: FilterActions) {
    val onlyRejects = uiState.filterOptions.flags == setOf(CullStatus.REJECT) && uiState.filtersEnabled
    if (!onlyRejects || uiState.rejectsCount == 0 || !uiState.isLocationAvailable) return
    TextButton(onClick = actions.onDeleteRejects, modifier = Modifier.testTag("filter_delete_rejects")) {
        Text("Delete rejects…", color = RawCullTheme.colors.reject, fontSize = 12.sp)
    }
}

@Composable
private fun ChipDivider() {
    Box(modifier = Modifier.height(20.dp).width(1.dp).background(OutlineWhite))
}

private fun formatChipColor(format: PhotoFormat): Color = when (format) {
    PhotoFormat.ARW -> FormatOrange
    PhotoFormat.DNG -> FormatCyan
    else -> PrimaryCyan
}

@Composable
private fun DamFilterChip(
    label: String,
    selected: Boolean,
    activeColor: Color = PrimaryCyan,
    tag: String? = null,
    onClick: () -> Unit,
) {
    // The visible chip is small, but its touch target is the full 48 dp.
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) activeColor else TextChip,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) activeColor.copy(alpha = 0.2f) else DarkroomSurfaceVariant)
                .border(1.dp, if (selected) activeColor else HairlineWhite, RoundedCornerShape(16.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private val SWATCHES = listOf(
    ColorLabel.RED,
    ColorLabel.YELLOW,
    ColorLabel.GREEN,
    ColorLabel.BLUE,
    ColorLabel.PURPLE,
    ColorLabel.NONE,
)
private val SEARCH_WIDTH = 180.dp
private val FIELD_HEIGHT = 34.dp
private val STAR_TARGET_WIDTH = 30.dp
private const val MAX_RATING = 5
