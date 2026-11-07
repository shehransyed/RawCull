package dev.shehran.rawcull

import android.content.Context
import android.util.Log
import dev.shehran.rawcull.data.importer.CardVolumes
import dev.shehran.rawcull.data.importer.SafImportSourceReader
import dev.shehran.rawcull.data.local.AppDatabase
import dev.shehran.rawcull.data.local.RawPhotoDao
import dev.shehran.rawcull.data.repository.LocationRepository
import dev.shehran.rawcull.data.repository.RawCatalogRepository
import dev.shehran.rawcull.data.repository.SafLocationAccess
import dev.shehran.rawcull.data.repository.SettingsRepository
import dev.shehran.rawcull.data.scan.ScanCoordinator
import dev.shehran.rawcull.data.xmp.LocalSidecarStore
import dev.shehran.rawcull.data.xmp.SafSidecarStore
import dev.shehran.rawcull.data.xmp.SidecarStores
import dev.shehran.rawcull.data.xmp.XMP_TOOLKIT
import dev.shehran.rawcull.data.xmp.XmpWriteQueue
import dev.shehran.rawcull.importer.ImportSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

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
        prefs = { settingsRepository.xmpPrefs.first() },
        toolkit = XMP_TOOLKIT,
        log = { Log.i("RawCull.Xmp", it) },
    )
    val catalogRepository: RawCatalogRepository =
        RawCatalogRepository(appContext, database, photoDao, xmpWriteQueue, sidecarStores, settingsRepository)
    val locationRepository: LocationRepository = LocationRepository(
        locations = database.locationDao(),
        photos = photoDao,
        access = SafLocationAccess(appContext),
        flushXmp = { xmpWriteQueue.flushNow() },
        isGrantInUse = { treeUri -> treeUri in settingsRepository.volumeGrants.first().values },
    )
    val cardVolumes: CardVolumes = CardVolumes(appContext)
    val importSession: ImportSession = ImportSession(
        reader = SafImportSourceReader(appContext),
        knownSizes = { database.importedFileDao().knownSizes().toSet() },
        isIndexed = { hash, size -> database.importedFileDao().contains(hash, size) },
        scope = applicationScope,
        log = { Log.i("RawCull.Import", it) },
    )
    val scanCoordinator: ScanCoordinator =
        ScanCoordinator(appContext, database, photoDao, applicationScope, sidecarStores, xmpWriteQueue)
}
