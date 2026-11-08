package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.GeoPoint
import java.net.URLDecoder
import java.util.Locale
import kotlin.math.abs

/** What [GeoInput.parse] made of a piece of text. */
sealed interface GeoParse {
    data class Ok(val point: GeoPoint) : GeoParse
    data class Invalid(val reason: String) : GeoParse
}

/**
 * Turns whatever a person pastes (map links, `geo:` URIs, decimal, DMS or DMM text) into a
 * [GeoPoint], or says why it can't. Pure: nothing is fetched, so short links are refused.
 * Latitude and longitude are never swapped by guessing; only N/S/E/W letters can reorder them.
 */
object GeoInput {
    const val SHORT_LINK = "Open the link and copy the full address or the coordinates."
    private const val NOTHING = "Enter coordinates or paste a map link."
    private const val NOT_COORDINATES = "Couldn't read that as coordinates."

    fun parse(input: String): GeoParse {
        val text = input.trim()
        if (text.isEmpty()) return GeoParse.Invalid(NOTHING)
        if (SHORT_LINKS.containsMatchIn(text)) return GeoParse.Invalid(SHORT_LINK)
        return when {
            text.startsWith("geo:", ignoreCase = true) -> parseGeoUri(text)
            "://" in text -> parseLink(text)
            else -> parseText(text)
        }
    }

    /** `40.44619° N, 79.98222° W`, the form the Location card shows. */
    fun format(point: GeoPoint): String =
        "${axis(point.latitude, "N", "S")}, ${axis(point.longitude, "E", "W")}"

    /** `40.44619, -79.98222`, which every map app accepts. */
    fun plain(point: GeoPoint): String =
        String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude)

    /** `120 m`, or null when the point has no altitude. */
    fun formatAltitude(point: GeoPoint): String? =
        point.altitude?.let { String.format(Locale.US, "%.0f m", it) }

    private fun axis(value: Double, positive: String, negative: String) =
        String.format(Locale.US, "%.5f° %s", abs(value), if (value < 0) negative else positive)

    // --- Links -------------------------------------------------------------------------------

    private fun parseGeoUri(text: String): GeoParse {
        val body = text.substring(4)
        val path = body.substringBefore('?').substringBefore(';')
        val query = body.substringAfter('?', "")
        val parts = path.split(',').map { it.trim() }
        val lat = parts.getOrNull(0)?.toDoubleOrNull()
        val lon = parts.getOrNull(1)?.toDoubleOrNull()
        val alt = parts.getOrNull(2)?.toDoubleOrNull()
        val q = QUERY_PARAM.findAll("?$query").firstOrNull { it.groupValues[1] == "q" }?.groupValues?.get(2)
        // geo:0,0?q=lat,lon is how a place that has no name is shared.
        if ((lat == null || lon == null || (lat == 0.0 && lon == 0.0)) && q != null) {
            return decimalPair(decode(q).substringBefore('('))?.let(::withRangeCheck) ?: GeoParse.Invalid(NO_LINK_POSITION)
        }
        if (lat == null || lon == null) return GeoParse.Invalid(NOT_COORDINATES)
        return withRangeCheck(Candidate(lat, lon, alt))
    }

    private fun parseLink(raw: String): GeoParse {
        val text = decode(raw)
        val candidates = ArrayList<Candidate>()
        fun add(candidate: Candidate?) { if (candidate != null) candidates += candidate }

        // OpenStreetMap: ?mlat=..&mlon=.. and #map=zoom/lat/lon
        val mlat = QUERY_PARAM.findAll(text).firstOrNull { it.groupValues[1] == "mlat" }?.groupValues?.get(2)?.toDoubleOrNull()
        val mlon = QUERY_PARAM.findAll(text).firstOrNull { it.groupValues[1] == "mlon" }?.groupValues?.get(2)?.toDoubleOrNull()
        if (mlat != null && mlon != null) add(Candidate(mlat, mlon))
        // Google's pin (!3d..!4d..) is the place; @lat,lon is only where the map is centred.
        DATA_PIN.find(text)?.let { add(Candidate(it.groupValues[1].toDouble(), it.groupValues[2].toDouble())) }
        QUERY_PARAM.findAll(text)
            .filter { it.groupValues[1] in PAIR_PARAMS }
            .forEach { add(decimalPair(it.groupValues[2].substringBefore('('))) }
        OSM_MAP.find(text)?.let { add(Candidate(it.groupValues[1].toDouble(), it.groupValues[2].toDouble())) }
        PLACE_PATH.find(text)?.let { add(decimalPair(it.groupValues[1])) }
        AT_VIEW.find(text)?.let { add(Candidate(it.groupValues[1].toDouble(), it.groupValues[2].toDouble())) }

        candidates.firstOrNull { it.inRange }?.let { return GeoParse.Ok(GeoPoint(it.lat, it.lon, it.alt)) }
        candidates.firstOrNull()?.let { return withRangeCheck(it) }
        return GeoParse.Invalid(NO_LINK_POSITION)
    }

    private const val NO_LINK_POSITION = "No coordinates in that link. Open the place and copy its coordinates."
    private val SHORT_LINKS = Regex("""(?i)(maps\.app\.goo\.gl|goo\.gl/maps|g\.co/kgs)""")
    private val QUERY_PARAM = Regex("""[?&#;](\w+)=([^&#]*)""")
    private val PAIR_PARAMS = setOf("ll", "q", "query", "center")
    private val DATA_PIN = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")
    private val AT_VIEW = Regex("""@(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)""")
    private val OSM_MAP = Regex("""#map=\d+(?:\.\d+)?/(-?\d+(?:\.\d+)?)/(-?\d+(?:\.\d+)?)""")
    private val PLACE_PATH = Regex("""/(?:place|search|dir)/(-?\d+(?:\.\d+)?,\s*\+?-?\d+(?:\.\d+)?)""")

    private fun decode(text: String): String = try {
        URLDecoder.decode(text, "UTF-8")
    } catch (_: IllegalArgumentException) {
        text
    }

    // --- Typed text --------------------------------------------------------------------------

    private class Candidate(val lat: Double, val lon: Double, val alt: Double? = null) {
        val inRange get() = abs(lat) <= 90.0 && abs(lon) <= 180.0
    }

    private fun withRangeCheck(candidate: Candidate): GeoParse = when {
        abs(candidate.lat) > 90.0 -> GeoParse.Invalid("Latitude must be between -90 and 90.")
        abs(candidate.lon) > 180.0 -> GeoParse.Invalid("Longitude must be between -180 and 180.")
        else -> GeoParse.Ok(GeoPoint(candidate.lat, candidate.lon, candidate.alt))
    }

    /** `lat,lon` of two plain numbers, as a link's query holds them; anything else (a place name) is null. */
    private fun decimalPair(text: String): Candidate? {
        val match = DECIMAL_PAIR.matchEntire(text.trim()) ?: return null
        return Candidate(match.groupValues[1].toDouble(), match.groupValues[2].toDouble())
    }

    private val DECIMAL_PAIR = Regex("""\+?(-?\d+(?:\.\d+)?)\s*[, ]\s*\+?(-?\d+(?:\.\d+)?)""")
    private val ALTITUDE_SUFFIX = Regex("""(?i)[,;\s]\s*([+-]?\d+(?:\.\d+)?)\s*(?:m|meters?|metres?)\s*$""")
    private const val DEG = "[°º˚]"

    private fun component(prefixLetter: Boolean): Regex {
        val before = if (prefixLetter) """(?:(?<h>[NSEWnsew])\s*)""" else ""
        val after = if (prefixLetter) "" else """\s*(?<h>[NSEWnsew])?"""
        return Regex(
            before + """(?<d>[+-]?\d+(?:\.\d+)?)\s*(?:$DEG|d(?![a-z]))?\s*""" +
                """(?:(?<m>\d+(?:\.\d+)?)\s*['′]\s*(?:(?<s>\d+(?:\.\d+)?)\s*(?:["″]|''|′′))?)?""" + after,
        )
    }

    private class Part(val value: Double, val letter: Char?, val negativeSign: Boolean, val plain: Boolean)

    private fun parseText(input: String): GeoParse {
        var text = input.replace('’', '\'').replace('‘', '\'').replace('”', '"').replace('“', '"')
        var altitude: Double? = null
        ALTITUDE_SUFFIX.find(text)?.let {
            altitude = it.groupValues[1].toDouble()
            text = text.substring(0, it.range.first)
        }

        val prefixed = text.trimStart().firstOrNull()?.let { it.uppercaseChar() in "NSEW" } == true
        val matches = component(prefixed).findAll(text).toList()
        val leftover = StringBuilder(text).also { sb -> matches.asReversed().forEach { sb.delete(it.range.first, it.range.last + 1) } }
        if (leftover.any { it !in SEPARATORS }) return GeoParse.Invalid(NOT_COORDINATES)

        val parts = ArrayList<Part>()
        for (match in matches) {
            val g = match.groups
            val degrees = (g["d"]?.value ?: return GeoParse.Invalid(NOT_COORDINATES))
            val minutes = g["m"]?.value?.toDouble() ?: 0.0
            val seconds = g["s"]?.value?.toDouble() ?: 0.0
            if (minutes >= 60.0 || seconds >= 60.0) return GeoParse.Invalid("Minutes and seconds must be below 60.")
            val negative = degrees.startsWith("-")
            val magnitude = abs(degrees.toDouble()) + minutes / 60.0 + seconds / 3600.0
            val letter = g["h"]?.value?.firstOrNull()?.uppercaseChar()
            val plain = letter == null && g["m"] == null && !match.value.contains(Regex(DEG))
            parts += Part(if (negative) -magnitude else magnitude, letter, negative, plain)
        }

        // Three bare numbers: the last is metres above sea level.
        if (parts.size == 3 && altitude == null && parts.all { it.plain }) {
            altitude = parts.removeAt(2).value
        }
        if (parts.size != 2) return GeoParse.Invalid(NOT_COORDINATES)

        val (first, second) = parts
        val lettered = parts.count { it.letter != null }
        if (lettered == 1) return GeoParse.Invalid("Put N/S/E/W on both numbers, or on neither.")
        val lat: Double
        val lon: Double
        if (lettered == 0) {
            lat = first.value
            lon = second.value
        } else {
            for (part in parts) {
                if (part.negativeSign) return GeoParse.Invalid("A negative number can't also have N, S, E or W.")
            }
            val latPart = parts.firstOrNull { it.letter in NORTH_SOUTH }
            val lonPart = parts.firstOrNull { it.letter in EAST_WEST }
            if (latPart == null || lonPart == null) return GeoParse.Invalid("Give one latitude (N/S) and one longitude (E/W).")
            lat = if (latPart.letter == 'S') -latPart.value else latPart.value
            lon = if (lonPart.letter == 'W') -lonPart.value else lonPart.value
        }
        return withRangeCheck(Candidate(lat, lon, altitude))
    }

    private val SEPARATORS = setOf(' ', '\t', ',', ';', '/', '|', '\n', '\r')
    private val NORTH_SOUTH = setOf('N', 'S')
    private val EAST_WEST = setOf('E', 'W')
}
