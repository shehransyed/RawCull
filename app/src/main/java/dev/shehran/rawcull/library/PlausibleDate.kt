package dev.shehran.rawcull.library

/** Cameras with an unset clock write dates like 1970 or 2000-01-01, which mustn't become folders. */
object PlausibleDate {
    const val EARLIEST = 631_152_000_000L // 1990-01-01T00:00:00Z
    const val FUTURE_MARGIN_MILLIS = 48L * 60 * 60 * 1000

    fun isPlausible(date: Long?, now: Long): Boolean =
        date != null && date >= EARLIEST && date <= now + FUTURE_MARGIN_MILLIS

    /** The first believable date: EXIF, then the file's; null means the photo is undated. */
    fun pick(exif: Long?, fileDate: Long?, now: Long): Long? = when {
        isPlausible(exif, now) -> exif
        isPlausible(fileDate, now) -> fileDate
        else -> null
    }
}
