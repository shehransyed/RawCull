package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.FilterOptions
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.RatingOperator
import dev.shehran.rawcull.data.model.SortOrder
import dev.shehran.rawcull.data.model.XmpSyncStatus

/** A run of [count] visible photos from one folder, starting at [firstIndex]. */
data class FolderSection(val folder: String, val firstIndex: Int, val count: Int)

/** How many photos of the scope have each value, whatever the filters say. */
data class FilterCounts(
    val total: Int = 0,
    val flags: Map<CullStatus, Int> = emptyMap(),
    val ratings: Map<Int, Int> = emptyMap(),
    val colors: Map<ColorLabel, Int> = emptyMap(),
    val tags: Map<String, Int> = emptyMap(),
    val paired: Int = 0,
    val saving: Int = 0,
    val syncErrors: Int = 0,
)

data class LibraryView(
    val rows: List<PhotoAsset> = emptyList(),
    val sections: List<FolderSection> = emptyList(),
    val counts: FilterCounts = FilterCounts(),
)

object LibraryFilter {
    /**
     * @param rows every photo of the scope.
     * @param scopeSpansFolders whether the scope covers more than one folder. Folder sections only
     * appear then, and only for date and name sorts: a header per rating bucket says nothing.
     */
    fun apply(
        rows: List<PhotoAsset>,
        filter: FilterOptions,
        sort: SortOrder,
        scopeSpansFolders: Boolean,
        now: Long = System.currentTimeMillis(),
    ): LibraryView {
        val query = filter.searchQuery.trim()
        val sorted = sort(rows.filter { matches(it, filter, query) }, sort, now)
        if (!scopeSpansFolders || !sort.groupsByFolder) return LibraryView(sorted, emptyList(), count(rows))

        // groupBy keeps first-seen order, so folders follow the sort and each appears once.
        val grouped = sorted.groupBy { it.relativeDir }
        val flat = ArrayList<PhotoAsset>(sorted.size)
        val sections = ArrayList<FolderSection>(grouped.size)
        grouped.forEach { (folder, photos) ->
            sections += FolderSection(folder, flat.size, photos.size)
            flat += photos
        }
        return LibraryView(flat, sections, count(rows))
    }

    /** Whether [photo] passes [filter]; for telling at once if an edit moves it out of view. */
    fun matches(photo: PhotoAsset, filter: FilterOptions, query: String = filter.searchQuery.trim()): Boolean {
        if (filter.flags.isNotEmpty() && photo.cullStatus !in filter.flags) return false
        if (!matchesRating(photo.rating, filter)) return false
        if (filter.colors.isNotEmpty() && photo.colorLabel !in filter.colors) return false
        if (filter.tags.isNotEmpty() && photo.tags.none { it in filter.tags }) return false
        if (filter.formatFilter != null && photo.format != filter.formatFilter) return false
        if (filter.pairFilter != null && photo.pairMode != filter.pairFilter) return false
        if (filter.syncErrorsOnly && photo.xmpSyncStatus != XmpSyncStatus.ERROR) return false
        return query.isEmpty() || matchesSearch(photo, query)
    }

    private fun matchesRating(rating: Int, filter: FilterOptions): Boolean = when (filter.ratingOperator) {
        RatingOperator.AT_LEAST -> rating >= filter.rating
        RatingOperator.EXACTLY -> rating == filter.rating
        RatingOperator.AT_MOST -> rating <= filter.rating
    }

    private fun matchesSearch(photo: PhotoAsset, query: String): Boolean =
        photo.fileName.contains(query, ignoreCase = true) ||
            photo.cameraModel?.contains(query, ignoreCase = true) == true ||
            photo.lensModel?.contains(query, ignoreCase = true) == true ||
            photo.tags.any { it.contains(query, ignoreCase = true) }

    private fun sort(rows: List<PhotoAsset>, sort: SortOrder, now: Long): List<PhotoAsset> = when (sort) {
        SortOrder.DATE_ASC -> sortByDate(rows, now, newestFirst = false)
        SortOrder.DATE_DESC -> sortByDate(rows, now, newestFirst = true)
        SortOrder.NAME_ASC -> rows.sortedBy { it.fileName }
        SortOrder.NAME_DESC -> rows.sortedByDescending { it.fileName }
        SortOrder.RATING_DESC -> rows.sortedByDescending { it.rating }
        SortOrder.SIZE_DESC -> rows.sortedByDescending { it.fileSizeBytes }
    }

    /** Undated photos go last in both directions: they have no place in the timeline. */
    private fun sortByDate(rows: List<PhotoAsset>, now: Long, newestFirst: Boolean): List<PhotoAsset> {
        val dated = rows.map { it to PlausibleDate.pick(it.dateTaken, it.fileLastModified, now) }
        val (known, undated) = dated.partition { it.second != null }
        val ordered = if (newestFirst) known.sortedByDescending { it.second } else known.sortedBy { it.second }
        return (ordered + undated).map { it.first }
    }

    private fun count(rows: List<PhotoAsset>): FilterCounts {
        val flags = HashMap<CullStatus, Int>()
        val ratings = HashMap<Int, Int>()
        val colors = HashMap<ColorLabel, Int>()
        val tags = HashMap<String, Int>()
        var paired = 0
        var saving = 0
        var syncErrors = 0
        rows.forEach { photo ->
            flags.merge(photo.cullStatus, 1, Int::plus)
            ratings.merge(photo.rating, 1, Int::plus)
            colors.merge(photo.colorLabel, 1, Int::plus)
            photo.tags.forEach { tags.merge(it, 1, Int::plus) }
            if (photo.hasCompanion) paired++
            when (photo.xmpSyncStatus) {
                XmpSyncStatus.PENDING, XmpSyncStatus.WRITING -> saving++
                XmpSyncStatus.ERROR -> syncErrors++
                else -> Unit
            }
        }
        return FilterCounts(rows.size, flags, ratings, colors, tags, paired, saving, syncErrors)
    }

    private val SortOrder.groupsByFolder: Boolean
        get() = this == SortOrder.DATE_ASC || this == SortOrder.DATE_DESC ||
            this == SortOrder.NAME_ASC || this == SortOrder.NAME_DESC
}
