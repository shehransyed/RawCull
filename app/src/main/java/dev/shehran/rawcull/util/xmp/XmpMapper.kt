package dev.shehran.rawcull.util.xmp

import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import java.util.Date

data class XmpPrefs(val rejectAsNegativeRating: Boolean = true)

/** What the catalog says about a photo. */
data class CullState(
    val rating: Int,
    val cullStatus: CullStatus,
    val colorLabel: ColorLabel,
    val tags: List<String>,
)

/** What a sidecar says about a photo. */
data class ParsedCullState(
    val rating: Int,
    /** Null when the file says nothing: most apps have no pick flag, so that isn't "unflagged". */
    val cullStatus: CullStatus?,
    val colorLabel: ColorLabel,
    /** The label as written, which may be a custom one that [colorLabel] can only show as NONE. */
    val rawLabel: String?,
    val tags: List<String>,
)

/** The user-editable fields. A row's pending set says which ones the catalog is the authority on. */
enum class XmpField(val bit: Int) {
    RATING(1),
    PICK(2),
    LABEL(4),
    TAGS(8);

    companion object {
        val ALL: Set<XmpField> = entries.toSet()
        val ALL_BITS: Int = entries.fold(0) { bits, field -> bits or field.bit }

        fun fromBits(bits: Int): Set<XmpField> = entries.filterTo(LinkedHashSet()) { bits and it.bit != 0 }
    }
}

/** Translates between catalog state and XMP properties. */
object XmpMapper {

    /**
     * Writes [fields] of [state] into [doc]. Fields left out keep what the file says, which is what
     * protects values the catalog hasn't read yet or that another app changed in the meantime.
     */
    fun apply(
        doc: XmpDocument,
        state: CullState,
        prefs: XmpPrefs,
        fields: Set<XmpField> = XmpField.ALL,
        now: Date = Date(),
    ) {
        val onFile = read(doc)
        // xmp:Rating carries both the stars and the reject, so neither can be written alone.
        if (XmpField.RATING in fields || XmpField.PICK in fields || doc.has(XmpProps.CRS_PICK)) {
            val stars = if (XmpField.RATING in fields) state.rating else onFile.rating
            val status = if (XmpField.PICK in fields) state.cullStatus else onFile.cullStatus ?: state.cullStatus
            writeRatingAndPick(doc, stars, status, prefs)
        }
        if (XmpField.LABEL in fields) writeLabel(doc, state.colorLabel, onFile)
        if (XmpField.TAGS in fields && onFile.tags != state.tags) doc.setSubjects(state.tags)
        doc.touchMetadataDate(now)
    }

    fun read(doc: XmpDocument): ParsedCullState {
        val rejected = doc.rating == -1
        return ParsedCullState(
            rating = if (rejected) (doc.rawcullRating ?: 0).coerceAtLeast(0) else doc.rating ?: 0,
            cullStatus = if (rejected) CullStatus.REJECT else statusOf(doc.pick),
            colorLabel = labelOf(doc.label),
            rawLabel = doc.label,
            tags = doc.subjects,
        )
    }

    private fun writeRatingAndPick(doc: XmpDocument, stars: Int, status: CullStatus, prefs: XmpPrefs) {
        val asNegative = status == CullStatus.REJECT && prefs.rejectAsNegativeRating
        val pick = when (status) {
            CullStatus.PICK -> "1"
            CullStatus.UNFLAGGED -> "0"
            CullStatus.REJECT -> "-1"
        }
        doc.set(XmpProps.RATING, if (asNegative) "-1" else stars.toString())
        doc.set(XmpProps.RAWCULL_RATING, stars.toString())
        doc.set(XmpProps.RAWCULL_PICK, pick)
        doc.set(XmpProps.XMP_DM_PICK, pick)
        doc.set(XmpProps.CRS_PICK, null)
    }

    /**
     * An unchanged label is left alone, so a custom one ("Second") that the catalog can only show as
     * NONE survives until the user picks a different label. A blank `xmp:Label=""` has no such value.
     */
    private fun writeLabel(doc: XmpDocument, label: ColorLabel, onFile: ParsedCullState) {
        if (label != onFile.colorLabel || onFile.rawLabel == null) doc.set(XmpProps.LABEL, nameOf(label))
    }

    private fun statusOf(pick: Int?): CullStatus? = when (pick) {
        1 -> CullStatus.PICK
        0 -> CullStatus.UNFLAGGED
        -1 -> CullStatus.REJECT
        else -> null
    }

    private fun nameOf(label: ColorLabel): String? = when (label) {
        ColorLabel.NONE -> null
        ColorLabel.RED -> "Red"
        ColorLabel.YELLOW -> "Yellow"
        ColorLabel.GREEN -> "Green"
        ColorLabel.BLUE -> "Blue"
        ColorLabel.PURPLE -> "Purple"
    }

    private fun labelOf(name: String?): ColorLabel =
        ColorLabel.entries.firstOrNull { nameOf(it).equals(name, ignoreCase = true) && it != ColorLabel.NONE }
            ?: ColorLabel.NONE
}
