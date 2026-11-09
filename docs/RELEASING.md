# Releasing RawCull

How to build a signed APK and publish it as a GitHub release. Commands run in Git Bash from the
repository folder.

## One-time setup: the signing key

Android only installs an update when it carries the same signature as the installed app, so the
release key is the app's permanent identity.

```bash
bash scripts/new-release-key.sh
```

This asks for a password and your name, then creates:

- `~/keys/rawcull-release.jks`, the key itself, **outside the repository**;
- `keystore.properties` in the repository folder, which tells Gradle where the key is. It's
  git-ignored, so it never gets committed. `keystore.properties.example` shows the format.

**Back up the `.jks` file and its password right away**: put the password in a password manager,
and keep a copy of the file somewhere other than this PC (an encrypted cloud folder or a USB stick).
If you lose either one, you can never publish an update that installs over earlier releases, and
every user would have to uninstall and lose their catalog.

Release builds are signed with this key; debug and qa builds keep the debug key. They share an
application ID but have different signatures, so a debug build and a release build can't be
installed over each other on the same device.

CI or another machine can supply the key through environment variables instead of the file:
`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS` (default `upload`) and `KEY_PASSWORD`.

## Each release

1. **Version.** The round's first commit already bumped `versionCode` and `versionName` in
   `app/build.gradle.kts`. Versions follow [Semantic Versioning](https://semver.org): while the
   version starts with `0.`, bump the middle number for a round and the last number for a bug fix
   (`0.10.0` → `0.10.1`).
2. **Changelog.** In `CHANGELOG.md`, rename `## [Unreleased]` to `## [x.y.z] - YYYY-MM-DD`, add a
   new empty `## [Unreleased]` above it, and update the links at the bottom.
3. **Commit** that on `main`.
4. **Build:**
   ```bash
   bash scripts/release.sh
   ```
   It runs the unit tests, builds the release APK, checks it isn't debug-signed, and writes
   `dist/release/RawCull-x.y.z.apk` plus a `.sha256` checksum file. `dist/` is git-ignored: APKs
   belong on the release page, not in git.
5. **Push** `main` to GitHub.
6. **Publish** on GitHub: open **Releases → Draft a new release**, then
   - *Choose a tag*: type `vx.y.z` (for example `v0.10.0`) and choose *Create new tag on publish*,
     with `main` as the target;
   - *Release title*: `RawCull x.y.z`;
   - *Description*: paste that version's section from `CHANGELOG.md`;
   - attach `RawCull-x.y.z.apk` and `RawCull-x.y.z.apk.sha256`;
   - keep *Set as the latest release* ticked, then **Publish release**.

To try a release build on your own device first, use a device without the debug build (see the
signing note above).

## Words used here

- **Repository (repo):** the project folder and its full git history, on your PC and on GitHub.
- **Commit:** a saved snapshot of changes, with a message. **Push:** send your commits to GitHub.
- **Branch:** a line of commits. `main` is the one releases come from.
- **Tag:** a permanent name for one commit, such as `v0.10.0`.
- **Release:** a GitHub page tied to a tag, with notes and downloadable files (the APK).
- **CI (continuous integration):** GitHub Actions builds and tests every push and pull request
  (`.github/workflows/ci.yml`). The green or red mark beside a commit is its result.
- **Pull request (PR):** a proposed set of commits from a branch or fork, reviewed before merging.
- **Signing key / keystore:** the private file that proves an APK came from you.
- **Checksum (SHA-256):** a fingerprint of a file. A matching checksum means the download is the
  exact file that was published.
