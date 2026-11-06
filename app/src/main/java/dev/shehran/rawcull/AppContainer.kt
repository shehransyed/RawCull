package dev.shehran.rawcull

import android.content.Context
import android.util.Log
import dev.shehran.rawcull.data.local.AppDatabase
import dev.shehran.rawcull.data.local.RawPhotoDao
import dev.shehran.rawcull.data.repository.RawCatalogRepository
import dev.shehran.rawcull.data.repository.SettingsRepository
import dev.shehran.rawcull.data.scan.ScanCoordinator
import dev.shehran.rawcull.data.xmp.LocalSidecarStore
import dev.shehran.rawcull.data.xmp.SafSidecarStore
import dev.shehran.rawcull.data.xmp.SidecarStores
import dev.shehran.rawcull.data.xmp.XmpWriteQueue
import dev.shehran.rawcull.util.xmp.XmpPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container; one instance lives on [RawCullApp]. */
class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: AppDatabase = AppDatabase.build(appContext)
    val photoDao: RawPhotoDao = database.rawPhotoDao()
    val settingsRepository: SettingsRepository = SettingsRepository(appContext)
    val sidecarStores: SidecarStores = SidecarStores(SafSidecarStore(appContext.contentResolver), LocalSidecarStore())
    val xmpWriteQueue: XmpWriteQueue = XmpWriteQueue(
        scope = applicationScope,
        rows = photoDao,
        stores = sidecarStores,
        prefs = { XmpPrefs() },
        toolkit = "RawCull ${BuildConfig.VERSION_NAME}",
        log = { Log.i("RawCull.Xmp", it) },
    )
    val catalogRepository: RawCatalogRepository =
        RawCatalogRepository(appContext, database, photoDao, xmpWriteQueue)
    val scanCoordinator: ScanCoordinator = ScanCoordinator(appContext, database, photoDao, applicationScope)
}
