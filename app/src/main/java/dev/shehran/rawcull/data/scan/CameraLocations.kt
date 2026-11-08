package dev.shehran.rawcull.data.scan

import android.content.Context
import android.net.Uri
import dev.shehran.rawcull.data.model.GeoPoint
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.util.RawMetadataParser
import java.io.IOException
import kotlin.math.abs

/** The location in a photo's own EXIF, which RawCull never changes (R7 D12). */
fun interface CameraLocations {
    /** Null when the file has none or can't be read right now. */
    suspend fun of(photo: PhotoAsset): GeoPoint?

    companion object {
        val None = CameraLocations { null }

        fun from(context: Context) = CameraLocations { photo ->
            try {
                RawMetadataParser.parse(
                    context = context,
                    uri = Uri.parse(photo.fileUri),
                    format = photo.format,
                    companionUri = photo.companionFileUri?.let(Uri::parse),
                ).location
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

/** Two positions are the same place if they agree to about a tenth of a metre. */
fun GeoPoint.samePlaceAs(other: GeoPoint): Boolean =
    abs(latitude - other.latitude) < SAME_PLACE_DEGREES && abs(longitude - other.longitude) < SAME_PLACE_DEGREES

private const val SAME_PLACE_DEGREES = 1e-6
