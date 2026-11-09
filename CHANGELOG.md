# Changelog

All notable changes to RawCull are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the version starts with `0.`,
any release may change behaviour. Every release still installs over the previous one and keeps your
library, ratings and settings.

## [Unreleased]

## [0.10.0] - 2026-11-08

First public release.

### Import
- Import from an SD card (USB OTG) or any folder into named library Locations.
- Path templates (date formats, custom pattern, project folder, flat) with a live preview of the destination.
- Copy verification, a duplicate index, and never overwriting a file.
- Optional delete-from-card, only after a whole group has verified.
- Background imports that can be cancelled, resumed and retried.
- Add imported photos straight to a collection. GPS locations are read on import.

### Library and culling
- Folder tree, *All photos* and *Latest import* scopes across several Locations.
- Grid, loupe, compare, full window and slideshow views; true 100 % zoom.
- Picks, rejects, star ratings, colour labels, tags and GPS locations, with undo and redo.
- Lightroom-style keyboard shortcuts with a help sheet, auto-advance, swipe gestures and multi-select batch edits.
- Filters and sorting saved per Location, with live counts.
- Focus peaking and an RGB + luma histogram (linear or log).
- Delete rejects through the system trash on Android 11+, keeping a photo's files together.

### Organizing and export
- Collections and collection sets with a custom order.
- Ordered export of a collection with numbered filenames and optional time offsets.

### Files and formats
- XMP sidecars compatible with Lightroom Classic, darktable and RapidRAW; unknown content is always kept.
- Fast previews from the JPEGs embedded in ARW, CR2, CR3, DNG, NEF, NRW, ORF, PEF, RAF, RW2 and SRW files.
- RAW + JPEG and RAW + HEIF pairs are handled as one photo.

### Layout
- Adaptive layout for phones, tablets, portrait, landscape and resizable windows.

### Development history
0.10.0 is the result of nine planned rounds, each with its plan and implementation notes in
[docs/plans/](docs/plans/):

| Round | Version | Focus |
|---|---|---|
| R0–R1 | 0.4.0 | Project hygiene and real SD card support |
| R2 | 0.4.0 | Trustworthy XMP sidecars |
| R3 | 0.5.0 | App shell, Library and Import |
| R4 | 0.6.0 | Culling UX and reject deletion |
| R5 | 0.7.0 | Preview speed and quality |
| R6 | 0.8.0 | Viewing: full window, slideshow, focus peaking, histogram |
| R7 | 0.9.0 | Organizing: collections, export, GPS |
| R8 | 0.10.0 | On-device test suite and fixes |

[Unreleased]: https://github.com/shehransyed/RawCull/compare/v0.10.0...HEAD
[0.10.0]: https://github.com/shehransyed/RawCull/releases/tag/v0.10.0
