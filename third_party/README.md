# Third-Party Dependencies

This directory holds all third-party libraries needed to build the Lux Engine for Android.

## Quick Setup

```bash
# Run the setup script — it handles everything:
./scripts/setup_third_party.sh       # Debug build for all ABIs
./scripts/setup_third_party.sh Release  # Release build
```

**Prerequisites:** Android NDK 27+ (set `ANDROID_NDK` env var)

---

## Dependency Table

| # | Library | Version | Type | Android Support | Setup Time |
|---|---------|---------|------|-----------------|------------|
| 1 | **miniaudio** | 0.11.21 | Header-only (C) | ✅ Any CPU | ~2s |
| 2 | **libsodium** | 1.0.20 | Shared lib (.so) | ✅ Prebuilt AAR | ~10s |
| 3 | **ozz-animation** | 0.16.0 | Shared lib (.so) | ⚠️ Build from source | ~3-5 min |
| 4 | **Filament** | 1.53.2 | Shared lib (.so) | ✅ Prebuilt `.tar.gz` | ~30s |
| 5 | **Nakama C++** | 3.1.0 | Shared lib (.so) | ✅ Prebuilt `.zip` | ~20s |

---

## Per-Library Details

### 1. miniaudio
- **Repository:** https://github.com/mackron/miniaudio
- **License:** Public domain / MIT
- **Status:** ✅ **Ready to go** — single header, no compilation needed
- **Usage:** `#define MINIAUDIO_IMPLEMENTATION` in exactly one `.cpp`

### 2. libsodium
- **Repository:** https://github.com/jedisct1/libsodium
- **License:** ISC
- **Prebuilt:** Available from Maven Central as `.aar` (contains `.so` for all ABIs)
- **Fallback:** Build from source using `dist-build/android-*.sh`
- **Output:** `libsodium/lib/<ABI>/libsodium.so`

### 3. ozz-animation
- **Repository:** https://github.com/guillaumeblanc/ozz-animation
- **License:** MIT
- **Build:** Uses CMake + Android NDK toolchain
- **Note:** Disable SIMD tests (`-DOZZ_BUILD_UNIT_TESTS=OFF`) for Android
- **Output:** `ozz-animation/lib/<ABI>/libozz_{animation,base,geometry}.so`

### 4. Filament (Google)
- **Repository:** https://github.com/google/filament
- **License:** Apache 2.0
- **Prebuilt:** Official GitHub releases include `filament-*-android-*.tar.gz`
- **Fallback:** Building from source requires ~30min and host-side GL libs
- **Output:** `filament/lib/<ABI>/libfilament{,-gltfio,-ibl}.so`

### 5. Nakama C++ Client
- **Repository:** https://github.com/heroiclabs/nakama-cpp
- **License:** Apache 2.0
- **Prebuilt:** Official GitHub releases include `nakama-cpp-*-android-*.zip`
- **Output:** `nakama-cpp/lib/<ABI>/libnakama-sdk.so`

---

## Directory Structure (after setup)

```
third_party/
├── miniaudio/
│   └── miniaudio.h           ← Single header, ~300KB
├── libsodium/
│   ├── include/sodium.h
│   └── lib/
│       ├── arm64-v8a/libsodium.so
│       ├── armeabi-v7a/libsodium.so
│       └── x86_64/libsodium.so
├── ozz-animation/
│   ├── include/ozz/
│   └── lib/
│       ├── arm64-v8a/libozz_animation.so
│       ├── armeabi-v7a/libozz_animation.so
│       └── x86_64/libozz_animation.so
├── filament/
│   ├── include/filament/
│   ├── include/gltfio/
│   └── lib/
│       ├── arm64-v8a/libfilament.so
│       ├── armeabi-v7a/libfilament.so
│       └── x86_64/libfilament.so
└── nakama-cpp/
    ├── include/nakama-cpp/
    └── lib/
        ├── arm64-v8a/libnakama-sdk.so
        ├── armeabi-v7a/libnakama-sdk.so
        └── x86_64/libnakama-sdk.so
```

## Troubleshooting

**"NDK not found"**
```bash
export ANDROID_NDK=$HOME/Android/Sdk/ndk/27.0.12077973
```

**"Filament prebuilt download failed"**
The stub renderer will be used. Filament is optional. To force source build:
```bash
rm -rf third_party/.filament_done
./scripts/setup_third_party.sh
```

**"libsodium AAR download failed"**
Stub encryption will be used. To force source build:
```bash
rm -rf third_party/.libsodium_done
./scripts/setup_third_party.sh
```
