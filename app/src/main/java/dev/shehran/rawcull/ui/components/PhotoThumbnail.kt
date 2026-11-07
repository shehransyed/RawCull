package dev.shehran.rawcull.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.preview.DefaultPreviewRepository
import dev.shehran.rawcull.data.preview.PreviewKeys
import dev.shehran.rawcull.data.preview.PreviewLane
import dev.shehran.rawcull.data.preview.PreviewRepository
import dev.shehran.rawcull.data.preview.PreviewResult
import dev.shehran.rawcull.data.preview.PreviewSource

/** The app's one image pipeline. Null in previews and UI tests, which then show placeholders. */
val LocalPreviewRepository = staticCompositionLocalOf<PreviewRepository?> { null }

/** The cache identity of a photo, worked out once per file version rather than on every recomposition. */
@Composable
fun rememberPreviewSource(photo: PhotoAsset): PreviewSource = remember(
    photo.fileUri,
    photo.fileSizeBytes,
    photo.fileLastModified,
    photo.companionFileUri,
    photo.companionFileSizeBytes,
) { PreviewKeys.source(photo) }

/**
 * A photo's thumbnail, loaded through [PreviewRepository] in the visible lane.
 *
 * It asks on first composition and the request is dropped when the tile leaves the composition, so
 * a fast scroll doesn't leave a queue of tiles nobody sees. What RAM already holds is drawn in the
 * first frame, so scrolling back doesn't flash.
 *
 * @param sizePx the tile's longest edge in pixels; the repository rounds it to 128, 256 or 512.
 * @param placeholder shown while loading.
 */
@Composable
fun PhotoThumbnail(
    photo: PhotoAsset,
    sizePx: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alpha: Float = 1f,
    placeholder: @Composable () -> Unit = {},
) {
    val previews = LocalPreviewRepository.current
    val source = rememberPreviewSource(photo)
    val edge = DefaultPreviewRepository.bucket(sizePx)
    var bitmap by remember(source.key, edge) { mutableStateOf<Bitmap?>(previews?.peekThumbnail(source, edge)) }
    var loading by remember(source.key, edge) { mutableStateOf(previews != null && bitmap == null) }

    if (loading && previews != null) {
        LaunchedEffect(source.key, edge) {
            val result = previews.thumbnail(source, edge, PreviewLane.VISIBLE)
            bitmap = (result as? PreviewResult.Ready)?.image?.bitmap
            loading = false
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val shown = bitmap
        when {
            shown != null -> Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = contentScale,
                alpha = alpha,
                modifier = Modifier.matchParentSize(),
            )
            loading -> placeholder()
        }
    }
}
