package dev.shehran.rawcull.ui.components

import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat

/** Format badge shown on thumbnails: `ARW`, `JPG`, `HIF`, `ARW+JPG`, `ARW+HIF`, ... */
val PhotoAsset.badgeText: String
    get() = companionFormat?.let { "${format.extension}+${it.extension}" } ?: format.extension

/** Label for the paired-files chip, or null when nothing is paired. */
fun pairChipLabel(companions: Set<PhotoFormat>): String? = when (companions.size) {
    0 -> null
    1 -> "RAW+${companions.single().extension}"
    else -> "RAW+Raster"
}

sealed interface GridRow {
    val key: String

    data class Header(val title: String, override val key: String) : GridRow
    data class Photo(val photo: PhotoAsset, val index: Int) : GridRow {
        override val key: String get() = photo.id
    }
}

/**
 * Grid content with a header at each change of folder, so the active sort order is kept. Sources
 * with a single folder get no headers.
 */
fun buildGridRows(photos: List<PhotoAsset>): List<GridRow> {
    val showHeaders = photos.map { it.relativeDir }.distinct().size > 1
    val rows = ArrayList<GridRow>(photos.size + 8)
    var currentDir: String? = null
    photos.forEachIndexed { index, photo ->
        if (showHeaders && photo.relativeDir != currentDir) {
            currentDir = photo.relativeDir
            rows += GridRow.Header(photo.relativeDir.ifEmpty { "Top level" }, key = "header_$index")
        }
        rows += GridRow.Photo(photo, index)
    }
    return rows
}
