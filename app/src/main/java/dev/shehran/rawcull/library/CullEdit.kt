package dev.shehran.rawcull.library

import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.location
import dev.shehran.rawcull.util.xmp.CullState

/** One culling action, independent of how many photos it lands on. */
sealed interface CullEdit {
    /** With [toggle], photos already at [status] go back to unflagged. */
    data class Flag(val status: CullStatus, val toggle: Boolean = true) : CullEdit

    /** Moves along Reject → Unflagged → Pick. */
    data class FlagStep(val delta: Int) : CullEdit

    /** With [toggle], photos already at [stars] go back to no rating. */
    data class Rating(val stars: Int, val toggle: Boolean = true) : CullEdit
    data class RatingStep(val delta: Int) : CullEdit

    /** Photos already at [label] lose it. */
    data class LabelToggle(val label: ColorLabel) : CullEdit
    data class AddTag(val tag: String) : CullEdit
    data class RemoveTag(val tag: String) : CullEdit
}

/** What an edit did: each photo's exact state before and after, so undo restores mixed batches. */
data class CullChange(
    val ids: List<String>,
    val before: Map<String, CullState>,
    val after: Map<String, CullState>,
    val summary: String,
) {
    fun inverse(): CullChange = copy(before = after, after = before)

    /** This change without [removed], or null if nothing is left of it. */
    fun without(removed: Set<String>): CullChange? {
        val remaining = ids.filterNot { it in removed }
        if (remaining.isEmpty()) return null
        return copy(ids = remaining, before = before - removed, after = after - removed)
    }
}

fun PhotoAsset.toCullState() = CullState(rating, cullStatus, colorLabel, tags, location)

object CullEdits {
    private val FLAG_ORDER = listOf(CullStatus.REJECT, CullStatus.UNFLAGGED, CullStatus.PICK)

    /**
     * What [edit] does to [photos], leaving out those it wouldn't change; null if that's all of them.
     *
     * A toggle only switches off when every target already has the value. A selection of mixed
     * ratings pressed `4` should end up all at 4, not with the fours cleared.
     */
    fun resolve(edit: CullEdit, photos: List<PhotoAsset>): CullChange? {
        if (photos.isEmpty()) return null
        val before = photos.associate { it.id to it.toCullState() }
        val after = LinkedHashMap<String, CullState>()
        val apply = stateChange(edit, before.values)
        before.forEach { (id, state) ->
            val edited = apply(state)
            if (edited != state) after[id] = edited
        }
        if (after.isEmpty()) return null
        val ids = after.keys.toList()
        return CullChange(ids, before.filterKeys { it in after }, after, summary(edit, after.values, ids.size))
    }

    private fun stateChange(edit: CullEdit, states: Collection<CullState>): (CullState) -> CullState = when (edit) {
        is CullEdit.Flag -> {
            val target = if (edit.toggle && states.all { it.cullStatus == edit.status }) CullStatus.UNFLAGGED else edit.status
            { it.copy(cullStatus = target) }
        }
        is CullEdit.FlagStep -> { state ->
            val index = (FLAG_ORDER.indexOf(state.cullStatus) + edit.delta).coerceIn(FLAG_ORDER.indices)
            state.copy(cullStatus = FLAG_ORDER[index])
        }
        is CullEdit.Rating -> {
            val stars = edit.stars.coerceIn(0, MAX_RATING)
            val target = if (edit.toggle && states.all { it.rating == stars }) 0 else stars
            { it.copy(rating = target) }
        }
        is CullEdit.RatingStep -> { state -> state.copy(rating = (state.rating + edit.delta).coerceIn(0, MAX_RATING)) }
        is CullEdit.LabelToggle -> {
            val target = if (states.all { it.colorLabel == edit.label }) ColorLabel.NONE else edit.label
            { it.copy(colorLabel = target) }
        }
        is CullEdit.AddTag -> {
            val tag = edit.tag.trim();
            { state -> if (tag.isEmpty() || tag in state.tags) state else state.copy(tags = state.tags + tag) }
        }
        is CullEdit.RemoveTag -> { state -> state.copy(tags = state.tags - edit.tag) }
    }

    private fun summary(edit: CullEdit, results: Collection<CullState>, count: Int): String {
        val photos = if (count == 1) "1 photo" else "$count photos"
        val first = results.first()
        val uniform = results.all { it == first || sameValue(edit, it, first) }
        return when (edit) {
            is CullEdit.Flag, is CullEdit.FlagStep -> when {
                !uniform -> "Changed the flag on $photos"
                first.cullStatus == CullStatus.PICK -> "Picked $photos"
                first.cullStatus == CullStatus.REJECT -> "Rejected $photos"
                else -> "Unflagged $photos"
            }
            is CullEdit.Rating, is CullEdit.RatingStep -> when {
                !uniform -> "Changed the rating on $photos"
                first.rating == 0 -> "Cleared the rating on $photos"
                first.rating == 1 -> "Rated $photos 1 star"
                else -> "Rated $photos ${first.rating} stars"
            }
            is CullEdit.LabelToggle ->
                if (first.colorLabel == ColorLabel.NONE) "Cleared the label on $photos" else "Labelled $photos ${edit.label.shortName}"
            is CullEdit.AddTag -> "Tagged $photos ${edit.tag.trim()}"
            is CullEdit.RemoveTag -> "Removed ${edit.tag} from $photos"
        }
    }

    private fun sameValue(edit: CullEdit, a: CullState, b: CullState): Boolean = when (edit) {
        is CullEdit.Flag, is CullEdit.FlagStep -> a.cullStatus == b.cullStatus
        is CullEdit.Rating, is CullEdit.RatingStep -> a.rating == b.rating
        else -> true
    }

    private val ColorLabel.shortName: String get() = labelName.substringBefore(' ')

    private const val MAX_RATING = 5
}
