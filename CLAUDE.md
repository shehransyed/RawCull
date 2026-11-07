# RawCull — notes for Claude Code

Android photo culling + import-first DAM (tablets and phones, any orientation or window size). Kotlin, Jetpack Compose, Material 3, Room, DataStore, Storage Access Framework. Package `dev.shehran.rawcull`. minSdk 24, targetSdk 36.

## Build & test
- `./gradlew testDebugUnitTest assembleDebug` (Windows: `gradlew.bat ...`). Every commit must build and pass unit tests.
- Pure logic (grouping, merging, XMP, path building, selection) lives in Android-free classes with plain JUnit tests. Use Robolectric only where Android types are unavoidable.
- Real RAW samples live outside the repo (`C:\Users\shehr\RawCullSamples\`); never commit one. `gradlew.bat testDebugUnitTest -PrawSamples=<dir> --tests "*RawSampleReportTest*"` reports what the parsers find in each file. The fixtures in the repo are synthetic.
- `XmpDocumentDeviceTest` runs the XMP fixtures on Android's own XML stack. With a phone or tablet attached, set `ANDROID_SERIAL` first, because connected tests install on every device.

## Architecture (single Activity, manual DI)
- `RawCullApp` → `AppContainer` (database, DAOs, repositories, ScanCoordinator, import session/engine/controller, app scope).
- `ui/shell/` — `RawCullApp()` composable: `NavigationSuiteScaffold` (bar, rail or wide rail) around Nav3 `NavDisplay`. Tabs **Import**, **Library**, **Settings**, one back stack each in `TabStacks`. Nav3 keys in `AppKey`: `Library`, `ImportSource`, `ImportPreview`, `ImportProgress(jobId)`, `Settings`. `WindowLayout` turns the window size class into layout decisions; read it through `LocalWindowLayout`.
- ViewModels created inside a `NavEntry` must get their dependencies passed in: the entry's store has no `APPLICATION_KEY`. Activity-scoped ones (`LibraryViewModel`, `ImportViewModel`, `ShellViewModel`) can use it.
- `ui/library/LibraryViewModel` holds one `StateFlow<LibraryUiState>`: active Location, `LibraryScope` (`All`, `Folder`, `LatestImport`), filters, culling. Selection is **id-based** (`selectedPhotoId`); indices are derived.
- Culling has one path: `LibraryViewModel.apply(edit, advance)` targets the selection, else the current photo. It resolves a `CullEdit` into a `CullChange` (each photo's exact before and after), writes it as one batch through `PhotoCatalog.applyChange`, pushes it on `UndoStack`, then applies `AutoAdvance`. Undo and redo write stored states back the same way. Don't write flags, ratings, labels or tags any other way.
- Filtering is `LibraryFilter.apply`: in memory, on `Dispatchers.Default` behind `mapLatest`, giving rows, `FolderSection`s and `FilterCounts` (counts ignore filters). Filters are stored per Location. Read counts from `FilterCounts`, not by scanning rows while composing.
- Keys: `LibraryKeymap` is the only shortcut table (pure, on a `LibraryKey` enum; `ui/library/LibraryKeys.kt` maps Compose keys to it). The help sheet is generated from it, so add a shortcut there and nowhere else. Sheets are separate windows: they need `Modifier.dismissOnEscape`.
- Locations: `LibraryLocation` rows through `LocationRepository` (add, rename, relink, remove; refuses while edits are unsaved). The demo library is a pseudo-Location.
- `importer/` — Android-free import logic: `PathTemplate`, `ImportPlanner`, `ConflictNamer`, hashing, `DuplicateCheck`, `ImportPreview`, `ImportSession` (card state, memory only), `DocumentIo` (the SAF seam). `library/` — Android-free Library logic: `FolderTree`, `LibraryScope`, `CullEdit`, `UndoStack`, `AutoAdvance`, `LibraryFilter`, `LibraryKeymap`, `GridNavigation`, `LoupeSwipe`, `PlausibleDate`, `RejectDeletion`.
- `data/deletion/` — `DeletionGateway` (MediaStore and SAF), driven by `LibraryViewModel` from a `RejectDeletion` plan. See the deletion rules below.
- `data/importer/` — `ImportEngine` (all the copy, verify, resume and delete rules), `ImportWorker` (thin WorkManager foreground shell), `ImportController` (start, resume, retry, cancel), `SafDocumentIo`. The card never enters `photo_assets`; the worker inserts library rows itself.
- `data/scan/` — `SafTreeWalker` (DocumentsContract cursor walk), `AssetGrouper` (RAW+JPEG/HEIF pairing, pure), `ScanMerger` (pure), `ScanCoordinator` (phase A listing, phase B lazy EXIF/XMP enrichment; `rescanSubtree` for one folder).
- `data/model/PhotoAsset` (Room entity `photo_assets`), `PhotoFormat`, `PairMode`, `CullStatus`, `ColorLabel`.
- `preview/` — Android-free preview extraction: `ByteSource` (header slice, 64 KB block cache, stream tiers for pipes), `JpegProbe`, `TiffReader`, the scanners (TIFF family incl. Olympus MakerNote, CR3, RAF, JPEG with MPF), `PreviewChooser` (one pool from RAW and companion; fewest bytes that are big enough), `RegionMath` (orientation, fit, 100 %, tiles), `PrefetchWindows`.
- `data/preview/` — `PreviewRepository` (`DefaultPreviewRepository`: RAM → disk → file, four lanes in `PreviewScheduler`, merged duplicates), `PreviewMemory` (RAM caches), `ThumbnailStore` + `preview_cache.db` (512 px thumbnails and cached scans, LRU-bounded by `preview_cache_limit_mb`), `PreviewPlatform` (SAF descriptors, `BitmapFactory`, region decoder), `PrefetchController`, `PreviewWarmup`, `PreviewBenchmark`.
- UI: `PhotoThumbnail` for every thumbnail, `LoupeImage` + `LoupeZoomState` for the loupe and compare (fit at the view's real size, true 100 %, one region tile). Both reach the repository through `LocalPreviewRepository`.
- `util/RawMetadataParser` (ExifInterface via FileDescriptor), `util/xmp/` (`XmpDocument`, `XmpMapper`: pure sidecar model and mapping).
- `data/xmp/` — `SidecarStore` (SAF and local), `XmpWriteQueue` (debounced writes; only a row's `xmpPendingFields` are written), `SidecarReconciler` (catalog vs file, export merge).

## Rules
- **XMP sidecars are the source of truth; Room is a cache.** Never discard unknown content in an existing .xmp. Always merge through `XmpDocument`.
- File access is SAF only (no MANAGE_EXTERNAL_STORAGE). Always take persistable permissions for picked trees. Never use `DocumentFile.listFiles()`/`findFile()` in hot paths; use `DocumentsContract` queries and stored document URIs.
- Create sidecar documents with MIME `application/octet-stream` so providers don't append extensions; verify the returned display name.
- Any change to a Room entity bumps the DB version and commits the exported schema in `app/schemas/`.
- Room uses real migrations from v5; never add a destructive fallback. The catalog holds the duplicate index and pending XMP writes, which can't be rebuilt.
- The one exception is `preview_cache.db`: it's a cache that can always be rebuilt from the photos, so it may use a destructive migration. The catalog rule above is unchanged.
- All image loading goes through `PreviewRepository`; don't decode photos anywhere else.
- Previews are the JPEGs the camera embedded (or the companion JPEG), never a RAW sensor decode. The one exception is a DNG with no usable preview, which goes through `ImageDecoder` as a last resort.
- Every screen must work at Compact, Medium and Expanded widths, in portrait and landscape, and in freely resized windows: see the window size spec in `docs/plans/R3_shell_library_import.md`. Don't lock orientation. Keep screen state in ViewModels or `rememberSaveable`, apply insets per screen, and keep touch targets at 48 dp or more.
- An import never overwrites, never deletes what it can't prove it made, and only deletes from a detected card after a whole group has verified.
- I/O runs on `Dispatchers.IO`; nothing blocks the main thread; long work is cancellable.
- Colors: use theme tokens: `MaterialTheme.colorScheme`, `RawCullTheme.colors` (pick, reject, star, labels, sync states) and the named values in `ui/theme/Color.kt`. No hex literals in UI code. Shared sizes are in `ui/theme/Dimens.kt`.
- Deleting photos: a group's files go together. Flush `XmpWriteQueue` and re-read the rows first, because a write can create a sidecar. `MediaStore.getMediaUri` gives a Files URI that `createTrashRequest` refuses, so trashable files are addressed in the Images collection by id; anything without an image MIME type, every `.xmp` included, can only be deleted permanently. If the system dialog is cancelled, nothing is deleted, and a group whose file is still there after the trash skips its permanent part. Deleted rejects stay in `imported_files`.

## Every round is an update
A round's build must install over the previous round's and keep the tester's library, ratings and settings.
- Bump `versionCode` in a round's first commit, and never lower it.
- Room migrations only go forward, each with a migration test from the previous version with data.
- Never rename or drop a DataStore key without a migration.
- Never change `applicationId` or the debug signing key.
- Never ask the tester to uninstall.

## Planning docs
Feature plans live in `docs/plans/`. Implement exactly one plan at a time, one commit per numbered task, and append "Implementation notes" (deviations, open questions, measurements) to the plan when done.
