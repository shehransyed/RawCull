# RawCull — Round 8: On-device test suite (plus two R7 fixes)

Status: **planned, decisions to review** · 2026-11-08 · Branch `feature/r8-device-tests` from `r7` (`fc63e97`) · Version **0.10.0** (`versionCode` 10) · Catalog **Room v8** (data-only migration, D5).
Overview: `00_workflow_overhaul_overview.md`. O14/O15 are still later.

**How to read this plan (Claude Code):** read this header, *Decisions*, and only your session's tasks. Each session starts fresh; find where you are from `git log --oneline r7..feature/r8-device-tests`. **Every session in this round needs the tablet on USB** (`adb devices` shows it); if it isn't there, say so and stop.

## Sessions
| Session | Tasks | Model | Why that model |
|---|---|---|---|
| R8.0 | Round start | **Shehran, no Claude** | Commands below |
| S1 | R8.1, R8.2 | Opus | Crash diagnosis from logcat; GPS on import + backfill migration + card |
| S2 | R8.3, R8.4 | Opus | QA build type, fixture DocumentsProvider, harness, runner script, folder grant via UiAutomator |
| S3 | R8.5, R8.6 | Sonnet | Import and Library suites on the S2 harness |
| S4 | R8.7, R8.8 | Sonnet | Deletion/trash, layout + screenshots, benchmark, migrations; wrap-up |

## Handoff prompts (fresh Claude Code session each; tablet plugged in, USB debugging on)
- **S1:** `Round 8, session S1: implement tasks R8.1 and R8.2 of docs/plans/R8_device_tests.md on the local branch feature/r8-device-tests (R8.0 created it). The tablet is on USB. Local only: no push, no PR. One commit per task; bash scripts/check.sh must pass before each commit. Follow CLAUDE.md's worktree rule. Stop after R8.2.`
- **S2 / S3 / S4:** `Round 8, session S<n>: continue on feature/r8-device-tests with tasks <list> of docs/plans/R8_device_tests.md. The tablet is on USB. Same rules as before (local only, one commit per task, scripts/check.sh before each commit, CLAUDE.md's worktree rule). Stop after <last task>.`
- From S3 on, run device tests only through `bash scripts/device-test.sh` and read `build/device-report.md`, nothing else from the run.
- Where the plan and the code disagree, follow the code's patterns and note it under `### Running notes` (one line each; S4 writes the rest).

## R8.0 Round start (Shehran, Git Bash, in the RawCull folder)
Close any Claude Code session on this repo first. The working tree holds this plan, the 0.10.0 bump and the overview row, uncommitted.
```bash
./gradlew --stop
git status                             # on feature/r7-organizing; only the three files above changed
bash scripts/tidy-git.sh               # dry run; then:
bash scripts/tidy-git.sh --apply
git tag -a r7 fc63e97 -m "R7 organizing (0.9.0)"
git branch -f main r7                  # skip until R7 is tested enough
git switch -c feature/r8-device-tests
git add app/build.gradle.kts docs/plans/R8_device_tests.md docs/plans/00_workflow_overhaul_overview.md
git commit -m "chore: start R8: 0.10.0, plan (R8.0)"
```
Then: Settings → Developer options → USB debugging on, plug in, accept the PC's key, `adb devices` shows one device.

## Decisions
| # | Topic | Decision |
|---|---|---|
| D1 | Order | The two R7 bugs come first (S1), on the tester's own app, before any suite work. |
| D2 | "Cull in Library" crash | Not reproduced in the planning chat (no adb there). R8.1 captures it on the tablet (`adb logcat -b crash`, plus `-s AndroidRuntime`) while Shehran taps the button, fixes the cause, and adds a JVM test if the cause is logic. The device regression test is in R8.5. Leads, unconfirmed: (a) `onCullInLibrary` calls `stacks.resetTo(IMPORT)` + `select(LIBRARY)` from inside the ImportProgress entry, which pops that entry (and clears its `ViewModelStore`) during its own click; *Open collection* does the same, so check it too; (b) the `LatestImport` scope path (`observeByImportJob` ignores `rootUri`; `LatestImport` isn't restored on relaunch, which fits "works after relaunch"). Quick discriminator: does *Latest Import* in the folder pane crash too? |
| D3 | GPS cause (found) | Two gaps, both leave `metadataLoaded = 1` with no location, so phase B never reads it: **(1)** `ImportMetadataCodec` doesn't carry the location and `ImportEngine.libraryRow` sets neither `gps*` nor `gpsInFile` (and ignores the sidecar's `ParsedCullState.location`); **(2)** rows enriched before v7 got null GPS columns from the 6 → 7 AutoMigration and are never enriched again. |
| D4 | GPS on import | Codec gains `lat`, `lon`, `alt` (absent → none; old jobs' JSON still decodes). The library row takes the sidecar's location first, else the file's EXIF, and `gpsInFile` from EXIF: the same rule as enrichment, in one shared pure function used by both paths. The camera's location is still never written to a sidecar. |
| D5 | GPS backfill | Room **v8**, a manual data-only migration 7 → 8: `UPDATE photo_assets SET metadataLoaded = 0 WHERE gpsLatitude IS NULL AND gpsInFile = 0 AND (xmpPendingFields & 16) = 0`. The next scan of each Location re-enriches those rows lazily (header reads). Before relying on it, a test proves re-enrichment keeps pending edits (flags, ratings, labels, tags, GPS) and doesn't clear an edit badge. Commit `schemas/.../8.json`; migration test from v7 with rows. |
| D6 | Location card | **No location:** "No location" + **Add location** (filled button; opens the edit sheet with the field focused and the keyboard up, fixing R7.9's finding) and *Paste*. **Has location:** the value, its source, **Edit**, *Copy*, *Open in Maps*, *Clear* (sidecar only). **Camera location:** source "From the camera"; *Edit* saves an override to the sidecar, the file keeps its own. With several selected: "Add location to n photos" / "Edit location of n photos". Same card at every width. |
| D7 | QA app | New build type **`qa`** (`initWith(debug)`, `applicationIdSuffix = ".qa"`, `versionNameSuffix = "-qa"`, launcher label "RawCull QA", same debug signing) and `testBuildType = "qa"`. The suite only ever installs `dev.shehran.rawcull.qa`; the tester's `dev.shehran.rawcull`, its library, ratings, settings and grants are never installed over, cleared or uninstalled by a test run. Android Studio keeps running `debug`. |
| D8 | QA app stays installed | The runner passes `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, so its folder grant (D9 b) survives between runs. Reinstalls are `install -r`, keeping its data. |
| D9 | Storage, two tiers | **(a) Fixture provider:** a qa-only `FixtureDocumentsProvider` (`app/src/qa/`) serves `filesDir/fixtures/<name>/` as SAF trees with query, open (r/w), create, rename, delete. Most tests use it: no picker, fast, reset per test, exercises the app's real `DocumentsContract` code. The app's grant handling accepts its own authority (no persistable grant exists for it). **(b) Real storage:** `Download/RawCullQA/` on shared storage holds copies of the samples, granted once through DocumentsUI by UiAutomator (fallback: the runner prints "open RawCull QA → QA folder grant and pick Download/RawCullQA"). Used for the import smoke, MediaStore trash and real-provider behaviour. |
| D10 | Samples | Read from `/sdcard/Download/Quick Share/Sample Import` (instrumentation arg `samples` overrides it). Tests list and read it through `UiAutomation.executeShellCommand` (`ls`, `cat`) and copy into tier (a) or (b). **Never write to, rename or delete anything in that folder, in the tester's Library (`A6400/RAW/RawCull Test Samples`) or in the tester's app.** Tests that need samples `assume` the folder exists and skip with a reason otherwise. R8.1 records the folder's manifest (name, size) in `docs/testing/samples.md`; tests derive counts from the folder, not from that file. Synthetic repo fixtures cover edge cases (foreign XMP namespaces, darktable sidecars, no-EXIF JPEG). |
| D11 | State between tests | A `QaReset` rule (qa source set) clears the catalog, DataStore, preview cache and fixture trees before each test class and keeps the tier (b) grant. No Orchestrator: `clearPackageData` would drop the grant. Tests drive the real `MainActivity` with `createAndroidComposeRule`, keys with `performKeyInput`, system dialogs with UiAutomator (`androidx.test.uiautomator`). Small robots (`ImportRobot`, `LibraryRobot`) hold the tags and steps, so tests read as flows. |
| D12 | Suites | `@Smoke` (≈ 2 min: launch, fixture import, Cull in Library, one flag + rating, sidecar on disk) and **full** (target ≤ 15 min on the tablet). Tests live under `androidTest/.../device/<area>/`. Known R9 layout issues are `@KnownIssue("R9 …")`: run, reported, never fail the run. |
| D13 | Runner | `scripts/device-test.sh [smoke|full|<class pattern>]`, Git Bash on Windows: checks for exactly one device (or `ANDROID_SERIAL`), prints model and API, checks the samples folder, runs `connectedQaAndroidTest` quietly, pulls screenshots and benchmark JSON, writes **`build/device-report.md`** (device, duration, pass/fail per area, failures with the first 15 stack lines, known issues, benchmark table, screenshot paths) and prints one line on success or ≤ 60 lines of failures. Exit code passed through. Shehran runs it himself, no Claude needed; Claude reads only the report. |
| D14 | Layout | Compose tests at forced sizes (`DeviceConfigurationOverride.ForcedSize`): **360×640, 640×360, 840×600, 1280×800 dp**, Library grid, loupe, compare, full window, Import Preview, Settings. Checks: tagged controls don't overlap, touch targets ≥ 48 dp, nothing clipped off-screen. Screenshots (`captureToImage`) go to `build/device-screens/`. The tablet's own display size is never changed. |
| D15 | Benchmark | `PreviewBenchmark` on the samples in the qa build; numbers in the report beside the previous run (`build/device-bench/last.json`). Warn at > 2× slower, never fail. Debuggable build, so read trends, not absolutes. |
| D16 | Update check | The tester's app can't be update-tested without touching it, and older rounds have no qa build. R8: Room migrations v5 → v8 with data run on the device (`MigrationTest`), plus a DataStore test that every key from R3–R7 still reads. `scripts/update-check.sh <old-qa.apk>` installs the old qa APK, seeds a library through the fixture provider, `install -r` the new one, and checks rows, ratings and settings survived. Each round archives its qa APK in `dist/qa/` (git-ignored); the first real run is R9 against 0.10.0. |
| D17 | `check.sh` | Also compiles the device tests (`compileQaAndroidTestKotlin`) so they can't rot; it never runs them. |
| D18 | Manual only | Card insert/remove and the USB reader, transfer speed, picking a real SD card (O1), Lightroom/darktable reading sidecars, the Maps app, haptics and feel, the tester's own update install. |
| D19 | Versions | 0.10.0 (`versionCode` 10, R8.0); Room v8 (D5). No new DataStore keys in the app; qa-only code stays in `app/src/qa/`. |

## Tasks

### R8.1 "Cull in Library" crash — Opus
- Ask Shehran to reproduce (import a few files from `Sample Import` into the test Library, tap *Cull in Library*) while you run `adb logcat -c` then `adb logcat -b crash -d`. Try *Open collection* and the folder pane's *Latest Import* too (D2). Fix the cause; JVM test where the cause is logic. One line on the cause in *Running notes*.
- Record `adb shell ls -la "/sdcard/Download/Quick Share/Sample Import"` and the tablet's model/API into `docs/testing/samples.md` (D10).

### R8.2 GPS on import, backfill, Location card — Opus
- D3–D6. Shared pure picker (sidecar → EXIF) used by `ImportEngine` and `ScanCoordinator`; codec fields; migration 7 → 8 (manual, `AppDatabase` v8, schema JSON); card states in `LocationCard.kt`; sheet focuses its field.
- Tests: codec round trip and old JSON; library row from an import gets sidecar GPS first, else EXIF, `gpsInFile` set; migration from v7 with rows marks only the right ones; re-enrichment keeps pending edits (D5); card shows *Add location* / *Edit* per state (Robolectric Compose).
- On the tablet: install the debug build over R7 (Android Studio or `gradlew installDebug`), let the test Library rescan, ask Shehran to check a photo with GPS shows it.

### R8.3 QA build type and test harness — Opus
- D7, D9 (a), D10, D11: `qa` build type, `app/src/qa/` (provider, manifest, `QaReset`, a tiny *QA folder grant* activity for D9 b's fallback), samples access over `executeShellCommand`, robots, UiAutomator dependency, `@Smoke`/`@KnownIssue` annotations.
- One device test proving the chain: copy 3 samples into a fixture tree, add it as a Location, the grid shows 3 groups, a P writes `xmp:Rating`/`rawcull:Pick` to the sidecar on the provider.

### R8.4 Runner and reporting — Opus
- D8, D13, D16 (script + archive step), D17; tier (b) grant via UiAutomator through DocumentsUI, with the fallback message.
- Acceptance: `bash scripts/device-test.sh smoke` passes on the tablet; a deliberately failing test shows up in the report with its stack and the script exits non-zero (then remove it); the tester's app is untouched (`adb shell pm list packages rawcull` shows both; its data size unchanged).

### R8.5 Import suite — Sonnet
- On fixture trees: preview counts and pairing (RAW+JPEG, sidecars), path template folders and `Undated/`, Full verify, re-import skips duplicates, never overwrites (`ConflictNamer`), delete-from-card only after a group verifies, *Add to collection*, **Cull in Library and Open collection land on the right scope (R8.1 regression)**, cancel and resume. On tier (b): one smoke import of the real samples.

### R8.6 Library suite — Sonnet
- Culling keys from `LibraryKeymap` (flags, ratings, labels, undo/redo, auto-advance), multi-select batch edits, filters and `FilterCounts`, sidecar contents after edits (parse with `XmpDocument`; foreign namespaces kept), Locations (add, rename, relink, remove; refused while edits are unsaved), collections (sheet, B with target, reorder, export names/order/capture times), GPS (paste a Maps link, batch apply, undo, clear; camera location shows after import, D4), viewing toggles (F, Ctrl+Enter, O, H) open and close without errors.

### R8.7 Deletion, layout, benchmark, migrations — Sonnet
- Tier (b): delete rejects through `createTrashRequest` with UiAutomator on the system dialog (Allow and Cancel), group files go together, `.xmp` deleted permanently, Restore; deleted rejects stay in `imported_files`.
- D14 layout checks + screenshots (at most 24); D15 benchmark in the report; D16 migration and DataStore tests on the device.

### R8.8 Wrap-up — Sonnet
- `CLAUDE.md`: a *Device tests* section (qa app, tiers, never touch the samples or the tester's app, run through the script, read only the report) and the emulator rule replaced by "device tests only when a task says so". `docs/testing/README.md`: how Shehran runs it, what's covered, what stays manual (D18).
- Run `full` twice in a row on the tablet (flake check); archive the 0.10.0 qa APK in `dist/qa/`.
- Mark which acceptance items of R3–R7 are now covered (one table in the notes). *Implementation notes*, short: deviations, run times, flaky tests, open questions.

## Acceptance checklist (Shehran, on the tablet)
1. *Cull in Library* after an import opens the imported photos; no crash. *Open collection* too.
2. A photo with GPS in the file shows its location with *From the camera*; one without shows *Add location*; adding one works and undoes.
3. Photos already in the Library before this build show their camera location after the Library rescans.
4. `bash scripts/device-test.sh smoke` and `… full` pass; `build/device-report.md` is readable on its own.
5. After a full run, your own RawCull app still has your library, ratings and settings; `Sample Import` is unchanged. A separate "RawCull QA" app is installed.
6. The 0.10.0 debug build installed over R7 kept everything.

## Not in this round
Old-API devices or emulators (O11), CI, Firebase Test Lab, screenshot diffing against golden images, testing the tester's own update install, the R9 layout fixes (only reported here), O14/O15.

## Implementation notes
(Claude Code: S1–S3 add one-line deviations under *Running notes*; S4 writes the rest.)

### Running notes
- R8.1: the crash was already in the tablet's crash buffer (09:04, 8 s after import job 1 finished): `IndexOutOfBoundsException` 15/15 in grid prefetch. `PrefetchController` builds a debounced window lazily; it captured the old count (30) but `GridPrefetch` resolved it through `rememberUpdatedState`, i.e. against the new 15-photo `LatestImport` list. Windows now keep their own list (grid and loupe). Lead (a), the entry popping itself, isn't involved; no repro by Shehran was needed.
- R8.2: the shared picker is `library/FileLocation`; enrichment applies it in `RawPhotoDao.updateMetadataBatch` (the DAO keeps its edit guards) and `ScanCoordinator` takes `gpsInFile` from it. Migration 7 → 8 also skips demo rows (`rootUri != DEMO_ROOT_URI`): synthetic, never scanned. The tablet was migrated before that change, so its 8 demo rows sit at `metadataLoaded = 0`, which nothing but the scan reads. On the tablet after install: v8, all 15 imported `SS_010xx` rows and the 7 `_SS_07xx` rows got their GPS on the first rescan; no crash.
- R8.2: `Migration6To7Test` now opens through v8 (expects `metadataLoaded = 0` for its location-less row). The device `MigrationTest` still stops at v6; R8.7 extends it.
