# Luxe Texture3D — Mobile 3D Creation App

Package: `luxe.texture3d.app`

Luxe Texture3D is a simplified, touch-first 3D creation application for Android. Its goal is to provide an approachable but capable mobile workspace for geometry editing, texturing, animation, scene creation, lighting, rendering, and related 3D workflows—without reproducing the full complexity of a desktop DCC.

The interface and workflow draw broad inspiration from Blender, Prisma3D, Godot, Maya, Substance Painter, ArmorPaint, Unity, Unreal Engine, and other professional creative tools while maintaining Luxe's own mobile-focused design.

## Platform

- Android 8.0+ (`minSdk 26`)
- Android 16 target (`targetSdk 36`)
- Kotlin UI
- Google Filament 1.69.4
- GitHub Actions APK build

## Current viewport

- Elevated three-quarter default camera
- One-finger orbit
- Two-finger screen-space pan
- Pinch zoom
- Double-tap home reset
- Camera navigation enabled in an empty scene
- Dark neutral editor environment
- HDR used only for IBL
- Procedural minor/major ground grid with distance fade
- Compact world-origin marker
- Grounded and uniformly normalized GLB placement
- Floating Open and Settings buttons only

## Import behavior

The current viewer supports one GLB project at a time. Importing while a model is loaded asks whether to replace it. Multi-model scene support will be added only after a dedicated scene manager exists.

Imported models are:

1. uniformly scaled from their bounds;
2. horizontally centered at the editor origin;
3. moved so their lowest bound rests on the y=0 grid;
4. framed by the standard elevated camera.

## Memory safety

- GLBs stream directly into a direct buffer.
- No duplicate source `ByteArray` is kept during import.
- Import size is limited according to Android app memory class.
- Incomplete files and allocation failures show user-readable errors.

## Product and UI direction

Luxe is no longer scoped only as a texturing app. It is a simplified mobile 3D creation app covering geometry editing, texturing, animation, scene setup, lighting, rendering, and future supporting workflows.

The editor will provide two coordinated interface modes:

- **Simple UI:** a streamlined, button-oriented workspace for fast and approachable editing.
- **Full UI:** a denser, well-organized professional workspace—similar in capability density to a compact mobile Blender-style interface, but designed specifically for touch and for Luxe workflows.

Phase 5 builds both responsive interfaces with mock or placeholder data only. Existing rendering, scene, selection, transform, import, and project systems will not be connected to the new UI during this phase. Engine-to-UI integration will happen later in Phase 6.

## Build

1. Upload the project to GitHub.
2. Run **Actions → Build Android APK**.
3. Download `LuxeTexture3D-debug-apk`.
4. Extract and install `app-debug.apk`.

## Viewport visual correction — 0.11.1

- Grid colors converted to appropriately low linear-RGB values.
- Ground is now opaque dark charcoal rather than a bright transparent blend.
- Minor and major line intensity reduced significantly.
- Origin marker is flush with the ground and no longer has a floating Y line.
- Filament manipulator panning is explicitly enabled.

## Project Hub launcher — 0.12.0

- New landscape entry screen with Local Projects, Marketplace, Settings, and Plugin Manager tabs.
- Non-local tabs are clearly marked Coming Soon.
- Storage Access Framework directory selection with persisted read/write access; no broad storage permission.
- Every project is created as a subfolder inside the user-selected directory.
- Project metadata is written immediately to `project.json`.
- Template model is copied to `model.glb` and opens directly in the editor.
- New Project modal requires a name and offers Empty, Cube, Sphere, Cylinder, Capsule, Plane, Round Box, Torus, and Trolls templates.
- Dedicated XML vector thumbnail represents every template.
- Local project cards use the saved template thumbnail and reopen the project model.

## Premium Project Hub restyle — 0.12.1

- Compact logo/title header and editor-style tab placement.
- Secondary Create/Folder/Scan/search/sort toolbar.
- Human-readable selected folder name instead of raw content URI.
- Borderless dark project rows with stronger background hierarchy.
- Fully custom `#1e1e1e` New Project dialog instead of Android AlertDialog styling.
- Muted bordered input, inline orange validation, compact horizontal template selector, gray Cancel button, and filled blue Create & Open button.

## Signed release builds — 0.12.3

Run **Actions → Build Signed Release APK → Run workflow**. The workflow restores the JKS from encrypted repository secrets, signs `app-release.apk`, verifies the certificate with `apksigner`, generates `SHA256SUMS.txt`, and uploads both as the `LuxeTexture3D-signed-release` artifact. Signing material is never stored in the repository.

## Epic Home dashboard — 0.13.0

- Home is now the default highlighted launcher section.
- Full blue/black Luxe sidebar navigation with exclusive active-state highlighting.
- Supplied Luxe logo, hero artwork, recent-project imagery, and marketplace placeholders are integrated as optimized local resources.
- Functional New Project, Open Project, Projects navigation, folder selection, and real recent-project cards are retained.
- Market, Plugin, Draft, Teams, Settings, Quick Actions, News, and Plugins are polished offline placeholders.
- Release builds now enable R8 code optimization and resource shrinking with Filament/gltfio/JNI keep rules in `app/proguard-rules.pro`.

## Density-independent dashboard scaling — 0.13.1

The landscape dashboard now uses a 1600×720 reference canvas scaler based on actual available window pixels. Dimensions and font sizes share the same scale factor, preventing high-DPI phones from inflating the sidebar, hero, cards, and text while preserving the same visual proportions on larger screens. The template spinner uses a custom scaled adapter. A cropped Luxe launcher icon is now declared in the Android manifest.

## Workstation Project Library — 0.14.0

Home is now a dense project-library workspace inspired by Unreal/Unity hubs: narrow icon rail, flat neutral panels, compact toolbar, search/sort controls, metadata-rich project grid, and a bottom status bar. The promotional hero, welcome copy, marketplace feed, quick actions, news, and cloud upsell are removed from the active Home layout. Market and other external areas remain separate placeholder pages.

## Library refinement — 0.14.1

- Slightly larger icon rail and project cards with improved neutral contrast and spacing.
- Full edge-to-edge immersive launcher with short-edge display-cutout support.
- Project thumbnails now follow a future render-camera contract: the Hub loads `thumbnail.png` from each project when present.
- Projects without a render-camera thumbnail use the Luxe app icon on a neutral placeholder.
- New project metadata declares `thumbnail.png` and a nullable `renderCamera` field; no render camera is created yet.

## Import/export and signing foundation — 0.15.0

- All distributed GitHub builds now use one signed release workflow and the persistent JKS secrets; ephemeral debug APK artifacts were removed.
- Workflow uploads the signed APK, SHA-256 checksum, and signing-certificate report.
- Added canonical app-specific `imports`, `projects`, `staging`, `thumbnails`, and runtime-cache directories through `getExternalFilesDir()` / `externalCacheDir`.
- Added a custom editor Back modal for discard confirmation.
- Added `IMPORT_EXPORT_ARCHITECTURE.md` defining the accepted sequential Assimp queue, staging cleanup, glTF2 output, editor browser, export packaging, and memory-pressure strategy.

## Assimp conversion prototype — 0.16.0

- Native Assimp v5.4.3 is fetched at build time and compiled for `arm64-v8a` and `armeabi-v7a`.
- Enabled importers: FBX, COLLADA/DAE, OBJ, STL, PLY, 3DS, DXF, and glTF/GLB.
- JNI exports one file at a time through Assimp's `gltf2` exporter.
- Import / Export page supports multiple Android picker selections, smallest-first serial conversion, progress rows, app-specific staging cleanup, glTF output folders, metadata, and Luxe fallback thumbnails.
- ZIP packages, external OBJ/glTF dependencies, WorkManager recovery, USD/USDZ, and rendered thumbnails remain subsequent hardening work.

## Recursive packages and internal libraries — 0.17.0

- Project creation now uses app-specific `files/projects`; startup no longer asks for a SAF folder.
- Added `files/assets` and import-history storage.
- ZIP imports are securely scanned recursively through nested archives with path, depth, entry-count, and expanded-size limits.
- Every supported 3D candidate in a package is converted into a separate asset.
- Selected and packaged texture/resource files are staged beside models and copied into asset output.
- Import history records source/output sizes, model and texture counts, duration, memory delta, status, errors, and asset IDs.
- Home shows horizontal Projects and My Assets sections with View All actions.
- Added full-screen Asset Library; the editor folder button opens it.
- Import/Export history tabs open custom history modals.
- Screens are locked to normal landscape to prevent reverse-landscape queue UI.
- USD/USDZ remains excluded; Blender importer is enabled experimentally.

## Import stability pass — 0.17.2

- Imported thumbnails are downsampled to a maximum 512px before saving and are decoded with sampling plus a 16MB Hub LRU cache.
- Hub no longer walks complete asset directories on the UI thread to calculate card size.
- Queue rows automatically leave the active queue after completion/failure; JSON history remains available.
- History modal presents readable summaries instead of raw JSON.
- ZIP packages continue converting other candidate models when one malformed candidate fails, producing partial-success history where applicable.
- Project creation now writes directly to app-specific `files/projects`; SAF folder prompts are removed until Export is implemented.

## Persistent import queue — 0.18.0

- Room stores import jobs, progress, state, errors, resource URIs, and generated asset IDs.
- WorkManager runs a unique sequential Assimp queue that survives Activity recreation and process restart.
- Picker URI permissions are persisted before work is enqueued.
- Long conversions run as foreground data-sync work with a low-priority notification.
- Queue UI observes database state and supports cancellation, persistent history, retries-ready state data, and restart-safe progress.
- Completed/partial/failed jobs are stored both in Room and lightweight JSON history files.

## Queue drain and notifications — 0.18.2

- Replaced one-WorkRequest-per-file chaining with one durable queue-drain Worker that repeatedly claims the smallest WAITING/RUNNING Room job.
- App and Import/Export startup both ensure the unique drain worker is scheduled, recovering jobs left waiting by a previous process.
- Foreground notification now updates current filename, conversion phase, and progress.
- Per-file success notifications and an all-imports-complete notification use a monochrome Luxe status icon plus app-logo large icon.
- Low-memory devices pause 1.5 seconds between files after releasing native scenes; other devices pause briefly.

## Phase 2A transactional glTF validation — 0.19.0

- Assimp writes into hidden `.converting-*` transaction folders rather than final assets.
- A structural validator checks glTF 2.0 JSON, meshes, scenes/nodes, buffers, bufferViews, accessors, images, external file sizes, URI schemes, absolute paths, backslashes, and path traversal.
- Invalid transactions are deleted and never appear in My Assets.
- Valid transactions receive rich validation metadata and are atomically renamed into final asset folders.
- Startup recovery removes orphaned transactions left by process death.

## Phase 2B glTF textures — 0.20.0

- Extracts base64 glTF image data URIs into bounded texture files.
- Collects external package textures, repairs image URIs into `textures/`, normalizes separators/encoding, deduplicates identical images by SHA-256, and resolves filename collisions.
- Validates PNG/JPEG/WEBP/BMP decodability while preserving TGA/DDS with compatibility warnings.
- Deletes duplicate loose images only after all glTF image URIs are rewritten.
- Asset metadata records texture file paths and combined texture/structural warnings before transactional finalization.

## Phase 2C metadata and duplicate detection — 0.21.0

- Extracts vertex/triangle counts, local accessor bounds, node/mesh/material/texture/animation counts, and textured-material counts from validated glTF.
- Records SHA-256 source and converted-content fingerprints plus a per-file size/hash inventory in `asset.json`.
- Detects existing identical converted content and reuses the existing asset instead of writing a duplicate folder.
- Adds `DUPLICATE` history state and a dedicated reuse notification.
- Hub asset cards show triangle and texture counts from metadata.

## Asset metadata backfill — 0.21.1

- Startup asynchronously upgrades pre-2C `asset.json` files without reconverting models.
- Backfill calculates geometry/material/animation counts, texture inventory, bounds, content fingerprint, and file hashes, then replaces metadata atomically.
- Hub cards show mesh fallback text while migration runs instead of misleading `0 tris`.
- Asset-facing UI uses metadata display names and never exposes `model.gltf` or runtime folder IDs as product labels.

## Phase 2D format profiles — 0.22.0

- Assimp flags now vary by source: preserve-glTF, preserve-scene (FBX/DAE/BLEND), surface mesh (OBJ), and static geometry profiles.
- Existing glTF/GLB avoids normal/tangent regeneration and export post-processing where possible.
- Native conversion metadata records profile, source axis/unit hints, animations, cameras, lights, materials, and bone presence.
- Asset metadata records preserved-unit policy, static/animated kind, native warnings, and material quality warnings.
- Older assets receive inferred profile metadata through schema-version-3 backfill.

## Phase 3A Element Registry — 0.23.0

- Added Room registries for Elements, immutable revisions, dependencies, blobs, blob references, ULX projects, and project Element references.
- Added SHA-256 content-addressed blob storage with hard-link optimization and transactional copy fallback.
- Existing converted model assets are registered as library-scoped Model Elements with `.ulelement` text manifests and immutable revision manifests without reconversion.
- New conversions automatically register Model Elements after transactional glTF finalization.
- Existing working projects receive stable project UIDs and registry records.
- New projects create an unencrypted binary `.ulx` ZIP container containing a required text manifest and project payload.

## Phase 3B.1 Texture and Material Elements — 0.24.0

- Validated Model Elements automatically extract child Texture and Material Elements without duplicating geometry or image blobs.
- Texture roles are derived from authoritative glTF material slots with filename hints as fallback.
- Material Elements preserve glTF PBR payload and depend on Texture Elements plus read-only Luxe system shader Elements.
- Model revision manifests and Room dependency rows expose the Model → Material → Texture family tree.
- Image-only picker selections create standalone library Texture Elements instead of unsupported queue jobs; model+image selections still treat images as companion resources.
- Supported standalone image Elements include PNG, JPEG, BMP, WEBP, TGA, DDS, SVG, GIF, HEIC/HEIF, and AVIF, with fallback thumbnails when Android cannot decode the source.

## Phase 3B.2 Geometry Elements — 0.25.0

- Creates one dependency-scoped Geometry Element per glTF mesh without copying BIN data.
- Geometry manifests reference the source Model revision and record primitives, accessor indices, attribute layout, material assignments, morph targets, bounds, vertex/triangle counts, and topology signatures.
- Geometry revisions depend on extracted Material Elements per primitive; Model revisions depend on Geometry Elements.
- Existing Model Elements with extraction schema 1 are upgraded idempotently to schema 2 without Assimp reconversion.

## Phase 3B.3 Rig and Animation Elements — 0.26.0

- Creates dependency-scoped Rig Elements from glTF skins with joint hierarchy, parent mapping, skeleton root, inverse-bind accessor, and compatibility hash.
- Creates one Animation Element per glTF animation with duration, channels, samplers, interpolation, target paths, rig compatibility, and root-motion candidate metadata.
- Geometry Elements that are instantiated with a skin depend on the matching Rig Element.
- Model revisions depend on Rig and Animation Elements and migrate idempotently to extraction schema 3 without reconversion.

## Direct glTF packages and ZAE — 0.26.1

- JSON `.gltf` candidates bypass Assimp importer detection and are copied through Luxe's non-destructive resource, texture, validation, metadata, and Element extraction pipelines.
- ZIP packages containing glTF no longer fail when selective Assimp builds report no suitable glTF reader.
- `.zae` is treated as a COLLADA archive and recursively extracted with the same ZIP security limits.

## Final Asset / Library cleanup — 0.27.0

- Imported models remain Assets and receive `family.json`; they no longer auto-create Ulelements.
- Imported textures are hard-linked/deduplicated into `library/Texture/<asset-family>/` with `.texture` descriptors.
- Imported animations become lightweight `.anim` Library descriptors referencing source Asset/BIN accessors; no Rig files are generated.
- Standalone image imports become typed Library Texture resources instead of Model Assets or Ulelements.
- `library/Element/` is reserved exclusively for project-created text `.ulelement` family manifests and payloads.
- One-time cleanup removes the previous automatic Model/Texture/Material/Geometry/Rig/Animation/System-Shader Element registry entries and lowercase auto-manifest folder while retaining shared blobs.

## glTF ZIP source normalization — 0.27.2

- Validates extracted `.gltf` entries before conversion, rejects zero-byte/incomplete entries, ignores macOS archive metadata, and verifies declared ZIP entry sizes.
- Normalizes UTF-8 BOM, UTF-16 LE/BE, trailing NUL bytes, and whitespace before parsing JSON.
- Detects Git LFS pointers and mislabeled XML/HTML files with explicit errors.
- Writes normalized glTF transactionally before texture repair and structural validation.

## Lightweight Asset family index — 0.27.3

- `family.json` format 2 is a small Luxe resource index, not a duplicate scene description.
- Geometry entries retain only names, glTF indices, counts, and material indices.
- Material entries retain only names, glTF indices, shader class, render flags, and Library texture UIDs.
- Animation entries point to `.anim` descriptors; cameras/lights retain only names, indices, and types.
- Full primitives, accessors, material payloads, channels, samplers, transforms, and scene hierarchy remain authoritative in `model.gltf`.
- Existing format-1 families rebuild automatically from glTF and Library descriptors.

## Phase 3C.1 Unified Resource Browser — 0.28.0

- Replaced the basic Asset grid with a reusable full-screen browser component prepared for later floating editor use.
- Added logical sections for Projects, Assets, typed Library folders, project-created Elements, Recent, and Trash.
- Asset family trees read lightweight `family.json` and expose Geometry, Materials, Textures, Animations, Cameras, and Lights without parsing raw glTF in the UI.
- Added responsive tree pane, grid/list toggle, search, sorting, friendly metadata, sampled thumbnails, and category counts.

## Phase 3C.2 Resource Inspector and Actions — 0.29.0

- Browser selection opens a right-side Inspector with thumbnail, metadata, favorite state, validation, rename, ULX rebuild, Trash, restore, and permanent-delete actions where applicable.
- Asset, project, descriptor, and Ulelement validators report custom Luxe modal results.
- Rename updates friendly metadata without changing runtime IDs; project rename rebuilds ULX.
- Trash is soft deletion with original-location metadata, restore, and custom permanent-delete confirmation.
- Favorites are user-state preferences and do not modify payload files.

## Phase 3C.3 Floating Resource Browser — 0.30.0

- Added editor-embedded Resource Browser panel with draggable title bar, bottom-corner resizing, minimize, maximize/restore, close, viewport clamping, and compact-phone maximize behavior.
- Normalized position/size and window state persist across sessions and screen sizes; browser section, query, sort, and grid/list mode also persist.
- Browser panel consumes touch inside its bounds so Filament viewport orbit/pan/zoom only receives touches outside it.
- Added Phase 4 callback contract for project, asset, Library resource, Ulelement, Add to Project, and Add to Scene actions.

## Phase 4A ULX Project Sessions — 0.31.0

- Opening a project validates and mounts its `.ulx` payload into a project-specific runtime session.
- Added session locks, scene state, dirty tracking, camera-state serialization, transactional ULX save/backup replacement, autosave-on-background, and interrupted-session recovery.
- Editor Back now exits immediately when clean or offers Cancel, Discard, and Save & Exit when dirty.
- Temporary Save button shows a dirty indicator and writes the session safely back into ULX.

## Phase 4B Multi-asset Scene Manager — 0.32.0

- Added a gltfio scene manager that owns multiple Filament Assets inside ModelViewer's shared Engine/Scene.
- Supports GLB buffers and converted JSON glTF folders with external BIN/texture resource resolution, progressive loading, per-instance visibility/lock/name, removal, and native cleanup.
- Project scene instances serialize to `scene.json` and restore from ULX sessions without replacing existing models.
- Temporary Add Model and Remove Last controls allow multi-asset testing before Phase 5 UI.

## Phase 4C Browser-to-Editor actions — 0.33.0

- Resource selection is now read-only until an explicit Inspector action is pressed.
- Assets expose Add to Scene and load converted JSON glTF through the multi-asset manager.
- Texture/Animation/Library resources and Ulelements expose Add to Project and persist deduplicated references in scene state.
- Project cards expose Open Project with clean switching or a custom Cancel/Discard/Save & Open dirty-session flow.
- Browser callbacks are connected only in the editor; full-screen Hub browsing remains non-destructive.

## Phase 4D Selection and Scene Hierarchy — 0.34.0

- Viewport taps use Filament picking to map renderable entities to owning Scene Instance UIDs; empty taps clear selection and drags remain camera gestures.
- Added ice-blue 3D selection bounds, lock/visibility-aware selection, selection persistence, and stale-entity cleanup.
- Temporary Scene modal supports select, show/hide, lock/unlock, friendly rename, and delete with dirty-state integration.

## Phase 4E Transform Foundation — 0.35.0

- Scene records now own persisted position, quaternion rotation, and non-uniform scale composed over the model's grounded normalization transform.
- Temporary Transform dialog provides numeric position/rotation/scale, Apply, Reset, Ground, and Duplicate actions for the selected unlocked instance.
- Selection bounds update after transforms; transformed bounds account for rotation and scale.
- Scene serialization/restoration preserves transforms, and duplicated models reuse project-local or Asset sources without copying shared resources.

## Phase 5 top and left editor shell — 0.36.0

- Added the approved responsive Full UI top navigation and eight-position left tool rail.
- Added Edit Preview/Matcap indicators and workspace-specific mock contextual controls.
- File opens the existing Resource Browser; long-press File invokes project Save.
- Mesh editing controls remain clearly identified placeholders until the Phase 6 MeshLibs engine.
- Existing Phase 4 testing controls remain available behind a temporary DEV toggle.

## Phase 5 compact WIP editor chrome — 0.36.1

- Renamed the active edit-preview workspace to WIP mode.
- Replaced temporary text glyphs in the left rail with original Canvas-rendered monochrome tool icons.
- Compacted the top bar, contextual row, and eight-tool rail while preserving touch targets.
- Added dropdown indicators to editor tabs and a custom Luxe dropdown surface with placeholder feature data.
- File dropdown exposes the real Resource Browser and Save actions; no platform AlertDialog is used by the new chrome.
