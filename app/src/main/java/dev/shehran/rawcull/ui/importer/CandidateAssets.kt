package dev.shehran.rawcull.ui.importer

import dev.shehran.rawcull.data.importer.ImportMetadataCodec
import dev.shehran.rawcull.data.model.ImportItemRole
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.importer.ImportCandidate

/**
 * A throwaway row, so the preview can reuse the thumbnail loader and loupe. It never reaches the
 * catalog: the card is a source, not a library.
 */
internal fun ImportCandidate.toPreviewAsset(): PhotoAsset {
    val primary = files.first { it.role == ImportItemRole.PRIMARY }
    val companion = files.firstOrNull { it.role == ImportItemRole.COMPANION }
    return PhotoAsset(
        id = id,
        rootUri = "",
        fileName = primary.name,
        fileUri = primary.uri,
        format = primary.format ?: PhotoFormat.RAW_OTHER,
        fileSizeBytes = primary.sizeBytes,
        fileLastModified = fallbackDate ?: 0L,
        dateTaken = folderDate ?: 0L,
        companionFileName = companion?.name,
        companionFileUri = companion?.uri,
        companionFileSizeBytes = companion?.sizeBytes,
        companionFormat = companion?.format,
    )
}

internal fun ImportCandidate.cameraName(): String? {
    val metadata = ImportMetadataCodec.decode(metadataJson) ?: return null
    return listOfNotNull(metadata.cameraMake, metadata.cameraModel).joinToString(" ").ifBlank { null }
}
