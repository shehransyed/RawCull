package dev.shehran.rawcull

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.util.Log
import dev.shehran.rawcull.data.deletion.DeletionGateway
import dev.shehran.rawcull.data.deletion.SafDeletionGateway
import dev.shehran.rawcull.data.importer.CardVolumes
import dev.shehran.rawcull.data.importer.ImportController
import dev.shehran.rawcull.data.importer.ImportEngine
import dev.shehran.rawcull.data.importer.SafDocumentIo
import dev.shehran.rawcull.data.importer.SafImportSourceReader
import dev.shehran.rawcull.data.importer.WorkManagerImportScheduler
import dev.shehran.rawcull.data.local.AppDatabase
import dev.shehran.rawcull.data.local.RawPhotoDao
import dev.shehran.rawcull.data.preview.AndroidPreviewCodec
import dev.shehran.rawcull.data.preview.DefaultPreviewRepository
import dev.shehran.rawcull.data.preview.PreviewCacheDatabase
import dev.shehran.rawcull.data.preview.PreviewMemory
import dev.shehran.rawcull.data.preview.PreviewRepository
import dev.shehran.rawcull.data.preview.SafPreviewFiles
import dev.shehran.rawcull.data.preview.ThumbnailStore
import dev.shehran.rawcull.data.repository.LegacyPreviewCache
import dev.shehran.rawcull.data.repository.LocationRepository
import dev.shehran.rawcull.data.repository.CollectionRepository
import dev.shehran.rawcull.data.repository.RawCatalogRepository
import dev.shehran.rawcull.data.repository.SafLocationAccess
import dev.shehran.rawcull.data.repository.SettingsRepository
import dev.shehran.rawcull.data.repository.StartupCleanup
import dev.shehran.rawcull.data.scan.ScanCoordinator
import dev.shehran.rawcull.data.xmp.LocalSidecarStore
import dev.shehran.rawcull.data.xmp.SafSidecarStore
import dev.shehran.rawcull.data.xmp.SidecarStores
import dev.shehran.rawcull.data.xmp.XMP_TOOLKIT
import dev.shehran.rawcull.data.export.ExifJpegTimeWriter
import dev.shehran.rawcull.data.export.ExportController
import dev.shehran.rawcull.data.export.ExportEngine
import dev.shehran.rawcull.data.export.WorkManagerExportScheduler
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
import kotlinx.coroutines.launch
import java.io.File
import dev.shehran.rawcull.data.model.ImportJobStatus
import dev.shehran.rawcull.data.preview.PreviewKeys
import dev.shehran.rawcull.data.preview.PreviewWarmup
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop

private const val LEGACY_PREVIEW_CACHE_DIR = "rawcull_preview_cache"
private const val THUMBNAIL_DIR = "previews/v1"
private const val EXPORT_TEMP_DIR = "export"
private const val BYTES_PER_MB = 1024L * 1024
private const val PREVIEW_IO_THREADS = 4
private const val PREVIEW_DECODE_THREADS = 3

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
    val collectionRepository: CollectionRepository = CollectionRepository(database)
    val locationRepository: LocationRepository = LocationRepository(
        locations = database.locationDao(),
        photos = photoDao,
        collections = collectionRepository,
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
            legacyPreviewCache = LegacyPreviewCache(
                isCleared = settingsRepository::legacyPreviewCacheCleared,
                markCleared = settingsRepository::setLegacyPreviewCacheCleared,
                delete = { File(appContext.cacheDir, LEGACY_PREVIEW_CACHE_DIR).deleteRecursively() },
            ),
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
        collections = collectionRepository,
        log = { Log.i("RawCull.Import", it) },
    )
    val importController: ImportController = ImportController(
        jobs = database.importJobDao(),
        engine = importEngine,
        io = documentIo,
        scheduler = WorkManagerImportScheduler(appContext),
        collections = collectionRepository,
    )
    val deletionGateway: DeletionGateway = SafDeletionGateway(appContext)
    val exportEngine: ExportEngine = ExportEngine(documentIo, ExifJpegTimeWriter(), File(appContext.cacheDir, EXPORT_TEMP_DIR), XMP_TOOLKIT)
    val exportController: ExportController = ExportController(
        context = appContext,
        scheduler = WorkManagerExportScheduler(appContext),
        isGrantInUse = { treeUri ->
            treeUri in settingsRepository.volumeGrants.first().values || database.locationDao().getByTreeUri(treeUri) != null
        },
    )
    val scanCoordinator: ScanCoordinator =
        ScanCoordinator(appContext, database, photoDao, applicationScope, sidecarStores, xmpWriteQueue)
    private val previewDatabase: PreviewCacheDatabase = PreviewCacheDatabase.build(appContext)
    private val thumbnailStore: ThumbnailStore = ThumbnailStore(
        dao = previewDatabase.dao(),
        root = File(appContext.cacheDir, THUMBNAIL_DIR),
        limitBytes = { settingsRepository.previewCacheLimitMb.first() * BYTES_PER_MB },
    )
    val previewRepository: PreviewRepository = DefaultPreviewRepository(
        files = SafPreviewFiles(appContext),
        codec = AndroidPreviewCodec(),
        store = thumbnailStore,
        dao = previewDatabase.dao(),
        memory = PreviewMemory.forMemoryClass(appContext.getSystemService(ActivityManager::class.java).memoryClass),
        scope = applicationScope,
        io = Dispatchers.IO.limitedParallelism(PREVIEW_IO_THREADS),
        decoding = Dispatchers.Default.limitedParallelism(PREVIEW_DECODE_THREADS),
        log = { Log.i("RawCull.Preview", it) },
    )

    val previewWarmup: PreviewWarmup = PreviewWarmup(
        repository = previewRepository,
        scope = applicationScope,
        importCopying = database.importJobDao().observeActiveJob()
            .map { it?.status == ImportJobStatus.RUNNING }
            .distinctUntilChanged(),
        log = { Log.i("RawCull.Preview", it) },
    )

    init {
        applicationScope.launch { thumbnailStore.reconcile() }
        applicationScope.launch { settingsRepository.previewCacheLimitMb.drop(1).collect { thumbnailStore.evict() } }
        applicationScope.launch { warmFinishedImports() }
    }

    /** D12: when an import finishes, its new photos are the ones about to be culled. */
    private suspend fun warmFinishedImports() {
        val jobs = database.importJobDao()
        var running: Long? = null
        jobs.observeActiveJob().collect { job ->
            val previous = running
            running = job?.id
            if (previous == null || previous == job?.id) return@collect
            val finished = jobs.getJob(previous) ?: return@collect
            if (finished.status == ImportJobStatus.COMPLETED || finished.status == ImportJobStatus.COMPLETED_WITH_ERRORS) {
                val photos = photoDao.observeByImportJob(previous).first()
                previewWarmup.start(PreviewWarmup.LATEST_IMPORT_LABEL, photos.map(PreviewKeys::source))
            }
        }
    }
}
