package dev.shehran.rawcull.data.xmp

import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.XmpSyncStatus

enum class SidecarAction {
    /** The row already matches the file. */
    NOTHING,

    /** The row has changes of its own, which the queue merges into the file. */
    KEEP_CATALOG,

    /** The file changed outside RawCull, so it wins. */
    READ_FILE,
}

/** Decides between catalog and sidecar. */
object SidecarReconciler {

    fun actionFor(row: PhotoAsset, fileMtime: Long?): SidecarAction = when {
        row.xmpSyncStatus == XmpSyncStatus.PENDING ||
            row.xmpSyncStatus == XmpSyncStatus.WRITING ||
            row.xmpPendingFields != 0 -> SidecarAction.KEEP_CATALOG
        fileMtime != null && fileMtime == row.xmpLastSyncedMtime -> SidecarAction.NOTHING
        else -> SidecarAction.READ_FILE
    }
}
