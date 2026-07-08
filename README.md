# SimpleGlbViewer

Current milestone:

```txt
App opens landscape
→ user taps file picker icon
→ selects .glb
→ Filament renders it
→ 1 finger rotates camera
→ pinch zooms / pulls out
→ 2 finger drag pans/moves model on screen
→ axis gizmo stays top-right
```

No JNI, no xatlas, no export yet.

## Gesture controls

- **1 finger drag:** rotate/orbit camera
- **2 finger pinch:** zoom in/out
- **2 finger drag together:** pan model left/right/up/down without rotating

## Build

```bash
gradle assembleDebug
```

GitHub Actions workflow included at:

```txt
.github/workflows/android-apk.yml
```

APK artifact:

```txt
app/build/outputs/apk/debug/app-debug.apk
```
