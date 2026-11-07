package dev.shehran.rawcull.importer

import dev.shehran.rawcull.data.model.StructureMode
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Turns an import preset and a capture date into a folder path relative to the Location. */
object PathTemplate {
    const val UNDATED = "Undated"
    const val TOKEN_YEAR = "{year}"
    const val TOKEN_MONTH = "{month}"
    const val TOKEN_DAY = "{day}"

    private val DATE_TOKENS = listOf(TOKEN_YEAR, TOKEN_MONTH, TOKEN_DAY)
    private val FORBIDDEN = Regex("""[\\:*?"<>|]""")

    /**
     * @param captureDate epoch millis, or null if the photo has no usable date.
     * @return a `/`-separated path without leading or trailing slash; empty for the Location's root.
     */
    fun resolve(
        mode: StructureMode,
        datePattern: String,
        customPattern: String,
        projectName: String,
        captureDate: Long?,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String = when (mode) {
        StructureMode.FLAT -> ""
        StructureMode.PROJECT -> sanitize(projectName.replace('/', '_'))
        StructureMode.DATE -> fill(datePattern, captureDate, timeZone)
        StructureMode.CUSTOM -> fill(customPattern, captureDate, timeZone)
    }

    private fun fill(pattern: String, captureDate: Long?, timeZone: TimeZone): String {
        if (DATE_TOKENS.none { it in pattern }) return sanitize(pattern)
        if (captureDate == null) return UNDATED
        val calendar = Calendar.getInstance(timeZone, Locale.ROOT).apply { timeInMillis = captureDate }
        return sanitize(
            pattern
                .replace(TOKEN_YEAR, "%04d".format(Locale.ROOT, calendar.get(Calendar.YEAR)))
                .replace(TOKEN_MONTH, "%02d".format(Locale.ROOT, calendar.get(Calendar.MONTH) + 1))
                .replace(TOKEN_DAY, "%02d".format(Locale.ROOT, calendar.get(Calendar.DAY_OF_MONTH))),
        )
    }

    /** Makes every segment safe for SAF providers; segments that end up empty are dropped. */
    fun sanitize(path: String): String = path
        .split('/')
        .map { segment -> segment.replace(FORBIDDEN, "_").trim(' ', '.') }
        .filter { it.isNotEmpty() }
        .joinToString("/")
}
