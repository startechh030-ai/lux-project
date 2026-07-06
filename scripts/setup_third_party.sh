#!/usr/bin/env bash
# =============================================================================
# Lux Engine — Third-Party Dependencies Setup
#
# This script downloads and (if needed) builds all third-party libraries
# for Android NDK cross-compilation.
#
# Usage:
#   ./scripts/setup_third_party.sh              # Build for all ABIs (Debug)
#   ./scripts/setup_third_party.sh Release      # Build for all ABIs (Release)
#   ./scripts/setup_third_party.sh Debug arm64-v8a  # Single ABI (Debug)
#
# Prerequisites:
#   - Android NDK 27+ installed (set ANDROID_NDK env var or pass --ndk-path)
#   - CMake 3.22+, Ninja, make, curl, unzip
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
THIRD_PARTY_DIR="$PROJECT_DIR/third_party"
BUILD_TYPE="${1:-Debug}"
ABIS="${2:-arm64-v8a armeabi-v7a x86_64}"
ANDROID_API=26
JOBS=$(nproc 2>/dev/null || echo 4)

# ── Colors ─────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*"; }

# ── Android NDK Detection ──────────────────────────────────────────────────
detect_ndk() {
    if [ -n "${ANDROID_NDK:-}" ] && [ -d "$ANDROID_NDK" ]; then
        NDK_PATH="$ANDROID_NDK"
    elif [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
        NDK_PATH="$ANDROID_NDK_HOME"
    elif [ -d "$ANDROID_HOME/ndk" ]; then
        NDK_PATH=$(ls -d "$ANDROID_HOME/ndk"/* 2>/dev/null | sort -V | tail -1) || true
    elif [ -d "$HOME/Android/Sdk/ndk" ]; then
        NDK_PATH=$(ls -d "$HOME/Android/Sdk/ndk"/* 2>/dev/null | sort -V | tail -1) || true
    fi

    if [ -z "${NDK_PATH:-}" ] || [ ! -d "$NDK_PATH" ]; then
        err "Android NDK not found!"
        err "Set ANDROID_NDK environment variable to your NDK path"
        err "  export ANDROID_NDK=\$HOME/Android/Sdk/ndk/27.0.12077973"
        exit 1
    fi

    TOOLCHAIN="$NDK_PATH/build/cmake/android.toolchain.cmake"
    if [ ! -f "$TOOLCHAIN" ]; then
        err "CMake toolchain not found at: $TOOLCHAIN"
        exit 1
    fi

    info "Using NDK: $NDK_PATH"
    ok "CMake toolchain: $TOOLCHAIN"
}

# ── Build one ABI ──────────────────────────────────────────────────────────
cmake_android() {
    local src="$1" build="$2" lib="$3"
    shift 3
    cmake -S "$src" -B "$build" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=android-$ANDROID_API \
        -DANDROID_STL=c++_shared \
        -G Ninja \
        "$@"
    cmake --build "$build" --target "$lib" --parallel "$JOBS"
}

# ══════════════════════════════════════════════════════════════════════════
#  1. miniaudio (single header — just download)
# ══════════════════════════════════════════════════════════════════════════
setup_miniaudio() {
    local dir="$THIRD_PARTY_DIR/miniaudio"
    local header="$dir/miniaudio.h"
    local version="0.11.21"

    if [ -f "$header" ] && [ "$(wc -c < "$header")" -gt 100000 ]; then
        ok "miniaudio $version already downloaded ($(wc -c < "$header") bytes)"
        return
    fi

    mkdir -p "$dir"
    info "Downloading miniaudio v$version..."
    curl -fsSL "https://github.com/mackron/miniaudio/raw/refs/tags/$version/miniaudio.h" \
        -o "$header" || {
        # Fallback: try master
        warn "Tag not found, trying master..."
        curl -fsSL "https://raw.githubusercontent.com/mackron/miniaudio/master/miniaudio.h" \
            -o "$header"
    }

    if [ -f "$header" ] && [ "$(wc -c < "$header")" -gt 100000 ]; then
        ok "miniaudio v$version — $(wc -c < "$header") bytes"
    else
        err "miniaudio download failed"
        exit 1
    fi
}

# ══════════════════════════════════════════════════════════════════════════
#  2. libsodium (build from source with NDK)
# ══════════════════════════════════════════════════════════════════════════
setup_libsodium() {
    local version="1.0.20"
    local src_dir="$THIRD_PARTY_DIR/libsodium-src"
    local out_dir="$THIRD_PARTY_DIR/libsodium"
    local tag="1.0.20-stable"

    # Check if already built
    local all_done=true
    for abi in $ABIS; do
        if [ ! -f "$out_dir/lib/$abi/libsodium.so" ]; then
            all_done=false; break
        fi
    done
    if [ "$all_done" = true ]; then
        ok "libsodium already built for all ABIs"
        return
    fi

    # Clone or update source
    if [ ! -d "$src_dir/.git" ]; then
        info "Cloning libsodium v$version..."
        git clone --depth=1 --branch "$tag" \
            "https://github.com/jedisct1/libsodium.git" "$src_dir"
    else
        info "libsodium source already present"
    fi

    # Build for each ABI
    for ABI in $ABIS; do
        local build_dir="$src_dir/build/$ABI"
        local install_dir="$out_dir"

        info "Building libsodium for $ABI..."

        # libsodium has its own Android build script
        pushd "$src_dir" > /dev/null
        # Determine arch for libsodium's script
        local arch
        case "$ABI" in
            arm64-v8a)  arch="arm64";;
            armeabi-v7a) arch="arm";;
            x86_64)     arch="x86_64";;
            x86)        arch="x86";;
        esac

        export ANDROID_NDK_HOME="$NDK_PATH"
        # Use libsodium's dist-build for Android
        if [ -f "dist-build/android-$arch.sh" ]; then
            bash "dist-build/android-$arch.sh" || {
                warn "libsodium dist-build for $ABI failed, trying CMake..."
                cmake_android_fallback
            }
            # Copy output
            mkdir -p "$out_dir/lib/$ABI" "$out_dir/include"
            cp -a "libsodium-android-$arch/lib/"*.so "$out_dir/lib/$ABI/" 2>/dev/null || true
            cp -a "libsodium-android-$arch/include/." "$out_dir/include/" 2>/dev/null || true
        else
            cmake_android_fallback
        fi
        popd > /dev/null
    done

    # Verify
    for ABI in $ABIS; do
        if [ -f "$out_dir/lib/$ABI/libsodium.so" ]; then
            ok "libsodium $ABI — $(wc -c < "$out_dir/lib/$ABI/libsodium.so") bytes"
        else
            warn "libsodium $ABI — NOT BUILT"
        fi
    done
}

# Fallback CMake build for libsodium
cmake_android_fallback() {
    local build_dir="$src_dir/build/cmake-build-$ABI"
    cmake -S "$src_dir" -B "$build_dir" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=android-$ANDROID_API \
        -DANDROID_STL=c++_shared \
        -DBUILD_SHARED_LIBS=ON \
        -DSODIUM_BUILD_TESTS=OFF \
        -G Ninja \
        -DCMAKE_INSTALL_PREFIX="$out_dir"
    cmake --build "$build_dir" --parallel "$JOBS"
    cmake --install "$build_dir"
}

# ══════════════════════════════════════════════════════════════════════════
#  3. ozz-animation (build from source with NDK)
# ══════════════════════════════════════════════════════════════════════════
setup_ozz() {
    local version="0.16.0"
    local src_dir="$THIRD_PARTY_DIR/ozz-animation-src"
    local out_dir="$THIRD_PARTY_DIR/ozz-animation"
    local tag="v$version"

    local all_done=true
    for abi in $ABIS; do
        for lib in libozz_animation.so libozz_base.so libozz_geometry.so; do
            if [ ! -f "$out_dir/lib/$abi/$lib" ]; then
                all_done=false; break 2
            fi
        done
    done
    if [ "$all_done" = true ]; then
        ok "ozz-animation already built for all ABIs"
        return
    fi

    if [ ! -d "$src_dir/.git" ]; then
        info "Cloning ozz-animation $tag..."
        git clone --depth=1 --branch "$tag" \
            "https://github.com/guillaumeblanc/ozz-animation.git" "$src_dir"
    fi

    for ABI in $ABIS; do
        info "Building ozz-animation for $ABI..."
        local build_dir="$src_dir/build/$ABI"
        local install_dir="$out_dir"

        cmake -S "$src_dir" -B "$build_dir" \
            -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
            -DANDROID_ABI="$ABI" \
            -DANDROID_PLATFORM=android-$ANDROID_API \
            -DANDROID_STL=c++_shared \
            -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
            -DOZZ_BUILD_SIMD=ON \
            -DOZZ_BUILD_UNIT_TESTS=OFF \
            -DOZZ_BUILD_SAMPLES=OFF \
            -DOZZ_BUILD_DATA=OFF \
            -G Ninja \
            -DCMAKE_INSTALL_PREFIX="$install_dir"

        cmake --build "$build_dir" --parallel "$JOBS"
        cmake --install "$build_dir"
    done

    # Verify
    for ABI in $ABIS; do
        if [ -f "$out_dir/lib/$ABI/libozz_animation.so" ]; then
            ok "ozz-animation $ABI — built"
        else
            warn "ozz-animation $ABI — NOT BUILT"
        fi
    done
}

# ══════════════════════════════════════════════════════════════════════════
#  4. Filament (download prebuilt Android binaries)
# ══════════════════════════════════════════════════════════════════════════
setup_filament() {
    local version="1.53.2"
    local out_dir="$THIRD_PARTY_DIR/filament"

    # Check if already downloaded
    local all_done=true
    for abi in $ABIS; do
        if [ ! -f "$out_dir/lib/$abi/libfilament.so" ]; then
            all_done=false; break
        fi
    done
    if [ "$all_done" = true ]; then
        ok "Filament $version already downloaded for all ABIs"
        return
    fi

    mkdir -p "$out_dir"

    for ABI in $ABIS; do
        # Map ABI to Filament's naming
        local fabi
        case "$ABI" in
            arm64-v8a)  fabi="aarch64";;
            armeabi-v7a) fabi="armv7";;
            x86_64)     fabi="x86_64";;
            x86)        fabi="x86";;
        esac

        local archive="filament-$version-android-$fabi.tar.gz"
        local url="https://github.com/google/filament/releases/download/v$version/$archive"

        info "Downloading Filament $version for $ABI ($fabi)..."

        curl -fsSL "$url" -o "/tmp/$archive" || {
            warn "Failed to download $archive, trying alternative URL..."
            # Try the unified tarball approach
            continue
        }

        mkdir -p "$out_dir/lib/$ABI" "$out_dir/include"
        tar -xzf "/tmp/$archive" -C "/tmp/filament-extract-$ABI" 2>/dev/null || {
            # Try direct extraction
            tar -xzf "/tmp/$archive" -C "$out_dir" --strip-components=1 2>/dev/null || true
        }

        # Find and copy .so files
        find "/tmp/filament-extract-$ABI" -name '*.so' -exec cp {} "$out_dir/lib/$ABI/" \; 2>/dev/null || true
        find "/tmp/filament-extract-$ABI" -name '*.h' -exec cp --parents {} "$out_dir/include/" \; 2>/dev/null || true
        # Try direct extraction path
        if [ -d "$out_dir/lib/$fabi" ]; then
            mv "$out_dir/lib/$fabi"/*.so "$out_dir/lib/$ABI/" 2>/dev/null || true
            rm -rf "$out_dir/lib/$fabi"
        fi

        rm -f "/tmp/$archive"
    done

    # If the official release failed, build from source
    if ! ls "$out_dir/lib/arm64-v8a/"libfilament*.so 1>/dev/null 2>&1; then
        warn "Filament prebuilts not available — building from source (this takes a while)"
        setup_filament_from_source
    fi

    # Verify
    for ABI in $ABIS; do
        if ls "$out_dir/lib/$ABI/"libfilament*.so 1>/dev/null 2>&1; then
            ok "Filament $ABI — present"
        else
            warn "Filament $ABI — NOT AVAILABLE"
        fi
    done
}

setup_filament_from_source() {
    local src_dir="$THIRD_PARTY_DIR/filament-src"
    if [ ! -d "$src_dir/.git" ]; then
        info "Cloning Filament source..."
        git clone --depth=1 --branch "v1.53.2" \
            "https://github.com/google/filament.git" "$src_dir"
    fi

    for ABI in $ABIS; do
        info "Building Filament for $ABI (this WILL take a while)..."
        cd "$src_dir"
        # Filament has its own build script
        ./build.sh -p android -a "$ABI" -c "$BUILD_TYPE" || {
            warn "Filament build for $ABI failed — check Filament build prerequisites"
            warn "You may need: 'sudo apt install libgl1-mesa-dev libxi-dev libxrandr-dev'"
        }
        # Copy outputs
        mkdir -p "$out_dir/lib/$ABI"
        cp "out/android-$ABI-$BUILD_TYPE/filament/lib/"*.so "$out_dir/lib/$ABI/" 2>/dev/null || true
        cp -r "out/android-$ABI-$BUILD_TYPE/filament/include/." "$out_dir/include/" 2>/dev/null || true
    done
}

# ══════════════════════════════════════════════════════════════════════════
#  5. Nakama C++ Client (download prebuilt + source headers)
# ══════════════════════════════════════════════════════════════════════════
setup_nakama() {
    local version="3.1.0"
    local out_dir="$THIRD_PARTY_DIR/nakama-cpp"
    local src_dir="$THIRD_PARTY_DIR/nakama-cpp-src"

    # Check if already set up
    if [ -f "$out_dir/include/nakama-cpp/nakama.h" ] && \
       ls "$out_dir/lib/arm64-v8a/"libnakama-sdk.so 1>/dev/null 2>&1; then
        ok "Nakama SDK already set up"
        return
    fi

    mkdir -p "$out_dir/lib" "$out_dir/include"

    # Clone source for headers (always needed)
    if [ ! -d "$src_dir/.git" ]; then
        info "Cloning Nakama C++ client v$version..."
        git clone --depth=1 --branch "v$version" \
            "https://github.com/heroiclabs/nakama-cpp.git" "$src_dir"
    fi

    # Copy headers from source
    cp -r "$src_dir/include/"* "$out_dir/include/" 2>/dev/null || true
    ok "Nakama headers — copied"

    # Download prebuilt binaries
    for ABI in $ABIS; do
        local fabi
        case "$ABI" in
            arm64-v8a)  fabi="arm64-v8a";;
            armeabi-v7a) fabi="armeabi-v7a";;
            x86_64)     fabi="x86_64";;
        esac

        local archive="nakama-cpp-$version-android-$fabi.zip"
        local url="https://github.com/heroiclabs/nakama-cpp/releases/download/v$version/$archive"

        info "Downloading Nakama SDK for $ABI..."
        mkdir -p "$out_dir/lib/$ABI"

        curl -fsSL "$url" -o "/tmp/$archive" && {
            unzip -o "/tmp/$archive" -d "/tmp/nakama-extract" 2>/dev/null
            find "/tmp/nakama-extract" -name '*.so' -exec cp {} "$out_dir/lib/$ABI/" \; 2>/dev/null || true
            rm -f "/tmp/$archive"
        } || {
            warn "Prebuilt Nakama SDK not available for $ABI — building from source..."
            build_nakama_from_source "$ABI"
        }
    done

    # Verify
    for ABI in $ABIS; do
        if ls "$out_dir/lib/$ABI/"libnakama-sdk.so 1>/dev/null 2>&1; then
            ok "Nakama SDK $ABI — present"
        else
            warn "Nakama SDK $ABI — NOT AVAILABLE (stub networking will be used)"
        fi
    done
}

build_nakama_from_source() {
    local abi="$1"
    local build_dir="$src_dir/build/$abi"

    info "Building Nakama SDK from source for $abi..."

    cmake -S "$src_dir" -B "$build_dir" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM=android-$ANDROID_API \
        -DANDROID_STL=c++_shared \
        -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
        -DNAKAMA_SDK_BUILD_TESTS=OFF \
        -DNAKAMA_SDK_BUILD_EXAMPLES=OFF \
        -G Ninja

    cmake --build "$build_dir" --parallel "$JOBS"
    cmake --install "$build_dir" --prefix "$out_dir"
}

# ══════════════════════════════════════════════════════════════════════════
#  Main
# ══════════════════════════════════════════════════════════════════════════
main() {
    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║         Lux Engine — Third-Party Dependencies Setup         ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo "  Build type: $BUILD_TYPE"
    echo "  ABIs:       $ABIS"
    echo "  Output:     $THIRD_PARTY_DIR"
    echo ""

    detect_ndk

    # Ensure third_party dir exists
    mkdir -p "$THIRD_PARTY_DIR"

    # Step 1: miniaudio (quick — just download a header)
    echo ""
    echo "─── Step 1/5: miniaudio ──────────────────────────────────────"
    setup_miniaudio

    # Step 2: libsodium
    echo ""
    echo "─── Step 2/5: libsodium ──────────────────────────────────────"
    setup_libsodium

    # Step 3: ozz-animation
    echo ""
    echo "─── Step 3/5: ozz-animation ──────────────────────────────────"
    setup_ozz

    # Step 4: Filament (prebuilt or source)
    echo ""
    echo "─── Step 4/5: Filament ───────────────────────────────────────"
    setup_filament

    # Step 5: Nakama C++ client
    echo ""
    echo "─── Step 5/5: Nakama C++ Client ──────────────────────────────"
    setup_nakama

    # ── Summary ───────────────────────────────────────────────────────
    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║                     Setup Complete!                         ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""
    echo "Third-party directory: $THIRD_PARTY_DIR"
    echo ""
    echo "To build the engine:"
    echo "  cmake -B build/arm64-v8a \\"
    echo "    -DCMAKE_TOOLCHAIN_FILE=\"\$ANDROID_NDK/build/cmake/android.toolchain.cmake\" \\"
    echo "    -DANDROID_ABI=arm64-v8a \\"
    echo "    -DANDROID_PLATFORM=android-$ANDROID_API \\"
    echo "    -DCMAKE_BUILD_TYPE=$BUILD_TYPE \\"
    echo "    -G Ninja"
    echo "  cmake --build build/arm64-v8a --target lux_shared --parallel $JOBS"
    echo ""

    # Show sizes
    echo "─── Library Sizes ────────────────────────────────────────────"
    for abi in $ABIS; do
        echo "  [$abi]"
        for lib in "$THIRD_PARTY_DIR"/{filament,libsodium,ozz-animation,nakama-cpp}/lib/$abi/*.so; do
            if [ -f "$lib" ]; then
                size=$(wc -c < "$lib")
                name=$(basename "$lib")
                printf "    %-30s %8d bytes\n" "$name" "$size"
            fi
        done
    done
}

main "$@"
