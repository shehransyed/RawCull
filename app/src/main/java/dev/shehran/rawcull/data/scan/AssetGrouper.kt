package dev.shehran.rawcull.data.scan

import dev.shehran.rawcull.data.model.AssetIds
import dev.shehran.rawcull.data.model.PairMode
import dev.shehran.rawcull.data.model.PhotoFormat
import java.util.Locale

data class AssetDraft(
    val primary: ScannedEntry,
    val format: PhotoFormat,
    val companion: ScannedEntry? = null,
    val companionFormat: PhotoFormat? = null,
    val pairMode: PairMode,
    val adobeXmp: ScannedEntry? = null,
    val darktableXmp: ScannedEntry? = null,
    /** Set on every asset after the first in a stem group; see [AssetIds]. */
    val discriminator: String? = null,
) {
    val stem: String
        get() = primary.name.substringBeforeLast('.')

    val sidecar: ScannedEntry?
        get() = adobeXmp ?: darktableXmp

    fun idIn(rootUri: String): String = AssetIds.of(rootUri, primary.relativeDir, stem, discriminator)
}

data class GroupingResult(
    val assets: List<AssetDraft>,
    val orphanSidecars: List<ScannedEntry>,
)

/** Turns a flat file listing into assets: RAW + raster pairs, standalone files and their sidecars. */
object AssetGrouper {

    private data class Candidate(val entry: ScannedEntry, val format: PhotoFormat)

    fun group(entries: List<ScannedEntry>): GroupingResult {
        // Dot files include macOS "._DSC001.ARW" AppleDouble stubs, which aren't photos.
        val visible = entries.filterNot { it.name.startsWith(".") }
        val sidecars = visible.filter { it.name.lowercase(Locale.ROOT).endsWith(XMP_SUFFIX) }
        val sidecarsByName = sidecars.associateBy { sidecarKey(it.parentDocId, it.name) }

        val groups = LinkedHashMap<Pair<String, String>, MutableList<Candidate>>()
        for (entry in visible) {
            val format = PhotoFormat.fromFileNameOrNull(entry.name) ?: continue
            val stem = entry.name.substringBeforeLast('.').lowercase(Locale.ROOT)
            groups.getOrPut(entry.parentDocId to stem) { mutableListOf() } += Candidate(entry, format)
        }

        val assets = groups.values.flatMap { buildAssets(it, sidecarsByName) }
        val claimed = assets.flatMap { listOfNotNull(it.adobeXmp, it.darktableXmp) }.map { it.docId }.toSet()
        return GroupingResult(assets, sidecars.filterNot { it.docId in claimed })
    }

    private fun buildAssets(
        group: List<Candidate>,
        sidecars: Map<String, ScannedEntry>,
    ): List<AssetDraft> {
        val raws = group.filter { it.format.isRaw }.sortedBy { it.format.ordinal }
        val rasters = group.filterNot { it.format.isRaw }.sortedBy { it.format.ordinal }

        val companion = rasters.firstOrNull().takeIf { raws.isNotEmpty() }
        val standalones = raws + rasters.filterNot { it === companion }

        return standalones.mapIndexed { index, candidate ->
            val isFirst = index == 0
            val entry = candidate.entry
            val paired = isFirst && companion != null
            AssetDraft(
                primary = entry,
                format = candidate.format,
                companion = companion?.entry.takeIf { paired },
                companionFormat = companion?.format.takeIf { paired },
                pairMode = when {
                    paired -> PairMode.RAW_AND_RASTER
                    candidate.format.isRaw -> PairMode.RAW_ONLY
                    else -> PairMode.RASTER_ONLY
                },
                // <stem>.xmp can only belong to one asset, so it goes to the first of the group.
                adobeXmp = if (isFirst) {
                    sidecars[sidecarKey(entry.parentDocId, entry.name.substringBeforeLast('.') + XMP_SUFFIX)]
                } else {
                    null
                },
                darktableXmp = sidecars[sidecarKey(entry.parentDocId, entry.name + XMP_SUFFIX)],
                discriminator = if (isFirst) null else entry.name.substringAfterLast('.').lowercase(Locale.ROOT),
            )
        }
    }

    private fun sidecarKey(parentDocId: String, name: String): String =
        "$parentDocId/${name.lowercase(Locale.ROOT)}"

    private const val XMP_SUFFIX = ".xmp"
}
