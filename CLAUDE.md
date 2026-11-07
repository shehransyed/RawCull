# RawCull — notes for Claude Code

Android photo culling + import-first DAM (tablets and phones, any orientation or window size). Kotlin, Jetpack Compose, Material 3, Room, DataStore, Storage Access Framework. Package `dev.shehran.rawcull`. minSdk 24, targetSdk 36.

## Build & test
- `./gradlew testDebugUnitTest assembleDebug` (Windows: `gradlew.bat ...`). Every commit must build and pass unit tests.
- Pure logic (grouping, merging, XMP, path building, selection) lives in Android-free classes with plain JUnit tests. Use Robolectric only where Android types are unavoidable.
- `XmpDocumentDeviceTest` runs the XMP fixtures on Android's own XML stack. With a phone or tablet attached, set `ANDROID_SERIAL` first, because connected tests install on every device.

## Architecture (single Activity, manual DI)
- `RawCullApp` → `AppContainer` (database, DAOs, repositories, ScanCoordinator, import session/engine/controller, app scope).
- `ui/shell/` — `RawCullApp()` composable: `NavigationSuiteScaffold` (bar, rail or wide rail) around Nav3 `NavDisplay`. Tabs **Import**, **Library**, **Settings**, one back stack each in `TabStacks`. Nav3 keys in `AppKey`: `Library`, `ImportSource`, `ImportPreview`, `ImportProgress(jobId)`, `Settings`. `WindowLayout` turns the window size class into layout decisions; read it through `LocalWindowLayout`.
- ViewModels created inside a `NavEntry` must get their dependencies passed in: the entry's store has no `APPLICATION_KEY`. Activity-scoped ones (`LibraryViewModel`, `ImportViewModel`, `ShellViewModel`) can use it.
- `ui/library/LibraryViewModel` holds one `StateFlow<LibraryUiState>`: active Location, `LibraryScope` (`All`, `Folder`, `LatestImport`), filters, culling. Selection is **id-based** (`selectedPhotoId`); indices are derived.
- Locations: `LibraryLocation` rows through `LocationRepository` (add, rename, relink, remove; refuses while edits are unsaved). The demo library is a pseudo-Location.
- `importer/` — Android-free import logic: `PathTemplate`, `ImportPlanner`, `ConflictNamer`, hashing, `DuplicateCheck`, `ImportPreview`, `ImportSession` (card state, memory only), `DocumentIo` (the SAF seam). `library/` — `FolderTree`, `LibraryScope`.
- `data/importer/` — `ImportEngine` (all the copy, verify, resume and delete rules), `ImportWorker` (thin WorkManager foreground shell), `ImportController` (start, resume, retry, cancel), `SafDocumentIo`. The card never enters `photo_assets`; the worker inserts library rows itself.
- `data/scan/` — `SafTreeWalker` (DocumentsContract cursor walk), `AssetGrouper` (RAW+JPEG/HEIF pairing, pure), `ScanMerger` (pure), `ScanCoordinator` (phase A listing, phase B lazy EXIF/XMP enrichment; `rescanSubtree` for one folder).
- `data/model/PhotoAsset` (Room entity `photo_assets`), `PhotoFormat`, `PairMode`, `CullStatus`, `ColorLabel`.
- `util/RawPreviewLoader` (previews/thumbnails), `util/RawMetadataParser` (ExifInterface via FileDescriptor), `util/xmp/` (`XmpDocument`, `XmpMapper`: pure sidecar model and mapping).
- `data/xmp/` — `SidecarStore` (SAF and local), `XmpWriteQueue` (debounced writes; only a row's `xmpPendingFields` are written), `SidecarReconciler` (catalog vs file, export merge).

## Rules
- **XMP sidecars are the source of truth; Room is a cache.** Never discard unknown content in an existing .xmp. Always merge through `XmpDocument`.
- File access is SAF only (no MANAGE_EXTERNAL_STORAGE). Always take persistable permissions for picked trees. Never use `DocumentFile.listFiles()`/`findFile()` in hot paths; use `DocumentsContract` queries and stored document URIs.
- Create sidecar documents with MIME `application/octet-stream` so providers don't append extensions; verify the returned display name.
- Any change to a Room entity bumps the DB version and commits the exported schema in `app/schemas/`.
- Room uses real migrations from v5; never add a destructive fallback. The catalog holds the duplicate index and pending XMP writes, which can't be rebuilt.
- Every screen must work at Compact, Medium and Expanded widths, in portrait and landscape, and in freely resized windows: see the window size spec in `docs/plans/R3_shell_library_import.md`. Don't lock orientation. Keep screen state in ViewModels or `rememberSaveable`, apply insets per screen, and keep touch targets at 48 dp or more.
- An import never overwrites, never deletes what it can't prove it made, and only deletes from a detected card after a whole group has verified.
- I/O runs on `Dispatchers.IO`; nothing blocks the main thread; long work is cancellable.
- Colors: use the existing palette (bg #07090D, surface #0F141B, cyan #38BDF8, pick #10B981, reject #EF4444, stars #FBBF24). Move toward theme tokens when touching a component.

## Planning docs
Feature plans live in `docs/plans/`. Implement exactly one plan at a time, one commit per numbered task, and append "Implementation notes" (deviations, open questions, measurements) to the plan when done.
