# =============================================================================
# Lux Game Engine - Top-Level CMake Configuration
# Target: Android NDK (arm64-v8a, armeabi-v7a, x86_64)
# =============================================================================
cmake_minimum_required(VERSION 3.22)
project(lux_engine
    VERSION 0.1.0
    DESCRIPTION "Lux Multi-Game Engine Framework"
    LANGUAGES C CXX
)

# ── Android NDK settings ──────────────────────────────────────────────────
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ── Options ────────────────────────────────────────────────────────────────
option(LUX_BUILD_TESTS       "Build unit tests"                   OFF)
option(LUX_ENABLE_HOT_RELOAD "Enable asset hot-reloading"         ON)
option(LUX_USE_FILAMENT     "Use Google Filament renderer"        ON)
option(LUX_USE_MINIAUDIO    "Use miniaudio for audio"             ON)
option(LUX_USE_OZZ          "Use ozz-animation"                   ON)
option(LUX_USE_SODIUM       "Use libsodium for encryption"        ON)
option(LUX_USE_NAKAMA       "Use Nakama client for networking"    ON)

# ── Dependencies ───────────────────────────────────────────────────────────
include(cmake/FindDependencies.cmake)

# ── Subdirectories ─────────────────────────────────────────────────────────
add_subdirectory(lux)

# Mini-games register their sources into lux_shared via their own CMakeLists
add_subdirectory(mini_games)

if(LUX_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()
