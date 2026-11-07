package dev.shehran.rawcull.importer

/**
 * Asks the duplicate index about a file. Hashing means reading from the card, so it only happens
 * for files whose size matches something already imported.
 *
 * @param knownSizes every size in the index.
 * @param contains whether the index has that head hash and size.
 */
class DuplicateCheck(
    private val knownSizes: Set<Long>,
    private val contains: suspend (headHash: String, sizeBytes: Long) -> Boolean,
) {
    @Volatile
    var hashesComputed = 0
        private set

    @Volatile
    var hashesSkipped = 0
        private set

    /** @param headHash reads the file; null means it couldn't be read, so it counts as not imported. */
    suspend fun isImported(sizeBytes: Long, headHash: suspend () -> String?): Boolean {
        if (sizeBytes !in knownSizes) {
            hashesSkipped++
            return false
        }
        hashesComputed++
        val hash = headHash() ?: return false
        return contains(hash, sizeBytes)
    }
}
