package dev.shehran.rawcull.data.importer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import dev.shehran.rawcull.MainActivity
import dev.shehran.rawcull.R
import dev.shehran.rawcull.RawCullApp
import dev.shehran.rawcull.data.model.ImportJob
import dev.shehran.rawcull.data.model.ImportJobStatus
import kotlinx.coroutines.launch
import java.util.UUID

interface ImportNotifier {
    fun foregroundInfo(workId: UUID, job: ImportJob?, currentFile: String?, bytesPerSecond: Long): ForegroundInfo
    fun notifyFinished(job: ImportJob)
}

/** The progress and summary notifications. An import works without them if they're denied. */
class ImportNotifications(context: Context) : ImportNotifier {
    private val appContext = context.applicationContext

    override fun foregroundInfo(workId: UUID, job: ImportJob?, currentFile: String?, bytesPerSecond: Long): ForegroundInfo {
        ensureChannel()
        val total = job?.totalBytes?.coerceAtLeast(1L) ?: 1L
        val done = job?.doneBytes ?: 0L
        val size = Formatter.formatShortFileSize(appContext, done) + " of " + Formatter.formatShortFileSize(appContext, total)
        val speed = if (bytesPerSecond > 0) " · ${Formatter.formatShortFileSize(appContext, bytesPerSecond)}/s" else ""

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Importing ${job?.doneFiles ?: 0} of ${job?.totalFiles ?: 0} files")
            .setContentText(listOfNotNull(currentFile, size + speed).joinToString(" · "))
            .setProgress(PROGRESS_MAX, (done * PROGRESS_MAX / total).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openProgress(job?.id))
            .addAction(0, "Cancel", cancelIntent(job?.id))
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ONGOING_ID, notification)
        }
    }

    override fun notifyFinished(job: ImportJob) {
        ensureChannel()
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) return

        val title = when (job.status) {
            ImportJobStatus.SOURCE_LOST -> "Import stopped: card disconnected"
            ImportJobStatus.PAUSED -> "Import paused"
            else -> finishedText(job)
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setAutoCancel(true)
            .setContentIntent(openProgress(job.id))
            .build()
        try {
            manager.notify(FINISHED_ID, notification)
        } catch (e: SecurityException) {
            // The permission was withdrawn after the check above; the summary is still in the app.
        }
    }

    private fun finishedText(job: ImportJob): String {
        val failed = if (job.failedFiles > 0) " · ${job.failedFiles} failed" else ""
        return "${job.doneFiles} files imported$failed"
    }

    private fun openProgress(jobId: Long?): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (jobId != null) intent.putExtra(EXTRA_JOB_ID, jobId)
        return PendingIntent.getActivity(
            appContext,
            jobId?.toInt() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelIntent(jobId: Long?): PendingIntent {
        val intent = Intent(appContext, ImportCancelReceiver::class.java).putExtra(EXTRA_JOB_ID, jobId ?: NO_JOB)
        return PendingIntent.getBroadcast(
            appContext,
            jobId?.toInt() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Imports", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        const val EXTRA_JOB_ID = "dev.shehran.rawcull.IMPORT_JOB_ID"
        const val NO_JOB = -1L
        private const val CHANNEL_ID = "imports"
        private const val ONGOING_ID = 41
        private const val FINISHED_ID = 42
        private const val PROGRESS_MAX = 1000
    }
}

/** The notification's Cancel: it goes through the controller, so the job ends up CANCELLED. */
class ImportCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getLongExtra(ImportNotifications.EXTRA_JOB_ID, ImportNotifications.NO_JOB)
        if (jobId == ImportNotifications.NO_JOB) return
        val container = (context.applicationContext as RawCullApp).container
        val pending = goAsync()
        container.applicationScope.launch {
            try {
                container.importController.cancel(jobId)
            } finally {
                pending.finish()
            }
        }
    }
}
