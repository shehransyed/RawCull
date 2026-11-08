package dev.shehran.rawcull.data.model

/** A place on Earth: degrees (south and west negative) and, when known, metres above sea level. */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
) {
    companion object {
        /** Builds a point from the three nullable columns, or null if there is no position. */
        fun ofOrNull(latitude: Double?, longitude: Double?, altitude: Double?): GeoPoint? =
            if (latitude == null || longitude == null) null else GeoPoint(latitude, longitude, altitude)
    }
}

/** The row's location, if it has one. */
val PhotoAsset.location: GeoPoint?
    get() = GeoPoint.ofOrNull(gpsLatitude, gpsLongitude, gpsAltitude)
