# Device tests (R8)

An on-device suite that drives the real app on a tablet. It installs a separate **RawCull QA**
app (`dev.shehran.rawcull.qa`); your own RawCull, its library, ratings, settings and folder grants
are never touched, and neither is `Sample Import`.

## Run it (Git Bash, in the RawCull folder, tablet on USB with USB debugging on)
```bash
bash scripts/device-test.sh smoke      # about 2 minutes
bash scripts/device-test.sh full       # everything
bash scripts/device-test.sh Deletion   # one test file, matched by name
```
Unlock the tablet first and keep it awake. MIUI asks on the tablet before each USB install: tap
*Install* (or turn on Developer options → *Install via USB*). The result is one line on the
console and **`build/device-report.md`**: device, duration, pass/fail per area, failures with their
stack, known issues, skipped tests, benchmark table (⚠ at more than 2× the last run) and the screenshot
paths (`build/device-screens/`). `SAMPLES="/sdcard/…"` overrides the samples folder.

First run only: the runner grants `Download/RawCullQA` through the system picker by itself. If it
can't, open *QA folder grant* in RawCull QA and pick that folder; tests that need it skip until then.

## What it covers
| Area | Tests |
|---|---|
| Import | preview counts and pairing, path templates and `Undated/`, Full verify, duplicates, never overwriting, delete-from-card, collections, *Cull in Library* / *Open collection* landing (the R8.1 crash), cancel and resume, a real-storage smoke import |
| Library | culling keys, undo/redo, auto-advance, batch edits, filters and counts, sidecar contents, Locations, collections and export, GPS, viewing toggles |
| Deletion | MediaStore trash on real storage through the system dialog: Allow, Cancel, Restore; a group's files go together; sidecars are deleted for good; deleted rejects stay in `imported_files` |
| Layout | Library grid, loupe, compare, full window, Settings and Import Preview at 360×640, 640×360, 840×600 and 1280×800 dp (24 screenshots); nothing clipped; overlaps and touch targets under 48 dp are reported as known issues for R9 |
| Benchmark | `PreviewBenchmark` on the samples, p50/p90 per stage, beside the last run |
| Migrations | Room v5 → v8 with data on the device; every DataStore key from R3–R7 still reads |

`scripts/update-check.sh <old-qa.apk>` installs an older qa build, seeds a library, installs the
new one over it and checks that rows, ratings and settings survived. Each run archives its qa APK
in `dist/qa/` (git-ignored); the first real use is R9 against `rawcull-qa-0.10.0.apk`.

## Stays manual
Inserting and removing a card and the USB reader, transfer speed, picking a real SD card, Lightroom
or darktable reading the sidecars, the Maps app, haptics and feel, and installing a new build over
your own RawCull.
