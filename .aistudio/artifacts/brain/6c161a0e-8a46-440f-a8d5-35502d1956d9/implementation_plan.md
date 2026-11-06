# RawCull Refinement: RapidReady-Inspired DAM Architecture & Workflow

A comprehensive plan to evolve RawCull from a proof-of-concept into a high-performance, production-grade Android Digital Asset Manager (DAM) modeled after RapidReady, tailored for tablets and mobile devices with native XMP sidecar interoperability.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The following decisions were clarified in Phase 1 and are baked into this plan:
> - **Live Continuous XMP Sidecar Writing**: Ratings, picks/rejects, color labels, and keyword tags are written to `.xmp` sidecar files in real time directly inside the source/working folder, ensuring seamless synchronization with Lightroom, Snapseed, and RapidRAW.
> - **Structured Ingest Organization**: Exported culled files are organized into structured directories formatted as `YYYY/MM-DD/[Shoot_Name]` (with custom shoot title support and date extraction from EXIF).
> - **Smart RAW + JPEG Stacking**: Companion RAW and JPEG pairs sharing the same base filename (e.g. `DSC04891.ARW` + `DSC04891.JPG`) are stacked as a single asset during culling, with an ingest toggle to transfer Both, RAW only, or JPEG only.

---

## 1. Overview & Core Concept

### What It Does
RawCull is a fast, local-first DAM and Ingest tool for digital photographers on Android. Users plug in an SD card or card reader via USB OTG, immediately browse and inspect full-resolution RAW photos (Sony ARW, Adobe DNG, etc.) directly on the external card before committing gigabytes of storage, rapidly cull bad frames, and ingest only selected picks into structured local storage with standardized Adobe XMP sidecars.

### Target Audience & Persona
- **Photographers in the Field**: Reviewing and culling camera bursts on location using an Android tablet or phone directly off their SD card before heading to a computer.
- **Mobile Edit Workflow**: Bringing culled RAW files into mobile editors (Snapseed, Lightroom Mobile, RapidRAW) with ratings, color flags, and tags pre-populated.

### Key Value Proposition
- **Zero-Copy Pre-Ingest Culling**: Cull and discard rejects on the SD card before wasting device storage and battery on massive 50GB file copies.
- **Universal XMP Interoperability**: Every rating, color tag, and pick flag writes standard Dublin Core and Adobe XMP tags, ensuring zero vendor lock-in.
- **Instantaneous 60fps Flipping**: Seamless navigation between 24–60MP RAW files without lag or memory leaks.

---

## 2. User Experience & Visual Design

### Key User Flows

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. CONNECT & INGEST WIZARD                                                  │
│    Connect SD Card via OTG ──► Select Card Root ──► Auto-Scan & Stack Pairs │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 2. ULTRA-FAST CULLING (TABLET 3-PANE / MOBILE GESTURES)                     │
│    Filmstrip Jump ──► 100% Loupe / Side-by-Side Compare ──► P/X/1-5 Keys    │
│    └──► Continuous Real-Time .XMP Sidecar Write to Storage                 │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 3. STRUCTURED INGEST / EXPORT                                               │
│    Select Picks ──► Shoot Name Input ──► Create YYYY/MM-DD/Shoot ──► Ingest │
└─────────────────────────────────────────────────────────────────────────────┘
```

1. **Card Connect & Scan**:
   - The user plugs in their SD card via USB-C OTG and selects the folder.
   - The scanner indexes RAW files, discovers companion JPEGs, parses EXIF dates and camera metadata, and reads existing `.xmp` sidecars if already present.
2. **Loupe & Split Inspection**:
   - Primary view: large responsive canvas with pinch-to-zoom (up to 600%), 1:1 pixel peek button, and double-tap zoom centered on tap.
   - Synchronized Split Compare view: place burst frames side-by-side to determine eye sharpness with linked pan and zoom.
3. **Live Rating & Tagging**:
   - Tapping Pick (`P`), Reject (`X`), Star 1–5, or Color Label immediately updates UI state, database cache, and writes/updates `[filename].xmp` in the directory.
4. **Structured Ingest Execution**:
   - User taps "Ingest Culled Picks".
   - Dialog prompts for Shoot Name (e.g. `Golden_Hour_Coast`).
   - App automatically creates destination path `[DestRoot]/2026/11-05/Golden_Hour_Coast/`.
   - Copies RAW, paired JPEG (if enabled), and `.xmp` sidecar with transfer speed (MB/s) and progress indicators.

### Visual Identity & Theme
- **Theme**: Darkroom Minimalist (`#070A0E` background, `#0F141B` surfaces, `#161E27` cards).
- **Accents**: Electric Cyan (`#38BDF8`) for selection and active tools; Emerald (`#10B981`) for Picks; Crimson (`#EF4444`) for Rejects; Amber (`#FBBF24`) for Stars.
- **Typography**: Clean, tabular monospace numbers for exposure settings (`1/2000s`, `f/2.8`, `ISO 100`), bold uppercase badges for formats (`ARW+JPG`, `DNG`).
- **Responsive Layouts**:
  - *Tablet*: 3-pane layout with docked inspector, live RGB histogram, loupe/compare viewport, and horizontal bottom filmstrip.
  - *Mobile*: Full-bleed loupe with swipe transitions, floating quick-cull bar, and modal EXIF drawer.

---

## 3. Key Technical Decisions & Trade-Offs

### 1. Continuous Live XMP Writing vs. Batch Export Only
- **Decision**: Write/update `.xmp` sidecars in real time whenever metadata changes on the active folder, while keeping Room as an indexed query cache.
- **Rationale**: Eliminates data loss if an app crashes or an SD card is unmounted, and allows external desktop tools or mobile editors running in parallel to immediately pick up ratings.
- **Trade-Off**: File I/O happens on each button press; mitigated by executing XMP updates on an asynchronous I/O coroutine dispatcher with debouncing.

### 2. RAW + JPEG Pairing Engine
- **Decision**: Group files sharing the same directory and stem (e.g., `DSC0001.ARW` and `DSC0001.JPG`) into a single `MediaAssetItem` with child format references.
- **Rationale**: Prevents users from having to rate and cull the same image twice. When companion JPEG is present, preview generation is 3x faster by extracting the companion JPEG before falling back to RAW embedded previews.
- **Ingest Options**: Provide user checkboxes: `[x] Copy RAW`, `[x] Copy JPEG`, `[x] Copy XMP Sidecar`.

### 3. Folder Destination Structuring (`YYYY/MM-DD/Shoot_Name`)
- **Decision**: Parse earliest capture date from EXIF (`ExifInterface.TAG_DATETIME_ORIGINAL`) and build directory trees: `[Root]/[Year]/[Month-Day]/[ShootName]`.
- **Rationale**: Standard industry organization matching Lightroom and RapidReady import templates, preventing disorganized dumps of thousands of files in a single flat folder.

---

## 4. Technical Architecture & Component Structure

### System Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         PRESENTATION LAYER (COMPOSE)                        │
│                                                                             │
│  ┌───────────────────────┐ ┌──────────────────────┐ ┌────────────────────┐ │
│  │   TopDamBar (Ingest)  │ │ ZoomableLoupeView /  │ │ ExifInspectorPanel │ │
│  │   - Folder selector   │ │ CompareLoupeView     │ │ & HistogramView    │ │
│  │   - Format & Star bar │ │ - Pinch, Pan, 1:1    │ │ - Live RGB curves  │ │
│  └───────────┬───────────┘ └──────────┬───────────┘ └─────────┬──────────┘ │
│              │                        │                       │            │
│  ┌───────────┴────────────────────────┴───────────────────────┴──────────┐ │
│  │                FilmstripBar / GridViewGallery (Thumbnails)            │ │
│  │                - RAW+JPEG Badge, Pick/Reject Flag, Star Badges        │ │
│  └────────────────────────────────────┬──────────────────────────────────┘ │
└───────────────────────────────────────┼─────────────────────────────────────┘
                                        │ State & Actions
                                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           VIEWMODEL & DOMAIN LAYER                          │
│                                                                             │
│                        RawCullViewModel (StateFlow)                         │
│   ├── Photo List & Filter Pipeline (Sort, Picks, Stars, Color, Format)      │
│   ├── Real-Time Culling Handlers (togglePick, setRating, updateTag)         │
│   └── Ingest Controller (Folder Structuring, Speed Telemetry, Copy Engine)  │
└───────────────────┬───────────────────────────────────────┬─────────────────┘
                    │                                       │
                    ▼                                       ▼
┌──────────────────────────────────────┐ ┌────────────────────────────────────┐
│         DATA & STORAGE LAYER         │ │      RAW PIPELINE & UTILITIES      │
│                                      │ │                                    │
│  ┌────────────────────────────────┐  │ │  ┌──────────────────────────────┐  │
│  │ Room Database (Local Cache)    │  │ │  │ RawPreviewLoader (LRU + Disk)│  │
│  │ - RawPhotoDao (Indexed Assets) │  │ │  │ - ARW/DNG Embedded Preview   │  │
│  └────────────────────────────────┘  │ │  └──────────────────────────────┘  │
│  ┌────────────────────────────────┐  │ │  ┌──────────────────────────────┐  │
│  │ SAF Storage Access Framework   │  │ │  │ XmpSidecarManager            │  │
│  │ - DocumentFile I/O Engine      │  │ │  │ - Live Bidirectional Read/   │  │
│  │ - Ingest Destination Builder   │  │ │  │   Write of XMP Sidecars      │  │
│  └────────────────────────────────┘  │ │  └──────────────────────────────┘  │
└──────────────────────────────────────┘ └────────────────────────────────────┘
```

### Core Entities & Interfaces

1. **`RawPhotoItem` Refinements**:
   - `pairMode`: `RAW_ONLY`, `JPEG_ONLY`, `RAW_AND_JPEG`
   - `rawFileUri`: String
   - `jpegFileUri`: String?
   - `xmpFileUri`: String?
   - `hasEmbeddedXmp`: Boolean
2. **`XmpSidecarManager`**:
   - `readSidecar(xmpUri: Uri)`: Loads existing ratings and tags from pre-existing XMP files on the card.
   - `writeSidecar(targetDir: DocumentFile, photo: RawPhotoItem)`: Atomically writes or updates standard Adobe XMP XML sidecars alongside the photos.
3. **`IngestPipeline`**:
   - `executeIngest(assets: List<RawPhotoItem>, config: IngestConfig)`:
     - Resolves date-based destination folder: `destUri/YYYY/MM-DD/ShootName`.
     - Creates subdirectories via SAF `DocumentFile.createDirectory()`.
     - Stream copies selected components (RAW, JPEG, XMP).
     - Emits throughput telemetry (MB/s, file index, progress percentage).

---

## 5. Implementation Roadmap & Steps

1. **RAW + JPEG Pairing**:
   - Update repository scanner to correlate matching file stems across supported extensions (`.ARW`, `.DNG`, `.CR3`, `.NEF` paired with `.JPG`/`.JPEG`).
   - Expose pair badges in the UI and add user selection for display/ingest preferences.
2. **Live Bidirectional XMP Engine**:
   - Implement `XmpSidecarManager` for direct file I/O on the source directory.
   - Read pre-existing XMPs on scan; update `.xmp` immediately when ratings, flags, or tags change.
3. **Structured Ingest Wizard**:
   - Build date-based path generator (`YYYY/MM-DD/[Shoot_Name]`).
   - Create Ingest Modal with shoot name input, transfer options (RAW / JPEG / XMP), and live MB/s transfer telemetry.
4. **UI & Performance Tuning**:
   - Ensure instant 60fps loupe navigation by prioritizing companion JPEG previews when available, falling back to embedded RAW previews.
   - Refine tablet 3-pane layout and mobile bottom sheets.
5. **Verification & Testing**:
   - Local JVM Robolectric tests for pairing, XMP parsing/writing, and date folder path calculations.
   - Compile and verify Android build.
