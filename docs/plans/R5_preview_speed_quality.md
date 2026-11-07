# RawCull — Round 5: Preview speed & quality

Status: **PLANNED, decisions to review** · Planned 2026-11-07 · Base: local branch `feature/r4-culling-ux` @ `309f6d0` (R4 done, on-device testing in progress, R3 + R4 not merged into `main` yet)
Overview of the whole overhaul: `00_workflow_overhaul_overview.md`. R6 (extras) and R7 (design pass) are out of scope here. R4's D12 (50k-row libraries) is **shelved** by Shehran (2026-11-07) and isn't part of this round.

## Goal

Every photo shows up fast and sharp, from the camera's own embedded JPEG, the way RapidReady does it:

- **Grid, filmstrip and Import preview:** thumbnails come from a bounded, persistent disk cache. A cold thumbnail is read from a small slice of the RAW file, not the whole file.
- **Loupe:** the next photo is ready before you swipe to it (prefetch). The fit preview is the right size for the screen.
- **True 100 %:** `Z`, `Space` or a double-tap shows real pixels from the largest JPEG available (the embedded full-size preview or the companion JPEG), decoded region by region, so focus can actually be checked. When the best source is small (for example a 1616 px Sony preview), the UI says so instead of upscaling quietly.
- **Every RAW format RawCull lists:** ARW, NEF, NRW, CR2, CR3, RAF, ORF, RW2, PEF, SRW, DNG, plus JPEG and HEIF. Fixes O10.

### What's wrong today (from `util/RawPreviewLoader.kt`)

- Each request opens the file 2–4 times (EXIF thumbnail, decoder, byte scan, then the orientation read again).
- RAWs use `ExifInterface.thumbnailBytes` first. That's often the 160 px IFD1 thumbnail, which the loupe then stretches. The SOI scan only searches the first 64 KB.
- The loupe's "1:1" is `ONE_TO_ONE_SCALE = 2.5f` applied to a 2560 × 1600 bitmap, so it's an upscale.
- The disk cache (`cacheDir/rawcull_preview_cache`) has no limit and no eviction, and it stores 2560 px loupe previews as re-encoded JPEGs.
- RAM: one `LruCache` (1/8 of the heap) holds both thumbnails and 2560 px previews as heap bitmaps.
- Nothing is prefetched. Every composable runs its own `LaunchedEffect` load.
- With a RAW+JPEG pair the companion JPEG always wins, so every grid tile fully decodes a 6000 px JPEG.

---

## Decisions baked into this plan (Shehran: change any of these before handing off)

| # | Decision | Default chosen | Why |
|---|---|---|---|
| D1 | Version and schema | `versionCode = 7`, `versionName = "0.7.0"` in R5.0. **The catalog stays at Room v6**: no entity changes this round. The preview index is a separate cache database (D6). | Every round is an update. Leaving the catalog alone keeps the migration risk at zero. |
| D2 | What pixels are shown | **Only JPEGs the camera embedded** (or the companion JPEG), never a RAW sensor decode, as in RapidReady. One exception, as a last resort: a DNG with no usable preview (some phone DNGs) goes through `ImageDecoder` at reduced size, labelled *Decoded RAW (slow)*. A LibRaw/NDK decoder is out of scope. | It's what makes culling fast, and it's what the camera showed on its own screen. |
| D3 | Which embedded JPEG | All candidates from the primary file **and** the companion JPEG go into one pool. **Thumbnail / fit:** the candidate with the fewest bytes to read whose long edge is ≥ the requested size; if none is big enough, the largest. **100 %:** the candidate with the most pixels. Ties go to the primary. The bitstream is validated (SOI, SOF dimensions, EOI); a broken candidate falls through to the next one. | This replaces "companion always wins", which fully decoded a 6000 px JPEG for every grid tile. A pure `PreviewChooser` makes the rule testable. |
| D4 | Image-loading library | **No Coil or Glide.** A small purpose-built pipeline in `preview/` (pure parsing and choice) and `data/preview/` (I/O, caches, scheduling). | The hard parts are format-specific byte ranges read through SAF descriptors, region decoding, and prefetch that follows the Library's visible order. A library would only supply the RAM cache and request lifecycle; its disk cache stores fetched bytes, not resized thumbnails. One less dependency for the update rule. |
| D5 | Reading the file | **One `ParcelFileDescriptor` per request**, with positional reads through `FileChannel`. Reading starts with a **256 KB header slice**. Offsets beyond it are read where they point, in 64 KB-aligned blocks with a small block cache, so the IFD walk doesn't make dozens of tiny reads. If the descriptor isn't seekable (a pipe from a cloud provider), the fallback reads forward in RapidReady's tiers: **256 KB → 2 MB → whole file** (capped at 64 MB, then *No preview*). | RapidReady's tiers, adapted. On Android most SAF descriptors are seekable, so jumping straight to the preview is cheaper than over-reading. |
| D6 | Disk cache | **Thumbnails only:** 512 px long edge, JPEG quality 80, upright (D8), one file per thumbnail under `cacheDir/previews/v1/<2 hex>/<hash>.jpg`. Index in a **separate Room database `preview_cache.db`**: key, file, bytes, size, last access, plus the **extraction result** (each candidate's offset, length and dimensions, and the orientation), so the next loupe open is a single positional read. Key = document URI + size + mtime (+ the companion's size + mtime). **Limit: 1 GB by default** (Settings: 256 MB, 512 MB, 1 GB, 2 GB, 4 GB), LRU eviction in the background. Loupe-size previews aren't stored on disk (D7). | Bounded, survives restarts, and Android can clear it under storage pressure without losing anything. `preview_cache.db` is a cache, so **it may use a destructive migration**: the one stated exception to the "no destructive fallback" rule, recorded in `CLAUDE.md`. RapidReady's 64 MB pack files were considered and rejected: eviction would need compaction, and one file per thumbnail is simpler on Android. |
| D7 | RAM | Thumbnails: an LRU of decoded bitmaps bounded by bytes, sized from `ActivityManager.memoryClass`, with a second, larger LRU of the **compressed** 512 px JPEG bytes (RapidReady keeps JPEGs in RAM too), so the bitmap LRU can stay small. Loupe previews: count-bounded (current + the prefetch window), decoded as **`Bitmap.Config.HARDWARE` on API 26+**, so they don't sit on the Java heap. Histograms are computed from a software thumbnail. | A 2560 px preview is ~17 MB. Five of them on the heap next to the catalog is how an `OutOfMemoryError` happens (see R4's D12 notes). |
| D8 | Orientation | Read from the RAW's own metadata (TIFF IFD0, CR3 `CMT1`, the RAF JPEG's EXIF) by the parser, not by a separate `ExifInterface` open. **Thumbnails** are rotated once at decode and stored upright. **Loupe previews and 100 % tiles** stay in source orientation and are rotated at draw time, so the region decoder and the screen share one coordinate model. | Rotating a 17 MB bitmap costs a second 17 MB copy, while a draw-time rotation is free. Region maths stays in one place (`RegionMath`, pure, tested). |
| D9 | Prefetch | **Grid:** ±3 rows around the viewport, debounced 100 ms. **Loupe:** +5 ahead in the direction of travel and 1 behind at Medium and wider widths, +3 ahead at Compact, debounced 100 ms so held arrow keys skip frames. Order: visible > loupe current > prefetch > warm-up (D12). Stale prefetches are cancelled when the window moves. Identical in-flight requests are merged. I/O and decoding run in bounded pools on `Dispatchers.IO` and `Dispatchers.Default`. | RapidReady's rule ("+3 on phones"), using the window size class the app already has. |
| D10 | 100 % and zoom range | **100 % = one source pixel per physical screen pixel** of the 100 % source (D3). Zoom runs from fit to **200 %** (pinch); `Z`, `Space` and double-tap toggle fit ↔ 100 %, centred on the pointer or the tap. If the 100 % source is no larger than fit (small embedded preview), the toggle goes to 2× fit, and the zoom pill shows the real percentage plus *Preview only 1616 × 1080*. The inspector shows the source: *Embedded JPEG 6000 × 4000*, *Companion JPEG*, *Decoded RAW*. | Honest focus checking. With Sony-style small previews, it tells you why RAW+JPEG helps. |
| D11 | Region decode | `BitmapRegionDecoder` over the 100 % source: the byte range of the embedded JPEG (read once per photo and kept while it's current) or the companion JPEG's descriptor. When the displayed scale exceeds the base preview's resolution, the visible rectangle **+ 25 % margin** is decoded at the matching sample size, **120 ms after the gesture settles**. The old tile stays on screen until the new one is ready, and the base preview is always drawn underneath. One tile at most, about 1.5× the viewport. **Compare uses the same `LoupeImage` component**, one decoder per pane; zoom stays per pane (synced zoom is backlog). R4's swipe gestures stay fit-zoom only. | Full-size decoding of a 24–45 MP JPEG doesn't fit in memory, and tiling the whole image is more machinery than one viewport tile needs. |
| D12 | Background warm-up | Builds disk thumbnails ahead of time. It **starts on its own for Latest Import** when an import finishes, and on demand from Settings → *Build previews for this Location*, with progress, ETA, pause, resume and cancel. It **yields** whenever the UI is waiting for an image, **pauses while an import is copying**, runs in-process (not WorkManager), and skips what's already cached, so a restart continues where it stopped. | RapidReady's warm-up. Shooting, importing, then opening *Latest Import* is the common path, so that's where it pays off first. |
| D13 | Targets | **Measured and reported, not gates.** The only gates are no `OutOfMemoryError`, no ANR, and no change to culling behaviour. Targets on the tablet with real files on internal storage: cached thumbnail decode p90 ≤ 16 ms; cold thumbnail from a RAW p50 ≤ 80 ms; next loupe photo with prefetch shown in ≤ 50 ms; cold loupe fit preview p50 ≤ 250 ms; 100 % tile ≤ 300 ms after the gesture settles. A debug benchmark (R5.9) prints these per stage, so Shehran can paste the numbers back. | Phone and tablet speeds differ too much to hard-code a gate. R4 showed that measuring first is worth it. |
| D14 | Test data | **Synthetic fixtures** built in plain JUnit (TIFF/IFD, ISO-BMFF for CR3, RAF header, JPEG with APP1 and MPF, real JPEG bytes from `javax.imageio`), covering every parser path and every malformed case. **Real samples** live outside the repo in `C:\Users\shehr\RawCullSamples\` (one or two RAW and RAW+JPEG files per camera Shehran owns) and run in an opt-in test (`-PrawSamples=<dir>`) that reports what it finds per file. CC0 samples from raw.pixls.us fill in the formats Shehran doesn't shoot, if Claude Code can download them. **No RAW files are committed.** | Real RAWs are 20–80 MB each. The parsers need to be proven on real files, and the repo should stay small. |
| D15 | Old cache | At the first start of 0.7.0, `cacheDir/rawcull_preview_cache` is deleted, once (DataStore flag `legacy_preview_cache_cleared`). | Unbounded, and only reachable through the old loader. It's a cache, so nothing user-visible is lost. |
| D16 | Scope guard | R5 adds **no** new Room catalog observers and doesn't touch filtering, culling or XMP. `RawPreviewLoader` is deleted at the end: all image loading goes through `PreviewRepository`. | Keeps D12 (shelved) from getting worse, and keeps the round reviewable. |

### Embedded-preview map (what the parsers look for)

The RapidReady source at `C:\Users\shehr\Desktop\rapidready` (v0.6.1, commit `0eefa9e`) is the reference implementation: read its header-slice, CR3 and RAF code before writing these parsers.

| Format | Container | Where the JPEGs are | Orientation |
|---|---|---|---|
| ARW, NEF, NRW, CR2, DNG, PEF, SRW | TIFF (`II*` / `MM*`) | IFD0 → IFD1 chain and `SubIFDs` (0x14A): `JPEGInterchangeFormat` / `…Length` (0x201/0x202), or a single strip (`StripOffsets` / `StripByteCounts`) with `Compression` 6 or 7. CR2: IFD0's strip is a full-size JPEG. NEF: the largest is in a SubIFD. DNG: previews are `NewSubFileType` = 1 SubIFDs; also IFD0 at the end of the file (Capture One). | IFD0 0x112 |
| ORF | TIFF variant (`IIRO` / `MMOR`) | IFD1 thumbnail; the larger preview is in the Olympus MakerNote (`PreviewImageStart` / `Length`): best effort | IFD0 0x112 |
| RW2 | TIFF variant (`IIU`) | `JpgFromRaw` (0x2E) in IFD0: a full embedded JPEG with its own EXIF | IFD0 0x112 |
| CR3 | ISO-BMFF (`ftyp crx `) | `moov` → `uuid 85c0b687-820f-11e0-8111-f4ce462b6a48` → `THMB` (160 × 120); `uuid eaf42b5e-1c98-4b88-b9fb-b7dc406e4d16` → `PRVW` (~1620 × 1080); the full-size JPEG is track 1 in `mdat` (offset from `co64`/`stco`, size from `stsz`) | `CMT1` (TIFF IFD0) |
| RAF | Fuji header (`FUJIFILMCCD-RAW`) | JPEG offset at byte 84, length at 88 (big-endian); its MPF may hold more | The embedded JPEG's EXIF |
| JPEG | JFIF / EXIF | The image itself (dimensions from SOF); APP1 EXIF thumbnail; **APP2 MPF secondary images** (Sony, Canon, Nikon and Fuji usually embed a ~1600–1920 px preview) | APP1 0x112 |
| HEIF | ISO-BMFF | `ImageDecoder` with a target size (API 28+); below 28, the EXIF thumbnail or *No preview* | Handled by `ImageDecoder` |

---

## Before handing off (Shehran)

1. Review D1–D16, especially D3 (the source choice), D6 (cache size and location), D10 (100 % behaviour) and D12 (automatic warm-up after an import).
2. Make `C:\Users\shehr\RawCullSamples\` and put in **one or two files per camera you shoot**, RAW-only and RAW+JPEG. Include a portrait-orientation shot and, if you have one, a B/W picture-style shot. Name the folder per camera (`a7iv/`, `x-t5/` …). These never go into git.
3. Hand R5 to Claude Code with the prompt below. It can run while R4 testing continues. R4 fixes go on `feature/r4-culling-ux`, and Claude Code merges them in.

---

## Handoff prompt (paste into Claude Code)

> You are working in the RawCull Android repo (Kotlin, Jetpack Compose, Material 3, Room, DataStore, SAF). Implement **Round 5** exactly as specified in `docs/plans/R5_preview_speed_quality.md`. R3 and R4 aren't merged into `main`: create a local branch `feature/r5-previews` **from `feature/r4-culling-ux`** (tip `309f6d0` or later). If `feature/r4-culling-ux` gets new commits while you work (fixes from Shehran's device testing), merge them into your branch. **Work locally only: don't push, don't create a pull request, and don't merge into `main` or the R3/R4 branches.** Make **one commit per numbered task** (R5.0 … R5.10) with a conventional-commit message. After every commit run `gradlew.bat testDebugUnitTest assembleDebug`; the app must build and tests must pass at every commit, and every commit's APK must install over the R4 build as an update (R5.0 bumps the version first). Before writing the parsers, read the preview pipeline in RapidReady's source at `C:\Users\shehr\Desktop\rapidready` (Rust crate: header slice, CR3 and RAF parsers, `chunk_cache.rs`) and port its logic; if you can't read that folder, say so and work from the plan's format table. Real RAW samples are in `C:\Users\shehr\RawCullSamples\` (never commit them); run the opt-in sample test against them. Don't implement anything from Rounds 6–7, and don't work on R4's D12 (shelved). Where this plan and the code disagree, follow the code's established patterns and note the deviation. When finished, append "Implementation notes" to the plan (deviations, open questions, the per-format sample results, the benchmark numbers against D13, memory use in the loupe, what was verified on which emulator sizes), and update `CLAUDE.md` as R5.10 says.

---

## Tasks

### R5.0 Version, legacy cache, skeleton
- `versionCode = 7`, `versionName = "0.7.0"` **in this first commit**. Catalog Room version unchanged (v6).
- D15: delete `cacheDir/rawcull_preview_cache` once at startup (in `StartupCleanup`), guarded by the DataStore flag `legacy_preview_cache_cleared`.
- Empty packages `preview/` (Android-free) and `data/preview/`, and a `PreviewRepository` interface that `RawPreviewLoader` implements for now, so later tasks can switch callers one at a time.

### R5.1 Pure preview extraction (`preview/`, no Android types)
- `ByteSource` (`size: Long?`, `read(offset, length): ByteArray`, `seekable`) and `BlockCachedSource` (64 KB-aligned blocks, small LRU). `StreamTierSource` implements D5's non-seekable tiers over an `InputStream` factory.
- `JpegProbe`: SOI check, SOF dimensions (baseline, progressive, extended), EOI within the range, APP1 EXIF orientation and thumbnail, APP2 MPF entries.
- Scanners returning `PreviewScan(candidates: List<PreviewCandidate(offset, length, width, height, origin)>, orientation)`: `TiffPreviewScanner` (II/MM, ORF and RW2 variants, IFD0/IFD1 chain, SubIFDs, ExifIFD skipped, JPEG and single-strip candidates, end-of-file IFD0), `Cr3PreviewScanner` (box walk with 64-bit sizes, THMB, PRVW, track-1 full-size JPEG, `CMT1` orientation), `RafPreviewScanner`, `JpegPreviewScanner` (the image itself + EXIF thumbnail + MPF images). `PreviewScanner.forFormat(PhotoFormat)` routes them; an unknown RAW (`RAW_OTHER`) tries TIFF, then a bounded SOI search.
- Candidate dimensions come from the JPEG's own SOF, never trusted from tags alone. A candidate whose range runs past the file end, or whose SOI/SOF is invalid, is dropped.
- Every scanner is defensive: an IFD loop guard, a maximum entry count, offsets checked against the file size, and no allocation larger than the candidate length (capped at 64 MB). A malformed file returns an empty scan, never throws out of the scanner.
- `PreviewChooser.pick(pool, request)` implements D3 (`Thumbnail(sizePx)`, `Fit(widthPx, heightPx)`, `FullResolution`).
- `RegionMath`: viewport ↔ source rectangles with the eight EXIF orientations, sample-size choice and the 25 % margin (D8, D11).

### R5.2 Fixtures and parser tests
- Test-only builders: `TiffBuilder` (both byte orders, IFD chains, SubIFDs, strips, end-of-file IFD), `BmffBuilder` (CR3 boxes incl. the two uuids, `co64`), `RafBuilder`, `JpegBuilder` (real JPEG bytes from `javax.imageio`, plus injected APP1/APP2 segments).
- Tests: each format's candidates and orientation; choose-by-size for thumbnail, fit and full; truncated files; offsets past the end; IFD cycles; zero-length and garbage candidates; big- and little-endian; the stream-tier fallback finds a preview that sits past 2 MB; the block cache coalesces reads (count them); `RegionMath` for all eight orientations, at the edges, and at 100 % / 200 %.
- **Opt-in real-sample test** (`-PrawSamples=<dir>`, skipped when not set): for every file it records format, candidates (dimensions, bytes, offset), the chosen thumbnail/fit/full candidate, orientation and parse time, and asserts that each RAW yields at least one valid JPEG ≥ 1000 px on its long edge. Print a table; copy it into the implementation notes.

### R5.3 Disk cache and index (`data/preview/`)
- `preview_cache.db` (Room, separate from the catalog, `fallbackToDestructiveMigration` allowed here per D6): `thumbnails(key PK, path, bytes, width, height, lastAccess)` and `scans(fileKey PK, candidatesJson, orientation, scannedAt)`.
- `ThumbnailStore`: write (temp file + rename inside `cacheDir`, so a crash can't leave half a JPEG), read, touch, evict to the limit (LRU by `lastAccess`, in batches, on `Dispatchers.IO`), clear, and report the size used. It reconciles itself at startup when the OS has deleted files from `cacheDir` behind it.
- `PreviewKeys`: the D6 key, from a `PhotoAsset` (library) and from an `ImportSession` entry (card).
- Robolectric tests: eviction order, limit changes, files missing behind the index, concurrent writes of the same key.

### R5.4 `PreviewRepository` pipeline
- `thumbnail(source, sizePx)`, `fitPreview(source, w, h)`, `regionSource(source)`, `prefetch(requests, lane)`, `cancel(lane)`. A source is a primary document URI + format + optional companion URI + key.
- One descriptor per file per request (D5); the cached scan skips parsing the next time; decode with `BitmapFactory` from the candidate's bytes using `inSampleSize` (then an exact downscale only for thumbnails); `HARDWARE` config for previews on API 26+ (D7); thumbnails rotated upright (D8) and written to `ThumbnailStore`.
- In-flight requests for the same key are merged; four priority lanes (D9); bounded parallelism; everything cancellable.
- Fallbacks in order: next candidate → companion → `ImageDecoder` (DNG and HEIF, API 28+) → a *No preview* state with the reason. Each result carries its `PreviewSourceInfo` (kind, dimensions) for the UI (D10).
- `RawCull.Preview` logs per request: key, lane, cache hit level (RAM / disk / file), and ms for open, scan, read, decode and total.

### R5.5 Thumbnails everywhere
- `PhotoThumbnail` composable (`photo`, tile size in px, bucketed to 128 / 256 / 512) used by `GridViewGallery`, `FilmstripBar` and `ImportPreviewScreen`. It requests on first composition, cancels when it leaves, and shows the existing placeholder while loading.
- The histogram (`LibraryViewModel.refreshHistogram`) reads a software 320 px thumbnail through the repository.
- After this task nothing in the grid, filmstrip or Import preview calls `RawPreviewLoader`.

### R5.6 Prefetch
- Grid: from `LazyGridState`'s visible items, ±3 rows (from the current column count), debounced 100 ms, in the visible order the `LibraryFilter` already produces; cancel what scrolled out of range.
- Loupe: +5 / +3 ahead in the last navigation direction (D9) and 1 behind, debounced 100 ms; the direction comes from the last move (keys, swipe, filmstrip tap).
- Import preview grid: the same grid rule.
- A test with a fake repository: the requested windows for scroll and navigation sequences, debounce, cancellation and the lane order.

### R5.7 Loupe: right-sized fit, true 100 %, region decode
- `LoupeImage`, shared by `ZoomableLoupeView` and `CompareLoupeView`: the fit preview at the view's real pixel size (not a fixed 2560 × 1600), drawn with the D8 rotation, plus the D11 region tile above it.
- Zoom model per D10: `ONE_TO_ONE_SCALE` goes; the scale for 100 % is computed from the 100 % source and the view size; fit → 200 % by pinch; `Z` / `Space` / double-tap toggle fit ↔ 100 % at the pointer or tap; pan clamped to the image.
- The zoom pill shows the real percentage, plus *Preview only W × H* when the source is no larger than fit. The inspector's file section shows the preview source (D10).
- R4's behaviour is unchanged: swipe-to-flag only at fit zoom, haptics, auto-advance, undo controls, keyboard. Re-run R4's loupe tests.
- Memory: one region decoder (and the bytes behind it) for the current photo only, released on navigation; record the loupe's heap and graphics memory in the notes (`dumpsys meminfo`) while flicking through 50 photos and zooming.

### R5.8 Background warm-up
- `PreviewWarmup` (`data/preview/`): a queue over a list of sources, with progress, ETA, pause, resume and cancel, exposed as a `StateFlow`. It yields to the visible lane, pauses while `ImportWorker` is copying, and skips what's cached (D12).
- Starts on its own for a finished import's new files (*Latest Import*). Settings → *Build previews for this Location* runs it over the whole Location.
- A small progress line in the Library top bar while it runs, with Pause and Cancel.

### R5.9 Settings and diagnostics
- Settings → *Previews*: cache used / limit (DataStore key `preview_cache_limit_mb`, default 1024), *Clear preview cache* (with confirmation), *Build previews for this Location* with progress.
- Debug builds, Settings → *Developer*: *Show preview timing* (an overlay in the loupe: source, dimensions, hit level, ms), and *Benchmark previews in this folder*: cold (cache cleared for those files) and warm runs over the current folder, logging a table of p50/p90 per stage and per format under `RawCull.Preview`, plus a share button that copies the table.

### R5.10 Tests, cleanup, docs, verification
- Delete `RawPreviewLoader`; `PreviewRepository` is the only image path (D16).
- Compose tests (Robolectric, w360dp, w640dp-h360dp, w1280dp): the zoom pill text at fit and 100 %, *Preview only* for a small source, the Settings cache section.
- Verify on emulators at 360×640, 640×360, 600×960, 840×1280 and 1280×800: grid, filmstrip, loupe at fit and 100 % (portrait and landscape photos), compare, Import preview.
- If an API 26 (or lower) emulator image can be installed, check that software bitmaps and the no-`ImageDecoder` path work there (O11). Say if it couldn't be done.
- **Update check:** install the R4 build (`309f6d0` or the latest R4 tip) on an emulator with a library, ratings and settings; install R5 over it with `adb install -r`; everything is still there, and the old cache folder is gone.
- `CLAUDE.md`: the `preview/` and `data/preview/` packages; "all image loading goes through `PreviewRepository`"; "embedded JPEGs only, no RAW decode except DNG as a last resort"; the `preview_cache.db` exception to the destructive-migration rule (it's a cache; the catalog rule is unchanged); "real RAW samples live outside the repo".
- Implementation notes appended to this plan.

---

## Acceptance checklist (Shehran, on the tablet; phone if handy)

0. **Update:** with the R4 build installed and your library set up, run R5 from Android Studio. No uninstall prompt; library, ratings and settings all still there; Settings → About shows 0.7.0.
1. **Every camera you own:** open a folder of its RAWs. Thumbnails appear and are the right way up (include a portrait shot); the loupe is sharp at fit.
2. **Speed:** scroll quickly through a big folder: tiles fill in without stalls, and a second visit is instant. Flick through 50 photos in the loupe: each one is sharp straight away. Hold `→`: it skips frames smoothly and settles sharp.
3. **100 %:** press `Z` (or double-tap) on a RAW-only file and on a RAW+JPEG pair: the pill shows 100 %, focus detail is real, not mushy; pan around and the detail follows within a moment. If your camera's embedded preview is small, the pill says *Preview only …*.
4. **Compare:** both panes load, and each zooms to 100 % on its own.
5. **Import:** the card's preview grid fills in quickly. After the import, *Latest Import* thumbnails are already there (or fill in fast while warm-up runs), and warm-up stays out of the way while you scroll.
6. **Settings:** the cache size goes up while you browse; *Clear preview cache* empties it; set 256 MB, browse a big folder, and the size stays under the limit.
7. **Debug:** run *Benchmark previews in this folder* on one folder per camera and paste the table into the next planning chat.
8. **Culling is unchanged:** R4's checklist items 1–3 still pass (keys, swipes, auto-advance).
9. Rotate and resize in the loupe at 100 %: nothing crashes; the zoom resets or holds sensibly.
