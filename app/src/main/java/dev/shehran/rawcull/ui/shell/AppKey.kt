package dev.shehran.rawcull.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

/** Every destination of the app. Encoded as text, so back stacks survive process recreation. */
sealed interface AppKey {
    data object Library : AppKey
    data object ImportSource : AppKey
    data object ImportPreview : AppKey
    data class ImportProgress(val jobId: Long) : AppKey
    data object Settings : AppKey

    fun encode(): String = when (this) {
        Library -> "library"
        ImportSource -> "import-source"
        ImportPreview -> "import-preview"
        is ImportProgress -> "import-progress:$jobId"
        Settings -> "settings"
    }

    companion object {
        fun decode(text: String): AppKey? = when {
            text == "library" -> Library
            text == "import-source" -> ImportSource
            text == "import-preview" -> ImportPreview
            text == "settings" -> Settings
            text.startsWith("import-progress:") ->
                text.substringAfter(':').toLongOrNull()?.let(::ImportProgress)
            else -> null
        }
    }
}

enum class TopLevelTab(val root: AppKey) {
    IMPORT(AppKey.ImportSource),
    LIBRARY(AppKey.Library),
    SETTINGS(AppKey.Settings),
}

/** The selected tab and one back stack per tab. */
class TabStacks(
    selected: TopLevelTab = TopLevelTab.LIBRARY,
    stacks: Map<TopLevelTab, List<AppKey>> = emptyMap(),
) {
    var selected: TopLevelTab by mutableStateOf(selected)
        private set

    private val stacks: Map<TopLevelTab, SnapshotStateList<AppKey>> = TopLevelTab.entries.associateWith { tab ->
        mutableStateListOf<AppKey>().apply { addAll(stacks[tab]?.takeIf { it.isNotEmpty() } ?: listOf(tab.root)) }
    }

    val current: SnapshotStateList<AppKey> get() = stack(selected)

    fun stack(tab: TopLevelTab): SnapshotStateList<AppKey> = stacks.getValue(tab)

    fun select(tab: TopLevelTab) {
        selected = tab
    }

    /** Opens [key] on top of [tab], unless it's already what that tab shows. */
    fun push(key: AppKey, tab: TopLevelTab = selected) {
        selected = tab
        val stack = stack(tab)
        if (stack.last() != key) stack.add(key)
    }

    /** Replaces everything above the root of [tab]. */
    fun resetTo(tab: TopLevelTab, vararg keys: AppKey) {
        selected = tab
        val stack = stack(tab)
        stack.subList(1, stack.size).clear()
        stack.addAll(keys)
    }

    /** One step back: down this tab's stack, then to Library. False if there's nowhere left to go. */
    fun back(): Boolean {
        val stack = current
        return when {
            stack.size > 1 -> {
                stack.removeAt(stack.lastIndex)
                true
            }
            selected != TopLevelTab.LIBRARY -> {
                selected = TopLevelTab.LIBRARY
                true
            }
            else -> false
        }
    }

    companion object {
        private const val SEPARATOR = "\n"

        val Saver: Saver<TabStacks, List<String>> = Saver(
            save = { state ->
                listOf(state.selected.name) + TopLevelTab.entries.map { tab ->
                    state.stack(tab).joinToString(SEPARATOR) { it.encode() }
                }
            },
            restore = { saved ->
                val selected = TopLevelTab.entries.firstOrNull { it.name == saved.first() } ?: TopLevelTab.LIBRARY
                val stacks = TopLevelTab.entries.withIndex().associate { (index, tab) ->
                    tab to saved[index + 1].split(SEPARATOR).mapNotNull(AppKey::decode)
                }
                TabStacks(selected, stacks)
            },
        )
    }
}
