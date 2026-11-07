package dev.shehran.rawcull.importer

import java.util.Locale

/**
 * Hands out `-1`, `-2`, … suffixes and remembers every name a job has planned. Names compare
 * case-insensitively, because cards and many library volumes are FAT or exFAT.
 */
class ConflictNamer {
    private val reserved = HashSet<String>()

    fun isReserved(dir: String, name: String): Boolean = key(dir, name) in reserved

    fun reserve(dir: String, name: String) {
        reserved += key(dir, name)
    }

    private fun key(dir: String, name: String) = "$dir/${name.lowercase(Locale.ROOT)}"

    companion object {
        /**
         * [name] with the suffix after [stem], so `DSC1.ARW` and the sidecar `DSC1.ARW.xmp` both
         * become `DSC1-1…`. [stem] must be a prefix of [name], ignoring case; 0 leaves it as it is.
         */
        fun suffixed(name: String, stem: String, attempt: Int): String {
            if (attempt == 0) return name
            return name.substring(0, stem.length) + "-" + attempt + name.substring(stem.length)
        }
    }
}
