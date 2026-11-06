package dev.shehran.rawcull.data.scan

import java.util.Locale

/** Which directories the scan enters. The picked root is depth 0 and is always entered. */
object WalkPolicy {
    const val MAX_DEPTH = 6

    // PRIVATE holds Sony video; video is out of scope.
    private val SKIPPED_DIRECTORIES = setOf(
        "misc",
        "avf_info",
        "private",
        "system volume information",
        "lost.dir",
        "\$recycle.bin",
        ".trashes",
        ".spotlight-v100",
        ".fseventsd",
    )

    fun shouldDescend(name: String, depth: Int): Boolean =
        depth <= MAX_DEPTH && !name.startsWith(".") && name.lowercase(Locale.ROOT) !in SKIPPED_DIRECTORIES
}
