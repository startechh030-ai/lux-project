# Luxe Texture3D — Mobile Texture Viewer Foundation

Package: `luxe.texture3d.app`

This baseline intentionally uses a simple, reliable viewport camera. Luxe is a texture-painting app, so camera navigation supports the workflow rather than dominating it.

## Current features

- Android 8.0+ (`minSdk 26`), Android 16 target (`targetSdk 36`)
- Filament 1.69.4 GLB rendering
- Android system file picker; no storage permission
- Filament-native orbit camera
- One-finger orbit
- Responsive intent-separated two-finger pan and pinch zoom
- Consistent unit-cube model framing
- Shanghai Bund HDR used only for IBL
- Neutral dark editor background
- Immersive landscape fullscreen
- Direct-buffer GLB loading without a duplicate heap byte array
- Device-aware GLB import safety limit
- GitHub Actions debug APK build

## Camera principles

- No automatic mesh picking
- No dynamic surface pivot
- No camera target changes during ordinary gestures
- No app-specific C++ camera competing with Filament
- Every imported model starts from the same home view
- Camera input is disabled while the scene is empty

The camera is informed by common patterns across Nomad Sculpt, ArmorPaint, Sculpt+, Prisma3D, and other mobile 3D tools, but Luxe uses its own texture-focused interaction design.

## Controls

- Open icon: select a `.glb`
- One finger: orbit
- Two-finger movement: pan
- Pinch: zoom

## Build with GitHub Actions

1. Upload this project to a GitHub repository.
2. Open **Actions → Build Android APK → Run workflow**.
3. Download the `LuxeTexture3D-debug-apk` artifact.
4. Extract and install `app-debug.apk`.

## Next product work

1. Editor UI structure
2. Lighting controls and studio presets
3. Material inspection modes
4. Base-color texture painting
5. UV workflow and layers

## Editor shell and 3D guides — 0.9.0

- Actual Filament line geometry provides a 20×20 ground grid.
- X/Y/Z axes intersect at the normalized model center `(0,0,-4)`.
- A small center cross marks the current loaded model pivot.
- Grid and axes participate in real 3D camera orbit, zoom, depth testing, and perspective.
- Filament pan ground plane is aligned to `z=-4` for viewport-wide response.
- Full-height warm-black left/right rails reserve future tool locations.
- A clipped 80%-width top rail establishes the editor silhouette.
- Settings icon from the supplied outline pack is placed at lower-left.
- Settings currently opens a placeholder message; no custom native layer is added until a setting actually needs C++.
