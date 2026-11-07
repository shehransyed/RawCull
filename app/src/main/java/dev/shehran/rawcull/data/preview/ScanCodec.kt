package dev.shehran.rawcull.data.preview

import dev.shehran.rawcull.preview.PooledCandidate
import dev.shehran.rawcull.preview.PreviewCandidate
import dev.shehran.rawcull.preview.PreviewFile
import dev.shehran.rawcull.preview.PreviewOrigin
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The candidate pool as `scans.candidatesJson`. A row that won't parse is just a cache miss. */
object ScanCodec {
    fun encode(pool: List<PooledCandidate>): String {
        val json = JSONArray()
        pool.forEach {
            json.put(
                JSONObject()
                    .put("o", it.candidate.offset)
                    .put("l", it.candidate.length)
                    .put("w", it.candidate.width)
                    .put("h", it.candidate.height)
                    .put("g", it.candidate.origin.name)
                    .put("f", it.file.name)
                    .put("r", it.orientation),
            )
        }
        return json.toString()
    }

    fun decode(text: String): List<PooledCandidate>? = try {
        val json = JSONArray(text)
        (0 until json.length()).map { i ->
            val entry = json.getJSONObject(i)
            PooledCandidate(
                candidate = PreviewCandidate(
                    offset = entry.getLong("o"),
                    length = entry.getLong("l"),
                    width = entry.getInt("w"),
                    height = entry.getInt("h"),
                    origin = PreviewOrigin.valueOf(entry.getString("g")),
                ),
                file = PreviewFile.valueOf(entry.getString("f")),
                orientation = entry.getInt("r"),
            )
        }
    } catch (e: JSONException) {
        null
    } catch (e: IllegalArgumentException) {
        null // An origin from a version that's gone
    }
}
