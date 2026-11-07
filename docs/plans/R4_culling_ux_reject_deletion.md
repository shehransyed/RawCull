# RawCull — Implementation Plan: Round 4 (Culling UX + reject deletion)

Status: **READY FOR CLAUDE CODE** · Planned 2026-11-07 · Base: local branch `feature/r3-shell-library-import` @ `a53eccb` (R3 done, on-device testing in progress, not merged into `main` yet)
Overview of the whole overhaul: `00_workflow_overhaul_overview.md`. R5 (previews), R6 (extras) and R7 (design pass) are out of scope here.

## Goal

Make culling in the Library fast and safe on every device and input method, and make every build install as an **in-place update** of the one before:

- **Keyboard:** the full Lightroom Library shortcut set, including set-and-advance, Caps Lock auto-advance, undo/redo and a shortcut help sheet.
- **Touch:** swipe up = pick, swipe down = reject, left/right = next/previous in the loupe, with haptics; long-press multi-select with drag-to-select in the grid; batch actions.
- **Undo / redo** for every culling edit, single or batch.
- **Filter bar:** flags, rating with operator, colour labels, tags with counts, search, sort; remembered per Location.
- **Delete rejects:** to the device trash where Android has one, otherwise permanently after a clear warning; sidecars included.
- R3 leftovers that belong here: sticky folder headers (O8), enrichment following the visible order (O9), plausible capture dates, old card rows, import timings that exclude paused time.

---

## Decisions baked into this plan (Shehran: change any of these before handing off)

| # | Decision | Default chosen | Why |
|---|---|---|---|
| D1 | Builds are updates | **Every round's first commit bumps `versionCode`** (R4 → 6, `0.6.0`), so any build of the branch installs over the previous round's build. Same `applicationId`, same debug key, Room migrations only forward, DataStore keys never renamed or dropped without a migration. Written into `CLAUDE.md` as a standing rule. | Shehran: each round's build should update the app on his device, keeping his library, ratings and settings. |
| D2 | Shortcut preset | **Lightroom Classic Library module** (table below). Unused LR keys stay unbound. `Delete` stays unbound (in LR it removes from the catalog). | Shehran: Lightroom preset only. |
| D3 | Auto-advance | **Off** by default. Caps Lock on a hardware keyboard turns it on (as in LR); `Shift` + any flag/rating/label key always advances once. A toggle in the loupe toolbar and in Settings covers touch. Advance moves to the next photo in the **visible** list. If the edit removes the photo from the current filter, the photo that followed it becomes current and that counts as the advance (no double step). | LR behaviour; the filter case is where naive implementations skip a photo. |
| D4 | Undo / redo | In-memory, per Location, 200 steps. Covers flag, rating, colour label and tags; a batch edit is **one** step. Undo selects the affected photo if it's visible. Not persisted across launches. Deletion isn't on the undo stack (the trash has its own Restore, D8). | Cheap and predictable; edits already go through one repository path and `XmpWriteQueue`. |
| D5 | Filter semantics | Flags: any of Pick / Unflagged / Reject (multi). Rating: operator `≥` `=` `≤` plus 0–5. Colours: any of the five labels plus "No label" (multi). Tags: any of the chosen tags (multi). Search: file name, camera, lens, tags. Sort: the existing six. Counts next to every option are counts **within the current scope, ignoring filters** (LR-style, predictable). Filters are remembered **per Location** (DataStore) and can be switched off and on in one go (`Ctrl+L`). | Matches the overview; per-Location memory fits several libraries. |
| D6 | Gestures | Loupe only, and only at fit zoom: swipe up = pick, swipe down = reject (each toggles, like `P`/`X`), swipe left/right = next/previous. The photo follows the finger with a tinted badge, commits past ~20 % of the height or on a fling, and springs back otherwise. Haptics: `Confirm` on pick, `Reject` on reject, a light tick on navigation. Setting: *Swipe to flag* (default on). No swipe-to-flag in the grid (it fights scrolling). | Shehran's overview items; zoomed photos keep their pan gesture. |
| D7 | Multi-select | Long-press a tile enters selection; dragging after the long-press extends it (Google Photos style, with edge auto-scroll); tap toggles while selecting; `Shift`+click range, `Ctrl`+click toggle, `Ctrl+A` / `Ctrl+D` / `Ctrl+Shift+D`. A contextual top bar shows the count and batch actions (flag, rating, label, tags, delete the rejects in the selection). While a selection exists, culling keys apply to the whole selection, as in LR's grid. Back / `Esc` clears it. | Current multi-select is a mode toggle in the overflow; this is the standard Android pattern plus desktop modifiers. |
| D8 | Delete rejects | Action **"Delete rejects…"** (overflow, filter bar when *Reject* is shown, `Ctrl+Backspace`) over the current scope, and **"Delete rejects in selection"** in the contextual bar. Per file: Android 11+ and the file resolves to a MediaStore item (`MediaStore.getMediaUri`) → **system trash** (`MediaStore.createTrashRequest`, one system confirmation for the batch, restorable for 30 days); otherwise (USB drive not indexed by MediaStore, Android < 11, unresolvable sidecar) → **permanent** `DocumentsContract.deleteDocument` after RawCull's warning. Primary, companion and both sidecar styles go together; a group is never half deleted. A snackbar offers **Restore** for trashed items (untrash request). | Decision of 2026-11-06 (trash where possible, permanent with warning otherwise, sidecars included). |
| D9 | Duplicate index after deletion | Deleted rejects **stay** in `imported_files`, so re-importing the card keeps them marked *Imported* and hidden by default. | You already decided against them; the Import preview can still show them with *Hide already imported* off. |
| D10 | Folder headers | **Sticky** (O8). Shown only when the scope spans several folders **and** the sort is capture time or name; photos are grouped by folder, then sorted inside each group, so a folder appears once. Rating and size sorts show a flat grid. | Fixes the repeating headers; a header per rating bucket isn't useful. |
| D11 | Enrichment order | Follows the **visible** order (active filter and sort), working outward from the selected photo (O9). | What you're looking at fills in first. |
| D12 | Large libraries | Filtering, sorting and counting stay **in memory** but move to one pure function run on `Dispatchers.Default`, measured with a synthetic 50,000-row library. Budget: ≤ 150 ms per filter change on the tablet, no dropped frames while scrolling. Over budget → stop and report with numbers; moving the query into Room / Paging is a separate decision. | Measure before re-architecting (R3 open question 9). |
| D13 | Design scope | **No visual redesign in R4** (that's R7, after real-world use). New UI uses Material 3 components and theme tokens only; R4.1 moves the palette into the theme so R7 is a re-skin, not a rewrite. | See the overview's R7 entry. |
| D14 | `compileSdk` 37 | If the Android 37 SDK platform can be installed (`sdkmanager "platforms;android-37"`), move `compileSdk` to 37 and take the versions R3 couldn't (Compose BOM ≥ 2026.09, adaptive 1.3, lifecycle 2.11). `targetSdk` **stays 36**. If anything breaks, revert to 36 and note it. | Unblocks current libraries without changing runtime behaviour (R3 open question 1). |
| D15 | Plausible dates | A capture date counts only if it's between **1990-01-01** and **now + 48 h**. Otherwise the next source is tried (EXIF → file date), then `Undated/`. Applies to import folders and to the Library's date sort. | Cameras with an unset clock write 1970 or 2000-01-01 (R3 open question 7). |
| D16 | Old card rows | At startup, delete `photo_assets` rows whose `rootUri` is neither a Location nor the demo root **and** have nothing pending (`SYNCED`). Rows with pending XMP stay until their card is back. | R3 open question 5, without losing a pending rating. |
| D17 | Import timings | Add `activeMillis` to `import_jobs` (Room v6, auto-migration): elapsed time and average speed exclude paused time. | R3 open question 3. It's one nullable-default column, and a v5 → v6 migration also proves the "every round is an update" rule early. |

### Shortcut map (Lightroom Classic, Library module)

| Keys | Action | Notes |
|---|---|---|
| `P` / `X` / `U` | Pick / Reject / Unflag | Existing |
| `` ` `` | Toggle Pick ↔ Unflagged | |
| `Ctrl+↑` / `Ctrl+↓` | Raise / lower flag (Reject → Unflagged → Pick) | |
| `0`–`5` | Set rating (pressing the current rating clears it, as today) | Existing |
| `[` / `]` | Lower / raise rating by one | |
| `6` `7` `8` `9` | Toggle Red / Yellow / Green / Blue label | Purple stays in the menu (LR has no key) |
| `Shift` + any of the above | Apply and advance | D3 |
| Caps Lock | Auto-advance on while locked | D3 |
| `←` `→` | Previous / next | Existing |
| `↑` `↓` | Row up / down in the grid; previous / next in the loupe | |
| `Home` / `End` | First / last photo | |
| `G` / `E` / `C` | Grid / Loupe / Compare | `C` exists |
| `Z`, `Space` | Toggle fit ↔ 1:1 in the loupe | |
| `Tab` / `Shift+Tab` | Toggle side panes / all chrome | |
| `Ctrl+Z` / `Ctrl+Shift+Z`, `Ctrl+Y` | Undo / Redo | D4 |
| `Ctrl+A` / `Ctrl+D`, `Ctrl+Shift+D` | Select all / select none | D7 |
| `Ctrl+F` | Focus search | |
| `\` | Show / hide the filter bar | |
| `Ctrl+L` | Filters on / off | D5 |
| `Ctrl+K` | Add tags to current photo / selection | Opens the tag dialog |
| `Ctrl+Backspace` | Delete rejects… | D8 |
| `Ctrl+/`, `?` | Shortcut help sheet | |
| `Esc` | Close dialog → clear selection → loupe to grid → back to *All photos* | Ladder, one step per press |

`Shift+Tab`, `Tab` and `Z` act only when no text field has focus. On macOS-style keyboards `Meta` is treated as `Ctrl`.

---

## Handoff prompt (paste into Claude Code)

> You are working in the RawCull Android repo (Kotlin, Jetpack Compose, Material 3, Room, DataStore, SAF). Implement **Round 4** exactly as specified in `docs/plans/R4_culling_ux_reject_deletion.md`. Round 3 isn't merged yet: create a local branch `feature/r4-culling-ux` **from `feature/r3-shell-library-import`** (tip `a53eccb` or later). If `feature/r3-shell-library-import` gets new commits while you work (fixes from Shehran's device testing), merge them into your branch. First, clean up stale worktrees: `git worktree prune`, then delete the local branches `feature/r0-r1-real-card`, `feature/r2-xmp` and `claude/r3-shell-library-import-4d1efc` (all merged or empty; use `-D`). **Work locally only: don't push, don't create a pull request, and don't merge into `main` or the R3 branch**; Shehran builds the branch in Android Studio, tests it on his tablet and merges it himself. Make **one commit per numbered task** (R4.0 … R4.10) with a conventional-commit message. After every commit run `./gradlew testDebugUnitTest assembleDebug` (Windows: `gradlew.bat …`); the app must build and tests must pass at every commit, and **every commit's APK must install over the R3 build as an update** (R4.0 bumps the version first). Don't implement anything from Rounds 5–7. Where this plan and the code disagree, follow the code's established patterns and note the deviation. When finished, append "Implementation notes" to the plan (deviations, open questions, the 50k-row timings, the trash behaviour you observed per storage type, what was verified on which emulator sizes), and update `CLAUDE.md` as R4.10 says.

---

## Tasks

### R4.0 Update-safe builds, version, schema v6
- `versionCode = 6`, `versionName = "0.6.0"` **in this first commit**.
- D14: try `compileSdk` 37 and the library versions R3 had to skip; record what you chose (or why you stayed on 36).
- Room v6: `import_jobs.activeMillis` (Long, default 0) via `@AutoMigration(5, 6)`; commit `6.json`. `ImportEngine` accumulates running time only; `ImportProgress` shows elapsed and average speed from it (D17).
- Migration test (androidTest, `MigrationTestHelper`) **5 → 6 with real data**: a Location, a custom preset, `imported_files` rows, a finished job with items, and a `photo_assets` row with `PENDING` XMP and tags. All survive.
- Signing: keep the debug signing logic as it is (a `debug.keystore` in the repo root wins, else Android's per-user key). Add a comment in `app/build.gradle.kts` that changing the debug key breaks in-place updates on test devices, and nothing else.
- `CLAUDE.md`, new rule section **"Every round is an update"**: bump `versionCode` in a round's first commit; never lower it; Room migrations only forward, each with a migration test from the previous version with data; never rename or drop a DataStore key without a migration; never change `applicationId` or the debug signing key; never ask the tester to uninstall.

### R4.1 Design foundations (no visual change)
- Move the palette into the theme: a `ColorScheme` for the M3 roles plus a small `RawCullColors` extension (`pick`, `reject`, `star`, the five label colours, sync states) provided through a `CompositionLocal`. `ColorLabel.hexColor` becomes a lookup into it.
- Spacing and sizes the culling UI uses (tile gap, bar heights, 48 dp targets) as named constants in `ui/theme/`.
- Replace hex literals and ad-hoc colours in every file R4 touches. Screens must look the same before and after (compare screenshots at 1280×800 and 360×640 and say so in the notes).

### R4.2 Pure culling logic (plain JUnit, no Android types)
In `dev.shehran.rawcull.library` (or `ui/library/logic/`, following the code):
- `CullEdit` (sealed: flag, rating, rating step, label toggle, tags add/remove) and `CullChange(ids, before, after)` so undo can restore exactly what was there (per photo, for batches).
- `UndoStack` (200 steps, redo cleared by a new edit, cleared on Location switch; drops entries whose photos were deleted).
- `AutoAdvance.next(visibleBefore, visibleAfter, editedId)` implementing D3, including the "edited photo left the filter" case.
- `LibraryFilter.apply(rows, filter, sort, scopeSpansFolders): LibraryView` → visible rows, folder sections (D10), counts per option (D5). Replaces `applyFilterAndSort`. `FilterOptions` gets the new fields (flag set, rating operator, colour set incl. none, tag set); keep a decoder for the old persisted shape.
- `PlausibleDate.pick(exif, fileDate, now)` (D15), used by `PathTemplate` callers and the date sort.
- `LibraryKeymap.actionFor(key, ctrl, shift, alt, meta, capsLock, textFieldFocused): LibraryKeyAction?` replacing `LibraryKeys`, covering the whole shortcut table.
- Tests: every row of the shortcut table; Shift-advance and Caps Lock; advance at the end of the list (stays put); advance when the photo leaves an *Unflagged* filter; undo/redo of a batch of mixed prior states; undo after a filter change; `≤`/`=`/`≥` rating; colour "No label"; multi-tag any-of; counts ignore filters; folder grouping only for date/name sorts; old `FilterOptions` JSON decodes; plausible dates at the boundaries.
- A JVM test that builds 50,000 synthetic rows and records `LibraryFilter.apply` time (report it; don't assert on it).

### R4.3 ViewModel wiring
- All culling goes through one `LibraryViewModel.apply(edit, target)` where `target` is the selection if one exists, else the current photo (D7). It snapshots `before`, writes through new **batch** repository methods (one Room transaction; `XmpWriteQueue` marks each id), pushes onto `UndoStack`, then applies auto-advance. `undo()` / `redo()` write the stored states back through the same path.
- Filtering runs on `Dispatchers.Default`, cancelling the previous run on each change (`mapLatest`), and logs `RawCull.Library` with row count and ms.
- Filters persist per Location (DataStore map `locationId → FilterOptions JSON`); `filtersEnabled` flag for `Ctrl+L`.
- O9: whenever the visible order changes, hand it to `ScanCoordinator` (`EnrichmentQueue` gets the visible id order; prioritise outward from the selected photo).
- D16 cleanup in `StartupCleanup`; log how many rows went and how many pending ones stayed.
- Debug builds only: Settings → *Developer* → "Add 50,000 synthetic photos to the demo library" (rows only, no files), to measure D12 on the device.

### R4.4 Keyboard
- `LibraryKeymap` wired into the Library screen on every layout (grid, loupe, compare; Compact to Extra-large). Text fields (search, tag dialog) swallow keys; `Esc` ladder as in the table.
- **Shortcut help sheet** (`Ctrl+/`, `?`, and a "Keyboard shortcuts" item in the overflow): grouped list generated from the keymap so it can't drift.
- An auto-advance indicator in the loupe toolbar when Caps Lock or the toggle is on.

### R4.5 Filter bar
- Expanded and wider: a filter bar under the top bar (toggle with `\` and a filter button): Flags (three toggle chips with counts), Rating (operator + stars), Colours (six swatches with counts), Tags (menu with search and counts), Search field, Sort menu, *Filters on/off*, *Clear*. Compact and Medium: a chip row showing only active filters plus a **Filters** button that opens a modal bottom sheet with the same controls.
- The top bar shows "137 of 842" when filters hide photos. An empty result shows *No photos match* with *Clear filters*.
- When *Reject* is the only flag shown, the bar offers *Delete rejects…* (D8).
- Existing `syncErrorsOnly` stays reachable from the sync chip.

### R4.6 Grid: sticky headers and multi-select
- Sticky folder headers (D10): use `LazyGridScope.stickyHeader` if the chosen Compose foundation has it; otherwise overlay the current section's header above the grid. Header: folder path (relative to the Location) and count.
- Multi-select per D7: long-press to start, drag to extend with auto-scroll at the edges, tap to toggle, `Shift`/`Ctrl` click, select-all keys, a contextual top bar (count, *Select all*, batch Pick / Reject / Unflag, rating, label, tags, *Delete rejects in selection*, close). Predictive back clears the selection. Replace the overflow's multi-select mode toggle and the old batch bar.
- Selection survives rotation and resizing (ids in the ViewModel); it's cleared on scope or Location change.

### R4.7 Loupe: gestures, haptics, auto-advance, undo
- D6 gestures in `ZoomableLoupeView` and `CompareLoupeView` (compare: on the active photo), only at fit zoom; pinch, double-tap and pan unchanged when zoomed. Drag feedback: the photo translates with the finger and a PICK / REJECT badge fades in; release commits or springs back. Respect *Swipe to flag* off.
- Haptics through `LocalHapticFeedback` (`Confirm`, `Reject`, `SegmentTick` or the closest available), only on commit.
- Touch controls in the culling bar: Undo and Redo buttons (enabled state from the stack), the auto-advance toggle. Phone landscape keeps the vertical side bar from R3.
- After a batch edit or any undo, a snackbar names what changed ("Picked 24 photos · Undo").

### R4.8 Delete rejects
- `RejectDeletion` planner (pure, tested): from the target rows, list every file per group (primary, companion, `xmpFileUri`, `darktableXmpUri`), size totals, and which go to trash vs permanent given `(apiLevel, mediaUri resolved?)`.
- Android side (`data/deletion/`): flush `XmpWriteQueue` for the rows first; resolve each document URI with `MediaStore.getMediaUri` (API 29+; treat exceptions as "not resolvable"); on API 30+ build one `createTrashRequest(resolver, uris, true)` and launch it via `StartIntentSenderForResult`. If the user cancels the system dialog, **nothing** is deleted, including the permanent part. After the trash succeeds, permanently delete the unresolvable files of the **same groups** with `DocumentsContract.deleteDocument`; if a group's permanent part fails, report it (its trashed part stays in the trash).
- RawCull's own confirmation first: "Delete 37 rejected photos? 74 files · 1.9 GB. 72 go to the device trash (restorable for 30 days). 2 will be deleted permanently." When everything is permanent (USB drive, Android < 11): a destructive-styled dialog, "This can't be undone", button *Delete permanently*.
- Then remove the rows, drop undo entries for those ids, keep `imported_files` (D9), and show "37 photos moved to trash · Restore" (Restore = `createTrashRequest(…, false)` for the trashed ones, then `rescanSubtree` for the affected folders). Permanent-only deletions get no Restore.
- Trashed files are renamed `.trashed-…` by Android: confirm `WalkPolicy` skips them (hidden files) and add a test.
- Demo library: deletes rows only, no dialogs about files. Unavailable Location: action disabled.
- Verify on the emulator, and say in the notes what happened for each: internal shared storage (expect trash for photos; record what happens to `.xmp`), the virtual SD card, a folder MediaStore doesn't index (if you can make one), and API 29 (expect permanent).

### R4.9 Plausible dates in import
- `PlausibleDate` (D15) in `ImportSession`'s date reading and in the worker's target-folder choice; the Import preview groups implausible dates under *Undated*. Existing library rows aren't moved.
- Tests: a 1970 EXIF date with a good file date → file date; both bad → `Undated`.

### R4.10 Tests, docs, verification
- Compose UI tests (Robolectric, `@Config(qualifiers = …)` at w360dp, w640dp-h360dp, w1280dp): filter bar vs filter sheet by width; contextual bar appears on long-press and clears on back; help sheet lists the keymap; selection and filters survive a configuration change.
- ViewModel tests with a fake repository: `apply` on a selection writes one batch and one undo step; auto-advance with a filter; undo restores mixed states; delete removes rows and undo entries.
- Verify every changed screen at 360×640, 640×360, 600×960, 840×1280 and 1280×800 (as in R3), with a hardware keyboard on the tablet AVD, and list what was checked.
- **Update check:** install the R3 build (`a53eccb`) on an emulator, add a Location, import, rate a few photos, change settings; then install R4 over it with `adb install -r` and confirm everything is still there. Record it in the notes.
- `CLAUDE.md`: the "Every round is an update" rules (R4.0), `LibraryKeymap` + the help sheet, the single `apply` path and undo stack, `LibraryFilter`, `data/deletion/` and the trash rules, theme tokens (`RawCullColors`; no hex literals in UI code). Drop the hex palette line from Rules in favour of "use theme tokens".
- Implementation notes appended to this plan.

---

## Acceptance checklist (Shehran, on the tablet; phone if handy)

0. **Update:** with the R3 build installed and your real library set up, run R4 from Android Studio. No uninstall prompt; Locations, folders, ratings, import history and settings are all still there. Settings → About shows 0.6.0.
1. With the keyboard: `P` `X` `U` `` ` `` `0`–`5` `[` `]` `6`–`9` all work in grid and loupe; `Shift+P` picks and advances; Caps Lock makes every key advance; `Ctrl+Z` / `Ctrl+Shift+Z` undo and redo, including a batch; `Ctrl+/` shows the sheet; `Esc` steps back one level at a time.
2. In the loupe by touch: swipe up picks, down rejects, sideways navigates, with a buzz on pick and reject; a short swipe springs back; zoomed in, a drag pans instead.
3. Filter *Unflagged* with auto-advance on, pick ten photos in a row: no photo is skipped.
4. Filters: Picks + ≥ 3 stars + Green shows the right photos; counts match; switch Location and back, the filters are remembered; `Ctrl+L` toggles them.
5. Long-press a tile, drag across two rows: they're selected; rate them 4 stars at once; one undo restores each photo's own previous rating.
6. A scope spanning several date folders sorted by capture time: one sticky header per folder, no repeats. Sorted by rating: flat grid.
7. **Delete rejects** in a folder on internal storage: RawCull's dialog shows counts and size; the system trash dialog follows; files are gone from the folder and from Files' view; *Restore* brings them back with their sidecars (or note if the sidecar came back without its rating). On a USB drive: the permanent warning, then they're gone for good.
8. Re-import the card those rejects came from: they show as *Imported* and are hidden.
9. Debug build: add 50,000 synthetic photos to the demo library; changing filters stays snappy (note the `RawCull.Library` ms from logcat); scrolling doesn't stutter.
10. Rotate and resize on the filter bar / sheet, multi-select and loupe: nothing resets.
