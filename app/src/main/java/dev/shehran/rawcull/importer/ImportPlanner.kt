package dev.shehran.rawcull.importer

import dev.shehran.rawcull.data.model.ImportItemRole
import dev.shehran.rawcull.data.model.PhotoFormat
import java.util.Locale

/** One file of a candidate. [format] is null for sidecars. */
data class CandidateFile(
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val role: ImportItemRole,
    val format: PhotoFormat? = null,
    val alreadyImported: Boolean = false, // Found in the duplicate index
)

/** An asset found on the source: its image files, its sidecars and what is known about it. */
data class ImportCandidate(
    val id: String,
    val stemGroup: String, // Assets of one folder and stem take a conflict suffix together
    val stem: String,
    val files: List<CandidateFile>,
    val captureDate: Long? = null,
    val fallbackDate: Long? = null, // File LAST_MODIFIED (D6)
    val metadataJson: String? = null,
) {
    val folderDate: Long? get() = captureDate ?: fallbackDate
}

data class ImportSelection(
    val selectedIds: Set<String>,
    val includeRaw: Boolean = true,
    val includeRaster: Boolean = true,
)

/** A file already in the library; [headHash] is only read when name and size both match. */
class ExistingTarget(val uri: String, val sizeBytes: Long, val headHash: () -> String?)

/** What's already in the target folders, by relative dir and file name. */
fun interface ExistingTargets {
    fun find(dir: String, name: String): ExistingTarget?
}

data class PlannedItem(
    val groupKey: String,
    val sourceUri: String,
    val sourceName: String,
    val sizeBytes: Long,
    val targetRelativeDir: String,
    val targetName: String,
    val role: ImportItemRole,
    val captureDate: Long,
    val skippedDuplicate: Boolean = false,
    val existingTargetUri: String? = null,
    val metadataJson: String? = null,
    val assetDiscriminator: String? = null,
)

/** Decides which files of a selection go where. Pure, so every rule is covered by plain tests. */
object ImportPlanner {

    /**
     * @param template the relative dir for a capture date (see [PathTemplate]).
     * @param sourceHead the head hash of a source file, read only when a same-named, same-sized
     * file exists in the target. Null means unreadable, which counts as different content.
     */
    fun plan(
        candidates: List<ImportCandidate>,
        selection: ImportSelection,
        template: (Long?) -> String,
        existingTargets: ExistingTargets,
        sourceHead: (CandidateFile) -> String? = { null },
    ): List<PlannedItem> {
        val namer = ConflictNamer()
        val groups = candidates
            .filter { it.id in selection.selectedIds }
            .mapNotNull { candidate -> applyFormats(candidate, selection) }
            .groupBy { it.candidate.stemGroup }
            .values
            .sortedBy { group -> group.minOf { it.candidate.folderDate ?: Long.MAX_VALUE } }

        return groups.flatMap { group -> planGroup(group, template, existingTargets, sourceHead, namer) }
    }

    private class Filtered(val candidate: ImportCandidate, val files: List<CandidateFile>)

    /** Drops the formats turned off. A kept companion becomes the primary; null if nothing is left. */
    private fun applyFormats(candidate: ImportCandidate, selection: ImportSelection): Filtered? {
        fun enabled(file: CandidateFile): Boolean {
            val format = file.format ?: return true
            return if (format.isRaw) selection.includeRaw else selection.includeRaster
        }

        val images = candidate.files.filter { !it.role.isSidecar && enabled(it) }
        if (images.isEmpty()) return null
        val imageNames = images.map { it.name.lowercase(Locale.ROOT) }.toSet()

        val sidecars = candidate.files.filter { it.role.isSidecar }.filter { sidecar ->
            when (sidecar.role) {
                // <name>.<ext>.xmp describes one file, so it can't outlive that file.
                ImportItemRole.DARKTABLE_SIDECAR ->
                    sidecar.name.lowercase(Locale.ROOT).removeSuffix(".xmp") in imageNames
                else -> true
            }
        }
        val roles = images.mapIndexed { index, image ->
            image.copy(role = if (index == 0) ImportItemRole.PRIMARY else ImportItemRole.COMPANION)
        }
        return Filtered(candidate, roles + sidecars)
    }

    private fun planGroup(
        group: List<Filtered>,
        template: (Long?) -> String,
        existing: ExistingTargets,
        sourceHead: (CandidateFile) -> String?,
        namer: ConflictNamer,
    ): List<PlannedItem> {
        // One folder for the group: its members can have different dates but must stay together.
        val folderDate = group.mapNotNull { it.candidate.folderDate }.minOrNull()
        val dir = template(folderDate)
        val stem = group.first().candidate.stem

        var attempt = 0
        while (true) {
            val outcomes = group.flatMap { member -> member.files.map { resolve(it, dir, stem, attempt, existing, sourceHead, namer) } }
            if (outcomes.none { it is Outcome.Conflict }) {
                var outcomeIndex = 0
                return group.flatMapIndexed { memberIndex, member ->
                    val primary = member.files.first()
                    val discriminator = if (memberIndex == 0) null else primary.name.substringAfterLast('.').lowercase(Locale.ROOT)
                    member.files.map { file ->
                        val outcome = outcomes[outcomeIndex++]
                        val name = ConflictNamer.suffixed(file.name, stem, attempt)
                        namer.reserve(dir, name)
                        PlannedItem(
                            groupKey = member.candidate.id,
                            sourceUri = file.uri,
                            sourceName = file.name,
                            sizeBytes = file.sizeBytes,
                            targetRelativeDir = dir,
                            targetName = name,
                            role = file.role,
                            captureDate = member.candidate.folderDate ?: 0L,
                            skippedDuplicate = outcome is Outcome.Skip,
                            existingTargetUri = (outcome as? Outcome.Skip)?.uri,
                            metadataJson = member.candidate.metadataJson.takeIf { file.role == ImportItemRole.PRIMARY },
                            assetDiscriminator = discriminator.takeIf { file.role == ImportItemRole.PRIMARY },
                        )
                    }
                }
            }
            attempt++
        }
    }

    private sealed interface Outcome {
        data object Free : Outcome
        data object Conflict : Outcome
        data class Skip(val uri: String) : Outcome
    }

    private fun resolve(
        file: CandidateFile,
        dir: String,
        stem: String,
        attempt: Int,
        existing: ExistingTargets,
        sourceHead: (CandidateFile) -> String?,
        namer: ConflictNamer,
    ): Outcome {
        val name = ConflictNamer.suffixed(file.name, stem, attempt)
        if (namer.isReserved(dir, name)) return Outcome.Conflict
        val found = existing.find(dir, name) ?: return Outcome.Free
        // The library's sidecar is the source of truth and RawCull rewrites it, so it never counts
        // as a conflict, and it is never replaced by the one on the card.
        if (file.role.isSidecar) return Outcome.Skip(found.uri)
        if (found.sizeBytes != file.sizeBytes) return Outcome.Conflict
        val head = sourceHead(file) ?: return Outcome.Conflict
        return if (head == found.headHash()) Outcome.Skip(found.uri) else Outcome.Conflict
    }
}
