# RawCull — Workflow Overhaul (import-first): Overview of Rounds R3–R9

Status: **R3–R7 implemented (on-device testing) · R8 planned** · updated 2026-11-08 · Round tips are tags `r1`–`r6` (R3 `a53eccb`, R4 `309f6d0`, R5 `810fd72`, R6 `d6e477f`); after R7.0 `main` = `r6` and R7 runs on `feature/r7-organizing` (all local)
Source of requirements: the "RapidReady → RawCull: Workflow Reference" doc (https://claude.ai/artifact/LZE1ekdGgE2v3wdrymAhxV), section 8 answers, and the decision log in `rawcull/01_roadmap_and_decisions.md`.

## What changes

RawCull moves from **cull-on-card, then export** to RapidReady's **import-first** model:

1. **Import:** pick a card (or folder), tick what to bring in, copy it with verification into a user-chosen **library Location** using a folder pattern (default `YYYY/YYYY-MM-DD`). Already-imported files are recognised and hidden.
2. **Library:** browse the whole library with a folder tree, a *Latest Import* view and all photos. All culling (flags, stars, labels, tags) happens here and writes XMP next to the library files (the R2 stack, unchanged).
3. **Clean up:** delete rejects, using Android's trash where it exists.

The card is never culled in place any more and never enters the catalog.

## Shehran's decisions this builds on (2026-11-06)

| Topic | Decision |
|---|---|
| Workflow | A: import-first |
| Library | User picks the root (named Locations, RapidReady-style); whole archive with folder tree |
| Verification | Claude's choice → full SHA-256 while copying + re-read of the copy; "Quick" (size + first 64 KB) in Settings |
| Delete from card | Settings toggle, behaviour follows it |
| Rejects | Delete straight away; device trash where available, otherwise permanent with a clear warning |
| Renaming | Not now |
| Sidecars | XMP only |
| Shortcuts | Lightroom preset only |
| Extras | Keep collections, GPS editing, slideshow, focus peaking, histogram; drop "open in another app" |
| Video | Ignore |
| Design | Current Android design guidance; responsive on tablets and phones, portrait and landscape |
| Plan shape | Several rounds, each shipping a working app |
| Updates (2026-11-07) | Every round's build installs over the previous one as an update (version bump first, forward-only migrations, same key) |

## Rounds

| Round | Name | Ships | Plan |
|---|---|---|---|
| **R3** | Adaptive shell + Library + Import | New app shell (Import / Library / Settings) that adapts to every window size and orientation; Locations; whole-library catalog with folder tree and *Latest Import*; the three-step Import wizard with a verified, resumable background copy, duplicate index and delete-from-card; a full Settings screen. Retires the Welcome screen, recent sources and the export dialog. | `R3_shell_library_import.md` (**implemented**, testing on the tablet) |
| **R4** | Culling UX + reject deletion | Update-safe builds; full Lightroom shortcut set with help sheet; auto-advance; undo/redo; filter bar (flags, rating with operator, colours, tags with counts, search, sort; per Location); loupe swipe gestures + haptics; long-press / drag multi-select with batch actions; **Delete rejects** via MediaStore trash (Android 11+) else permanent with warning, sidecars included; sticky folder headers (O8); enrichment follows the visible order (O9); 50k-row performance check; theme tokens; R3 leftovers (plausible dates, old card rows, active import time). | `R4_culling_ux_reject_deletion.md` (**implemented**, testing on the tablet; D12 open) |
| **R5** | Preview speed & quality | Embedded-JPEG previews for every listed RAW format (TIFF family, CR3, RAF, JPEG MPF), ported from RapidReady: one descriptor per request, 256 KB slice + positional reads (stream tiers 256 KB → 2 MB → file as fallback); bounded persistent thumbnail cache with a Room index; hardware-bitmap RAM cache; prefetch (grid ±3 rows, loupe +5/+3 ahead, 1 behind, 100 ms debounce); true 100 % via region decode, honest about small previews; background warm-up after import. Covers O10. Used by the library and the import preview. | `R5_preview_speed_quality.md` (**implemented**, testing on the tablet) |
| **R6** | Viewing | Full window (F, system bars hidden); slideshow (selection or filtered view, 2/3/5/10 s, crossfade, loop, culling keys, Ctrl+Enter); focus peaking (O; AGSL on API 33+, CPU below; sensitivity and colour); histogram modes (RGB+Luma/RGB/Luma, linear/log) and an overlay (H); Settings → Viewing. Starts with the quiet check script and a split of `LibraryViewModel`. | `R6_viewing.md` (**implemented**, tag `r6`) |
| **R7** | Organizing | Starts with git cleanup (tags `r1`–`r6`, `main` = R6, worktrees removed, `scripts/tidy-git.sh`). Collections and sets (per Location, B adds to the target collection, add on import); custom order with drag reorder; ordered export (`NNN_` names, capture times +10 s on the copies); GPS editing (Maps links, DD/DMS/DMM, batch, undo, XMP `exif:GPS*`); *Location* filter. Room v7. | `R7_organizing.md` (implemented, tag `r7` = `fc63e97`) |
| **R8** | On-device test suite | Starts with two R7 fixes (crash on *Cull in Library*; GPS never read on import or for pre-R7 rows, plus an *Add location* card). A separate **RawCull QA** app (`qa` build type, `.qa` suffix) so tests never touch the tester's app; a qa-only fixture DocumentsProvider plus one real `Download/RawCullQA` folder; samples read from `Download/Quick Share/Sample Import`, never changed; import, Library, deletion/trash (UiAutomator), layout checks at forced sizes, preview benchmark, migrations; `scripts/device-test.sh` writes `build/device-report.md`. Room v8 (data only). | `R8_device_tests.md` (**planned**) |
| R9 | Design pass (UI/UX) | Visual and interaction polish driven by Shehran's field use of R3–R7: hierarchy, density, typography, motion, empty states, copy, accessibility audit; built on the theme tokens R4.1 introduces. Input: the friction log Shehran keeps while using the app. | to plan after real-world use |

R5 is independent of R4 and can swap order if previews feel like the bigger pain. R7 can move earlier if a usability problem blocks real use; small blocking UX bugs are fixed in whichever round is next, not saved for R7.

## Before or alongside each round

The R0–R3 manual acceptance checklists (real card, real RAWs, real Lightroom) are still partly open. Later rounds reuse the scanner, XMP and import stacks, so running them early catches problems before more is built on top. Especially: O1 (does the tablet allow the card root?), O3 (does Lightroom keep the `rawcull` namespace?), real scan timing and real copy/verify speeds.
