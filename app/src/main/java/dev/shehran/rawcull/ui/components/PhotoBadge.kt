package dev.shehran.rawcull.ui.components

import dev.shehran.rawcull.data.model.PhotoAsset

/** Format badge shown on thumbnails: `ARW`, `JPG`, `HIF`, `ARW+JPG`, `ARW+HIF`, ... */
val PhotoAsset.badgeText: String
    get() = companionFormat?.let { "${format.extension}+${it.extension}" } ?: format.extension
