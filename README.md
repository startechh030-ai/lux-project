# 🎮 Lux Engine

A C++ game engine framework for Android that hosts multiple mini-games.

> **Vision**: A "game of games" — instead of a single-genre title like Free Fire,
> this engine lets you switch between different game genres (racing, shooter,
> platformer, etc.) within one app.

## Architecture

```
lux-project/
├── CMakeLists.txt              # Top-level CMake (builds liblux_shared.so)
├── cmake/FindDependencies.cmake # Dependency discovery
│
├── lux/                         # C++ Core (.so)
│   ├── core/                    # App lifecycle, memory allocator, job queue
│   ├── renderer/                # Filament (Google PBR renderer)
│   ├── audio/                   # miniaudio (single-header audio)
│   ├── animation/               # ozz-animation (runtime skeletal)
│   ├── assets/                  # gltfio + hot reload
│   ├── encryption/              # libsodium (battle-tested crypto)
│   ├── networking/              # Nakama C++ client (auth, matchmaking)
│   ├── physics/                 # STUB — interface only (Jolt/PhysX TBD)
│   ├── game/                    # MiniGame base class + framework manager
│   └── platform/                # JNI bridge, Android input, Kotlin callbacks
│
├── mini_games/                  # Mini-games (compiled into lux or dynamic)
│   └── game_1_racing/           # "Speed Rush" — top-down arcade racer
│
├── android/                     # Android project (Kotlin)
│   └── app/src/main/java/com/lux/engine/
│       ├── MainMenuActivity.kt  # Game picker, Nakama auth, settings
│       ├── GameActivity.kt      # SurfaceView + JNI bridge to lux
│       └── HUDOverlay.kt        # Canvas overlay on top of Filament
│
└── .github/workflows/           # GitHub Actions CI
```

## Tech Stack

| Component     | Library              | Status     |
|---------------|----------------------|------------|
| Rendering     | Google Filament      | ✅ Wrapper  |
| Audio         | miniaudio            | ✅ Wrapper  |
| Animation     | ozz-animation        | ✅ Wrapper  |
| 3D Assets     | gltfio (Filament)    | ✅ Wrapper  |
| Encryption    | libsodium            | ✅ Wrapper  |
| Networking    | Nakama (Heroic Labs) | ✅ Wrapper  |
| Physics       | TBD (Jolt/PhysX)     | 🔲 Stub     |

## Building

### Prerequisites
- Android Studio Hedgehog (2023.1.1+) or later
- Android NDK 27+
- CMake 3.22+

### Via Android Studio
```bash
open android/
# File → Sync Project with Gradle Files
# Build → Make Project
```

### Via Command Line / GitHub Actions
```bash
# Build native .so for all ABIs
cmake -B build/arm64-v8a \
    -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Debug \
    -G Ninja
cmake --build build/arm64-v8a --target lux_shared --parallel

# Build APK
cd android && ./gradlew assembleDebug
```

## Adding a New Mini-Game

1. Create `mini_games/game_X_name/` with a class inheriting `MiniGame`
2. Use `LUX_REGISTER_GAME("name", YourGameClass)` to self-register
3. Add the source files to `mini_games/game_X_name/CMakeLists.txt`
4. Build — the game appears automatically in the MainMenuActivity list!

## Roadmap

- [x] Skeleton project structure & CMake build
- [x] Core subsystems (lifecycle, memory, jobs)
- [x] MiniGame framework & registry
- [x] Racing game skeleton
- [x] Android UI & JNI bridge
- [x] GitHub Actions CI
- [ ] Racing game: track rendering, physics, AI opponents
- [ ] Shooter mini-game (game_2_shooter)
- [ ] Physics integration (Jolt Physics)
- [ ] Dynamic loading of mini-games at runtime
- [ ] Nakama multiplayer support
# lux-project
