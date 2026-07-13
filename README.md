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
