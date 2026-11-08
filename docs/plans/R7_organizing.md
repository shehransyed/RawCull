# RawCull — Round 7: Organizing (collections, ordered export, GPS editing)

Status: **planned** · 2026-11-07 · Branch `feature/r7-organizing` from `main` (= R6 tip after R7.0) · Version **0.9.0** (`versionCode` 9) · Catalog **Room v7**.
Overview: `00_workflow_overhaul_overview.md`. The R4 open items (O14 sidecars on Restore, O15 mixed-selection toggles) are still later. Don't implement them.

**How to read this plan (Claude Code):** read this header, *Decisions*, and only your session's tasks. Each session starts fresh; find where you are from `git log --oneline main..feature/r7-organizing`.

## Sessions
| Session | Tasks | Model | Why that model |
|---|---|---|---|
| R7.0 | Git cleanup + round start | **Shehran, no Claude** | Commands below; costs no usage |
| S1 | R7.1, R7.2 | Opus | Catalog v7 + migration with data; flaky-test hardening |
| S2 | R7.3, R7.4 | Sonnet | Collections in the Library and on import: wiring on existing patterns |
| S3 | R7.5, R7.6 | Opus | Drag reorder in a lazy grid; ordered export worker with EXIF/XMP time rewrite |
| S4 | R7.7, R7.8, R7.9 | Sonnet | GPS parser and editing, wrap-up, short emulator check |

## Handoff prompts (one per session; start each in a fresh Claude Code session)
- **S1:** `Round 7, session S1: implement tasks R7.1 and R7.2 of docs/plans/R7_organizing.md on the local branch feature/r7-organizing (R7.0 created it). Local only: no push, no PR. One commit per task; bash scripts/check.sh must pass before each commit. Follow CLAUDE.md's worktree rule. Stop after R7.2.`
- **S2 / S3 / S4:** `Round 7, session S<n>: continue on feature/r7-organizing with tasks <list> of docs/plans/R7_organizing.md. Same rules as before (local only, one commit per task, scripts/check.sh before each commit, CLAUDE.md's worktree rule). Stop after <last task>.`
- Fixes from tablet testing of R6 go on `feature/r7-organizing` as their own `fix:` commits before the session's tasks, if Shehran's prompt lists any.
- Where the plan and the code disagree, follow the code's patterns and note it (S1–S3: one line per deviation under `### Running notes`; S4 writes the rest).

## R7.0 Git cleanup and round start (Shehran, Git Bash, in the RawCull folder)
Close any Claude Code session on this repo first. The working tree holds this plan, `scripts/tidy-git.sh`, the `CLAUDE.md` worktree rule, `.gitignore` and the 0.9.0 bump, uncommitted.
```bash
./gradlew --stop                       # Gradle daemons can lock files in the old worktrees
git status                             # on feature/r6-viewing; only the files above changed
git tag -a r1 ebd221a -m "R0+R1 hygiene and real card"
git tag -a r2 73eb70f -m "R2 trustworthy XMP"
git tag -a r3 a53eccb -m "R3 shell, Library, Import (0.5.0)"
git tag -a r4 309f6d0 -m "R4 culling UX, reject deletion (0.6.0)"
git tag -a r5 810fd72 -m "R5 previews (0.7.0)"
git tag -a r6 d6e477f -m "R6 viewing (0.8.0)"
git branch -f main r6                  # D1: main = R6. Skip this line to keep main at R2 for now.
git switch -c feature/r7-organizing
bash scripts/tidy-git.sh               # dry run: 6 worktrees and 6 claude/* branches to remove
bash scripts/tidy-git.sh --apply
git branch -d feature/r5-previews feature/r6-viewing
git add .gitignore CLAUDE.md app/build.gradle.kts scripts/tidy-git.sh docs/plans/R7_organizing.md docs/plans/00_workflow_overhaul_overview.md
git commit -m "chore: start R7: 0.9.0, git tidy script, plan (R7.0)"
git branch; git worktree list          # expect: main, feature/r7-organizing; one worktree (this folder)
```
If a worktree is reported *KEPT*, look at what it lists before doing anything by hand. Afterwards, `build/r6tmp/` goes with the next `gradlew clean`. From now on, run `bash scripts/tidy-git.sh --apply` at the end of each round.

## Decisions
| # | Topic | Decision |
|---|---|---|
| D1 | Git | After R7.0: branches `main` (= R6 tip) and `feature/r7-organizing`; tags `r1`–`r6` mark round tips; no worktrees left. Claude Code sessions in an app-made worktree fast-forward the round branch themselves (`CLAUDE.md`). `scripts/tidy-git.sh` clears worktrees and `claude/*` branches between rounds. Still local only: nothing pushed. |
| D2 | Collections model | Lightroom-style: **collections** hold photos; **collection sets** hold collections and sets, never photos. Any depth; names unique among siblings (case-insensitive). Each belongs to one Location (`locationId`; `0` = the demo library, so no FK). Removing a Location removes its collections. |
| D3 | Membership | `collection_items(collectionId → collections CASCADE, assetId, position, addedAt)`, PK `(collectionId, assetId)`, index on `assetId`. **No FK to `photo_assets`:** a photo missing after a rescan, or on an unavailable Location, keeps its place; views skip ids with no row. Deleting rejects in the app and removing a Location delete memberships. A photo can be in many collections. |
| D4 | Where it's stored | Catalog only. Nothing else reads collections from sidecars, and keywords would pollute tags. A backup file in the Location is backlog. |
| D5 | In the Library | New `LibraryScope.Collection(id)`. A *Collections* section under the folder tree, wherever the tree is shown at each width: expand/collapse, *New collection*, *New set*, rename, move (into a set or the top level; no cycles), delete (confirm; photos are never touched). Filters, counts and culling work inside a collection as in a folder. The active collection is saved (`active_collection_id`) and restored like a folder. |
| D6 | Adding and removing | *Add to collection…* sheet (search, recent first, *New collection* inline) from the selection bar, grid and loupe overflow, and the inspector. **B** toggles the selection (else the current photo) in the **target collection** (Lightroom's key; set with *Set as target* in a collection's menu; marked in the pane; `target_collection_id`). No target → B opens the sheet. *Remove from collection* in a collection view. Membership changes get a snackbar *Undo*; they aren't on the culling `UndoStack`. Inspector shows the photo's collections as chips (tap opens one). |
| D7 | Manual order | New `SortOrder.CUSTOM` ("Custom order"), offered only in a collection and its default there; each collection keeps its own sort (`collections.sortOrder`). In Custom order without filters, a *Reorder* button enters reorder mode: drag a photo, or the selection as one block, to a new place; Alt+←/→ moves the selection one step, Alt+Home/End to start/end (in `LibraryKeymap`); *Done* or Esc leaves. Long-press multi-select is off in that mode. With filters active the button explains "Clear filters to reorder". New photos go to the end. Positions are rewritten for the whole collection in one transaction. |
| D8 | Add on import | Import Preview's destination panel: *Add to collection* (none / existing / new name). Stored on `import_jobs.collectionId`; the worker adds each group after it verifies, in capture order. The Done screen gets *Open collection*. |
| D9 | Ordered export | A collection's menu → *Export in order…*: pick a destination folder (SAF tree; take the persistable permission, release it when the job ends). Content **Originals** (default: RAW + companion + sidecar) or **JPEGs only** (companion JPEG or the JPEG file; photos without one are listed as skipped). Order = the collection's current sort; filters ignored unless *Only the n shown* is ticked. Names `NNN_<original name>`, NNN zero-padded to max(3, digits of the count), the same NNN for a group's files and its sidecar. Never overwrites (`ConflictNamer`). Flush `XmpWriteQueue` first. |
| D10 | Keeping the order elsewhere | *Keep the order in other apps* (default on, `export_keep_order`): photo i gets the first photo's capture time + 10 s × i, written **only to the copies**: JPEG copies through `ExifInterface` (`DateTimeOriginal`, `DateTimeDigitized`, `DateTime`; offsets kept); sidecar copies through `XmpDocument` (`exif:DateTimeOriginal`, `xmp:CreateDate`, `photoshop:DateCreated`), creating one for a RAW that has none. RAW copies are byte copies. The library's files and sidecars never change. |
| D11 | Export job | `ExportWorker` (WorkManager foreground, thin like `ImportWorker`) over an Android-free `ExportEngine` behind `DocumentIo`. Progress notification, cancel (finished files stay), each copy checked by size, summary (exported, skipped, failed) with *Open folder* if a handler exists. Last choices in `export_content`. |
| D12 | GPS data | `photo_assets`: `gpsLatitude`, `gpsLongitude`, `gpsAltitude` (Double?), `gpsInFile` (Boolean: the file's own EXIF has a location). Enrichment reads XMP `exif:GPS*` first, else the file's EXIF (`ExifInterface` lat/long, altitude). New `XmpField.GPS(16)`: only edited rows write GPS. Written as XMP does it: `exif:GPSLatitude`/`GPSLongitude` as `DDD,MM.mmmmmmK`, `exif:GPSAltitude` rational + `GPSAltitudeRef` when given, `exif:GPSVersionID` `2.2.0.0`. Original files never change. |
| D13 | GPS editing | `CullEdit.SetLocation(lat, lon, alt?)` and `ClearLocation`, through `LibraryViewModel.apply`: batch on the selection, undo/redo, edit badge. Inspector *Location* card: the value (`40.44619° N, 79.98222° W`), source (*Sidecar* / *From the camera*), *Edit*, *Copy*, *Paste* (from another photo), *Open in Maps* (`geo:` intent), *Clear*. Clear removes the sidecar's location; if `gpsInFile`, the camera's location shows again, labelled, because other apps will see it. |
| D14 | GPS input | One text field; the parsed point shows live, *Apply* is enabled only when it parses. Accepts: Google Maps URLs (`@lat,lon`, `!3dlat!4dlon`, `q=`/`ll=`/`query=`/`center=`), Apple Maps (`ll=`, `q=`), OpenStreetMap (`#map=z/lat/lon`, `mlat`/`mlon`), `geo:` URIs, DD (`40.4461, -79.9822`, with or without N/S/E/W), DMS (`40°26'46.3"N 79°58'56"W`, ASCII `d ' "` variants too), DMM (`40°26.767'N 79°58.933'W`). Optional altitude. Short links (`maps.app.goo.gl`, `goo.gl/maps`) aren't followed (the app has no network permission): say "Open the link and copy the full address or the coordinates." Out-of-range values are rejected; lat/lon are never swapped by guessing. |
| D15 | Filter | *Location*: Any / Has location / No location, in `LibraryFilter` and `FilterCounts`, stored with the other filters (unknown stored value → Any). |
| D16 | Versions, keys | 0.9.0 (`versionCode` 9, R7.0); Room **v7** (new `collections`, `collection_items`; `photo_assets` GPS columns; `import_jobs.collectionId`) with a migration test from v6 with data. New DataStore keys only: `active_collection_id`, `target_collection_id`, `export_content`, `export_keep_order`. |
| D17 | Size discipline | Collections logic in a new internal `CollectionController` (like the R6.1 controllers); `LibraryViewModel.kt` ≤ 450 lines; `LibraryScreen.kt` grows only by wiring. New UI in new files, each under ~400 lines. |

## Tasks

### R7.1 Catalog v7 and collection logic — Opus
- Entities and DAOs for D2, D3, the D12 columns and `import_jobs.collectionId`; DB version 7; migration 6 → 7 (AutoMigration if it fits, else manual); commit `app/schemas/.../7.json`.
- `CollectionRepository`: create collection/set, rename, move, delete, add/remove (batch), reorder (whole-list rewrite), members in position order, collections containing an asset. Pure `library/CollectionTree.kt`: tree from rows, depth-first order for the pane, sibling-name check, move-cycle guard.
- `LocationRepository.remove` and the reject-deletion path delete memberships.
- Tests: migration from v6 **with rows** (photos keep every field; new columns null/false); tree build and cycle guard; name clash; reorder keeps every id once; delete set cascades to its collections and items; Location removal.

### R7.2 Harden the flaky `LibraryViewModelTest` cases — Opus
- R6 saw `a later message doesn't inherit Restore` and `filters are remembered per Location` fail once each under load. Find the shared cause (likely a shared DataStore file or a wall-clock wait); give each test its own DataStore file and wait on state, not time.
- Acceptance: the class passes 5 full-check runs in a row; note the cause in one line.

### R7.3 Collections in the Library — Sonnet
- D5, D6: `LibraryScope.Collection`, rows for a collection in `ViewController` (membership join, then the usual filter path), the pane section (new file `ui/library/CollectionsPane.kt`), the add sheet (`AddToCollectionSheet.kt`, `Modifier.dismissOnEscape`), B in `LibraryKey`/`LibraryKeymap`, inspector chips, the two DataStore keys.
- Tests: scope switch and restore; counts inside a collection; B with and without a target; add/remove + snackbar Undo; missing asset skipped; keymap test for B; pane at Compact and Expanded.

### R7.4 Add on import — Sonnet
- D8 in `ImportPreviewScreen` (destination panel), `ImportController`/`ImportEngine` (add after each verified group), Done screen button.
- Tests: engine adds groups in capture order and never adds a failed group; resume after a kill doesn't add twice; *New collection* name created once.

### R7.5 Custom order and reorder mode — Opus
- D7. `SortOrder.CUSTOM` in `FilterOptions` (codec: unknown → the default), per-collection sort, reorder mode with drag in the grid (a small in-house drag helper or a maintained reorder library; say which) and the Alt keys.
- Tests: CUSTOM ordering; block move of a non-contiguous selection keeps its relative order; Alt keys at the edges; reorder disabled with filters; positions persist; keymap tests.

### R7.6 Ordered export — Opus
- D9–D11: `library/ExportPlan.kt` (pure: order, names, padding, groups, skipped list, time offsets), `ExportEngine` + `ExportWorker`, dialog and summary UI, the two DataStore keys.
- Tests (JVM, fake `DocumentIo`): names and padding (9, 10, 999, 1000 photos); group files share NNN; never overwrites; JPEGs-only skips; sidecar time rewrite merges (unknown content kept); JPEG copy gets new EXIF times and the original's bytes are unchanged; cancel mid-job; library rows and files untouched.

### R7.7 GPS: parser, XMP, enrichment — Sonnet
- D12, D14: pure `library/GeoInput.kt` (all D14 forms → `GeoPoint` or a reason), formatting for display; `XmpMapper`/`XmpDocument` GPS read/write; enrichment reads XMP then EXIF.
- Tests: a table of inputs per D14 form incl. bad ones (short link, out of range, `N` with a negative number); XMP round trip in Lightroom's format and parsing of what darktable writes; unknown sidecar content kept. Add a GPS fixture to `XmpDocumentDeviceTest` (device suite).

### R7.8 GPS editing UI and filter — Sonnet
- D13, D15: `SetLocation`/`ClearLocation` through `apply` (undo/redo, badge), new `ui/library/LocationCard.kt` + edit sheet, Copy/Paste/Open in Maps, the filter.
- Tests: batch apply then undo restores each photo's own previous value; clear with and without `gpsInFile`; only `XmpField.GPS` is pending after an edit; filter counts.

### R7.9 Wrap-up — Sonnet
- `CLAUDE.md`: one line each for collections (`CollectionRepository`, `CollectionController`, scope), export (`ExportEngine`/`ExportWorker`), GPS (`GeoInput`, `XmpField.GPS`).
- **Emulator check, limited:** Medium_Tablet API 36 at **360 × 640 dp** and **1280 × 800 dp**; at most **8 screenshots**: collections pane, add sheet, reorder mode, export dialog, location card + edit sheet at 360 dp. Update check: install the R6 build, rate a photo and change a setting, `adb install -r` R7; all kept, migration ran.
- *Implementation notes*, short: deviations, open questions, the update check.

## Acceptance checklist (Shehran, on the tablet)
Items marked *(auto)* become device tests in R8.
1. Make a set with two collections; add photos by sheet and by B with a target; remove one; Undo. *(auto)*
2. Reorder by drag and by Alt+arrows; leave and come back; order kept. *(auto)*
3. Import with *Add to collection*; the new photos are in it after the import. *(auto)*
4. Export a collection (Originals, then JPEGs only) to a folder; names numbered; open the JPEGs in Google Photos or another app: in order? Lightroom: does it read the capture time from the exported sidecar? *(judgement)*
5. Paste a full Google Maps link, then a DMS string; batch-apply to 5 photos; undo; clear. A short link gives the message. *(auto except Maps)*
6. Open the edited sidecars in Lightroom or darktable on the PC: location shown? *(judgement)*
7. R7 installed over R6 kept your library, ratings and settings. *(auto)*

## Not in this round
Smart collections, RapidRAW album sync, a map view, reverse geocoding, GPX track matching, collections spanning Locations, a collections backup file, O14/O15.

## Implementation notes
(Claude Code: S1–S3 add one-line deviations under *Running notes*; S4 writes the rest.)

### Running notes
- R7.1: the v6 → v7 migration test is `Migration6To7Test` (Robolectric, so `check.sh` runs it): Robolectric can't read test assets, so it builds v6 from `schemas/.../6.json` and opens it with the real AutoMigration and Room's schema check. No device case added to `MigrationTest`.
- R7.1: open question: relink drops a Location's rows and asset ids include `rootUri`, so after a relink a collection's items no longer match the rescanned photos (they stay, skipped). Needs an id remap on relink; not in this round's tasks.
- R7.2 cause: the tests called the ViewModel from the JUnit thread while its coroutines ran on the test "Main" thread, so `ViewController.publish` (read state, copy, write) could overwrite a concurrent update such as `canUndo`; and every test shared the app's one-per-process settings DataStore. Now ViewModel calls go through `onMain { }`, each test gets its own DataStore file (`SettingsRepository` takes a `DataStore`), the seed-row id offset is gone, and the 50 ms wait is a Main round trip.
- R7.3: rows for a collection come from a membership join (`CollectionDao.observeMembers`, reached through `PhotoCatalog.observeScope`), so `ViewController` is unchanged and the usual filter and sort run on them. Until R7.5 adds Custom order a collection therefore shows in the current sort, not in member order.
- R7.3: "recent first" in the Add sheet means the target first, then newest created (collections store no last-used time). A new collection from the sheet is made at the top level.
- R7.3: a saved collection that no longer exists (or belongs to another Location) restores as All photos.
- R7.4: the Import Preview screen has no destination panel (the destination is chosen in step 1), so *Add to collection* is a row above the action bar in the summary bar (`ImportCollectionRow`). The choice isn't remembered between imports.
- R7.4: a group is filed only when its primary landed and none of its files failed; a retry that lands it files it then. Filing is idempotent (`CollectionRepository.add` skips members), so a resume repeats it harmlessly, and it also runs for groups finished before a kill. `ImportEngine` and `ImportController` take a `CollectionRepository`; a *New* name reuses a top-level collection of that name (case-insensitive) and is refused if a set has it.
