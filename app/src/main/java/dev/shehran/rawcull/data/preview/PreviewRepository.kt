package dev.shehran.rawcull.data.preview

import android.graphics.Bitmap
import dev.shehran.rawcull.data.model.PhotoFormat

/**
 * One photo as the preview pipeline sees it.
 *
 * @param key changes whenever a file behind the photo changes.
 */
data class PreviewSource(
    val primaryUri: String,
    val format: PhotoFormat,
    val companionUri: String?,
    val key: String,
    val companionFormat: PhotoFormat? = null,
)

/** The one way images get loaded. */
interface PreviewRepository {
    suspend fun thumbnail(source: PreviewSource, sizePx: Int): Bitmap?

    suspend fun fitPreview(source: PreviewSource, widthPx: Int, heightPx: Int): Bitmap?
}
