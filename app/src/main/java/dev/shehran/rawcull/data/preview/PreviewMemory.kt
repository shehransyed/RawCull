package dev.shehran.rawcull.data.preview

import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache

/**
 * D7's three RAM caches. A 512 px thumbnail is ~700 KB decoded and ~30 KB as a JPEG, so the second
 * cache holds many more photos than the first. Loupe previews are bounded by count: as hardware
 * bitmaps they live in graphics memory, where bytes are not what runs out.
 */
class PreviewMemory(bitmapBytes: Int, compressedBytes: Int, previewCount: Int) {
    private val bitmaps = object : LruCache<String, Bitmap>(bitmapBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val compressed = object : LruCache<String, ByteArray>(compressedBytes) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val previews = LruCache<String, PreviewImage>(previewCount)

    fun bitmap(key: String): Bitmap? = bitmaps[key]

    fun putBitmap(key: String, bitmap: Bitmap) {
        bitmaps.put(key, bitmap)
    }

    fun jpeg(key: String): ByteArray? = compressed[key]

    fun putJpeg(key: String, jpeg: ByteArray) {
        compressed.put(key, jpeg)
    }

    fun preview(key: String): PreviewImage? = previews[key]

    fun putPreview(key: String, image: PreviewImage) {
        previews.put(key, image)
    }

    /** Drops one photo; [matches] looks at each cache key. */
    fun remove(matches: (String) -> Boolean) {
        bitmaps.snapshot().keys.filter(matches).forEach(bitmaps::remove)
        compressed.snapshot().keys.filter(matches).forEach(compressed::remove)
        previews.snapshot().keys.filter(matches).forEach(previews::remove)
    }

    fun clear() {
        bitmaps.evictAll()
        compressed.evictAll()
        previews.evictAll()
    }

    companion object {
        private const val BYTES_PER_MB = 1024 * 1024
        private const val BITMAP_SHARE = 8
        private const val COMPRESSED_SHARE = 4
        private const val HARDWARE_PREVIEWS = 8 // Current, 5 ahead, 1 behind, and the one just left
        private const val SOFTWARE_PREVIEWS = 3

        /** @param memoryClassMb the heap the system gives this app; see `ActivityManager.memoryClass`. */
        fun forMemoryClass(memoryClassMb: Int): PreviewMemory = PreviewMemory(
            bitmapBytes = memoryClassMb * BYTES_PER_MB / BITMAP_SHARE,
            compressedBytes = memoryClassMb * BYTES_PER_MB / COMPRESSED_SHARE,
            previewCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) HARDWARE_PREVIEWS else SOFTWARE_PREVIEWS,
        )
    }
}
