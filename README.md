# TexturePaintMobile

Fast Android/Kotlin + C++ scaffold for a mobile texture-painting APK.

## Current wiring

- Android Kotlin app shell
- Filament GLB viewport
- Storage Access Framework GLB picker
- JNI native project handle
- C++17 native core via CMake
- `tinygltf` GLB/GLTF mesh parsing
- `xatlas` auto UV unwrap path
- Custom Android `UvPaintView` with brush/eraser painting into a 2048 PNG texture
- GitHub Actions debug APK build

## First MVP loop in this scaffold

```txt
Import GLB
→ Filament preview loads original model
→ native tinygltf parses mesh stats
→ xatlas unwrap runs native-side
→ switch to UV paint view
→ paint/erase on 2D texture
→ save paint_texture.png
```

## Open in Android Studio

Open the `TexturePaintMobile` folder, let Gradle sync, then run `app`.

The native dependencies are fetched by CMake:

- `tinygltf` from GitHub
- `xatlas` from GitHub

If you already vendor deps locally, replace the `FetchContent` blocks in:

```txt
app/src/main/cpp/CMakeLists.txt
```

## GitHub Actions APK

Push this folder to GitHub. The workflow is:

```txt
.github/workflows/android-apk.yml
```

Run manually from **Actions → Android APK → Run workflow** or push to `main/master`.

APK output artifact:

```txt
app/build/outputs/apk/debug/app-debug.apk
```

## Important next coding steps

1. Return native unwrapped buffers to Kotlin/Filament.
2. Draw real UV island outlines in `UvPaintView` instead of the temporary debug oval.
3. Apply `paint_texture.png` as a live Filament material texture.
4. Implement real GLB export with embedded PNG using `tinygltf` writer.
5. Add seam dilation shader/pass after painting.

## Package

```txt
com.arena.texturepaint
```

Change it in:

- `app/build.gradle.kts`
- `AndroidManifest.xml`
- Kotlin package declarations
- JNI function names in `texture_core.cpp`
