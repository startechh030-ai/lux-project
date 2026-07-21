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
