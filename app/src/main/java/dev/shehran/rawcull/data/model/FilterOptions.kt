package dev.shehran.rawcull.data.model

enum class RatingOperator(val symbol: String) {
    AT_LEAST("≥"),
    EXACTLY("="),
    AT_MOST("≤"),
}

/** An empty set means that dimension isn't filtered. */
data class FilterOptions(
    val flags: Set<CullStatus> = emptySet(),
    val ratingOperator: RatingOperator = RatingOperator.AT_LEAST,
    val rating: Int = 0,
    val colors: Set<ColorLabel> = emptySet(), // ColorLabel.NONE is "No label"
    val tags: Set<String> = emptySet(),
    val formatFilter: PhotoFormat? = null,
    val pairFilter: PairMode? = null,
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.DATE_ASC,
    val syncErrorsOnly: Boolean = false,
) {
    val filtersRating: Boolean get() = ratingOperator != RatingOperator.AT_LEAST || rating > 0

    /** Whether anything can hide a photo. Sorting can't. */
    val isActive: Boolean
        get() = flags.isNotEmpty() || filtersRating || colors.isNotEmpty() || tags.isNotEmpty() ||
            formatFilter != null || pairFilter != null || searchQuery.isNotBlank() || syncErrorsOnly

    /** The same sort with nothing filtered. */
    fun cleared(): FilterOptions = FilterOptions(sortOrder = sortOrder)
}

enum class SortOrder(val label: String) {
    DATE_ASC("Capture Time (Oldest)"),
    DATE_DESC("Capture Time (Newest)"),
    NAME_ASC("File Name (A-Z)"),
    NAME_DESC("File Name (Z-A)"),
    RATING_DESC("Rating (Highest)"),
    SIZE_DESC("File Size (Largest)")
}
