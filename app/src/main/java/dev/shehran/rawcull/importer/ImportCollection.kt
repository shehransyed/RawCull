package dev.shehran.rawcull.importer

/** D8: where an import's verified photos are also filed. */
sealed interface ImportCollection {
    data object None : ImportCollection

    data class Existing(val id: Long) : ImportCollection

    /** Made when the import starts, at the top level; a collection of that name already there is reused. */
    data class New(val name: String) : ImportCollection
}
