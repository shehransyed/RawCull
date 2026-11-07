package dev.shehran.rawcull

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.shehran.rawcull.data.importer.CardVolumes
import dev.shehran.rawcull.data.importer.ImportController
import dev.shehran.rawcull.data.importer.ImportEngine
import dev.shehran.rawcull.data.importer.SafDocumentIo
import dev.shehran.rawcull.data.importer.SafImportSourceReader
import dev.shehran.rawcull.data.importer.WorkManagerImportScheduler
import dev.shehran.rawcull.data.local.AppDatabase
import dev.shehran.rawcull.data.local.RawPhotoDao
import dev.shehran.rawcull.data.repository.LocationRepository
import dev.shehran.rawcull.data.repository.RawCatalogRepository
import dev.shehran.rawcull.data.repository.SafLocationAccess
import dev.shehran.rawcull.data.repository.SettingsRepository
import dev.shehran.rawcull.data.repository.StartupCleanup
import dev.shehran.rawcull.data.scan.ScanCoordinator
import dev.shehran.rawcull.data.xmp.LocalSidecarStore
import dev.shehran.rawcull.data.xmp.SafSidecarStore
import dev.shehran.rawcull.data.xmp.SidecarStores
import dev.shehran.rawcull.data.xmp.XMP_TOOLKIT
import dev.shehran.rawcull.data.xmp.XmpWriteQueue
import dev.shehran.rawcull.importer.DocumentIo
import dev.shehran.rawcull.importer.ImportSession
import dev.shehran.rawcull.util.SafPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
        RawCatalogRepository(appContext, database, photoDao, xmpWriteQueue)
    val locationRepository: LocationRepository = LocationRepository(
        locations = database.locationDao(),
        photos = photoDao,
        access = SafLocationAccess(appContext),
        flushXmp = { xmpWriteQueue.flushNow() },
        isGrantInUse = { treeUri -> treeUri in settingsRepository.volumeGrants.first().values },
    )

    /** Awaited before the Library restores itself; see [StartupCleanup]. */
    val startup: Deferred<Unit> = applicationScope.async(Dispatchers.IO) {
        StartupCleanup(
            legacyFolders = settingsRepository::legacyFolders,
            clearLegacyFolders = settingsRepository::clearLegacyFolders,
            locations = locationRepository,
            volumeGrants = { settingsRepository.volumeGrants.first().values },
            unfinishedJobSources = { database.importJobDao().getUnfinishedSources() },
            persistedGrants = { SafPermissions.persistedUris(appContext) },
            release = { SafPermissions.release(appContext, Uri.parse(it)) },
            removeOrphanRows = { keep ->
                val orphans = photoDao.countOrphans(keep)
                val removed = photoDao.deleteOrphans(keep)
                Log.i("RawCull.Library", "orphanRows removed=$removed keptPending=${orphans - removed}")
            },
        ).run()
    }
    val cardVolumes: CardVolumes = CardVolumes(appContext)
    val documentIo: DocumentIo = SafDocumentIo(appContext)
    val importSession: ImportSession = ImportSession(
        reader = SafImportSourceReader(appContext),
        knownSizes = { database.importedFileDao().knownSizes().toSet() },
        isIndexed = { hash, size -> database.importedFileDao().contains(hash, size) },
        scope = applicationScope,
        log = { Log.i("RawCull.Import", it) },
    )
    val importEngine: ImportEngine = ImportEngine(
        jobs = database.importJobDao(),
        importedFiles = database.importedFileDao(),
        locations = database.locationDao(),
        photos = photoDao,
        io = documentIo,
        log = { Log.i("RawCull.Import", it) },
    )
    val importController: ImportController = ImportController(
        jobs = database.importJobDao(),
        engine = importEngine,
        io = documentIo,
        scheduler = WorkManagerImportScheduler(appContext),
    )
    val scanCoordinator: ScanCoordinator =
        ScanCoordinator(appContext, database, photoDao, applicationScope, sidecarStores, xmpWriteQueue)
}
