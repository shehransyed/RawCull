package dev.shehran.rawcull.data.scan

import dev.shehran.rawcull.data.model.PhotoAsset

data class MergeResult(
    /** New rows and rows that changed; rows identical to what's stored are left out. */
    val toUpsert: List<PhotoAsset>,
    val toDelete: List<String>,
    /** Sidecar modification times by asset id, for the XMP-vs-row check during enrichment. */
    val sidecarLastModified: Map<String, Long>,
)

/** Reconciles a fresh listing with the stored catalog, keeping user state on existing rows. */
object ScanMerger {

    fun merge(rootUri: String, existing: Map<String, PhotoAsset>, drafts: List<AssetDraft>): MergeResult {
        val toUpsert = ArrayList<PhotoAsset>()
        val sidecarLastModified = HashMap<String, Long>()
        val seenIds = HashSet<String>(drafts.size * 2)

        for (draft in drafts) {
            val id = draft.idIn(rootUri)
            seenIds += id
            draft.sidecar?.let { sidecarLastModified[id] = it.lastModified }

            val stored = existing[id]
            val merged = if (stored == null) newAsset(id, rootUri, draft) else refresh(stored, draft)
            if (merged != stored) toUpsert += merged
        }
        return MergeResult(toUpsert, existing.keys.filterNot { it in seenIds }, sidecarLastModified)
    }

    /** lastModified = 0 marks the row as never changed, so any sidecar on disk wins over it. */
    private fun newAsset(id: String, rootUri: String, draft: AssetDraft): PhotoAsset = PhotoAsset(
        id = id,
        rootUri = rootUri,
        relativeDir = draft.primary.relativeDir,
        parentDocUri = draft.primary.parentDocUri,
        fileName = draft.primary.name,
        fileUri = draft.primary.docUri,
        format = draft.format,
        fileSizeBytes = draft.primary.size,
        fileLastModified = draft.primary.lastModified,
        dateTaken = draft.primary.lastModified,
        metadataLoaded = false,
        lastModified = 0L,
        companionFileName = draft.companion?.name,
        companionFileUri = draft.companion?.docUri,
        companionFileSizeBytes = draft.companion?.size,
        companionFormat = draft.companionFormat,
        xmpFileUri = draft.sidecar?.docUri,
        pairMode = draft.pairMode,
    )

    private fun refresh(stored: PhotoAsset, draft: AssetDraft): PhotoAsset {
        val fileChanged = stored.fileLastModified != draft.primary.lastModified
        val sidecarNewer = (draft.sidecar?.lastModified ?: 0L) > stored.lastModified
        return stored.copy(
            relativeDir = draft.primary.relativeDir,
            parentDocUri = draft.primary.parentDocUri,
            fileName = draft.primary.name,
            fileUri = draft.primary.docUri,
            format = draft.format,
            fileSizeBytes = draft.primary.size,
            fileLastModified = draft.primary.lastModified,
            dateTaken = if (fileChanged) draft.primary.lastModified else stored.dateTaken,
            metadataLoaded = stored.metadataLoaded && !fileChanged && !sidecarNewer,
            companionFileName = draft.companion?.name,
            companionFileUri = draft.companion?.docUri,
            companionFileSizeBytes = draft.companion?.size,
            companionFormat = draft.companionFormat,
            xmpFileUri = draft.sidecar?.docUri,
            pairMode = draft.pairMode,
        )
    }
}
