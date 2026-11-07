# RawCull — Workflow Overhaul (import-first): Overview of Rounds R3–R6

Status: **PLANNED** · 2026-11-06 · Base: `main` @ `73eb70f` (R0–R2 merged)
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

## Rounds

| Round | Name | Ships | Plan |
|---|---|---|---|
| **R3** | Adaptive shell + Library + Import | New app shell (Import / Library / Settings) that adapts to every window size and orientation; Locations; whole-library catalog with folder tree and *Latest Import*; the three-step Import wizard with a verified, resumable background copy, duplicate index and delete-from-card; a full Settings screen. Retires the Welcome screen, recent sources and the export dialog. | `R3_shell_library_import.md` (**ready**) |
| R4 | Culling UX + reject deletion | Full Lightroom shortcut set; auto-advance; undo/redo; filter bar (flag, min rating, colour, tag with counts, search, sort); touch gestures (swipe up = pick, down = reject, haptics); long-press multi-select; **Delete rejects** via MediaStore trash (Android 11+, media on shared storage) else permanent with warning, sidecars included; sticky folder headers (O8); enrichment order follows the active sort (O9). | to plan after R3 lands |
| R5 | Preview speed & quality | Embedded large preview via TIFF IFD / CR3 / RAF parsing with RapidReady's 256 KB → 2 MB → end-of-file tiers; one file-descriptor open; persistent thumbnail cache index; prefetch (grid ±3 rows, loupe +3/+5 ahead, 1 behind, 100 ms debounce); true 1:1 region decode. Covers O10. Used by the library and the import preview. | to plan |
| R6 | Extras | Full window + slideshow (immersive, 2/3/5/10 s, loop, crossfade, culling keys); focus peaking (AGSL `RuntimeShader` on API 33+, CPU fallback below); histogram modes (overlay/full, log/linear); collections (virtual albums, manual order, ordered export); GPS editing (paste a Maps link / DMS / DD, batch apply, written to XMP `exif:GPS*`). | to plan |

R5 is independent of R4 and can swap order if previews feel like the bigger pain after R3.

## Before or alongside R3

The R0–R2 manual acceptance checklists (real card, real RAWs, real Lightroom) are still open. R3 reuses the scanner and XMP stack, so running them early catches problems before more is built on top. Especially: O1 (does the tablet allow the card root?), O3 (does Lightroom keep the `rawcull` namespace?) and the real scan timing.
