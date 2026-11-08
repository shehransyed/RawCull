package dev.shehran.rawcull.data.importer

import dev.shehran.rawcull.data.model.GeoPoint
import dev.shehran.rawcull.util.ExtractedRawMetadata
import org.json.JSONException
import org.json.JSONObject

/** The EXIF read during the card scan, kept on the job item so the worker can use it after a restart. */
object ImportMetadataCodec {

    /** Null for fallback values, which were never read from the file. */
    fun encode(metadata: ExtractedRawMetadata): String? {
        if (!metadata.parsed) return null
        return JSONObject()
            .putOpt("make", metadata.cameraMake)
            .putOpt("model", metadata.cameraModel)
            .putOpt("lens", metadata.lensModel)
            .putOpt("shutter", metadata.shutterSpeed)
            .putOpt("aperture", metadata.aperture)
            .putOpt("iso", metadata.iso)
            .putOpt("focal", metadata.focalLength)
            .putOpt("bias", metadata.exposureBias)
            .putOpt("date", metadata.dateTaken)
            .put("width", metadata.width)
            .put("height", metadata.height)
            .put("orientation", metadata.orientation)
            .apply {
                metadata.location?.let { point ->
                    put("lat", point.latitude)
                    put("lon", point.longitude)
                    putOpt("alt", point.altitude)
                }
            }
            .toString()
    }

    fun decode(json: String?): ExtractedRawMetadata? {
        if (json.isNullOrBlank()) return null
        return try {
            val parsed = JSONObject(json)
            ExtractedRawMetadata(
                cameraMake = parsed.text("make"),
                cameraModel = parsed.text("model"),
                lensModel = parsed.text("lens"),
                shutterSpeed = parsed.text("shutter"),
                aperture = parsed.text("aperture"),
                iso = parsed.text("iso"),
                focalLength = parsed.text("focal"),
                exposureBias = parsed.text("bias"),
                dateTaken = if (parsed.has("date")) parsed.getLong("date") else null,
                width = parsed.optInt("width"),
                height = parsed.optInt("height"),
                orientation = parsed.optInt("orientation"),
                location = parsed.location(), // Absent in jobs scanned before R8: no location
            )
        } catch (e: JSONException) {
            null
        }
    }

    private fun JSONObject.location(): GeoPoint? {
        if (!has("lat") || !has("lon")) return null
        return GeoPoint(getDouble("lat"), getDouble("lon"), if (has("alt")) getDouble("alt") else null)
    }

    private fun JSONObject.text(key: String): String? = if (has(key)) getString(key) else null
}
