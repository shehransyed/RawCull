package dev.shehran.rawcull.data.model

data class FilterOptions(
    val cullFilter: CullFilterMode = CullFilterMode.ALL,
    val minRating: Int = 0, // 0 = all, 1..5
    val exactRating: Int? = null,
    val colorFilter: ColorLabel? = null,
    val formatFilter: PhotoFormat? = null,
    val pairFilter: PairMode? = null,
    val tagFilter: String? = null,
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.DATE_ASC,
    val syncErrorsOnly: Boolean = false
)

enum class CullFilterMode(val label: String) {
    ALL("All Photos"),
    PICKS_ONLY("Picks Only"),
    UNFLAGGED_ONLY("Unflagged"),
    REJECTS_ONLY("Rejects"),
    PICKS_AND_UNFLAGGED("Picks & Unflagged")
}

enum class SortOrder(val label: String) {
    DATE_ASC("Capture Time (Oldest)"),
    DATE_DESC("Capture Time (Newest)"),
    NAME_ASC("File Name (A-Z)"),
    NAME_DESC("File Name (Z-A)"),
    RATING_DESC("Rating (Highest)"),
    SIZE_DESC("File Size (Largest)")
}
