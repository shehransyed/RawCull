package dev.shehran.rawcull.data.xmp

import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.XmpSyncStatus
import dev.shehran.rawcull.util.xmp.CullState
import dev.shehran.rawcull.util.xmp.XmpDocument
import dev.shehran.rawcull.util.xmp.XmpField
import dev.shehran.rawcull.util.xmp.XmpMapper
import dev.shehran.rawcull.util.xmp.XmpPrefs
import java.util.Date

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

    /**
     * The sidecar to write next to an exported photo: the source sidecar, so develop settings
     * travel with the file, plus the current state. A row the scan hasn't read yet only contributes
     * the fields changed in RawCull, because its other values are still defaults.
     */
    fun exportBytes(row: PhotoAsset, source: ByteArray?, prefs: XmpPrefs, toolkit: String, now: Date): ByteArray {
        val existing = source?.let { XmpDocument.parse(it).getOrNull() }
        val doc = existing ?: XmpDocument.newEmpty(toolkit)
        val fields = if (existing == null || row.metadataLoaded) XmpField.ALL else XmpField.fromBits(row.xmpPendingFields)
        XmpMapper.apply(doc, CullState(row.rating, row.cullStatus, row.colorLabel, row.tags), prefs, fields, now)
        return doc.toBytes()
    }
}
