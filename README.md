# SimpleGlbViewer

Current milestone:

```txt
Landscape editor shell
→ user taps file picker icon
→ selects .glb
→ Filament renders it
→ native C++ camera handles orbit / pan / zoom
→ Kotlin applies native camera state to Filament
→ axis gizmo stays top-right
```

## Gesture controls

- **1 finger drag:** orbit camera
- **2 finger pinch:** zoom in/out
- **2 finger drag together:** pan camera/target left/right/up/down
- **Double tap:** reset camera

## Native camera wiring

C++ files:

```txt
app/src/main/cpp/CMakeLists.txt
app/src/main/cpp/NativeCamera.cpp
```

Kotlin JNI bridge:

```txt
app/src/main/java/com/arena/simpleglbviewer/NativeCamera.kt
app/src/main/java/com/arena/simpleglbviewer/NativeCameraGestureHandler.kt
```

This does not link Filament from C++. Native code only calculates camera math and returns eye/target/up/yaw/pitch. Kotlin applies it to Filament. This keeps GitHub Actions simple and avoids native Filament package headaches.

## UI

A lightweight editor chrome overlay is included:

```txt
EditorChromeView.kt
```

It is only visual for now. Real buttons/tools will be wired one by one.

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
