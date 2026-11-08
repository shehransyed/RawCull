package dev.shehran.rawcull.data.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.exifinterface.media.ExifInterface
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.shehran.rawcull.R
import dev.shehran.rawcull.RawCullApp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Keeps an ordered export alive in the background. The rules are in [ExportEngine]. */
class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container = (context.applicationContext as RawCullApp).container
    private val controller = container.exportController
    private val notifications = ExportNotifications(context)
    private var lastNotified = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.progress(id, controller.status.value as? ExportStatus.Running)

    override suspend fun doWork(): Result {
        val request = controller.pending() ?: return Result.failure()
        try {
            setForeground(getForegroundInfo())
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Couldn't go to the foreground", e) // It still runs, just less safely
        }
        // Cancelled before the engine started, unless it gets to say otherwise.
        var summary = ExportSummary(0, emptyList(), emptyList(), cancelled = true)
        try {
            // A pending write could create or change a sidecar the export is about to copy.
            container.xmpWriteQueue.flushNow()
            val photos = request.order(container.collectionRepository.members(request.collectionId))
            summary = container.exportEngine.run(request.treeUri, photos, request.options) { done, total, current ->
                controller.onProgress(request, done, total, current)
                val now = System.currentTimeMillis()
                if (now - lastNotified >= NOTIFY_INTERVAL_MS) {
                    lastNotified = now
                    runCatching { setForeground(notifications.progress(id, controller.status.value as? ExportStatus.Running)) }
                }
            }
        } catch (e: IOException) {
            summary = summary.copy(failed = listOf(e.message ?: "The export failed"), cancelled = false)
        } finally {
            withContext(NonCancellable) { controller.onFinished(request, summary) }
            notifications.finished(request.collectionName, summary)
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "RawCull.Export"
        const val NOTIFY_INTERVAL_MS = 1_000L
    }
}

/** [ExportScheduler] on WorkManager. */
class WorkManagerExportScheduler(context: Context) : ExportScheduler {
    private val appContext = context.applicationContext
    private val workManager by lazy { WorkManager.getInstance(appContext) }

    override fun enqueue() {
        val request = OneTimeWorkRequestBuilder<ExportWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "rawcull_export"
    }
}

/** EXIF times through AndroidX ExifInterface, on a local copy. */
class ExifJpegTimeWriter : JpegTimeWriter {
    override fun write(file: File, exifDateTime: String) {
        try {
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDateTime)
                setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifDateTime)
                setAttribute(ExifInterface.TAG_DATETIME, exifDateTime)
                saveAttributes()
            }
        } catch (e: RuntimeException) {
            throw IOException("Couldn't write the capture time", e) // ExifInterface throws more than IOException
        }
    }
}

/** Progress (with Cancel) and the summary. The export works without them if they're denied. */
class ExportNotifications(context: Context) {
    private val appContext = context.applicationContext

    fun progress(workId: UUID, status: ExportStatus.Running?): ForegroundInfo {
        ensureChannel()
        val total = status?.total ?: 0
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (total > 0) "Exporting ${status?.done} of $total photos" else "Exporting")
            .setContentText(status?.current ?: status?.collectionName)
            .setProgress(total, status?.done ?: 0, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "Cancel", WorkManager.getInstance(appContext).createCancelPendingIntent(workId))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ONGOING_ID, notification)
        }
    }

    fun finished(collectionName: String, summary: ExportSummary) {
        ensureChannel()
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) return
        val title = (if (summary.cancelled) "Export cancelled: " else "Exported ") + photos(summary.exported) + " from \"$collectionName\""
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(summaryLine(summary))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        try {
            manager.notify(FINISHED_ID, notification)
        } catch (e: SecurityException) {
            Log.w("RawCull.Export", "No permission to notify", e)
        }
    }

    private fun openApp() = android.app.PendingIntent.getActivity(
        appContext,
        0,
        appContext.packageManager.getLaunchIntentForPackage(appContext.packageName),
        android.app.PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Exports", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        private const val CHANNEL_ID = "exports"
        private const val ONGOING_ID = 51
        private const val FINISHED_ID = 52

        fun photos(count: Int) = if (count == 1) "1 photo" else "$count photos"

        /** "2 skipped · 1 failed", or empty when neither happened. */
        fun summaryLine(summary: ExportSummary): String = listOfNotNull(
            summary.skipped.size.takeIf { it > 0 }?.let { "$it skipped (no JPEG)" },
            summary.failed.size.takeIf { it > 0 }?.let { if (it == 1) "1 file failed" else "$it files failed" },
        ).joinToString(" · ")
    }
}
