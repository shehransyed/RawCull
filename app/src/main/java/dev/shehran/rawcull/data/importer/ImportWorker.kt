package dev.shehran.rawcull.data.importer

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.shehran.rawcull.RawCullApp
import dev.shehran.rawcull.data.model.ImportJob
import dev.shehran.rawcull.data.model.ImportJobStatus
import kotlinx.coroutines.flow.first

/** Keeps an import alive in the background. All the import rules are in [ImportEngine]. */
class ImportWorker(
    context: Context,
    params: WorkerParameters,
    private val engine: ImportEngine,
    private val jobLookup: suspend (Long) -> ImportJob?,
    private val notifications: ImportNotifier,
) : CoroutineWorker(context, params) {

    /** The constructor WorkManager's default factory calls. */
    @Suppress("unused")
    constructor(context: Context, params: WorkerParameters) : this(
        context,
        params,
        (context.applicationContext as RawCullApp).container.importEngine,
        { id -> (context.applicationContext as RawCullApp).container.database.importJobDao().observeJob(id).first() },
        ImportNotifications(context),
    )

    private val jobId: Long get() = inputData.getLong(KEY_JOB_ID, NO_JOB)
    private var lastNotified = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifications.foregroundInfo(id, jobLookup(jobId), currentFile = null, bytesPerSecond = 0L)

    override suspend fun doWork(): Result {
        if (jobId == NO_JOB) return Result.failure()
        try {
            setForeground(getForegroundInfo())
        } catch (e: IllegalStateException) {
            // Android won't start a foreground service from the background here; the user resumes it.
            Log.w(TAG, "Couldn't go to the foreground", e)
            return Result.failure()
        }

        val status = engine.run(jobId) { job, currentFile, bytesPerSecond ->
            val now = System.currentTimeMillis()
            if (now - lastNotified >= NOTIFY_INTERVAL_MS) {
                lastNotified = now
                setForeground(notifications.foregroundInfo(id, job, currentFile, bytesPerSecond))
            }
        }
        if (status != ImportJobStatus.CANCELLED) jobLookup(jobId)?.let(notifications::notifyFinished)
        return Result.success()
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val UNIQUE_WORK_NAME = "rawcull_import"
        private const val NO_JOB = -1L
        private const val NOTIFY_INTERVAL_MS = 1_000L
        private const val TAG = "RawCull.Import"
    }
}

/** [ImportScheduler] on WorkManager. */
class WorkManagerImportScheduler(context: Context) : ImportScheduler {
    private val appContext = context.applicationContext
    private val workManager by lazy { WorkManager.getInstance(appContext) } // Not ready while the app object is being built

    override fun enqueue(jobId: Long) {
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(workDataOf(ImportWorker.KEY_JOB_ID to jobId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(ImportWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        workManager.cancelUniqueWork(ImportWorker.UNIQUE_WORK_NAME)
    }
}
