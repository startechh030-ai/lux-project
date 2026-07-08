# SimpleGlbViewer

Minimal first milestone:

```txt
App opens → user taps file picker icon → selects .glb → Filament renders it
```

No JNI. No xatlas. No painting. No export.

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
