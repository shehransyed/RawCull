package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.location

/** What the full window's edit badge says after a photo changed (R6 D3). Pure, so it can be tested. */
object EditBadgeText {
    /** The words for the first thing that differs, or null when flag, rating and label are all the same. */
    fun between(before: PhotoAsset, after: PhotoAsset): String? {
        if (before.id != after.id) return null
        return when {
            before.cullStatus != after.cullStatus -> when (after.cullStatus) {
                CullStatus.PICK -> "Pick"
                CullStatus.REJECT -> "Reject"
                CullStatus.UNFLAGGED -> "Unflagged"
            }
            before.rating != after.rating -> if (after.rating == 0) "No rating" else "${after.rating} ★"
            before.colorLabel != after.colorLabel -> when (after.colorLabel) {
                ColorLabel.NONE -> "No label"
                else -> after.colorLabel.name.lowercase().replaceFirstChar { it.uppercase() } + " label"
            }
            before.location != after.location -> if (after.location == null) "Location cleared" else "Location set"
            else -> null
        }
    }
}
