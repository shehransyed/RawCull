package dev.shehran.rawcull.util.xmp

import dev.shehran.rawcull.data.model.GeoPoint
import java.util.Locale
import javax.xml.namespace.QName
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * A sidecar's `exif:GPS*` properties, in the form Lightroom writes: `DDD,MM.mmmmmmK` with the
 * hemisphere letter K, an altitude as a rational and `GPSAltitudeRef` 0 (above) or 1 (below).
 * Reading also takes `DDD,MM,SSK`, which XMP allows, and plain decimal degrees.
 */
object XmpGps {
    private val NS = XmpProps.NS_EXIF
    val VERSION_ID = QName(NS, "GPSVersionID", "exif")
    val LATITUDE = QName(NS, "GPSLatitude", "exif")
    val LONGITUDE = QName(NS, "GPSLongitude", "exif")
    val ALTITUDE = QName(NS, "GPSAltitude", "exif")
    val ALTITUDE_REF = QName(NS, "GPSAltitudeRef", "exif")
    private val ALL = listOf(VERSION_ID, LATITUDE, LONGITUDE, ALTITUDE, ALTITUDE_REF)

    /** The location the sidecar holds, or null if it has none or only half of one. */
    fun read(doc: XmpDocument): GeoPoint? {
        val latitude = coordinate(doc.valueOf(LATITUDE), "NS", 90.0) ?: return null
        val longitude = coordinate(doc.valueOf(LONGITUDE), "EW", 180.0) ?: return null
        val magnitude = number(doc.valueOf(ALTITUDE))
        val below = doc.valueOf(ALTITUDE_REF)?.trim()?.toDoubleOrNull() == 1.0
        val altitude = magnitude?.let { abs(it).let { metres -> if (below) -metres else metres } }
        return GeoPoint(latitude, longitude, altitude)
    }

    /** Replaces the sidecar's location with [point]; null removes it. Other `exif:` properties stay. */
    fun write(doc: XmpDocument, point: GeoPoint?) {
        ALL.forEach { doc.set(it, null) }
        if (point == null) return
        doc.set(VERSION_ID, "2.2.0.0")
        doc.set(LATITUDE, degreesMinutes(point.latitude, "N", "S"))
        doc.set(LONGITUDE, degreesMinutes(point.longitude, "E", "W"))
        point.altitude?.let {
            doc.set(ALTITUDE, "${(abs(it) * ALTITUDE_SCALE).roundToLong()}/$ALTITUDE_SCALE")
            doc.set(ALTITUDE_REF, if (it < 0) "1" else "0")
        }
    }

    internal fun degreesMinutes(value: Double, positive: String, negative: String): String {
        var degrees = floor(abs(value)).toInt()
        var minutes = (abs(value) - degrees) * 60.0
        // 59.9999996 prints as 60.000000, which isn't a valid minute.
        if (String.format(Locale.US, "%.6f", minutes).startsWith("60")) {
            degrees += 1
            minutes = 0.0
        }
        val letter = if (value < 0) negative else positive
        return String.format(Locale.US, "%d,%.6f%s", degrees, minutes, letter)
    }

    private fun coordinate(text: String?, letters: String, limit: Double): Double? {
        val match = COORDINATE.matchEntire(text?.trim() ?: return null) ?: return null
        val (sign, degrees, first, second, letter) = match.destructured
        val hemisphere = letter.uppercase()
        if (hemisphere.isNotEmpty() && hemisphere !in letters.map { it.toString() }) return null
        val minutes = if (first.isEmpty()) 0.0 else first.toDouble()
        val seconds = if (second.isEmpty()) 0.0 else second.toDouble()
        if (minutes >= 60.0 || seconds >= 60.0) return null
        val magnitude = degrees.toDouble() + minutes / 60.0 + seconds / 3600.0
        val negative = sign == "-" || hemisphere == letters.substring(1)
        return (if (negative) -magnitude else magnitude).takeIf { abs(it) <= limit }
    }

    /** `12345/100` or `123.45`. */
    private fun number(text: String?): Double? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val slash = value.indexOf('/')
        if (slash < 0) return value.toDoubleOrNull()
        val numerator = value.substring(0, slash).trim().toDoubleOrNull() ?: return null
        val denominator = value.substring(slash + 1).trim().toDoubleOrNull() ?: return null
        return if (denominator == 0.0) null else numerator / denominator
    }

    private const val ALTITUDE_SCALE = 100L
    private val COORDINATE = Regex(
        """(-?)(\d+(?:\.\d+)?)(?:[,:]\s*(\d+(?:\.\d+)?))?(?:[,:]\s*(\d+(?:\.\d+)?))?\s*([NSEWnsew]?)""",
    )
}
