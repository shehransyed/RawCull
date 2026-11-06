package dev.shehran.rawcull.data.model

import java.security.MessageDigest
import java.util.Locale

/** Stable, content-independent asset ids: the same file keeps its id across rescans. */
object AssetIds {
    /**
     * @param discriminator null for the first asset of a stem group. Files that share a folder and
     * stem but become separate assets (a second RAW, a leftover raster) pass their lowercase
     * extension, because the root/dir/stem triple alone would collide.
     */
    fun of(rootUri: String, relativeDir: String, stem: String, discriminator: String? = null): String {
        val key = buildString {
            append(rootUri).append('|')
            append(relativeDir.lowercase(Locale.ROOT)).append('|')
            append(stem.lowercase(Locale.ROOT))
            if (discriminator != null) append('|').append(discriminator)
        }
        return MessageDigest.getInstance("SHA-1")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
