package dev.shehran.rawcull.preview

/** Which file a candidate is in. The order is the tie-break: the primary wins. */
enum class PreviewFile {
    PRIMARY,
    COMPANION,
}

/** A candidate from either file, with the orientation of the file it came from. */
data class PooledCandidate(
    val candidate: PreviewCandidate,
    val file: PreviewFile,
    val orientation: Int,
)

sealed interface PreviewRequest {
    data class Thumbnail(val sizePx: Int) : PreviewRequest
    data class Fit(val widthPx: Int, val heightPx: Int) : PreviewRequest
    data object FullResolution : PreviewRequest
}

/** D3: one pool for the RAW and its companion JPEG, so a grid tile never decodes 6000 px for nothing. */
object PreviewChooser {
    fun pool(primary: PreviewScan, companion: PreviewScan?): List<PooledCandidate> =
        primary.candidates.map { PooledCandidate(it, PreviewFile.PRIMARY, primary.orientation) } +
            companion?.candidates.orEmpty().map { PooledCandidate(it, PreviewFile.COMPANION, companion!!.orientation) }

    fun pick(pool: List<PooledCandidate>, request: PreviewRequest): PooledCandidate? = ranked(pool, request).firstOrNull()

    /** Best first; the rest are what to fall back on if the best turns out not to decode. */
    fun ranked(pool: List<PooledCandidate>, request: PreviewRequest): List<PooledCandidate> = when (request) {
        is PreviewRequest.Thumbnail -> smallestCovering(pool, request.sizePx)
        is PreviewRequest.Fit -> smallestCovering(pool, maxOf(request.widthPx, request.heightPx))
        PreviewRequest.FullResolution -> pool.sortedWith(byPixels)
    }

    /** Fewest bytes among those big enough; when none is, the largest there is. */
    private fun smallestCovering(pool: List<PooledCandidate>, longEdge: Int): List<PooledCandidate> {
        val (bigEnough, tooSmall) = pool.partition { it.candidate.longEdge >= longEdge }
        return bigEnough.sortedWith(byBytes) + tooSmall.sortedWith(byPixels)
    }

    private val byBytes = compareBy<PooledCandidate>({ it.candidate.length }, { it.file })
    private val byPixels = compareByDescending<PooledCandidate> { it.candidate.pixels }.thenBy { it.file }
}
