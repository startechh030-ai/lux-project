# Luxe Texture3D — Viewport Foundation

Package: `luxe.texture3d.app`

Luxe Texture3D is an Android texture-editing project. This build establishes a clean mobile 3D viewport inspired broadly by Prisma3D, Godot, ArmorPaint, and other touch-first editors.

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

## UI direction

The viewport remains uncluttered until tools are functional. Selection gizmos, contextual toolbars, free-look joystick, multi-model hierarchy, and painting controls are later milestones.

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
