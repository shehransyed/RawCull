package dev.shehran.rawcull

import android.content.Context
import dev.shehran.rawcull.data.local.AppDatabase
import dev.shehran.rawcull.data.local.RawPhotoDao
import dev.shehran.rawcull.data.repository.RawCatalogRepository
import dev.shehran.rawcull.data.repository.SettingsRepository
import dev.shehran.rawcull.data.scan.ScanCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container; one instance lives on [RawCullApp]. */
class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: AppDatabase = AppDatabase.build(appContext)
    val photoDao: RawPhotoDao = database.rawPhotoDao()
    val catalogRepository: RawCatalogRepository = RawCatalogRepository(appContext, photoDao)
    val settingsRepository: SettingsRepository = SettingsRepository(appContext)
    val scanCoordinator: ScanCoordinator = ScanCoordinator(appContext, database, photoDao, applicationScope)
}
