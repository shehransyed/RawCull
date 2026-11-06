package dev.shehran.rawcull.data.model

/** The columns phase-B enrichment owns. Everything else on the row belongs to the user or the scan. */
data class EnrichedMetadata(
    val id: String,
    val dateTaken: Long,
    val width: Int,
    val height: Int,
    val cameraMake: String?,
    val cameraModel: String?,
    val lensModel: String?,
    val shutterSpeed: String?,
    val aperture: String?,
    val iso: String?,
    val focalLength: String?,
    val exposureBias: String?,
    val orientation: Int,
    val metadataLoaded: Boolean = true,
)

/** What a sidecar changed outside RawCull says. A null [cullStatus] means it has no opinion. */
data class EnrichedXmp(
    val rating: Int,
    val cullStatus: CullStatus?,
    val colorLabel: ColorLabel,
    val tagsJson: String,
    val lastModified: Long,
)

data class EnrichmentResult(
    val metadata: EnrichedMetadata,
    val xmp: EnrichedXmp?,
    val sidecarUnreadable: Boolean = false,
)
