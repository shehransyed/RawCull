package dev.shehran.rawcull.data.repository

import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.FilterOptions
import dev.shehran.rawcull.data.model.LocationFilter
import dev.shehran.rawcull.data.model.PairMode
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.RatingOperator
import dev.shehran.rawcull.data.model.SortOrder
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Filters as stored per Location. `syncErrorsOnly` is a passing view, so it isn't kept. */
object FilterOptionsCodec {
    fun encode(filter: FilterOptions): String = JSONObject().apply {
        put("flags", JSONArray(filter.flags.map { it.name }))
        put("ratingOperator", filter.ratingOperator.name)
        put("rating", filter.rating)
        put("colors", JSONArray(filter.colors.map { it.name }))
        put("tags", JSONArray(filter.tags.toList()))
        filter.formatFilter?.let { put("formatFilter", it.name) }
        filter.pairFilter?.let { put("pairFilter", it.name) }
        put("searchQuery", filter.searchQuery)
        if (filter.locationFilter != LocationFilter.ANY) put("locationFilter", filter.locationFilter.name)
        put("sortOrder", filter.sortOrder.name)
    }.toString()

    /** Anything unreadable falls back to no filters, never to a crash at launch. */
    fun decode(json: String?): FilterOptions {
        if (json.isNullOrBlank()) return FilterOptions()
        return try {
            val parsed = JSONObject(json)
            if (parsed.has("cullFilter") || parsed.has("minRating")) decodeOldShape(parsed) else decodeCurrent(parsed)
        } catch (e: JSONException) {
            FilterOptions()
        }
    }

    private fun decodeCurrent(json: JSONObject) = FilterOptions(
        flags = json.names("flags", CullStatus::valueOf),
        ratingOperator = json.enum("ratingOperator", RatingOperator::valueOf) ?: RatingOperator.AT_LEAST,
        rating = json.optInt("rating", 0).coerceIn(0, MAX_RATING),
        colors = json.names("colors", ColorLabel::valueOf),
        tags = json.names("tags") { it },
        formatFilter = json.enum("formatFilter", PhotoFormat::valueOf),
        pairFilter = json.enum("pairFilter", PairMode::valueOf),
        searchQuery = json.optString("searchQuery"),
        sortOrder = json.enum("sortOrder", SortOrder::valueOf) ?: SortOrder.DATE_ASC,
        locationFilter = json.enum("locationFilter", LocationFilter::valueOf) ?: LocationFilter.ANY,
    )

    /** The fields before Round 4: one cull mode, one colour, one tag, and a minimum or exact rating. */
    private fun decodeOldShape(json: JSONObject): FilterOptions {
        val exact = if (json.has("exactRating") && !json.isNull("exactRating")) json.optInt("exactRating") else null
        val flags = when (json.optString("cullFilter")) {
            "PICKS_ONLY" -> setOf(CullStatus.PICK)
            "UNFLAGGED_ONLY" -> setOf(CullStatus.UNFLAGGED)
            "REJECTS_ONLY" -> setOf(CullStatus.REJECT)
            "PICKS_AND_UNFLAGGED" -> setOf(CullStatus.PICK, CullStatus.UNFLAGGED)
            else -> emptySet()
        }
        return FilterOptions(
            flags = flags,
            ratingOperator = if (exact != null) RatingOperator.EXACTLY else RatingOperator.AT_LEAST,
            rating = (exact ?: json.optInt("minRating", 0)).coerceIn(0, MAX_RATING),
            colors = setOfNotNull(json.enum("colorFilter", ColorLabel::valueOf)),
            tags = setOfNotNull(json.optString("tagFilter").takeIf { it.isNotBlank() && it != "null" }),
            formatFilter = json.enum("formatFilter", PhotoFormat::valueOf),
            pairFilter = json.enum("pairFilter", PairMode::valueOf),
            searchQuery = json.optString("searchQuery"),
            sortOrder = json.enum("sortOrder", SortOrder::valueOf) ?: SortOrder.DATE_ASC,
        )
    }

    /** A value this version doesn't know, say from a newer build, reads as unset. */
    private fun <T> JSONObject.enum(name: String, parse: (String) -> T): T? {
        if (!has(name) || isNull(name)) return null
        return try {
            parse(getString(name))
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun <T : Any> JSONObject.names(name: String, parse: (String) -> T): Set<T> {
        val array = optJSONArray(name) ?: return emptySet()
        return (0 until array.length()).mapNotNull { index ->
            try {
                parse(array.getString(index))
            } catch (e: IllegalArgumentException) {
                null
            }
        }.toSet()
    }

    private const val MAX_RATING = 5
}
