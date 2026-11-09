# RawCull

[![CI](https://github.com/shehransyed/RawCull/actions/workflows/ci.yml/badge.svg)](https://github.com/shehransyed/RawCull/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/shehransyed/RawCull?include_prereleases&sort=semver)](https://github.com/shehransyed/RawCull/releases)
[![License: GPL v3](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)

**Photo culling and import for Android tablets and phones.** Copy a shoot off your camera's SD card,
check that every file arrived intact, then pick, reject, rate and label it fast. Ratings and labels
go into standard XMP sidecars that Lightroom Classic, darktable and RapidRAW can read.

> **Status: early (0.x).** RawCull works end to end and its author uses it on real shoots, but it is
> young software, tested mainly on one Android 16 tablet. Back up photos you can't afford to lose
> before you let any app delete from a card.

<!-- Screenshots: add real tablet screenshots to docs/screenshots/ and link them here, e.g.
<p>
  <img src="docs/screenshots/library-grid.png" width="49%" alt="Library grid with filters and the culling panel">
  <img src="docs/screenshots/loupe.png" width="49%" alt="Loupe view with histogram">
</p>
-->

## Features

**Import**
- Import from an SD card over USB OTG, or from any folder, into a named library folder (a *Location*).
- Folder structure from templates: date formats, a custom pattern (`{year}/{month}/{day}`), a project name, or flat.
- Every copy is verified, and a duplicate index skips files you've already imported.
- An import never overwrites a file. It can clear the card afterwards, but only after every file in a group has verified.
- Imports run in the background and resume after an interruption.

**Cull**
- Grid, loupe, side-by-side compare, full window and slideshow views.
- Picks and rejects, 0–5 stars, colour labels, tags and GPS locations, with undo and redo.
- Lightroom-style keyboard shortcuts (a built-in help sheet lists them), auto-advance, and multi-select for batch edits.
- Filters by flag, rating, label, tag, location and filename, saved per Location.
- True 100 % zoom, focus peaking and an RGB + luma histogram.
- Delete rejects through the system trash (Android 11+). A photo's RAW, JPEG and sidecar always go together.

**Organize and export**
- Collections and collection sets, with a custom order you can drag into shape.
- Export a collection in order, with numbered filenames and optional time offsets.

**Works with your desktop editor**
- XMP sidecars are the source of truth. RawCull merges its changes into an existing `.xmp` and keeps everything it doesn't understand.
- Previews come from the JPEG the camera embedded in each RAW, so browsing is fast and never waits on a RAW decode.
- Supported formats: ARW, CR2, CR3, DNG, NEF, NRW, ORF, PEF, RAF, RW2, SRW, JPEG and HEIF. RAW + JPEG and RAW + HEIF pairs are grouped as one photo.

**Fits any screen**
- Phones and tablets, portrait and landscape, split screen and freely resized windows. Hardware keyboards are supported.

**Private by design**
- RawCull has no internet permission. Your photos and catalog never leave your device; see [PRIVACY.md](PRIVACY.md).

## Install

1. Open the [latest release](https://github.com/shehransyed/RawCull/releases/latest) on your Android device and download `RawCull-<version>.apk`.
2. Open the downloaded file. Android will ask you to allow installs from your browser or file manager the first time.
3. Updates install over the previous version and keep your library, ratings and settings.

Requires Android 7.0 (API 24) or newer. Google Play Protect may warn about an app it hasn't seen
before; that's expected for apps installed from outside the Play Store.

To check that the APK is the one published here, compare its SHA-256 checksum with the `.sha256`
file attached to the release.

## Build from source

You need a recent [Android Studio](https://developer.android.com/studio) (it includes the Android
SDK). Gradle downloads the JDK it needs on its own.

```bash
git clone https://github.com/shehransyed/RawCull.git
cd RawCull
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```

The APK is in `app/build/outputs/apk/debug/`. Or open the folder in Android Studio and press *Run*.

Run the unit tests and a build in one quiet step (Git Bash on Windows):

```bash
bash scripts/check.sh
```

The on-device test suite is described in [docs/testing/README.md](docs/testing/README.md), and
release builds in [docs/RELEASING.md](docs/RELEASING.md).

## Project layout

```
app/src/main/java/dev/shehran/rawcull/
  importer/   library/   preview/       Pure Kotlin logic (no Android), unit-tested with JUnit
  data/                                  Room catalog, SAF file access, import/export engines, XMP
  ui/                                    Jetpack Compose screens (Import, Library, Settings)
app/src/test/                            JVM unit tests (JUnit + Robolectric)
app/src/androidTest/                     On-device tests, run through scripts/device-test.sh
app/schemas/                             Exported Room schemas, used by migration tests
docs/                                    Design plans, testing and release guides
scripts/                                 Build, test and release helpers
```

[CLAUDE.md](CLAUDE.md) holds the detailed architecture map and the project's engineering rules.
RawCull is built in planned rounds with AI coding assistance; each round's plan and implementation
notes are in [docs/plans/](docs/plans/).

## Tech stack

Kotlin · Jetpack Compose · Material 3 (adaptive) · Navigation 3 · Room · DataStore · WorkManager ·
Storage Access Framework. No network, analytics or ads libraries.

## Roadmap

The next round is a design pass on the interface, shaped by a friction log from real-world use.
See [CHANGELOG.md](CHANGELOG.md) for what each version changed.

## Contributing

Bug reports and ideas are welcome: open an [issue](https://github.com/shehransyed/RawCull/issues).
Before writing code, please read [CONTRIBUTING.md](CONTRIBUTING.md). Security problems go through
[SECURITY.md](SECURITY.md), not public issues.

## Acknowledgements

The import and culling workflow was inspired by RapidReady, a desktop culling tool (MIT licence).

## License

Copyright © 2026 Shehran Syed.

RawCull is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License](LICENSE) as published by the Free Software Foundation, either version 3
of the License, or (at your option) any later version. It is distributed in the hope that it will
be useful, but WITHOUT ANY WARRANTY.
