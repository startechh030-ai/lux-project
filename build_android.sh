#!/usr/bin/env bash
# =============================================================================
# Lux Engine — One-command Android Build
#
# Usage:
#   ./build_android.sh                    # Debug build for all ABIs
#   ./build_android.sh Release arm64-v8a  # Release build for arm64-v8a only
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

BUILD_TYPE="${1:-Debug}"
ABI="${2:-all}"
JOBS=$(nproc 2>/dev/null || echo 4)

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*"; }

# ── Check NDK ──────────────────────────────────────────────────────────────
if [ -z "${ANDROID_NDK:-}" ] && [ -z "${ANDROID_NDK_HOME:-}" ]; then
    # Try common locations
    for dir in "$HOME/Android/Sdk/ndk" "$ANDROID_HOME/ndk"; do
        if [ -d "$dir" ]; then
            export ANDROID_NDK=$(ls -d "$dir"/* 2>/dev/null | sort -V | tail -1)
            break
        fi
    done
fi

if [ -z "${ANDROID_NDK:-}" ]; then
    err "Android NDK not found!"
    err "Set: export ANDROID_NDK=\$HOME/Android/Sdk/ndk/27.0.12077973"
    exit 1
fi

TOOLCHAIN="$ANDROID_NDK/build/cmake/android.toolchain.cmake"
if [ ! -f "$TOOLCHAIN" ]; then
    err "CMake toolchain not found at: $TOOLCHAIN"
    exit 1
fi
ok "Using NDK: $ANDROID_NDK"

# ── Step 1: Setup third-party libraries ────────────────────────────────────
echo ""
info "═══ Step 1/3: Third-party dependencies ═══"
if [ ! -f "$SCRIPT_DIR/third_party/miniaudio/miniaudio.h" ]; then
    bash "$SCRIPT_DIR/scripts/ci_setup_third_party.sh" "$BUILD_TYPE"
else
    ok "Third-party libs already set up"
fi

# ── Step 2: Build native .so via CMake ─────────────────────────────────────
echo ""
info "═══ Step 2/3: Native .so build ═══"

# Export for FindDependencies.cmake
export ANDROID_NDK

build_abi() {
    local abi="$1"
    local build_dir="$SCRIPT_DIR/build/$abi"

    info "Configuring CMake for $abi ($BUILD_TYPE)..."
    cmake -B "$build_dir" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM=android-26 \
        -DANDROID_STL=c++_shared \
        -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
        -DLUX_BUILD_TESTS=OFF \
        -G Ninja \
        -DCMAKE_INSTALL_PREFIX="$build_dir/install"

    info "Building for $abi..."
    cmake --build "$build_dir" --target lux_shared --parallel "$JOBS"

    # Copy to JNI libs
    local jni_dir="$SCRIPT_DIR/android/app/src/main/jniLibs/$abi"
    mkdir -p "$jni_dir"
    cp "$build_dir/lux/liblux_shared.so" "$jni_dir/"

    # Also copy third-party .so files needed at runtime
    for lib_dir in "$SCRIPT_DIR/third_party"/*/lib/$abi; do
        if [ -d "$lib_dir" ]; then
            cp "$lib_dir"/*.so "$jni_dir/" 2>/dev/null || true
        fi
    done

    ok "$abi — built: $(wc -c < "$jni_dir/liblux_shared.so") bytes"
}

if [ "$ABI" = "all" ]; then
    for abi in arm64-v8a armeabi-v7a x86_64; do
        build_abi "$abi"
    done
else
    build_abi "$ABI"
fi

# ── Step 3: Build Android APK ──────────────────────────────────────────────
echo ""
info "═══ Step 3/3: Android APK ═══"

if command -v java &>/dev/null; then
    cd "$SCRIPT_DIR/android"
    if [ ! -f "gradlew" ]; then
        # Generate Gradle wrapper
        info "Generating Gradle wrapper..."
        gradle wrapper --gradle-version 8.5 2>/dev/null || {
            # Manual wrapper download
            curl -fsSL "https://services.gradle.org/distributions/gradle-8.5-bin.zip" \
                -o /tmp/gradle-wrapper.zip 2>/dev/null || true
        }
    fi

    if [ -f "gradlew" ]; then
        chmod +x gradlew
        ./gradlew assembleDebug
        ok "APK built: $(find app/build/outputs/apk -name '*.apk' 2>/dev/null | head -3)"
    else
        warn "Gradle wrapper not found. Build APK manually: cd android && ./gradlew assembleDebug"
    fi
    cd "$SCRIPT_DIR"
else
    warn "Java not found — skipping APK packaging"
fi

# ── Done ───────────────────────────────────────────────────────────────────
echo ""
ok "═══ Build complete! ═══"
echo ""
echo "Native libs:"
find "$SCRIPT_DIR/android/app/src/main/jniLibs" -name '*.so' -exec ls -lh {} \; 2>/dev/null || echo "(no .so files yet)"
echo ""
echo "APK:"
ls -lh android/app/build/outputs/apk/debug/*.apk 2>/dev/null || echo "(run ./gradlew assembleDebug in android/ to generate)"
