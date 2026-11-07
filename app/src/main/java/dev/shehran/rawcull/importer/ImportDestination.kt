package dev.shehran.rawcull.importer

import dev.shehran.rawcull.data.model.ImportPreset
import dev.shehran.rawcull.data.model.LibraryLocation
import java.util.TimeZone

/** Where an import is coming from. [volumeUuid] is set for detected cards. */
data class ImportSource(val treeUri: String, val label: String, val volumeUuid: String? = null)

/** What the destination panel needs to say about a preset being edited. */
object ImportDestination {

    /** The relative folder a photo of [captureDate] would land in under [preset]. */
    fun relativeDir(preset: ImportPreset, captureDate: Long?, timeZone: TimeZone = TimeZone.getDefault()): String =
        PathTemplate.resolve(
            preset.structureMode,
            preset.datePattern,
            preset.customPattern,
            preset.projectName,
            captureDate,
            timeZone,
        )

    /** `Photos/2026/2026-11-06/`, as shown in the live preview. */
    fun previewPath(location: LibraryLocation?, preset: ImportPreset, captureDate: Long?, timeZone: TimeZone = TimeZone.getDefault()): String {
        val folder = relativeDir(preset, captureDate, timeZone)
        val root = location?.displayName ?: "(no location)"
        return if (folder.isEmpty()) "$root/" else "$root/$folder/"
    }

    /** The Location to start from: the preset's own, then the Library's, then any that can be written to. */
    fun defaultLocationId(preset: ImportPreset?, locations: List<LibraryLocation>, libraryLocationId: Long?): Long? {
        val usable = locations.filterNot { it.isDemo }
        return usable.firstOrNull { it.id == preset?.locationId }?.id
            ?: usable.firstOrNull { it.id == libraryLocationId }?.id
            ?: usable.maxByOrNull { it.lastOpenedAt }?.id
    }
}
