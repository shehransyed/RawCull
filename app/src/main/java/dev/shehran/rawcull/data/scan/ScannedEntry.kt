package dev.shehran.rawcull.data.scan

/** One file found by [SafTreeWalker]. Plain strings, so the grouping logic stays Android-free. */
data class ScannedEntry(
    val docId: String,
    val docUri: String,
    val parentDocId: String,
    val parentDocUri: String,
    val relativeDir: String,
    val name: String,
    val mime: String?,
    val size: Long,
    val lastModified: Long,
)
