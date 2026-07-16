# Luxe Texture3D — Viewer Milestone 1

Android GLB viewer for **Android 8.0 through Android 16** (`minSdk 26`, `targetSdk 36`).

## Included

- Filament GLB rendering and PBR materials
- Shanghai Bund 2K HDR preprocessed into Filament IBL KTX asset with a neutral solid editor background
- C++17/JNI camera math with frame-rate-independent exponential smoothing
- One-finger orbit, pinch zoom, two-finger pan, double-tap reset
- Android system file picker; no storage permission required
- Landscape-first minimal UI
- GitHub Actions debug APK build

## Build without Android Studio

1. Create a GitHub repository and upload all files in this directory.
2. Open **Actions → Build Android APK → Run workflow**.
3. When complete, open the workflow run and download `LuxeTexture3D-debug-apk`.
4. Extract the ZIP and install `app-debug.apk` on your Android device.

Android may ask you to allow installation from your browser/file manager. The debug APK is intended only for development testing.

## Controls

- **Open icon:** choose a `.glb` from Android's file manager
- **One finger:** orbit
- **Pinch:** zoom
- **Two fingers:** pan
- **Double tap:** reset camera

## Architecture

Kotlin owns Android lifecycle, UI, file selection, gesture recognition, and Filament calls. C++ owns camera state, orbit/pan/zoom math, clamping, and smoothing. JNI exposes only viewport, input, reset, and per-frame camera-pose methods.

## HDR note

The supplied `shanghai_bund_2k.hdr.txt` was verified as Radiance HDR data, renamed to `.hdr`, and converted offline with Filament `cmgen` 1.69.4. Runtime assets are:

- `app/src/main/assets/environments/shanghai_bund_2k_ibl.ktx`

## Camera revision 0.2.0

The camera input pipeline now uses a dedicated `CameraSurfaceView` rather than overlapping Android gesture detectors. Model and native orbit pivots both use world origin. Pan is calculated in the current camera screen plane. See `CAMERA_RESEARCH.md` for comparison and design notes.

The editor uses sticky immersive fullscreen, lays out through the navigation/status-bar regions and display cutout short edges, and restores immersion whenever window focus returns.

## Camera input hotfix 0.2.1

Filament continues rendering into a `SurfaceView`, but touch is now captured by a separate transparent `CameraInputView` layered above it. During testing the status pill displays `CAMERA INPUT • ORBIT`, `PAN / ZOOM`, or `RESET`, proving that Android delivered the gesture to the native camera pipeline.

## Single-camera fix 0.2.2

Root cause: `ModelViewer.render()` applies its Manipulator camera immediately before drawing, overwriting the separate JNI camera pose. This build uses Filament's native Manipulator as the sole camera owner. The transparent input layer forwards the original complete `MotionEvent` stream to `ModelViewer.onTouchEvent()`. Model placement is restored to ModelViewer's expected default framing at z = -4.

## Milestone 1 camera completion 0.3.0

The stock Filament GestureDetector intentionally waits for more than two motion samples, requires 4 px of midpoint travel for pan, requires 10 px of span change for zoom, and then locks the gesture to either pan or zoom. This caused the perceived two-finger dead zone.

`CameraInputView` now drives the same Filament-native `Manipulator` directly:

- one finger begins orbit immediately;
- a second finger switches immediately to strafe/pan;
- midpoint movement and pinch zoom are processed together;
- no per-MOVE objects or arrays are allocated;
- lifting one finger hands the gesture directly back to orbit;
- Filament remains the sole camera owner.

This is a small first step toward mobile-sculpting navigation without yet adding dynamic pivot picking or view snapping.

## Stable pan/zoom intent 0.3.1

Two-finger gestures now pass through a tiny 2dp intent threshold. Separation-dominant motion locks to zoom; midpoint-dominant motion locks to pan. Pan and zoom commands are never sent during the same gesture, eliminating pinch vibration while retaining a much smaller response threshold than Filament's stock detector.

## Nomad-style focus v1 — 0.4.0

- Double-tap a visible detail to smoothly bring that screen location to the viewport center.
- Filament strafe updates the persistent orbit target, so following orbit and zoom gestures stay focused there.
- A tiny ice-blue pivot dot appears briefly during orbit, pan, zoom, and focus.
- Focus animation lasts 220 ms with deceleration and allocates only when double-tap is used.
- Existing stable orbit, intent-locked pan, and intent-locked zoom behavior is preserved.

This is intentionally a lightweight screen-space focus approximation. Exact triangle hit-point raycasting is deferred to a later painting milestone.

## Milestone 1 stabilization — 0.4.1

The screen-space double-tap focus experiment from 0.4.0 was removed because repeated strafe operations accumulated target translation without a real mesh hit point. Double-tap now returns to the stable home bookmark. True mesh grabbing is deferred until exact surface picking is implemented.

Camera and memory changes:

- orbit speed reduced from Filament's 0.01 default to 0.0035 for a heavier sculpting feel;
- stable pan/zoom intent classifier retained;
- GLBs stream directly into one direct buffer instead of `readBytes()` plus a second copy;
- import limit is device-aware: 20% of Android app heap, clamped to 32–128 MB;
- incomplete reads and unknown-size documents fail safely;
- Java heap allocation failure displays a useful message and unloads the partial model.

## Fluid two-finger navigation — 0.4.2

Pan/zoom intent locking and its 2dp threshold were removed. Two-finger midpoint and separation are filtered independently, then applied together. Pan remains continuously active; pinch zoom is centered on the stable viewport pivot rather than the noisy touch midpoint. Orbit sensitivity and the one-finger code path are unchanged from 0.4.1.

## Stable navigation rollback and scene reset — 0.4.3

The simultaneous Filament grab+scroll experiment was removed because some devices stop zooming when both native operations share one grab session. The proven intent-separated pan/zoom path is restored with a reduced 0.75dp confidence threshold. Dynamic/fake pivot visuals are removed. Camera input is disabled while no model exists, and every successful import restores the native manipulator's home bookmark before input is enabled. This prevents empty-scene or previous-model target offsets from affecting a newly imported model.

## Phase 3A — native mesh-pivot prototype 0.5.0

- `LuxeModelViewer` is a version-matched fork of Filament 1.69.4 ModelViewer whose render path does not overwrite the external camera.
- C++ again owns yaw, pitch, distance, world-space target, screen-plane pan, clamps, and exponential smoothing.
- A one-finger touch submits an asynchronous Filament GPU pick query.
- Successful renderable picks use depth-buffer unprojection to obtain a world-space point.
- The desired native pivot glides toward the picked surface and persists after release.
- Empty-space picks leave the current pivot unchanged.
- Two-finger pan and pinch zoom are processed together by C++, without Filament Manipulator grab/scroll conflicts.
- Every model import resets camera and pivot to world origin.

This is the first device-validation build for exact picking. Angle snapping and trackball mode remain Phase 3B.

## Phase 3A pivot safety correction — 0.5.2

Picking depth is now supplied directly to Filament's inverse projection path instead of being remapped a second time. Reconstructed points must be finite and remain inside the normalized model bounds. Invalid or empty picks restore the authoritative model-center pivot `(0,0,0)`. C++ independently clamps and radius-limits every requested pivot, preventing a malformed GPU depth result from pulling the camera toward distant empty space.

## Phase 3A motion stabilization — 0.5.3

- orbit signs reversed so viewport content follows finger motion;
- pivot rebasing preserves both current and desired world-space eye positions;
- an asynchronous pick can no longer translate or zoom the visible camera when focus changes;
- JNI fills one lifetime `FloatArray(9)` rather than allocating a new array every rendered frame;
- gesture status text updates only when gesture mode changes, not on every MotionEvent;
- native bounds checks and depth validation remain active.

## Phase 3A clean gesture fix — 0.5.4

GPU pick results are queued as a pending pivot and committed only at the beginning of the next orbit stroke. The active pivot is immutable for the full gesture. Orbit orientation is calculated from total displacement relative to ACTION_DOWN and the saved starting orientation, so the final angle no longer depends on MotionEvent sample count or timing. Pointer handoff starts a new clean orbit baseline. Pivot rebasing remains eye-preserving and guarded by normalized bounds.

## Phase 3A direction and touch filtering — 0.5.6

Horizontal and vertical orbit signs were reversed to match the requested swipe direction. Two-finger centroid and separation now use independent low-pass input filtering before native pan and zoom, with no confidence dead zone. Valid GPU picks briefly display an ice-blue ring at the actual picked screen location; empty or rejected picks clear it. Pivot feedback is also cleared on every import.
