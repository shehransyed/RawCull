# RawCull — Implementation Plan: Round 2 (Trustworthy XMP)

Status: **READY FOR CLAUDE CODE after R0–R1 is merged** · Planned 2026-11-06
Prerequisite: `R0-R1_hygiene_and_real_card.md` (uses `PhotoAsset`, `parentDocUri`, `AppContainer`, `SettingsRepository`, `ScanCoordinator`).

## Goal
Rating, flagging, labelling or tagging a photo must **never destroy or corrupt** an existing sidecar, must write what other apps actually read, must not stall on large SAF folders, and must report its real sync state in the UI.

## Decisions baked into this plan (Shehran: confirm or change)

| # | Decision | Default chosen |
|---|---|---|
| X1 | Target apps for interop. | **Lightroom Classic / Camera Raw / Bridge, darktable, RapidRAW.** Snapseed and Lightroom Mobile mostly ignore sidecars, so they're best-effort only. |
| X2 | How a **reject** is written. | `xmp:Rating="-1"` (the Bridge and darktable convention), controlled by setting `rejectAsNegativeRating`, default **on**. The star rating the user had is kept in `rawcull:Rating`, so un-rejecting restores it. |
| X3 | How a **pick** is written. | No cross-app standard exists (Lightroom Classic doesn't round-trip pick flags through XMP). Write `rawcull:Pick` (1 / 0 / -1) as the authoritative value and mirror it to `xmpDM:pick`. **Stop writing** `crs:Pick` (it pollutes Camera Raw's develop namespace) and remove it when updating files RawCull wrote earlier. Still *read* `crs:Pick` for legacy files. |
| X4 | Sidecar name for new files. | `<stem>.xmp` (Adobe style). If a darktable-style `<name>.<ext>.xmp` exists, update it too. |
| X5 | Unparseable existing sidecar. | **Never overwrite.** Mark the asset `ERROR` and show it in the UI. The user can choose "Replace sidecar" per photo, which first renames the old file to `<name>.xmp.bak`. |
| X6 | JPEG/HEIF-only assets. | Still write a `<stem>.xmp` sidecar (darktable and RapidRAW read it). Lightroom ignores sidecars for JPEGs; embedding XMP into the JPEG is out of scope and logged for later. |
| X7 | RawCull namespace URI. | `http://ns.rawcull.dev/xmp/1.0/`, prefix `rawcull`. |

---

## Handoff prompt (paste into Claude Code)

> Implement **Round 2** as specified in `docs/plans/R2_trustworthy_xmp.md` on branch `feature/r2-xmp`, one commit per numbered task (X2.1 … X2.7). After every commit run `./gradlew testDebugUnitTest assembleDebug`. Build the XMP model on namespace-aware DOM (`javax.xml.parsers.DocumentBuilderFactory` with `isNamespaceAware = true`, serialised with `javax.xml.transform`). Don't add third-party XMP libraries unless DOM proves unworkable; if it does, stop and explain why in the plan's Implementation notes instead of switching on your own. Write the test fixtures first (X2.1) and make them pass. Append "Implementation notes" to the plan when finished.

---

### X2.1 Test fixtures (write first)
Create `app/src/test/resources/xmp/`, with realistic synthetic files for each case (Shehran will add real ones later from his own apps):

| File | Contents |
|---|---|
| `lightroom_classic_develop.xmp` | Attribute-form `xmp:Rating`, `xmp:Label`, `xmp:MetadataDate`; ~30 `crs:*` develop settings, including element-form `crs:ToneCurvePV2012` (an `rdf:Seq`); `dc:subject` bag; `lr:hierarchicalSubject` bag; `xmpMM:History` seq; `exif:GPSLatitude`. |
| `darktable.xmp` | Element-form properties, `darktable:history` seq, `xmp:Rating="-1"` (rejected), `dc:subject`. |
| `bridge_reject.xmp` | `xmp:Rating="-1"`, label "Red". |
| `element_form.xmp` | `<xmp:Rating>4</xmp:Rating>` and `<xmp:Label>Green</xmp:Label>` as elements. |
| `multi_description.xmp` | Two `rdf:Description` blocks; the rating is in the second. |
| `rawcull_v0_legacy.xmp` | Exactly what the current `XmpSidecarGenerator` writes (with `crs:Pick`, empty `xmp:Label=""`). |
| `malformed.xmp` | Truncated XML. |
| `no_xpacket.xmp` | Valid `x:xmpmeta` without the `<?xpacket?>` wrapper. |

### X2.2 `XmpDocument`: merge-preserving model (pure JVM, no Android types)
`util/xmp/XmpDocument.kt`:
- `companion fun parse(bytes: ByteArray): Result<XmpDocument>` and `fun newEmpty(toolkit: String): XmpDocument`.
- Reads (look in **every** `rdf:Description`, attribute or element form):
  - `rating: Int?` (−1..5)
  - `label: String?`
  - `pick: Int?`, with precedence `rawcull:Pick` > `xmpDM:pick` > `crs:Pick`
  - `rawcullRating: Int?`
  - `subjects: List<String>` (**only** `dc:subject/rdf:Bag/rdf:li`)
- Writes go through `fun set(prop: QName, value: String?)`. It removes every existing occurrence of the property (attribute or element, in any Description) and then, if the value isn't null, sets it as an attribute on the **first** Description, declaring the namespace on that element if it's missing. `null` removes the property.
- `setSubjects(list)`: replaces only the `dc:subject` bag, creating it as an element on the first Description. An empty list removes `dc:subject`. `lr:hierarchicalSubject` is left untouched.
- `touchMetadataDate(now)`: sets `xmp:MetadataDate` to ISO-8601 with offset (Lightroom uses it to notice external changes).
- `toBytes()`: UTF-8. Keep the `<?xpacket begin=…?>`…`<?xpacket end="w"?>` processing instructions if they were present, and add them when creating a new document. No pretty-printing of existing content.
- Escaping is handled by DOM serialisation; never build XML by string concatenation again.
- Delete `XmpSidecarGenerator` and the regex parser in `XmpSidecarManager` once everything has moved over.

### X2.3 `XmpMapper`: asset state ↔ XMP fields (pure)
`fun apply(doc, state: CullState, prefs: XmpPrefs)` and `fun read(doc): ParsedCullState`.

| App state | Write |
|---|---|
| rating r, not rejected | `xmp:Rating=r` (0 writes "0"); `rawcull:Rating=r` |
| REJECT, `rejectAsNegativeRating` on | `xmp:Rating=-1`; `rawcull:Rating`=the star rating, kept |
| REJECT, setting off | `xmp:Rating=r`; reject only in `rawcull:Pick=-1` / `xmpDM:pick=-1` |
| pick state | `rawcull:Pick` and `xmpDM:pick` set to 1/0/-1; `crs:Pick` removed |
| colour label | `xmp:Label` = `Red`/`Yellow`/`Green`/`Blue`/`Purple`; NONE → removed |
| tags | `dc:subject` bag |
| always | `xmp:MetadataDate` = now |

Reading:
- `xmp:Rating = -1` → REJECT, with stars taken from `rawcull:Rating` (or 0).
- Otherwise pick state comes from the precedence chain.
- Label matching is case-insensitive. An unknown label string (e.g. Lightroom's custom label sets, "Second") maps to `ColorLabel.NONE` **but the raw string is preserved**: only write `xmp:Label` when the user actually changed the label in RawCull, so an untouched custom label survives.

### X2.4 Sidecar locator and SAF I/O
`data/xmp/SidecarStore` (Android):
- `resolve(asset)`: use `asset.xmpFileUri` if set; otherwise create `<stem>.xmp` with `DocumentsContract.createDocument(resolver, parentDocUri, "application/octet-stream", name)`. If the provider returns a different display name (e.g. it appended an extension), rename it with `DocumentsContract.renameDocument`, or log it as an error if that fails. Store the resulting URI back on the row. **No `findFile`.**
- `read(uri)`: bytes + `LAST_MODIFIED` (one query).
- `write(uri, bytes)`: build the complete byte array first, then `openOutputStream(uri, "wt")` (fall back to `"w"` if `"wt"` throws `IllegalArgumentException`). Afterwards, re-query and store `LAST_MODIFIED`.
- darktable-style sidecar (X4): if `asset.darktableXmpUri` exists (add this column), apply the same merge to it.
- Not writable (the tree isn't writable, or a write throws `SecurityException`) → status `READ_ONLY`.

### X2.5 Debounced write queue and sync status (Room version 3 → 4)
Entity changes:
- `XmpSyncStatus` becomes `SYNCED, PENDING, WRITING, ERROR, READ_ONLY`.
- Add `xmpLastSyncedMtime: Long?`, `xmpError: String?`, `darktableXmpUri: String?`.

`data/xmp/XmpWriteQueue` (singleton in `AppContainer`, runs on the app scope):
- The repository's cull ops (`setRating`, `setCullStatus`, `setColorLabel`, tags, all batch variants) update Room **in one transaction** with `xmpSyncStatus = PENDING`; batch variants use `UPDATE … WHERE id IN (:ids)`. Then they call `queue.enqueue(ids)`. They no longer write files themselves.
- The queue coalesces by id with a 400 ms quiet period per id. A single worker coroutine processes the ids with I/O concurrency 2. For each id: load the **latest** row → `SidecarStore.read` → `XmpDocument.parse` → `XmpMapper.apply` → `write` → update row `SYNCED` + mtime. Set status `WRITING` while working.
- **Conflict rule:** if the file's current `LAST_MODIFIED` > `xmpLastSyncedMtime` **and** the row is PENDING, the file was edited externally while we had local changes. Merge field by field: our pending fields win, all other content in the file is preserved (that's the point of merging), and log it at INFO.
- **Parse failure** → `ERROR` with message "Existing sidecar isn't valid XMP; not overwritten" (X5).
- **Flush:** register `ProcessLifecycleOwner` (add `androidx.lifecycle:lifecycle-process`); on `ON_STOP`, `flushNow()` ignores the debounce and runs under `NonCancellable`.
- **Startup recovery:** on app start, re-enqueue every row with status `PENDING`, `WRITING` or `ERROR` (except parse errors), so changes survive process death.
- **Demo root:** writes go to local files, as today. Use a `SidecarStore` implementation chosen by root scheme.

### X2.6 Read path, rescan reconciliation, export
- Phase-B enrichment (R1.4) reads sidecars through `XmpDocument` + `XmpMapper.read`. Reconcile:
  - Row PENDING → keep the DB values (the queue will write them).
  - Otherwise, if the file mtime ≠ `xmpLastSyncedMtime` → **the file wins**; update the DB fields and set SYNCED with the new mtime.
- **Export** (`exportCulledFiles`): if the source has a sidecar, read it, merge in the current state and write the merged bytes to the destination, so Lightroom develop settings travel with the file. If it doesn't, write a new document. Create destination sidecars with `application/octet-stream`.
- Settings: `XmpPrefs(rejectAsNegativeRating = true)` in DataStore. Add a minimal **Settings** sheet (gear in the TopDamBar dropdown) with this one toggle and its explanation: "Lightroom/Bridge/darktable show rating −1 as Rejected. Turn off to keep stars on rejected photos in other apps."

### X2.7 UI: real sync state
- Replace the hard-coded "● XMP LIVE" badge in `ZoomableLoupeView` with a `SyncChip(status)`:
  - Synced ✓ (muted green)
  - Saving… (cyan, shown for PENDING and WRITING)
  - Error ⚠ (red; tap → dialog with `xmpError` and actions **Retry** / **Replace sidecar** (backs up to `.bak` first))
  - Read-only 🔒 (grey; tap → explains that the source is read-only and that changes are kept in the app and will be written on export)
- TopDamBar shows a small counter when any rows are pending or errored ("3 saving", "1 error"). Tapping it filters the view to errors.
- Grid and filmstrip: a tiny corner dot for ERROR only (don't clutter).

### Tests
Plain JUnit against the fixtures:
- **Round-trip preservation:** for every valid fixture, `parse → set(rating=3) → toBytes → parse`. Assert that every attribute and element other than the managed properties is identical (compare sets of (namespace, localName, value) plus `crs:ToneCurvePV2012` children, `lr:hierarchicalSubject`, `xmpMM:History`).
- **Reads:** each fixture gives the expected rating/pick/label/subjects (including element form, multi-Description, darktable reject).
- **Mapper:** reject with stars → `-1` + `rawcull:Rating`; un-reject restores stars; setting off keeps stars; label NONE removes `xmp:Label`; an untouched custom label is preserved; `crs:Pick` is removed from the legacy file.
- **Escaping:** tags `R&D`, `<test>`, `"quoted"`, emoji, and Bengali text round-trip.
- **Malformed:** parse fails and nothing is written (queue test with a fake store asserts no write call).
- `no_xpacket.xmp` stays parseable; a new document gets the xpacket wrapper.
- `XmpWriteQueueTest` (kotlinx-coroutines-test + fake store): 10 rapid rating changes → 1 write with the final value; batch of 50 ids → 50 writes, at most 2 concurrent; flushNow bypasses the debounce; PENDING rows re-enqueued at start.
- `ReconcileTest`: PENDING keeps DB values; a newer file mtime makes the file win.

**R2 acceptance checklist (Shehran verifies manually):**
1. Take a RAW with a sidecar that has real Lightroom Classic edits. Rate it in RawCull, then in Lightroom use *Metadata → Read Metadata from File*: the edits are intact and the new rating shows.
2. Reject a photo → Lightroom/Bridge shows it as rated "Rejected" (−1). darktable shows it as rejected.
3. Rapidly tap through 50 photos pressing 1–5 → no lag; the sync chip goes Saving… → Synced; killing the app right after the last tap still leaves the last change on disk after relaunch.
4. A tag `R&D <test>` produces a valid file (opens in Lightroom/darktable).
5. Hand-corrupt a sidecar → RawCull shows Error and leaves the file untouched; "Replace sidecar" creates a `.bak`.
6. A read-only source shows the 🔒 state; exporting it writes sidecars carrying the ratings at the destination.
