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
