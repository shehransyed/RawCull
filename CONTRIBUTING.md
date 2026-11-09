# Contributing to RawCull

Thanks for your interest. RawCull is a small project with one maintainer, so a little coordination
up front saves everyone time.

## Reporting bugs and asking for features

- Search the [existing issues](https://github.com/shehransyed/RawCull/issues) first.
- Use the **Bug report** or **Feature request** form when you open an issue. For bugs, the app
  version (Settings), your device and Android version, your camera model and RAW format, and the
  steps that reproduce it are what matter most.
- Never attach photos you don't want to be public. If a bug only happens with one RAW file, say
  so, and we'll find a private way to share it.

## Before you write code

Please **open an issue, or comment on an existing one, before starting a pull request** for
anything bigger than a typo or a one-line fix. RawCull is developed in planned rounds (see
[docs/plans/](docs/plans/)), and a quick check avoids work that clashes with the next round.

## Development setup

1. Install a recent [Android Studio](https://developer.android.com/studio).
2. Fork the repository and clone your fork.
3. Build and test:
   ```bash
   bash scripts/check.sh      # unit tests + debug build; prints one line when everything passes
   ```
   On Windows, run this from Git Bash. `./gradlew testDebugUnitTest assembleDebug` does the same
   thing with full output.

The on-device suite (`scripts/device-test.sh`) needs a phone or tablet over USB; see
[docs/testing/README.md](docs/testing/README.md). You don't have to run it for a pull request.

## Engineering rules

[CLAUDE.md](CLAUDE.md) is the architecture map and rulebook. It's written for AI coding assistants,
but it applies to everyone. The rules that matter most:

- **XMP sidecars are the source of truth; the Room database is a cache.** Never drop content you
  don't understand from an existing `.xmp`. Always merge through `XmpDocument`.
- **Never lose a photo.** An import never overwrites and never deletes what it can't prove it made.
  A photo's files (RAW, JPEG, sidecar) are deleted together or not at all.
- **Every release is an in-place update.** Room migrations only go forward and come with a
  migration test. Changing an entity bumps the database version and commits the exported schema in
  `app/schemas/`. DataStore keys are never renamed or dropped without a migration.
- **Storage Access Framework only**, no broad storage permission.
- **All image loading goes through `PreviewRepository`.** No I/O on the main thread; long work is cancellable.
- **Every screen works at every window size**, in portrait and landscape, with 48 dp touch targets.
- **Theme tokens only**: no hex colours in UI code.
- Pure logic goes in Android-free classes with plain JUnit tests.

## Pull requests

- One topic per pull request. Keep commits focused: each commit should build and pass the tests.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/):
  `feat(import): …`, `fix(preview): …`, `docs: …`, `test(qa): …`, `chore: …`.
- Add or update tests for the behaviour you change.
- Add a line under **Unreleased** in [CHANGELOG.md](CHANGELOG.md) for anything a user would notice.
- CI runs the unit tests and a build on every pull request; it has to be green before a merge.

## License

RawCull is licensed under the [GNU GPL v3.0 or later](LICENSE). By submitting a contribution, you
agree that it is licensed under the same terms.

## Code of conduct

Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
