package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.GeoPoint

/**
 * A row's location as read from its files (R8 D4): the sidecar's first, else the file's own EXIF.
 * Import and enrichment both use it, so a photo gets the same location either way. The camera's
 * location only ever lands in the catalog; it's never written to a sidecar.
 *
 * @property point what the row shows, or null.
 * @property gpsInFile the file's EXIF has a location, which other apps will see whatever RawCull does.
 */
data class FileLocation(val point: GeoPoint?, val gpsInFile: Boolean) {
    companion object {
        fun pick(sidecar: GeoPoint?, exif: GeoPoint?) = FileLocation(point = sidecar ?: exif, gpsInFile = exif != null)
    }
}
