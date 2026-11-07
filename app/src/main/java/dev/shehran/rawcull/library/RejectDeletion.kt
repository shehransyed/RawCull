package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.PhotoAsset
import java.util.Locale

/**
 * One file of a photo.
 *
 * @param mediaUri where MediaStore knows it, which is what makes it trashable. Null means it can
 * only be deleted permanently.
 */
data class DeletionFile(
    val photoId: String,
    val documentUri: String,
    val sizeBytes: Long,
    val isPrimary: Boolean,
    val mediaUri: String? = null,
)

data class DeletionPlan(val photoIds: List<String>, val files: List<DeletionFile>) {
    val photoCount: Int get() = photoIds.size
    val fileCount: Int get() = files.size
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
    val toTrash: List<DeletionFile> get() = files.filter { it.mediaUri != null }
    val toDelete: List<DeletionFile> get() = files.filter { it.mediaUri == null }

    /** This plan without the groups of [ids], whose files are all left alone. */
    fun withoutPhotos(ids: Set<String>) = DeletionPlan(photoIds - ids, files.filterNot { it.photoId in ids })

    /** Nothing can come back afterwards, so the warning has to say so. */
    val isAllPermanent: Boolean get() = files.isNotEmpty() && toTrash.isEmpty()
}

/** Which photos really went, once the permanent part has run. */
data class DeletionOutcome(val removedIds: List<String>, val failedFiles: Int)

object RejectDeletion {
    /** MediaStore.createTrashRequest arrived in Android 11. */
    const val FIRST_TRASH_API = 30

    /** Primary, companion and both sidecar styles: a group is never half deleted. */
    fun filesOf(row: PhotoAsset): List<DeletionFile> = buildList {
        add(DeletionFile(row.id, row.fileUri, row.fileSizeBytes, isPrimary = true))
        row.companionFileUri?.takeIf { it.isNotBlank() }?.let {
            add(DeletionFile(row.id, it, row.companionFileSizeBytes ?: 0L, isPrimary = false))
        }
        // The catalog doesn't keep a sidecar's size, and next to a RAW it's nothing.
        listOfNotNull(row.xmpFileUri, row.darktableXmpUri).distinct().forEach {
            add(DeletionFile(row.id, it, 0L, isPrimary = false))
        }
    }

    /** @param resolveMediaUri asked once per file; null if MediaStore doesn't have it. */
    fun plan(rows: List<PhotoAsset>, apiLevel: Int, resolveMediaUri: (String) -> String?): DeletionPlan {
        val canTrash = apiLevel >= FIRST_TRASH_API
        val files = rows.flatMap(::filesOf).map { file ->
            if (canTrash) file.copy(mediaUri = resolveMediaUri(file.documentUri)) else file
        }
        return DeletionPlan(rows.map { it.id }, files)
    }

    /** The demo has rows but no files of its own to touch. */
    fun rowsOnly(rows: List<PhotoAsset>) = DeletionPlan(rows.map { it.id }, emptyList())

    /**
     * A row goes once its primary file has: trashed or deleted, it no longer matches the disk. A
     * photo whose primary couldn't be deleted stays listed, because it's still there.
     */
    fun outcome(plan: DeletionPlan, failedUris: Set<String>): DeletionOutcome {
        val keptIds = plan.files.filter { it.isPrimary && it.documentUri in failedUris }.map { it.photoId }.toSet()
        return DeletionOutcome(plan.photoIds.filterNot { it in keptIds }, failedUris.size)
    }

    fun title(plan: DeletionPlan): String =
        if (plan.photoCount == 1) "Delete 1 rejected photo?" else "Delete ${plan.photoCount} rejected photos?"

    fun detail(plan: DeletionPlan): String {
        if (plan.files.isEmpty()) return "They'll be removed from the demo library."
        val summary = "${fileCount(plan.fileCount)} · ${size(plan.totalBytes)}."
        if (plan.isAllPermanent) return "$summary This can't be undone."
        val trashCount = plan.toTrash.size
        val trash = "$trashCount ${if (trashCount == 1) "goes" else "go"} to the device trash (restorable for 30 days)."
        val permanent = plan.toDelete.size
        return if (permanent == 0) "$summary $trash" else "$summary $trash $permanent will be deleted permanently."
    }

    /** The one folder that holds them all, so that a single rescan can bring them back. */
    fun commonFolder(folders: Collection<String>): String {
        val paths = folders.map { it.split('/') }
        val first = paths.firstOrNull() ?: return ""
        val shared = first.indices.takeWhile { index -> paths.all { it.getOrNull(index) == first[index] } }.size
        return first.take(shared).joinToString("/")
    }

    private fun fileCount(count: Int) = if (count == 1) "1 file" else "$count files"

    private fun size(bytes: Long): String = when {
        bytes >= GIGABYTE -> String.format(Locale.ROOT, "%.1f GB", bytes / GIGABYTE.toDouble())
        bytes >= MEGABYTE -> String.format(Locale.ROOT, "%.1f MB", bytes / MEGABYTE.toDouble())
        else -> "${bytes / KILOBYTE} KB"
    }

    private const val KILOBYTE = 1024L
    private const val MEGABYTE = KILOBYTE * 1024
    private const val GIGABYTE = MEGABYTE * 1024
}
