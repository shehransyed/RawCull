package dev.shehran.rawcull.data.export

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.shehran.rawcull.data.model.FilterOptions
import dev.shehran.rawcull.data.model.PhotoAsset
import dev.shehran.rawcull.data.model.SortOrder
import dev.shehran.rawcull.library.ExportContent
import dev.shehran.rawcull.library.LibraryFilter
import dev.shehran.rawcull.util.SafPermissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** What an ordered export was asked to do. [onlyIds] is set for "Only the n shown". */
data class ExportRequest(
    val treeUri: String,
    val collectionId: Long,
    val collectionName: String,
    val sort: SortOrder,
    val onlyIds: List<String>?,
    val options: ExportOptions,
) {
    /** The collection's photos in the export's order: its sort, filters ignored unless [onlyIds] says otherwise. */
    fun order(members: List<PhotoAsset>, now: Long = System.currentTimeMillis()): List<PhotoAsset> {
        val only = onlyIds?.toHashSet()
        val rows = if (only == null) members else members.filter { it.id in only }
        return LibraryFilter.apply(rows, FilterOptions(), sort, scopeSpansFolders = false, now = now).rows
    }
}

sealed interface ExportStatus {
    val collectionName: String

    data class Running(override val collectionName: String, val done: Int, val total: Int, val current: String?) : ExportStatus

    data class Finished(override val collectionName: String, val treeUri: String, val summary: ExportSummary) : ExportStatus
}

interface ExportScheduler {
    fun enqueue()
    fun cancel()
}

/**
 * Starts, follows and finishes ordered exports, one at a time (R7 D11). The request waits in a
 * file for [ExportWorker], so it survives the process; the status is for the screen on show.
 */
class ExportController(
    context: Context,
    private val scheduler: ExportScheduler,
    private val isGrantInUse: suspend (String) -> Boolean,
) {
    private val appContext = context.applicationContext
    private val requestFile = File(appContext.filesDir, "export/request.json")
    private val _status = MutableStateFlow<ExportStatus?>(null)
    val status: StateFlow<ExportStatus?> = _status.asStateFlow()

    /** Takes the folder's grant and queues the job. False while another export runs. */
    fun start(request: ExportRequest): Boolean {
        if (_status.value is ExportStatus.Running) return false
        SafPermissions.takePersistable(appContext, Uri.parse(request.treeUri))
        try {
            requestFile.parentFile?.mkdirs()
            requestFile.writeText(encode(request).toString())
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't queue the export", e)
            return false
        }
        _status.value = ExportStatus.Running(request.collectionName, 0, 0, null)
        scheduler.enqueue()
        return true
    }

    fun cancel() = scheduler.cancel()

    /** The summary was read. */
    fun dismiss() {
        if (_status.value is ExportStatus.Finished) _status.value = null
    }

    fun pending(): ExportRequest? = try {
        requestFile.takeIf { it.exists() }?.readText()?.let { decode(JSONObject(it)) }
    } catch (e: IOException) {
        null
    } catch (e: JSONException) {
        null
    }

    fun onProgress(request: ExportRequest, done: Int, total: Int, current: String?) {
        _status.value = ExportStatus.Running(request.collectionName, done, total, current)
    }

    /** The grant goes too, unless a Location or a card still needs it. */
    suspend fun onFinished(request: ExportRequest, summary: ExportSummary) {
        requestFile.delete()
        if (!isGrantInUse(request.treeUri)) SafPermissions.release(appContext, Uri.parse(request.treeUri))
        _status.value = ExportStatus.Finished(request.collectionName, request.treeUri, summary)
    }

    private fun encode(request: ExportRequest) = JSONObject().apply {
        put("treeUri", request.treeUri)
        put("collectionId", request.collectionId)
        put("collectionName", request.collectionName)
        put("sort", request.sort.name)
        request.onlyIds?.let { put("onlyIds", JSONArray(it)) }
        put("content", request.options.content.name)
        put("keepOrder", request.options.keepOrder)
    }

    private fun decode(json: JSONObject) = ExportRequest(
        treeUri = json.getString("treeUri"),
        collectionId = json.getLong("collectionId"),
        collectionName = json.optString("collectionName"),
        sort = SortOrder.forCollection(json.optString("sort")),
        onlyIds = json.optJSONArray("onlyIds")?.let { ids -> (0 until ids.length()).map(ids::getString) },
        options = ExportOptions(ExportContent.parse(json.optString("content")), json.optBoolean("keepOrder", true)),
    )

    private companion object {
        const val TAG = "RawCull.Export"
    }
}
