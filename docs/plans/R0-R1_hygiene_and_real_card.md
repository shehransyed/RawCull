# RawCull — Implementation Plan: Round 0 (Hygiene) + Round 1 (Works on a real card)

Status: **READY FOR CLAUDE CODE** · Planned 2026-11-06 · Base commit `44b9979` (main)
Follow-up: Round 2 (Trustworthy XMP) is in `R2_trustworthy_xmp.md` and assumes this plan is merged.

---

## Decisions baked into this plan (Shehran: change these before handing off if you disagree)

| # | Decision | Default chosen |
|---|---|---|
| D1 | Stop maintaining the Google AI Studio scaffolding (Firebase, Gemini key, google-services/secrets plugins, AI-Studio signing comments). | **Yes, remove.** Reversible via git. |
| D2 | New package / applicationId (installs as a new app; old demo data is lost, which is fine). | **`dev.shehran.rawcull`** for both `namespace` and `applicationId` |
| D3 | Scan the picked folder recursively. | **Yes**, max depth 6, skip hidden/system folders |
| D4 | Formats recognised in Round 1. | RAW: ARW, DNG, CR2, CR3, NEF, NRW, RAF, ORF, RW2, PEF, SRW. Raster: JPG/JPEG, HEIC/HEIF/HIF. |
| D5 | Room is a disposable cache until v1.0 (XMP is the source of truth), so destructive migration stays, but schemas get exported from now on. | Yes |
| D6 | Demo shoot only loads when the user asks; it gets its own pseudo-folder and never mixes with real catalogs. | Yes |

---

## Handoff prompt (paste into Claude Code)

> You are working in the RawCull Android repo (Kotlin, Jetpack Compose, Material 3, Room, SAF). Implement **Round 0** and **Round 1** exactly as specified in `docs/plans/R0-R1_hygiene_and_real_card.md`. Work on branch `feature/r0-r1-real-card`. Make **one commit per numbered task** (R0.1 … R1.7) with a conventional-commit message. After every commit the project must build: run `./gradlew testDebugUnitTest assembleDebug` (on Windows `gradlew.bat …`). Don't implement anything from Round 2 (XMP merge/write queue) beyond what this plan says. When finished, append a short "Implementation notes" section to the plan file listing deviations, open questions, and the measured scan timings from R1.4.

---

## Round 0 — Hygiene

### R0.1 Line endings and ignore rules
- Add `.gitattributes`:
  ```
  * text=auto eol=lf
  *.bat text eol=crlf
  *.jar binary
  *.png binary
  *.jpg binary
  *.webp binary
  *.ARW binary
  *.DNG binary
  ```
- Run `git add --renormalize .` and commit separately ("chore: normalise line endings").
- `.gitignore`: ignore `/.idea/` entirely (keep nothing from it). Commit `gradle/gradle-daemon-jvm.properties` (it's the Gradle toolchain file and belongs in VCS).

### R0.2 Remove AI Studio / unused dependencies (D1)
- Remove plugins: `google.services`, `secrets` (root + app `build.gradle.kts`, `libs.versions.toml`). Remove the `secrets {}` and `googleServices {}` blocks.
- Remove deps: `firebase-bom`, `firebase-ai`, `firebase-appcheck-*`, `retrofit`, `converter-moshi`, `okhttp`, `logging-interceptor`, `moshi-kotlin`, `ksp(moshi-kotlin-codegen)`, `coil-compose` (Round 3 decides on image loading). Delete their catalog entries plus the unused camera/location/credentials/auth/firestore/accompanist entries.
- Delete `.env.example`, `metadata.json`, `.aistudio/`, and `public/` (check `public/` only holds AI Studio assets first; if it holds anything else, leave it and note that).
- Signing: keep a `release` signing config that reads `KEYSTORE_PATH/STORE_PASSWORD/KEY_PASSWORD` env vars, but **only apply it if `KEYSTORE_PATH` is set**, so debug and release builds work locally without the AI Studio keystore. Remove the "Do not remove… AI Studio" comments. Keep the optional `debug.keystore` logic.
- Enable `buildConfig` only if something uses it (nothing should after this; if so, turn it off).

### R0.3 Rename package and identity (D2)
- `namespace = "dev.shehran.rawcull"`, `applicationId = "dev.shehran.rawcull"`, `versionCode = 4`, `versionName = "0.4.0"` (restart semver pre-1.0).
- Move all sources from `com/example` to `dev/shehran/rawcull` (main, test, androidTest) and update packages/imports.
- Rename leftover "Lumina" identifiers: DB file → `rawcull_catalog.db`; disk cache dir → `rawcull_preview_cache`; XMP toolkit string → `RawCull <versionName>` (read from `BuildConfig.VERSION_NAME` or a constant). Theme function `MyApplicationTheme` → `RawCullTheme`; `Theme.MyApplication` → `Theme.RawCull`.

### R0.4 App container and settings store
- Add `RawCullApp : Application` (register it in the manifest) holding an `AppContainer` (manual DI, no Hilt): `database`, `photoDao`, `catalogRepository`, `settingsRepository`, plus an application `CoroutineScope(SupervisorJob() + Dispatchers.Default)`.
- `RawCullViewModel` gets its dependencies from the container through a `ViewModelProvider.Factory` (`viewModelFactory { initializer { … } }`) and no longer builds the DB itself.
- Un-comment/add `androidx.datastore:datastore-preferences` (catalog already has 1.1.7). `SettingsRepository` wraps DataStore. Round 1 uses it for recent folders; Round 2 adds XMP prefs.
- Room: `exportSchema = true`, with the KSP arg `room.schemaLocation = "$projectDir/schemas"`. Commit the generated schema JSON. Keep `fallbackToDestructiveMigration(dropAllTables = true)` (D5).

### R0.5 `CLAUDE.md`
Create `CLAUDE.md` at the repo root with the content in **Appendix A** of this file (update package paths to the new ones).

**R0 acceptance:** clean build plus unit tests pass; the app installs as `dev.shehran.rawcull` and launches; no Firebase or Retrofit classes in the APK (`./gradlew :app:dependencies` shows none); `git status` is clean after a fresh build.

---

## Round 1 — Works on a real card

### Data-model changes (Room version 2 → 3)
Update `RawPhotoItem` (rename the file and class to **`PhotoAsset`**, table `photo_assets`, and update all call sites):

| Field | Change |
|---|---|
| `id: String` | **Stable id**: `sha1(rootTreeUri + "|" + relativeDir.lowercase() + "|" + stem.lowercase())` as hex. Pure function `AssetIds.of(...)`. |
| `rootUri: String` | *Rename of* `folderUri`: the picked tree URI (the "source"). Add `@Index`. |
| `relativeDir: String` | **New.** Path of the containing folder relative to the root, e.g. `DCIM/100MSDCF` (`""` at the root). |
| `parentDocUri: String` | **New.** Document URI of the containing directory (needed to create sidecars without `findFile`). |
| `fileName/fileUri/fileSizeBytes` | The **primary** file: the RAW if there is one, otherwise the raster file. |
| `format: PhotoFormat` | Rename the enum `RawFormat` → `PhotoFormat`. Entries: `ARW, DNG, CR2, CR3, NEF, NRW, RAF, ORF, RW2, PEF, SRW, RAW_OTHER, JPEG, HEIF`. Properties: `extension`, `displayName`, `isRaw`, `mimeType` (CR3 → `image/x-canon-cr3`, unknown RAW → `application/octet-stream`). |
| `jpegFileName/jpegFileUri/jpegFileSizeBytes` | Rename → `companionFileName/companionFileUri/companionFileSizeBytes`. Add `companionFormat: PhotoFormat?` (JPEG or HEIF). |
| `pairMode: PairMode` | Enum becomes `RAW_ONLY, RASTER_ONLY, RAW_AND_RASTER`. Badge text is computed in a UI helper: `ARW`, `JPG`, `HIF`, `ARW+JPG`, `ARW+HIF`, etc. |
| `xmpFileUri` | Keep. Set whichever existing sidecar is found (see R1.3). |
| `fileLastModified: Long` | **New.** Document `LAST_MODIFIED` of the primary file. |
| `metadataLoaded: Boolean` | **New**, default `false`. Phase-B enrichment sets it. |
| `dateTaken` | Stays. Initially `fileLastModified`, replaced by EXIF `DateTimeOriginal` in phase B. |
| `isSample` | Remove. The demo shoot uses `rootUri = "rawcull-demo://sample"`. |

Update `Converters` and every UI/VM usage. Keep the `hasCompanion` and `baseStem` helpers, renamed accordingly.

### R1.1 Persistable SAF permissions and recent sources
- Every `OpenDocumentTree` result (source **and** export destination) immediately calls `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)` inside `try/catch(SecurityException)`. Put this in a `SafPermissions` helper.
- Display names come from `DocumentFile.fromTreeUri(ctx, uri)?.name`, with the current `lastPathSegment` parsing as the fallback.
- `SettingsRepository` stores:
  - `recentSources`: up to 8 entries `{uri, displayName, lastOpenedAt}` as a JSON string. Most recent first, de-duplicated by URI.
  - `lastSourceUri`, `lastDestinationUri` (+ display name).
- When a recent entry is evicted and is neither the destination nor another recent entry, call `releasePersistableUriPermission`. On startup, prune recents whose URI isn't in `contentResolver.persistedUriPermissions`.
- **Restore on launch:** if `lastSourceUri` still has a persisted permission and `DocumentFile.fromTreeUri(...).exists()`, open it automatically (show the cached catalog right away, then rescan in the background; see R1.4). If the permission is there but the volume is missing (card unplugged), show the "Card not connected" state from R1.6 with a **Retry** button.
- The export dialog pre-fills the destination from `lastDestinationUri`.

### R1.2 Session-scoped catalog (D6)
- `RawPhotoDao`: add `observeByRoot(rootUri): Flow<List<PhotoAsset>>`, `getByRoot(rootUri)`, `getIdsByRoot(rootUri)`, `deleteByIds(ids)`, `upsertAll(list)` (`@Upsert`), and `updateMetadataBatch(list)` (`@Update` in a `@Transaction`).
- The VM holds `currentRootUri: StateFlow<String?>` and collects `currentRootUri.flatMapLatest { dao.observeByRoot(it) }`. **Delete** the "DB empty → load sample shoot" behaviour in `init`.
- `loadSampleShoot()` sets `currentRootUri = "rawcull-demo://sample"`. `SamplePhotosGenerator` writes its items with that root. XMP writes for the demo root go to its local `filesDir` folder, as they do today.
- When `currentRootUri == null`, the UI shows the **Welcome / empty state** (R1.6).

### R1.3 Recursive, cursor-based scanner and pure grouping logic
Create `data/scan/` with:

1. **`SafTreeWalker`**: breadth-first walk using `DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)` and a single `contentResolver.query` per directory with projection `COLUMN_DOCUMENT_ID, COLUMN_DISPLAY_NAME, COLUMN_MIME_TYPE, COLUMN_SIZE, COLUMN_LAST_MODIFIED`. Start from `DocumentsContract.getTreeDocumentId(treeUri)`. Emits `ScannedEntry(docId, docUri, parentDocId, parentDocUri, relativeDir, name, mime, size, lastModified)`.
   - Depth limit 6.
   - Skip directories whose name starts with `.` or is one of (case-insensitive): `MISC`, `AVF_INFO`, `PRIVATE`, `System Volume Information`, `LOST.DIR`, `$RECYCLE.BIN`, `.Trashes`, `.Spotlight-V100`, `.fseventsd`. (`PRIVATE` holds Sony video; video is out of scope.)
   - Never use `DocumentFile.listFiles()` in the scan path.
   - It's cancellable (`ensureActive()` per directory) and reports progress `(dirsVisited, filesSeen)`.
2. **`AssetGrouper`**: a **pure Kotlin object with no Android types** so it's JVM-testable. Input: `List<ScannedEntry>`. Output: `GroupingResult(assets: List<AssetDraft>, orphanSidecars: List<ScannedEntry>)`.
   - Group key = `(parentDocId, stem.lowercase())`, where stem = name before the **last** dot.
   - Within a group: primary = the RAW (if a group somehow has 2 RAWs, e.g. `.ARW` + `.DNG`, each RAW becomes its own asset and the raster companion attaches to the first by `PhotoFormat` order). Companion priority: JPEG > HEIF. A raster file left over after pairing becomes its own `RASTER_ONLY` asset.
   - Sidecars: match `<stem>.xmp` (Adobe style) **and** `<name>.<ext>.xmp` (darktable style, e.g. `DSC001.ARW.xmp`), case-insensitive. Record both in the draft (`adobeXmp`, `darktableXmp`); `xmpFileUri` = Adobe-style if present, else darktable-style.
   - Ignore everything else (video, `.THM`, etc.).
3. **`ScanMerger`** (pure): given `existing: Map<id, PhotoAsset>` and new drafts, produce `toUpsert` and `toDelete` (ids no longer on disk).
   - Existing rows keep their user state (cullStatus, rating, colorLabel, tags) and their enriched metadata **unless** `fileLastModified` changed, in which case `metadataLoaded = false` so the row is re-read.
   - New rows start with `dateTaken = fileLastModified`, `metadataLoaded = false`.
   - Round 2 refines how XMP-vs-DB conflicts resolve. For now, XMP is read during enrichment and wins only when the row is new or the XMP file's `lastModified` is newer than the row's `lastModified`.

### R1.4 Two-phase scan with lazy enrichment
Rewrite `RawCatalogRepository.scanSourceFolder` as `ScanCoordinator` (in the container, one active scan at a time; starting a new root cancels the old scan):

- **Phase A (listing):** walk the tree → group → merge → `upsertAll` + `deleteByIds` in one transaction. The UI shows the grid immediately from Room with filename-based placeholders. Status text: `Found 842 photos in 5 folders`.
- **Phase B (enrichment)** for rows with `metadataLoaded = false`:
  - Order: start from the currently selected asset and work outward (±), then the rest in display order. Expose `ScanCoordinator.prioritize(id)`, which the VM calls when the selection changes.
  - Open with `contentResolver.openFileDescriptor(uri, "r")` and use `ExifInterface(pfd.fileDescriptor)` (seekable, so only headers are read), **not** `ExifInterface(InputStream)`. Change `RawMetadataParser` to take a `FileDescriptor`, keep the `InputStream` overload for tests, and use the companion JPEG as the EXIF source when the primary isn't parseable (CR3, unknown RAW).
  - Read the sidecar with the existing `XmpSidecarManager.readSidecar` (Round 2 replaces the parser).
  - Concurrency: `Semaphore(3)` on `Dispatchers.IO`. Write results to Room in batches of 25 (or every 500 ms) through `updateMetadataBatch`, so the UI isn't flooded with emissions.
  - Status: `Reading metadata 120 / 842`. This phase is cancellable.
- **Instrumentation:** log with tag `RawCull.Scan` the phase-A duration, the files/sec rate, the phase-B total, and the median per-file time. Record one real measurement (card + reader model, file count) in the plan's Implementation notes.
- **Targets** (USB-3 card reader, ~1,000 files): phase A < 2 s; first thumbnails visible < 3 s after the pick. Record the measured numbers whether or not they meet the target.
- **Errors:** a `SecurityException`, `FileNotFoundException` or `IllegalArgumentException` during a scan (card pulled out) ends the scan in a `SourceUnavailable` state (R1.6) with no crash and no DB wipe.

### R1.5 Stable selection across re-sorts and filters
Enrichment changes `dateTaken`, which re-sorts the list, and today the selection is index-based, so the selected photo would jump. Change this now:
- `DamUiState` keeps `selectedPhotoId: String?` and `comparePhotoId: String?`. `selectedIndex` and `compareIndex` become **derived** properties (`filteredPhotos.indexOfFirst { it.id == selectedPhotoId }`).
- `nextPhoto`, `previousPhoto`, `selectPhotoIndex` and key handling resolve through the id.
- `updateFilter`: keep the current id if it still matches the new filter. Otherwise select the nearest photo that does (by position in the old list, searching forward then backward), not index 0.
- When flagging makes the current photo drop out of the active filter (e.g. Reject while viewing "Picks"), move to the next photo that still matches. (Full auto-advance is Round 4; this only prevents the selection being lost.)
- `FilmstripBar` and `GridViewGallery` scroll to the selected id.

### R1.6 Welcome, source and state UI
- **Welcome screen** (shown when `currentRootUri == null`), with dark styling consistent with the current theme:
  - Primary button **"Open SD card / USB drive"**: on API 29+, list `StorageManager.storageVolumes.filter { it.isRemovable && it.state == MEDIA_MOUNTED }`. One volume → launch `volume.createOpenDocumentTreeIntent()`. Several → a chooser with the volumes' descriptions. None → the button reads "No card detected — Pick folder" and launches the generic picker. Below API 29, use the generic `OpenDocumentTree`.
  - Secondary button **"Pick a folder"**: `OpenDocumentTree` with `EXTRA_INITIAL_URI` set to the last source when available.
  - **Recent sources** list (name, relative age, unavailable ones greyed out with "not connected").
  - Text link **"Try the demo shoot"**.
- **TopDamBar:**
  - The source label opens a dropdown: recents, "Open SD card…", "Pick folder…", "Close source" (back to Welcome), "Rescan".
  - Move "Demo Shoot" into that dropdown.
  - Show a subtle scan/enrichment progress line under the bar while a scan runs.
  - Below 600dp, collapse the row-1 view-mode labels to icons only and put Select and Ingest in an overflow, so nothing overflows on phones (test at 360dp width).
- **Source-unavailable state:** a banner over the catalog: "Card disconnected. Reconnect it and tap Retry" with a Retry action. The cached catalog stays browsable read-only (thumbnails that are already cached still show; flag/rate actions are disabled with a snackbar explaining why).
- **Folder breadcrumb:** in Grid view, group headers by `relativeDir` when the source contains more than one folder (a simple sticky header in the `LazyVerticalGrid` via `item(span = { GridItemSpan(maxLineSpan) })`).
- **Filters:** replace the hard-coded "Sony ARW" / "Adobe DNG" chips with chips generated from the formats actually present in the current catalog (`RAW+JPG`, `ARW`, `JPG`, …).

### R1.7 Tests
JVM unit tests (plain JUnit unless Android types are unavoidable; then Robolectric):
- `AssetGrouperTest`:
  - ARW+JPG pair
  - JPG only
  - HIF only
  - ARW+JPG+HIF (JPG wins; HIF becomes standalone)
  - case-insensitive names (`dsc001.arw` + `DSC001.JPG`)
  - same stem in two folders → 2 assets
  - multiple dots (`trip.v2.ARW`)
  - both sidecar naming styles
  - `.THM`/`.MP4` ignored
  - two RAWs with the same stem
- `AssetIdsTest`: the same inputs give the same id; different relativeDir gives a different id.
- `ScanMergerTest`: user state preserved on rescan; changed `fileLastModified` → `metadataLoaded = false`; removed files → `toDelete`; new files get defaults.
- `SafTreeWalkerTest` (Robolectric with a fake `DocumentsProvider`, or extract the skip/depth rules into a pure `WalkPolicy` and unit-test that instead; pick whichever is simpler and say which in the notes).
- `SelectionLogicTest`: extract the "nearest still-matching photo" logic into a pure function and test it.
- Keep or port the existing `ExampleRobolectricTest` cases to the renamed types.

**R1 acceptance checklist (Shehran verifies manually on the tablet):**
1. Pick the root of a real camera card → photos from `DCIM/*` appear in under 3 s; metadata and thumbnails fill in progressively; the selected photo doesn't jump while it happens.
2. Kill the app, relaunch → the same source reopens without a picker. Unplug the card → "Card disconnected" banner; plug it back in → Retry works.
3. A folder containing only JPEGs shows them all, badged `JPG`.
4. Demo shoot only appears when chosen and never mixes with card photos.
5. Rate/flag still writes `.xmp` next to the files (current Round-1 writer).
6. At 360dp width the top bar doesn't overflow.

---

## Appendix A — `CLAUDE.md` content

```markdown
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
```

---

## Implementation notes

Implemented on `feature/r0-r1-real-card`. 86 JVM tests pass. The flows below were also run end to end on an API 36 emulator with a synthetic card on its removable volume. **No real card, reader, tablet or camera RAW file has been tested yet**, so the R1 acceptance checklist still needs its manual pass.

### Measured scan timings (R1.4)
**Not a real measurement.** The plan asks for one with a real card and reader, which wasn't available. These are emulator numbers only and say little about a USB-3 reader. 1,007 files (500 ARW+JPG pairs, 2 JPEGs, 5 other files), 5 folders, very small files, Medium Phone API 36 with software rendering.

| Run | Phase A | of which walk | Files/s | Phase B (502 assets) | Median per file |
|---|---|---|---|---|---|
| Cold, first run after copying | 8.6 s | not logged | 117 | 12.2 s | 62 ms |
| Second run | 4.1 s | 3.3 s | 242 | 7.6 s | 38 ms |
| Warm | 1.6 s | 1.3 s | 616 | 5.9 s | 30 ms |

- The target of phase A < 2 s was met only warm. A freshly inserted card is the cold case, so this needs checking on hardware.
- The walk dominates phase A; grouping and merging took about 0.3–0.8 s.
- Time to first thumbnail wasn't measured.
- To record the real figure: `adb logcat -s RawCull.Scan`, then add the card and reader model and the file count here.

### Deviations
1. **Branch and repo setup.** `docs/plans/` and `gradle-daemon-jvm.properties` were untracked in the main checkout, so they were copied in and committed. The repo had no `gradlew`, `gradlew.bat` or wrapper jar, so these were added in R0.1.
2. **Commit count.** R0.1 is two commits, as the plan asks. The Room v3 data model, which has no task number, is its own commit before R1.1.
3. **Asset ids.** `sha1(root|dir|stem)` collides for two RAWs with one stem and for a leftover raster. `AssetIds.of` takes an optional discriminator (the lowercase extension) for every asset after the first in a stem group.
4. **Shared sidecars.** Those same assets would all write `<stem>.xmp`. It now goes to the first asset only; the others use `<name>.<ext>.xmp`.
5. **`updateMetadataBatch`** is not a whole-row `@Update`, which would overwrite a flag set during enrichment. It uses a partial entity plus a conditional `UPDATE ... WHERE lastModified < :xmpLastModified`. New rows start with `lastModified = 0`, which is how "XMP wins on a new row" is expressed.
6. **`buildConfig` stays on**, because the XMP toolkit string uses `BuildConfig.VERSION_NAME`.
7. **`.RAW` files** are still recognised (as `RAW_OTHER`), as the old scanner did, though D4 doesn't list them.
8. **`SafTreeWalkerTest`** took the plan's simpler option: the rules are in the pure `WalkPolicy`, tested by `WalkPolicyTest`. The walker itself was exercised on the emulator only.
9. **Grid headers aren't sticky.** `LazyVerticalGrid` has no sticky-header API, so they are full-width items that scroll away. One appears at each change of folder, which keeps the active sort but can repeat a folder if sort order interleaves folders.
10. **Enrichment order** follows capture date, not the active sort, so it matches display order only for the default sort. It still works outward from the selection.

### Additions the plan didn't list
- A `MEDIA_*` broadcast receiver and a resume check. Without them, unplugging a card while no scan is running would never show the banner.
- "Close source" clears `lastSourceUri`, so the next launch goes to Welcome.
- `ScanMerger` also resets `metadataLoaded` when a sidecar is newer than the row. Otherwise an externally edited sidecar would never be read on rescan.
- The sidecar URI is stored after the first write, so repeat ratings don't create `DSC001 (1).xmp`.
- Preview caches are keyed on id + `fileLastModified`: stable ids could otherwise show a stale preview for a reused file name.
- JPEG/HEIF primaries decode directly, because the EXIF thumbnail is too small for the loupe.
- `._*` macOS stubs are ignored.
- Exported sidecars and HEIF files are created as `application/octet-stream`.
- The histogram no longer restarts on every enrichment batch.

### Open questions
1. **Android refuses the root of an SD card in the folder picker** ("Can't use this folder"), seen on the emulator's removable volume, including through `createOpenDocumentTreeIntent()`. Acceptance step 1 says to pick the card root; here the user has to pick `DCIM`, which works. USB readers may be exempt, but that's untested. If your tablet blocks the root, `PRIVATE` is simply never in scope, but the Welcome screen could use a hint to open `DCIM`. Decide after trying it on the tablet.
2. **Rating a photo currently replaces an existing sidecar's contents.** Confirmed on the emulator: the `.xmp` is rewritten and anything else in it is lost. That's the plan's accepted Round-1 writer, but on a card with Lightroom or darktable sidecars it destroys their edits. **Don't rate photos with sidecars you care about until Round 2 lands.**
3. **The `XMP LIVE` badge is static.** `xmpSyncStatus = ERROR` is now recorded, but the UI doesn't show it, so a failed write (for example a write-protected card) looks like success. Round 2 covers this.
4. **RAW previews weren't tested with real files.** The fixture `.ARW` files are JPEG bytes. Real ARW, CR3 (companion EXIF path) and HEIF decoding haven't been run, and RAW loupe quality still depends on the EXIF thumbnail (Round 3).
5. **The selected photo isn't restored after a relaunch**; the first photo is selected. The plan doesn't ask for it.

### Verified on the emulator
Open via the card button; 52 assets in 3 folders with hidden, `PRIVATE`, `MISC` and video files excluded; both sidecar styles read; selection held while enrichment re-sorted; JPEG-only folder badged `JPG`; one new sidecar next to the file after three ratings; relaunch reopens without a picker; banner on unmount while idle; banner after relaunch with the card out; edits blocked with a snackbar; Retry after remount; unmount during a 1,007-file scan with no crash and no catalog loss; demo isolated from card photos; Close source and recents; no top-bar overflow at 360dp; layouts at 640dp and 1280dp.

Two bugs were found this way and fixed in R1.6: snackbars were cancelled before they could show, and cards without EXIF showed a stray `• • ISO`.
