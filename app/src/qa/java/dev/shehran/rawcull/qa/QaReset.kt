package dev.shehran.rawcull.qa

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import dev.shehran.rawcull.RawCullApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * qa only (R8 D11): puts the app back to a first start between device test classes: catalog,
 * settings, preview cache, scans, jobs and fixture trees. Persisted grants stay, so the shared
 * storage folder (D9 b) is granted once. The JUnit rule around this lives in androidTest.
 */
object QaReset {
    private const val TAG = "RawCull.QaReset"

    suspend fun resetAll(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext as RawCullApp
        val container = app.container
        container.startup.await()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        container.scanCoordinator.cancel()
        container.importSession.clear()
        container.xmpWriteQueue.flushNow()
        container.database.clearAllTables()
        container.settingsRepository.clearAll()
        container.previewRepository.clearCache()
        FixtureDocumentsProvider.clear(app)
        Log.i(TAG, "reset done")
    }
}
