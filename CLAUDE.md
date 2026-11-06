# RawCull — notes for Claude Code

Android photo culling + ingest DAM (tablet-first, phone-responsive). Kotlin, Jetpack Compose, Material 3, Room, DataStore, Storage Access Framework. Package `dev.shehran.rawcull`. minSdk 24, targetSdk 36.

## Build & test
- `./gradlew testDebugUnitTest assembleDebug` (Windows: `gradlew.bat ...`). Every commit must build and pass unit tests.
- Pure logic (grouping, merging, XMP, path building, selection) lives in Android-free classes with plain JUnit tests. Use Robolectric only where Android types are unavoidable.

## Architecture (single Activity, manual DI)
- `RawCullApp` → `AppContainer` (database, DAO, repositories, ScanCoordinator, app scope).
- `ui/RawCullViewModel` holds one `StateFlow<DamUiState>`. Selection is **id-based** (`selectedPhotoId`); indices are derived.
- `data/scan/` — `SafTreeWalker` (DocumentsContract cursor walk), `AssetGrouper` (RAW+JPEG/HEIF pairing, pure), `ScanMerger` (pure), `ScanCoordinator` (phase A listing, phase B lazy EXIF/XMP enrichment).
- `data/model/PhotoAsset` (Room entity `photo_assets`), `PhotoFormat`, `PairMode`, `CullStatus`, `ColorLabel`.
- `util/RawPreviewLoader` (previews/thumbnails), `util/RawMetadataParser` (ExifInterface via FileDescriptor), `util/Xmp*` (sidecars).
- Layout switch: `MainDamScreen` → `TabletDamScreen` (≥700dp) / `MobileDamScreen`.

## Rules
- **XMP sidecars are the source of truth; Room is a cache.** Never discard unknown content in an existing .xmp (after Round 2: always merge through `XmpDocument`).
- File access is SAF only (no MANAGE_EXTERNAL_STORAGE). Always take persistable permissions for picked trees. Never use `DocumentFile.listFiles()`/`findFile()` in hot paths; use `DocumentsContract` queries and stored document URIs.
- Create sidecar documents with MIME `application/octet-stream` so providers don't append extensions; verify the returned display name.
- Any change to a Room entity bumps the DB version and commits the exported schema in `app/schemas/`.
- I/O runs on `Dispatchers.IO`; nothing blocks the main thread; long work is cancellable.
- Colors: use the existing palette (bg #07090D, surface #0F141B, cyan #38BDF8, pick #10B981, reject #EF4444, stars #FBBF24). Move toward theme tokens when touching a component.

## Planning docs
Feature plans live in `docs/plans/`. Implement exactly one plan at a time, one commit per numbered task, and append "Implementation notes" (deviations, open questions, measurements) to the plan when done.
