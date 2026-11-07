package dev.shehran.rawcull.data.preview

import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.importer.ImportCandidate
import dev.shehran.rawcull.data.model.ImportItemRole
import java.security.MessageDigest

/** D6: a file's identity for the cache. Any change to what's behind a photo makes a new key. */
object PreviewKeys {
    private const val SEPARATOR = "\n"

    fun fileKey(
        primaryUri: String,
        primarySize: Long,
        primaryModified: Long,
        companionUri: String?,
        companionSize: Long?,
    ): String {
        val identity = listOf(primaryUri, primarySize, primaryModified, companionUri.orEmpty(), companionSize ?: 0L)
            .joinToString(SEPARATOR)
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** The library's rows don't record the companion's modification time, so its size stands in. */
    fun source(photo: PhotoAsset): PreviewSource = PreviewSource(
        primaryUri = photo.fileUri,
        format = photo.format,
        companionUri = photo.companionFileUri,
        key = fileKey(
            photo.fileUri,
            photo.fileSizeBytes,
            photo.fileLastModified,
            photo.companionFileUri,
            photo.companionFileSizeBytes,
        ),
        companionFormat = photo.companionFormat,
    )

    /** A card entry, the same way. */
    fun source(candidate: ImportCandidate): PreviewSource {
        val primary = candidate.files.first { it.role == ImportItemRole.PRIMARY }
        val companion = candidate.files.firstOrNull { it.role == ImportItemRole.COMPANION }
        return PreviewSource(
            primaryUri = primary.uri,
            format = primary.format ?: PhotoFormat.RAW_OTHER,
            companionUri = companion?.uri,
            key = fileKey(primary.uri, primary.sizeBytes, candidate.fallbackDate ?: 0L, companion?.uri, companion?.sizeBytes),
            companionFormat = companion?.format,
        )
    }
}
