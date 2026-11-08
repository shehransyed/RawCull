# RawCull — Round 6: Viewing (full window, slideshow, focus peaking, histogram modes)

Status: **planned** · 2026-11-07 · Branch `feature/r6-viewing` from `feature/r5-previews` · Version **0.8.0** (`versionCode` 8) · Catalog stays **Room v6**; no new catalog data.
Overview: `00_workflow_overhaul_overview.md`. Collections and GPS editing are **R7 (Organizing)**; the R4 open items (O14 sidecars on Restore, mixed-selection toggles) are later. Don't implement either.

**How to read this plan (Claude Code):** read this header, *Decisions*, and only your session's tasks. Each session starts fresh; find where you are from `git log --oneline feature/r5-previews..`.

## Sessions
| Session | Tasks | Model | Why that model |
|---|---|---|---|
| S1 | R6.0, R6.1 | Opus | R6.1 is a behaviour-preserving split of a 1,067-line ViewModel |
| S2 | R6.2 – R6.5 | Sonnet | Settings, full window, slideshow: wiring on existing patterns |
| S3 | R6.6, R6.7 | Opus | Edge kernel, AGSL shader, GPU/CPU comparison test |
| S4 | R6.8, R6.9 | Sonnet | Histogram modes, wrap-up, short emulator check |

## Handoff prompts (one per session; start each in a fresh Claude Code session)
- **S1:** `Round 6, session S1: implement tasks R6.0 and R6.1 of docs/plans/R6_viewing.md. Create the local branch feature/r6-viewing from feature/r5-previews (merge any newer R5 commits first). Local only: no push, no PR, no merging into other branches. One commit per task; bash scripts/check.sh must pass before each commit (R6.0 creates it). Stop after R6.1.`
- **S2 / S3 / S4:** `Round 6, session S<n>: continue on feature/r6-viewing with tasks <list> of docs/plans/R6_viewing.md. Merge newer feature/r5-previews commits first if there are any. Same rules as before (local only, one commit per task, scripts/check.sh before each commit). Stop after <last task>.`
- Where the plan and the code disagree, follow the code's patterns and note it in *Implementation notes* (S4 writes them; S1–S3 add one line per deviation under a `### Running notes` heading).

## Decisions
| # | Topic | Decision |
|---|---|---|
| D1 | Keys | Added to `LibraryKey` and `LibraryKeymap`: **F** full window, **Ctrl+Enter** slideshow, **O** focus peaking on/off, **H** histogram overlay on/off (all new `Enter`, `O`, `H` keys). In a slideshow, **Space** pauses/resumes instead of toggling zoom. The help sheet picks them up from the keymap. |
| D2 | Full window | Enter with F, a toolbar button in the loupe, or from the grid (opens the selected photo); from compare it shows the active pane's photo. Hides all app chrome **and** the system bars (`WindowInsetsControllerCompat`, show transient bars by swipe), at every width. Black background (theme token). Exit with F, Esc, system Back or the overlay's exit button; system bars come back. State lives in `LibraryUiState` so rotation, resize and process death keep it. |
| D3 | Full window behaviour | Same `LoupeImage` + `LoupeZoomState` as the loupe: fit, true 100 %, pinch, double tap, *Preview only* label. Same swipes as the loupe (`LoupeSwipe`). All culling keys work, incl. Shift/Caps-Lock auto-advance and undo. Every edit flashes a small badge (flag / stars / label) for ~1 s. A tap toggles a controls overlay (file name, flag, rating, label, prev/next, slideshow, exit) that hides after 3 s without input. |
| D4 | Slideshow: what plays | Ctrl+Enter, a play button in the loupe toolbar and the full window overlay, and *Slideshow* in the grid's overflow menu. Plays the **selection** (in view order) if ≥ 2 photos are selected, else the **current filtered view**, starting at the current photo. The playlist is a snapshot of ids taken at start, so culling during the show doesn't make photos vanish or jump. Ids that disappear (deleted, Location removed) are skipped. Empty playlist → snackbar, no show. |
| D5 | Slideshow: timing | Always in full window. Interval 2 / 3 / **5** / 10 s. The interval counts from when the photo's fit preview is on screen, so a slow load never gets skipped; a photo with *No preview* shows that state for one interval. Crossfade **on** (300 ms), can be turned off. Loop **on**; with loop off it stops paused on the last photo with "End of slideshow". Prefetch the next 2 at fit and keep 1 behind (existing loupe lanes). |
| D6 | Slideshow: controls | Space pause/resume; ←/→ step and restart the interval; Esc/Back ends the show and returns to the view it started from, with the last shown photo selected. Culling keys edit the shown photo and **never advance** (the clock does). Pinch or a zoom key pauses; resume refits. Screen stays on only while playing. Pauses on app stop and stays paused when you come back. Tap → overlay with play/pause, prev/next, interval chips, loop toggle, exit. |
| D7 | Focus peaking: look | Edge highlight drawn over the photo in the loupe, compare (both panes), full window and slideshow. Computed at **display scale** (what you see at fit, the tile at 100 %). Sensitivity Low / **Medium** / High; colour **Red** / Yellow / Cyan / Green (new theme tokens in `RawCullTheme.colors`). On/off is per session (starts off); sensitivity and colour are saved. |
| D8 | Focus peaking: how | Port RapidReady's peaking kernel and thresholds (`C:\Users\shehr\Desktop\rapidready`); if it has none worth porting, use a 3×3 Sobel on Rec. 709 luma with thresholds per sensitivity. **API 33+:** AGSL `RuntimeShader` with the preview as a child `BitmapShader` (works with hardware bitmaps), sampling neighbours one screen pixel apart. **API < 33:** CPU path on `Dispatchers.Default`: a software copy of what's drawn, scaled to the view (≤ 2560 px long edge) or the 100 % tile, turned into a mask; the photo shows first, the mask follows; cancelled on photo or zoom change. One pure `PeakingKernel` (IntArray in, mask out) is the reference for both paths. |
| D9 | Histogram | `HistogramCalculator` becomes pure (ARGB `IntArray` + size in, raw counts out); `HistogramData` holds counts, not normalised floats. Modes **RGB + Luma** (today's look, default) / RGB / Luma; scale **Linear** (default, ignores bins 0 and 255 for the peak, as today) / Log (`ln(1+c)/ln(1+max)`). Tap the histogram to cycle the mode; a *Log* chip toggles the scale. Both saved. Source stays R5's 320 px software thumbnail. |
| D10 | Histogram overlay | H toggles a translucent histogram over the loupe, full window and slideshow (not compare). Top-right, ~200 × 100 dp at Medium+, ~160 × 80 dp at Compact, clear of the zoom pill, *Preview only* label and badges at 360 dp. On/off is saved. The inspector histogram stays and uses the same modes. |
| D11 | Settings | New **Viewing** section: slideshow interval, loop, crossfade; peaking sensitivity and colour; histogram mode and scale. New DataStore keys only: `slideshow_interval_s`, `slideshow_loop`, `slideshow_crossfade`, `peaking_sensitivity`, `peaking_color`, `histogram_mode`, `histogram_log`, `histogram_overlay`. |
| D12 | Size discipline | `LibraryViewModel.kt` ≤ 450 lines after R6.1; full window, slideshow, peaking and histogram overlay are new files; `LibraryScreen.kt` grows only by wiring (~40 lines). No new file over ~400 lines. |
| D13 | Targets (reported, not gated) | Peaking on (AGSL) adds ≤ 2 ms per frame while flicking in the loupe on the tablet; CPU mask ≤ 200 ms for a 2560 × 1600 view on the emulator; slideshow never shows a photo for less than its interval. |

## Tasks

### R6.0 Version, quiet check script, docs — Sonnet-grade
- `versionCode = 8`, `versionName = "0.8.0"` **in this commit**.
- `scripts/check.sh` (runs under Git Bash; `bash scripts/check.sh [extra gradle args]`). Default tasks `testDebugUnitTest assembleDebug`, run with `-q --console=plain`, full output to `build/check.log`, exit code passed through. **Passing:** prints exactly one line, e.g. `OK  708 tests, 0 failed, 1 skipped  (1m42s)`, counts from `app/build/test-results/**/TEST-*.xml`. **Failing:** prints failed test names with the first ~10 lines of each failure, or the Kotlin `e:` lines for a compile error; at most 60 lines, then `full log: build/check.log`. Passes `--tests "<pattern>"` through.
- Commit the `CLAUDE.md` changes already in the working tree (*Working efficiently* section) and this plan.
- Acceptance: one passing run and one deliberately broken run (revert it) show the two output shapes; note both in *Running notes*.

### R6.1 Split `LibraryViewModel` (no behaviour change) — Opus
- Keep `LibraryViewModel` as the façade the UI talks to: same public functions, same single `StateFlow<LibraryUiState>`. Move the bodies into plain classes in `ui/library/` that share the ViewModel's scope and a state-update function, along these lines: culling (`apply`, undo/redo, swipes, auto-advance), reject deletion (prompt, trash request, permanent part), locations and scanning (add/relink/remove, availability, scan progress), filters/view computation, histogram loading. Pick the boundaries the code suggests; note them.
- `LibraryViewModelTest` passes with at most constructor/wiring changes. No UI file changes beyond imports.
- Update the `CLAUDE.md` architecture line for `ui/library/`.
- Acceptance: D12 line count; full check passes; R4/R5 behaviour unchanged (tests are the proof).

### R6.2 Viewing settings — Sonnet
- D11 keys in `SettingsRepository` with defaults; Viewing section in `SettingsScreen` (chips/switches following the existing sections' patterns; 48 dp targets; Compact to Expanded).
- New peaking colour tokens in `Color.kt` → `RawCullColors`.
- Tests: defaults, round-trip of each key, unknown stored enum value falls back to the default.

### R6.3 Full window — Sonnet
- D2, D3. New `ui/library/FullWindow.kt`; the shell's existing immersive path (`onImmersiveChange` → `NavigationSuiteType.None`) hides navigation; full window also hides the system bars and restores them on exit and on leaving the screen.
- Keymap: F (and Esc to exit). The edit badge is a small reusable composable.
- Tests (Robolectric/Compose): enter from loupe, grid and compare; exit by F, Esc, Back; culling key edits the shown photo and auto-advance works; state survives recreation; keymap test for F.

### R6.4 Slideshow logic (pure, `library/Slideshow.kt`) — Sonnet
- Playlist snapshot (D4), clock with pause/resume/step, dwell-from-shown (D5), loop/end, skipping missing ids. Driven by a clock interface so tests use virtual time.
- Tests: selection vs view; start index; step resets the interval; slow load doesn't shorten dwell; loop on/off; ids removed mid-show; pause across stop/start.

### R6.5 Slideshow UI — Sonnet
- D5, D6 in full window: crossfade (both images briefly composed; hardware bitmaps, so memory stays small), keep-screen-on while playing, prefetch, controls overlay, Space/←/→/Esc, pinch pauses. Entry points: Ctrl+Enter, loupe toolbar, full window overlay, grid overflow.
- Tests: starts from each entry point; advances with a test clock; Space pauses; culling doesn't advance; Esc returns to the starting view with the right selection; keymap tests for Ctrl+Enter and Space-in-slideshow.

### R6.6 Focus peaking: kernel, CPU path, overlay plumbing — Opus
- Read RapidReady's peaking code first (D8). Pure `library/PeakingKernel.kt` (or `preview/`), with JVM tests on synthetic images: a hard edge is marked, a flat or smoothly graded area isn't, sensitivities are ordered (High marks ⊇ Medium ⊇ Low), borders don't crash.
- `LoupeImage` gets an optional peaking overlay used by loupe, compare, full window and slideshow; the CPU path (D8) is what runs in this task on every API level. O toggles it; the toolbar gets a peaking toggle.
- Tests: toggling, cancel on photo change, mask follows the 100 % tile.

### R6.7 Focus peaking: AGSL path + first device test — Opus
- `RuntimeShader` path on API 33+ (D8), chosen at runtime; CPU path below 33. Same thresholds as the kernel.
- `androidTest/…/PeakingShaderDeviceTest` (API 33+, skipped below): renders a synthetic edge image through the shader off-screen (e.g. `HardwareRenderer` + `ImageReader`) and compares the marked pixels with `PeakingKernel` within a stated tolerance. This is the first test of the future on-device suite: keep it self-contained (no SAF, no pickers).
- Report D13's frame-time number from the emulator if measurable (say if SwiftShader can't run `RuntimeShader`).

### R6.8 Histogram modes and overlay — Sonnet
- D9, D10. Pure calculator and scaling (`histogramHeights(counts, mode, scale)`) with JVM tests (known images: all-black, all-white, two-tone, log vs linear ordering, clipping counts). `HistogramView` draws the modes; tap cycles, *Log* chip; new `ui/components/HistogramOverlay.kt`; H toggles.
- Tests: mode/scale persist; overlay toggles with H; overlay hidden in compare.

### R6.9 Wrap-up — Sonnet
- Help sheet shows F, Ctrl+Enter, O, H (generated; check the test covers it). `CLAUDE.md`: one line each for full window, slideshow, peaking (where the kernel and shader live), histogram.
- **Emulator check, limited:** Medium_Tablet API 36 at **360 × 640 dp** and **1280 × 800 dp** only; at most **8 screenshots** in total: full window with overlay, slideshow overlay, peaking on (loupe and 100 %), histogram overlay at 360 dp. Plus the update check (install the R5 build, add a rating and a setting, install R6 over it with `adb install -r`).
- *Implementation notes*, short: deviations, open questions, D13 numbers, the update check. No per-commit narration.

## Acceptance checklist (Shehran, on the tablet)
Items marked *(auto)* become device tests in the planned test-suite round; until then a quick look is enough.
1. F in grid, loupe and compare opens full window; system bars hidden; F/Esc/Back exit. Rotate while in it. *(auto)*
2. In full window: P/X/stars/labels work with the badge; Shift-advance works; swipes match the loupe. *(auto)*
3. Slideshow from selection and from a filtered view; 2 s and 10 s feel right; crossfade smooth; Space, ←/→, Esc; screen doesn't sleep; culling mid-show doesn't skip photos. *(partly auto)*
4. Peaking (O) on a sharp and a soft frame from your ARW samples: does it separate them at fit and at 100 %? Try all three sensitivities. *(judgement)*
5. Peaking while flicking fast through the loupe: any stutter? *(judgement)*
6. Histogram: modes, Log, overlay (H) on the loupe and in the slideshow; nothing overlaps at phone size. *(auto for layout)*
7. Settings → Viewing values survive an app restart, and R6 installed over R5 without losing your library. *(auto)*

## Not in this round
Clipping overlay on the image (Lightroom's J), info overlay cycling (I), per-pane compare zoom, slideshow music/transitions beyond crossfade, external display/casting, collections and GPS (R7), R4 open items.

## Implementation notes
(Claude Code: S1–S3 add one-line deviations under *Running notes*; S4 writes the rest.)

### Running notes
- R6.0: S1 ran in a Claude Code worktree (the app made it), not the main checkout; the branch is the same `feature/r6-viewing`. The `CLAUDE.md`/overview edits and this plan were copied from the main checkout's working tree, where they're still uncommitted on `feature/r5-previews`.
- R6.0: check.sh puts extra args right after `testDebugUnitTest` (so `--tests` binds to it). If `JAVA_HOME` is unset it picks the first Android Studio JBR with a `lib/jvm.cfg`. Failure output only reads test XML newer than the run's start, so stale results don't show.
- R6.0 acceptance: passing run printed `OK  708 tests, 0 failed, 1 skipped  (0m46s)`. Failing shapes: a failed test prints `FAILED (exit 1, …)`, the counts, `--- Class.test` and the first 10 trace lines; a deliberate compile break prints the `e:` lines; a Gradle option error prints *What went wrong*. Each ends with `full log: build/check.log`.
- R6.0: the first full run (cold, 2m47s) failed `LibraryViewModelTest.a later message doesn't inherit Restore` on a 10 s `waitUntil` timeout; it passed alone and on every later full run. Looks load-sensitive; watch it.
- R6.1: split as planned, plus `SelectionController` (multi-select) as its own part and `LibraryUiState` moved to its own file (same package, so no imports changed). Navigation, compare/view mode and the tag/sync dialogs stayed in the ViewModel: they're a few lines each. `LibraryViewModel.kt` is 325 lines; the largest new file is `LocationController.kt` at 332. `LibraryViewModelTest` is unchanged.
- R6.1: `LibraryViewModelTest.filters are remembered per Location` failed once in a full run (expected `[PICK]`, was `[]` after switching back) and passed in 3 class runs and 2 full runs after. With R6.0's flake that's two load-sensitive `LibraryViewModelTest` failures; worth hardening (fresh DataStore file per test?) in a later round.
