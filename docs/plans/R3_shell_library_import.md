# RawCull — Implementation Plan: Round 3 (Adaptive shell + Library + Import)

Status: **READY FOR CLAUDE CODE** · Planned 2026-11-06 · Base commit `73eb70f` (`main`, R0–R2 merged)
Overview of the whole overhaul: `00_workflow_overhaul_overview.md`. Next rounds (R4 culling UX + reject deletion, R5 previews, R6 extras) are out of scope here.

## Goal

Turn RawCull into an **import-first** app, modelled on RapidReady:

- **Import** a card or folder through a three-step wizard (Source → Preview → Import). The copy runs in the background, is verified, survives process death, skips files that were already imported, and can delete verified files from the card.
- **Library**: browse a user-chosen library Location as a whole archive with a folder tree, a *Latest Import* view and all photos, and cull there with the existing grid / loupe / compare / inspector and the R2 XMP stack.
- **Adaptive shell** that follows current Android guidance and works on phones and tablets, in portrait and landscape, in resizable windows.

The card is never culled in place and never written to the catalog.

---

## Decisions baked into this plan (Shehran: change any of these before handing off)

| # | Decision | Default chosen | Why |
|---|---|---|---|
| D1 | Navigation | **Navigation 3** (`androidx.navigation3`, latest stable) for the back stack, with Material 3 Adaptive `NavigationSuiteScaffold` for the top-level Import / Library / Settings switch | Nav3 is the current stable Compose navigation; it gives a saveable back stack and predictive back. `NavigationSuiteScaffold` picks a bottom bar or a rail by window size. |
| D2 | Room migrations | **Real migrations from v5 on** (auto-migrations where possible). `fallbackToDestructiveMigration` is removed. | The catalog now holds import history (the duplicate index) and pending XMP writes; wiping them on upgrade would lose data. *This reverses the earlier "destructive until v1.0" decision.* |
| D3 | Duplicate key | SHA-256 of the **first 64 KB** + file size | Same idea as RapidReady (BLAKE3 there). SHA-256 is hardware-accelerated on Android and needs no library. |
| D4 | Verification | **Full** (default): SHA-256 of the whole source computed while copying, then the copy is re-read and hashed. **Quick** (setting): size + first 64 KB. A mismatch deletes the copy, retries once, then marks the file failed and **continues** with the rest. | The card read is the bottleneck; hashing during the copy is free, and re-reading internal storage or a USB SSD is fast. RapidReady aborts the whole import on one failure; we don't. |
| D5 | Name conflicts in the library | Never overwrite. Same name **and** same content (size + head hash) → skipped as duplicate. Same name, different content → `<stem>-1.<ext>`, `-2`, … applied to the **whole group** (RAW, companion and sidecar share the suffix). | Keeps pairs paired and never loses a file. |
| D6 | Dates for folders | EXIF `DateTimeOriginal` → file `LAST_MODIFIED` (cameras set it) → `Undated/` folder | RapidReady falls back to 1970; an explicit folder is clearer. |
| D7 | Card photos | Held in memory in an `ImportSession` (survives rotation, not process death). Only the import *job* is persisted. | The card is a source, not a library. |
| D8 | Locations | Several saved Locations (named, persisted SAF trees); **one active** in the Library at a time | Matches RapidReady and keeps queries simple. |
| D9 | Default preset | "Standard": date mode, `{year}/{year}-{month}-{day}`. Modes: date (4 formats), custom pattern (`{year}` `{month}` `{day}`), project folder, flat. | RapidReady's set. Renaming is out of scope. |
| D10 | Delete from card | Setting, default **off**. When on, only files that finished **and verified** are deleted, after the job ends. Deletion is permanent (cards have no trash); the setting says so. | Shehran: settings toggle. |
| D11 | Export dialog | **Retired** with `exportCulledFiles`. No "copy selection elsewhere" until asked for. | Import replaces it. O2 (silent export errors) goes away with it. |
| D12 | Library rescans | Show the cached catalog at once; walk the whole Location in the background on open; re-walk only the **active folder subtree** on resume (`ON_START`) and on pull-to-refresh; imported files are inserted directly by the import job. | RapidReady's "cache first, delta later, check the active folder on focus". |
| D13 | Folder operations | Single folder (with subtree) selection only. Multi-select folders, create / rename / delete folder are deferred. | Keeps R3 bounded. |
| D14 | Demo shoot | Kept as a pseudo-Location "Demo library" reachable from the empty Library state. | Useful for testing without a card. |
| D15 | Theme | Keep the dark "darkroom" scheme (no dynamic colour: photo work needs neutral surroundings). Move colours touched in this round to theme tokens. | Existing rule in `CLAUDE.md`. |

---

## Handoff prompt (paste into Claude Code)

> You are working in the RawCull Android repo (Kotlin, Jetpack Compose, Material 3, Room, DataStore, SAF). Implement **Round 3** exactly as specified in `docs/plans/R3_shell_library_import.md`, starting from `main`. Work on branch `feature/r3-shell-library-import`. Make **one commit per numbered task** (R3.0 … R3.13) with a conventional-commit message. After every commit run `./gradlew testDebugUnitTest assembleDebug` (Windows: `gradlew.bat …`); the app must build and tests must pass at every commit. Use the **latest stable** versions of the libraries listed in R3.0 that work with the repo's AGP, Kotlin and compileSdk, and record the versions you chose. Don't implement anything from Rounds 4–6 (overview: `docs/plans/00_workflow_overhaul_overview.md`). Where this plan and the current code disagree, follow the code's established patterns and note the deviation. When finished, append "Implementation notes" to the plan (deviations, open questions, measured copy/verify throughput, what was verified on which emulator/device sizes), and update `CLAUDE.md` as R3.13 says.

---

## Window size and orientation spec (applies to every screen)

Use `currentWindowAdaptiveInfo()` size classes (Material breakpoints: Compact < 600 dp, Medium 600–839, Expanded 840–1199, Large 1200–1599, Extra-large ≥ 1600). Replace the `BoxWithConstraints` ≥ 700 dp switch in `MainDamScreen`.

| Width class | Top-level navigation | Library | Import wizard | Loupe |
|---|---|---|---|---|
| Compact (phones portrait, ~360–599 dp) | Bottom navigation bar | Grid full width. Folder tree + Locations in a modal navigation drawer (button in the top bar). Inspector as a modal bottom sheet. | One step per screen; destination settings stacked under the source list; sticky bottom action bar (Back / Next) | Full screen; culling bar docked at the bottom |
| Medium (phones landscape, foldables, small tablets portrait) | Navigation rail | Grid + inspector as a toggleable side pane (`SupportingPaneScaffold` or equivalent). Folder tree in a modal drawer. | Source and destination side by side | Culling bar docked at the side when height is Compact (phone landscape), else bottom |
| Expanded and wider (tablets landscape, desktop windows) | Navigation rail (expanded rail with labels at Large+) | Three panes: folder pane (~280 dp, collapsible), content, inspector (~320 dp, collapsible) | Source and destination side by side; preview grid with a side inspector | Filmstrip below, inspector at the side |

Rules:
- **All orientations, any window size.** The app targets API 36, so on screens ≥ 600 dp Android ignores orientation and resizability restrictions anyway. Don't lock orientation anywhere. All screen state lives in ViewModels or `rememberSaveable`; rotating or resizing never loses the selection, scroll position, wizard step or ticks.
- **Edge-to-edge done properly:** remove the `statusBarsPadding()/navigationBarsPadding()` wrapper in `MainActivity`; apply `WindowInsets` per screen (`Scaffold` content padding, `safeDrawing` for the loupe, display cutouts in landscape).
- **Predictive back:** `android:enableOnBackInvokedCallback="true"`; Nav3 back stack for screens, `BackHandler`/`PredictiveBackHandler` for in-screen states (loupe → grid, drawer, sheets).
- Touch targets ≥ 48 dp; text and icons readable at 360 dp width; hardware keyboard shortcuts keep working on every layout.
- Verify each screen at 360×640 (phone portrait), 640×360 (phone landscape), 600×960 / 840×1280 / 1280×800 (tablets), and a freely resized desktop window, and list what was checked in the notes.

---

## Data model (Room v4 → v5, real migrations)

New tables (entities in `data/model/`, DAOs in `data/local/`):

**`library_locations`** — `LibraryLocation`
| Column | Type | Notes |
|---|---|---|
| `id` | Long PK autogen | |
| `treeUri` | String, unique | persisted SAF tree (or `rawcull-demo://sample`) |
| `displayName` | String | editable |
| `addedAt`, `lastOpenedAt` | Long | |

**`import_presets`** — `ImportPreset`
| Column | Type | Notes |
|---|---|---|
| `id` | Long PK autogen | |
| `name` | String | |
| `locationId` | Long? | FK → `library_locations` (SET NULL on delete) |
| `structureMode` | enum `DATE, CUSTOM, PROJECT, FLAT` | |
| `datePattern` | String | one of the 4 date formats |
| `customPattern` | String | |
| `projectName` | String | |
Seed "Standard" (D9) on database creation and in the migration.

**`imported_files`** — the duplicate index
| Column | Type | Notes |
|---|---|---|
| `id` | Long PK autogen | |
| `headHash` | String | SHA-256 hex of first 64 KB |
| `sizeBytes` | Long | |
| `originalName` | String | |
| `targetUri` | String | document URI in the library |
| `importedAt` | Long | |
| `jobId` | Long? | |
Index `(headHash, sizeBytes)` and `(sizeBytes)`.

**`import_jobs`** — `ImportJob`: `id`, `createdAt`, `startedAt`, `finishedAt`, `status` (`QUEUED, RUNNING, PAUSED, COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED, SOURCE_LOST`), `sourceTreeUri`, `sourceLabel`, `locationId`, `template` (resolved pattern), `verifyMode` (`FULL, QUICK`), `deleteSourceAfter`, `totalFiles`, `totalBytes`, `doneFiles`, `doneBytes`, `skippedFiles`, `failedFiles`.

**`import_job_items`** — `ImportJobItem`: `id`, `jobId` (FK, CASCADE, indexed), `groupKey` (asset group so suffixes apply together), `sourceUri`, `sourceName`, `sizeBytes`, `targetRelativeDir`, `targetName`, `targetUri?`, `state` (`QUEUED, COPYING, VERIFYING, DONE, SKIPPED_DUPLICATE, FAILED`), `error?`, `headHash?`, `fullHash?`, `sourceDeleted` (bool).

Changes to **`photo_assets`**: add `lastImportJobId: Long?` and an index on `(rootUri, relativeDir)`.

Migration 4 → 5: `@AutoMigration(from = 4, to = 5)` if Room can express it (new tables + nullable column + index), plus a callback that seeds the "Standard" preset. Remove `fallbackToDestructiveMigration` and update the comment on `AppDatabase.build`. Commit `app/schemas/.../5.json`.

DataStore keys added (`SettingsRepository`): `activeLocationId`, `activeFolder` (relative dir, `""` = all), `selectedAssetId` (fixes O7), `verifyMode`, `deleteFromCardAfterImport`, `hideAlreadyImported` (default true), `lastPresetId`, and a map of **volume UUID → granted source tree URI** (so a re-inserted card opens without the picker). Remove the recent-source keys in R3.12.

---

## Tasks

### R3.0 Build upgrade (no behaviour change)
- Bump the Compose BOM to the latest stable (at least `2026.09.00`), lifecycle to the version Nav3 needs, and add: `androidx.compose.material3.adaptive:adaptive`, `adaptive-layout`, `adaptive-navigation` (1.3.x stable), `androidx.compose.material3:material3-adaptive-navigation-suite` (from the BOM), `androidx.navigation3:navigation3-runtime` + `navigation3-ui` (1.2.x stable), `androidx.lifecycle:lifecycle-viewmodel-navigation3`, `androidx.compose.material3.adaptive:adaptive-navigation3` (1.3.x, only if used), `androidx.work:work-runtime-ktx` (+ `work-testing` for tests).
- Manifest: `android:enableOnBackInvokedCallback="true"`.
- Fix any deprecations the bump causes. App behaves exactly as before.

### R3.1 Room v5 and real migrations
Everything in "Data model" above: entities, DAOs, the 4→5 migration, seed preset, schema JSON. Tests: a migration test (`MigrationTestHelper`, androidTest) from v4 with a row in `photo_assets` (including `PENDING` XMP state) that survives; JVM DAO tests (Robolectric, in-memory Room) for the new DAOs.

### R3.2 Pure import and library logic (no Android types, plain JUnit)
In a new package `dev.shehran.rawcull.importer` (pure parts) and `library/`:
- `PathTemplate.resolve(mode, datePattern, customPattern, projectName, captureDate?): String` — relative dir with `/` separators, no leading/trailing slash; `Undated` when the date is missing (D6); sanitises characters SAF providers reject (`\ : * ? " < > |`), trims dots/spaces, collapses empty segments; `FLAT` → `""`.
- `ImportPlanner.plan(candidates, selection, template, existingTargets) : List<PlannedItem>` — expands selected asset groups into files (primary, companion if its format is ticked, existing sidecars), applies the RAW/JPEG format toggles, computes target dir + name, applies D5 conflict naming per group (using a lookup of names already present in each target dir and their sizes/head hashes).
- `ConflictNamer` — `-1`, `-2`, … per group; never collides with names already planned in the same job.
- `FolderTree.build(dirsWithCounts: Map<String, Int>): List<FolderNode>` — nested nodes with subtree totals, sorted by name (date folders sort naturally).
- `HeadHash` interface + a JVM implementation over `InputStream` (first 64 KB, SHA-256 hex) and `StreamingHasher` (wraps copy so the full SHA-256 is computed while bytes are written).
- `DuplicateCheck` — given candidate (size, lazy headHash) and the index, returns imported / not imported, hashing **only** when the size matches a recorded size (RapidReady's pre-filter).
Tests for each, including: every structure mode; `Undated`; forbidden characters; a group renamed together on conflict; same-content skip; turning raster off drops both JPEG and HEIF companions and raster-only photos; sidecars follow the group's suffix; folder tree counts; hashing short files (< 64 KB).

### R3.3 Locations and library scope
- `LocationRepository`: add (from `OpenDocumentTree` result → `SafPermissions.takePersistable`, display name), rename, relink. Before relink or remove, `XmpWriteQueue.flushNow()` and refuse (with a message) while rows of that Location are still `PENDING`/`WRITING`, so no rating is lost. Relink (new tree replaces `treeUri`; rows for the old root are deleted and the new root is scanned; sidecars carry the culling state), remove (release permission, delete rows), availability check (`SafPermissions.isAvailable`), `observeAll()`.
- DAO queries: `observeFolderCounts(rootUri): Flow<Map<String, Int>>` (group by `relativeDir`); `observeScope(rootUri, folder)` returning rows where `relativeDir = :folder OR relativeDir LIKE :folder || '/%' ESCAPE '\'` (escape `%` and `_` in folder names; `folder = ""` → whole Location); `observeByImportJob(jobId)`; latest job id.
- `ScanCoordinator`: keep the two-phase scan per root, used for Locations. Add `rescanSubtree(rootUri, relativeDir)` that walks only that folder's subtree (start the walk at the folder's document id, keep `relativeDir` prefixes correct) and merges only rows inside that subtree. Trigger: open Location → background full scan (cached rows visible immediately); app `ON_START` and pull-to-refresh → `rescanSubtree` for the active folder.
- `RawCullViewModel` (or a new `LibraryViewModel`, see R3.5): the current `currentRootUri` becomes **active Location + active folder + scope** (`ALL`, `FOLDER(relDir)`, `LATEST_IMPORT(jobId)`). Restore active Location, folder and selected asset on launch (O7).
- Tests: scope query with subfolders and LIKE-special characters; restore logic (pure).

### R3.4 Adaptive app shell
- `MainActivity` hosts `RawCullApp()` composable: `NavigationSuiteScaffold` with destinations **Import**, **Library**, **Settings** (icons + labels); Nav3 `NavDisplay` with keys `Library`, `ImportSource`, `ImportPreview`, `ImportProgress(jobId)`, `Settings`. Each top-level tab keeps its own back stack; the selected tab and stacks survive rotation and process recreation (`rememberNavBackStack` or equivalent).
- Start destination: **Library** if any Location exists, else Library's empty state (D14), which offers "Add library location", "Import from card" and "Try demo library".
- While an import job runs, show a small progress indicator on the Import tab (badge) and a slim progress row at the top of Library; tapping either opens `ImportProgress`.
- Remove the `BoxWithConstraints` switch; screens read the window size class (spec above). Edge-to-edge and predictive back as specified.
- Keep keyboard handling: move `onKeyDown` dispatch to the Library screen (only when it is visible) so typing in text fields elsewhere is unaffected.

### R3.5 Library screen
- Split: a `LibraryViewModel` owns scope, filters, selection and culling (move the culling, filter and selection logic from `RawCullViewModel`; keep `SelectionLogic` and id-based selection). `RawCullViewModel` either disappears or keeps only cross-screen state; note what you chose.
- **Folder pane** (Expanded+) / **drawer** (Compact, Medium): Location switcher at the top (available ones selectable; unavailable ones greyed "not connected" with Relink), then *All photos*, *Latest Import* (shows the job date and count), then the folder tree (expand/collapse remembered per Location, counts per node, selected node highlighted). Selecting a node scopes the content; Esc / "All photos" returns to the whole Location.
- **Content**: existing grid (`GridViewGallery`), loupe (`ZoomableLoupeView`), compare (`CompareLoupeView`), filmstrip, culling bar. Grid folder headers when the scope spans several folders (existing behaviour). Pull-to-refresh → `rescanSubtree`.
- **Inspector**: existing histogram, EXIF and tags panels, as a side pane (Medium+) or bottom sheet (Compact).
- **Top bar**: Location / folder title (breadcrumb on Expanded), scan progress line, view-mode toggle (Grid / Loupe / Compare: icons on Compact), filter entry point (existing chips; full filter UI is R4), sync counter (from R2), overflow: Rescan, Select.
- Culling still writes XMP through `XmpWriteQueue` exactly as today; `SyncChip` stays.
- Loupe on phone landscape: image fills the screen (immersive allowed), culling bar vertical on the side.

### R3.6 Import — Source step
- **Detected cards:** list removable, mounted `StorageVolume`s (existing `removableVolumes`); refresh on the existing `MEDIA_*` broadcast and on `ON_START` (also `StorageManager.registerStorageVolumeCallback` on API 30+). Each tile: description, "SD CARD"/"USB" badge, free / total space when available.
- Tapping a card: if the UUID → tree map has a grant that is still persisted and available, open it directly. Otherwise launch the picker: `volume.createOpenDocumentTreeIntent()` with `EXTRA_INITIAL_URI` pointing at the volume's `DCIM` document (`DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "<uuid>:DCIM")`), and a one-line hint above it: *"Choose the DCIM folder (Android doesn't allow the card's top folder)."* (O1). Persist the grant and store UUID → tree.
- **"Choose a folder…"** for any other source (another folder on the device, a USB drive).
- **Destination panel** (same step; side by side on Medium+, stacked on Compact): Import Profile dropdown with "(modified)" + *Save* / *Save as new…*; Location selector (saved Locations, *Add location…*; unavailable ones show "not connected" + Relink); structure mode (date with the 4 formats, custom pattern with tappable tokens, project folder name, flat); **live preview** of the resolved path using today's date and, once scanned, the first photo's date (`<Location>/2026/2026-11-06/`). A warning when no Location is set.
- Selecting a source starts the card scan (R3.7). *Next* is enabled when a source is scanned with ≥ 1 photo and a destination is set.

### R3.7 Card scan → import candidates
- `ImportSession` (singleton in `AppContainer`, state as `StateFlow`): `Scanning(dirs, files)` → `Reading(done, total)` → `Ready(candidates)` / `SourceLost` / `Empty`.
- Walk with `SafTreeWalker` + `WalkPolicy` (skips `MISC`, `PRIVATE`, hidden, …) and group with `AssetGrouper` (pairs, both sidecar styles). Nothing goes into `photo_assets`.
- For each group: capture date via `RawMetadataParser` with a file descriptor (header-only; companion EXIF for CR3/unknown RAW), parallelism 3, newest-first publication so the preview can render while dates fill in; size per file; duplicate status via `DuplicateCheck` (hash only when the size matches the index).
- Card removal at any point → `SourceLost` (no crash); reinsertion of the same UUID restores the session automatically (re-scan).
- Log tag `RawCull.Import`: walk ms, files, dates ms, hashes computed vs skipped by the size pre-filter.

### R3.8 Import — Preview step
- **Grid grouped by capture day** with collapsible day headers; each header has a tri-state checkbox and "n photos". Tiles: thumbnail (existing `RawPreviewLoader` on card URIs), checkbox, format badge (`ARW+JPG`), **Imported** badge for duplicates. Long-press / Shift+click range selection.
- Toolbar: *Select all / none*; format toggles **RAW** and **JPG/HEIF** (each a chip showing counts; turning one off excludes that half of every pair and photos that only have that format); *Hide already imported* (default from settings, on).
- Tap a tile → **viewer** (reuse `ZoomableLoupeView`): fit / 1:1, swipe or arrows for next/previous, an *Include* toggle (also `Space`), and a file-info strip (name, size, date, camera). Back returns to the same scroll position.
- **Summary bar** (sticky): "Importing 412 of 842 photos · 824 files · 18.4 GB → *Pictures/Photos*/2026/…", a free-space check against the destination root (`DocumentsContract` root `COLUMN_AVAILABLE_BYTES` for the Location's root; if unknown, skip the check) that blocks *Start import* with a clear message when space is short, and a notice when delete-from-card is on: "Verified files will be deleted from the card."
- Card disconnected → overlay "Card disconnected — reconnect it to continue" with *Back to source*; restores automatically on reconnection (R3.7).

### R3.9 Import worker
- *Start import* writes the `ImportJob` and its `ImportJobItem`s (from `ImportPlanner`) in one transaction, then enqueues a **unique** WorkManager job (`ExistingWorkPolicy.KEEP`, one import at a time; a second request while one runs is refused with a message).
- `ImportWorker : CoroutineWorker` runs as a long-running worker: `setForeground(ForegroundInfo(id, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC))`; manifest declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` (request at first import on API 33+; the import still works if denied) and WorkManager's `SystemForegroundService` with `foregroundServiceType="dataSync"`. Notification: progress (files and GB), current file, speed, *Cancel* action; tap opens `ImportProgress`.
- Per item, in order of capture date: skip `DONE`/`SKIPPED_DUPLICATE`; ensure the target directory exists (create each path segment with `DocumentsContract.createDocument(..., MIME_TYPE_DIR, name)` after one children query per directory; cache relative path → document URI for the job); create the file with the format's MIME type (sidecars `application/octet-stream`) and **verify the returned display name** (provider renames → treat as a conflict and pick the next suffix); stream-copy with a 1 MiB buffer through `StreamingHasher`; verify per `verifyMode` (D4); on mismatch delete the target, retry once, then `FAILED` with the reason; on success `DONE`, record in `imported_files`, update job counters (throttled to ≤ 4 DB writes/s).
- Source errors (`FileNotFoundException`, `SecurityException`, `IOException` on read): check `SafPermissions.isAvailable(source)`; if the card is gone, mark the job `SOURCE_LOST`, leave remaining items `QUEUED`, and stop. Destination full (`IOException` with ENOSPC) → job `COMPLETED_WITH_ERRORS`, remaining items failed with "Not enough space".
- **Resume:** WorkManager re-runs the worker after process death; items already `DONE` are skipped; an item left `COPYING`/`VERIFYING` is restarted (its partial target is deleted first). On `onStopped`/timeout (Android 15 `dataSync` limit) the job becomes `PAUSED` and can be resumed from `ImportProgress`.
- **Library rows:** after each asset group completes, insert its `PhotoAsset` (root = Location tree URI, `relativeDir` = target dir, document URIs, capture date and EXIF already read in R3.7, `metadataLoaded` = true when EXIF was read, `lastImportJobId` = job id) so the Library updates live; sidecars copied from the source are recorded as `xmpFileUri`.
- **Delete from card** (D10): after the job ends, if the job's `deleteSourceAfter` is set, delete the source documents of items that are `DONE` (verified), group by group (never delete a RAW while its companion failed), mark `sourceDeleted`; failures are listed, not fatal.
- Instrumentation (`RawCull.Import`): per job totals, MB/s for copy and verify separately, time to first file.

### R3.10 Import — Progress and summary
- `ImportProgress(jobId)` observes the job and items from Room, so it works after process death and from the notification.
- Running: overall progress (files, GB), current file, speed, ETA, *Cancel* (confirm; finished files stay), list of created folders as they appear.
- Finished: totals (copied, skipped as already imported, failed, deleted from card), elapsed time and average speed, created folders, a failed-files list with reasons and **Retry failed** (re-enqueues the same job for `FAILED` items), and buttons **Cull in Library** (opens Library with scope *Latest Import*) and **New import** (clears the session).
- `SOURCE_LOST` / `PAUSED`: explanation + *Resume* when the card is back.
- Completion notification ("412 photos imported · 2 failed") opens this screen.

### R3.11 Settings screen
Full-screen destination (replaces `SettingsSheet`), grouped list, adaptive (two-pane list/detail on Expanded+ via `ListDetailPaneScaffold` is welcome but optional):
- **Library:** Locations (add, rename, relink, remove, availability).
- **Import:** verification *Full / Quick* with a one-line explanation each; *Delete files from the card after a verified import* (with "Deletion is permanent; cards have no trash"); *Hide already-imported photos* default; Import Profiles (list, edit, delete; "Standard" can't be deleted).
- **Sidecars:** the existing *Rejects as −1 rating* toggle and its explanation.
- **About:** version, licences link.

### R3.12 Remove retired code
- Delete `ExportDialog`, `RawCatalogRepository.exportCulledFiles`, `ExportConfig`, `ExportMode`, `ExportProgress`, `IngestFileSelection`, the export entry points in `TopDamBar` and the grid batch bar.
- Delete `WelcomeScreen`, recent sources (`RecentSources`, `KEY_RECENT_SOURCES`, `lastSource*`), and their UI in `TopDamBar`. **One-time migration:** if `lastDestinationUri` (or `lastSourceUri` that isn't a removable volume) still has a persisted permission, create a Location from it so existing users land in their library; then remove those keys. Release permissions that no Location or volume grant uses.
- Demo shoot becomes the "Demo library" pseudo-Location (D14).
- `SidecarReconciler.exportBytes` stays if the import of sidecars uses it to merge; otherwise remove it and its test, and say which.

### R3.13 Tests, docs, version
- Tests not already listed: `ImportWorker` with `TestListenableWorkerBuilder` and a fake document I/O layer (put SAF calls behind a small `DocumentIo` interface so the worker logic is testable): happy path; resume after a simulated kill mid-file; verify mismatch → retry → fail and continue; source lost → `SOURCE_LOST`; duplicate skip; conflict suffix for a group; delete-from-card only for verified groups. `ImportSession` with a fake walker. Compose UI tests (Robolectric with `@Config(qualifiers = …)` for w360dp, w640dp-h360dp, w1280dp) asserting: bottom bar vs rail, folder pane visible only on Expanded, wizard Next disabled until source + destination, state kept across a configuration change.
- `versionCode = 5`, `versionName = "0.5.0"`.
- Update `CLAUDE.md`: architecture (app shell + Nav3 keys, `LibraryViewModel`, `importer/` package, `ImportWorker`, Locations), the window-size spec rule ("every screen must work at Compact/Medium/Expanded, portrait and landscape"), "Room uses real migrations from v5; never add destructive fallback", and remove the `MainDamScreen ≥700dp` line.

---

## Acceptance checklist (Shehran, on the tablet and a phone)

1. Fresh install → Library empty state; *Add library location* (e.g. `Pictures/Photos`) shows its folder tree. *Try demo library* works and stays separate.
2. Plug in a card through the USB reader → it appears under Import within ~2 s. First time: the picker opens at `DCIM`; next time the card opens without the picker.
3. Photos appear grouped by day; dates fill in; RAW+JPG pairs show one tile. Untick a day and turn off JPG → the summary updates. Not enough space → *Start import* is blocked with a message.
4. Start import → notification with progress. Switch apps, lock the screen, come back: progress continues. Files land under `<Location>/2026/2026-11-06/` with sidecars for any that had them.
5. Force-stop the app mid-import → it resumes, and no file is duplicated or truncated (spot-check a few with a hash on the PC).
6. Pull the card mid-import → the job stops as "Card disconnected"; reinsert → *Resume* finishes it.
7. Summary shows copied / skipped / failed; *Cull in Library* opens *Latest Import*; rating there writes XMP next to the library files.
8. Import the same card again → every photo is marked *Imported* and hidden by default.
9. Delete-from-card on → after import, only verified files are gone from the card; everything is still in the library.
10. Rotate on every screen (Library grid/loupe, each wizard step, progress, settings) on the tablet and phone: nothing resets. Phone landscape loupe uses the full screen. Resize a desktop/freeform window: layouts switch at the breakpoints.
11. Relaunch → same Location, folder and selected photo.
12. Copy and verify speeds (from `adb logcat -s RawCull.Import`) recorded in the notes with card, reader and destination.
