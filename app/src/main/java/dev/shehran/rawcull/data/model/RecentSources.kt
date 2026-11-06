package dev.shehran.rawcull.data.model

data class RecentSource(
    val uri: String,
    val displayName: String,
    val lastOpenedAt: Long,
)

data class RecentSourcesUpdate(
    val recents: List<RecentSource>,
    val evicted: List<RecentSource>,
)

/** Most-recent-first list of opened sources, de-duplicated by URI. */
object RecentSources {
    const val MAX_ENTRIES = 8

    fun add(current: List<RecentSource>, entry: RecentSource): RecentSourcesUpdate {
        val merged = listOf(entry) + current.filterNot { it.uri == entry.uri }
        return RecentSourcesUpdate(merged.take(MAX_ENTRIES), merged.drop(MAX_ENTRIES))
    }
}
