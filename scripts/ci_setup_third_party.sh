#!/usr/bin/env bash
# =============================================================================
# Lux Engine — Third-Party Setup (CI-optimized)
#
# Lightning-fast version for GitHub Actions. Downloads prebuilts when
# available, only builds from source as fallback.
#
# Usage: ./scripts/ci_setup_third_party.sh [Debug|Release]
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
THIRD_PARTY_DIR="$PROJECT_DIR/third_party"
BUILD_TYPE="${1:-Debug}"
ABIS="${2:-arm64-v8a armeabi-v7a x86_64}"
JOBS=$(nproc 2>/dev/null || echo 4)

info()  { echo -e "\033[0;36m[INFO]\033[0m  $*"; }
ok()    { echo -e "\033[0;32m[OK]\033[0m    $*"; }
warn()  { echo -e "\033[1;33m[WARN]\033[0m  $*"; }

mkdir -p "$THIRD_PARTY_DIR"

# ══════════════════════════════════════════════════════════════════════════
# 1. miniaudio — single header download
# ══════════════════════════════════════════════════════════════════════════
setup_miniaudio() {
    local dir="$THIRD_PARTY_DIR/miniaudio"
    local header="$dir/miniaudio.h"

    if [ -f "$header" ] && [ "$(wc -c < "$header")" -gt 100000 ]; then
        ok "miniaudio already present"; return
    fi

    mkdir -p "$dir"
    info "Downloading miniaudio..."
    curl -fsSL "https://raw.githubusercontent.com/mackron/miniaudio/master/miniaudio.h" -o "$header"
    ok "miniaudio — $(wc -c < "$header") bytes"
}

# ══════════════════════════════════════════════════════════════════════════
# 2. libsodium — download prebuilt
# ══════════════════════════════════════════════════════════════════════════
setup_libsodium() {
    local version="1.0.20"
    local out_dir="$THIRD_PARTY_DIR/libsodium"

    # Check cache
    local cache_key="$THIRD_PARTY_DIR/.libsodium_done"
    if [ -f "$cache_key" ]; then ok "libsodium already set up"; return; fi

    mkdir -p "$out_dir"

    for ABI in $ABIS; do
        local fabi; local host_arch
        case "$ABI" in
            arm64-v8a)  fabi="arm64-v8a"; host_arch="armv8-a";;
            armeabi-v7a) fabi="armeabi-v7a"; host_arch="armv7-a";;
            x86_64)     fabi="x86_64"; host_arch="x86_64";;
        esac

        mkdir -p "$out_dir/lib/$ABI" "$out_dir/include"

        # Try the libsodium-android prebuilt repo
        local jar_url="https://repo1.maven.org/maven2/org/libsodium/libsodium-android/$version/libsodium-android-$version.aar"
        local aar_file="/tmp/libsodium-$ABI.aar"

        info "Fetching libsodium prebuilt for $ABI..."
        if curl -fsSL -o "$aar_file" "$jar_url" 2>/dev/null; then
            # AAR is a ZIP — extract the .so
            unzip -o "$aar_file" "jni/$ABI/*" -d "/tmp/libsodium-extract" 2>/dev/null || true
            if [ -f "/tmp/libsodium-extract/jni/$ABI/libsodium.so" ]; then
                cp "/tmp/libsodium-extract/jni/$ABI/libsodium.so" "$out_dir/lib/$ABI/"
                ok "libsodium $ABI — from AAR ($(wc -c < "$out_dir/lib/$ABI/libsodium.so") bytes)"
            fi
        fi

        # If AAR failed, try direct download from GitHub
        if [ ! -f "$out_dir/lib/$ABI/libsodium.so" ]; then
            local gh_url="https://github.com/jedisct1/libsodium/releases/download/$version-stable/libsodium-android-$fabi.tar.gz"
            info "Trying GitHub release for $ABI..."
            curl -fsSL -o "/tmp/libsodium-$ABI.tar.gz" "$gh_url" 2>/dev/null && {
                tar -xzf "/tmp/libsodium-$ABI.tar.gz" -C "/tmp/libsodium-gh" 2>/dev/null || true
                find "/tmp/libsodium-gh" -name "libsodium.so" -path "*$fabi*" -exec cp {} "$out_dir/lib/$ABI/" \; 2>/dev/null || true
            }
        fi

        # If we got headers yet?
        if [ ! -f "$out_dir/include/sodium.h" ]; then
            # Download headers from GitHub
            curl -fsSL "https://raw.githubusercontent.com/jedisct1/libsodium/1.0.20-stable/src/libsodium/include/sodium.h" \
                -o "$out_dir/include/sodium.h" 2>/dev/null || true
        fi
    done

    touch "$cache_key"
    ok "libsodium setup complete"
}

# ══════════════════════════════════════════════════════════════════════════
# 3. ozz-animation — build from source (light CMake lib)
# ══════════════════════════════════════════════════════════════════════════
setup_ozz() {
    local version="0.16.0"
    local src_dir="$THIRD_PARTY_DIR/ozz-animation-src"
    local out_dir="$THIRD_PARTY_DIR/ozz-animation"
    local cache_key="$THIRD_PARTY_DIR/.ozz_done"

    if [ -f "$cache_key" ]; then ok "ozz-animation already built"; return; fi

    if [ ! -d "$src_dir/.git" ]; then
        info "Cloning ozz-animation v$version..."
        git clone --depth=1 --branch "v$version" \
            "https://github.com/guillaumeblanc/ozz-animation.git" "$src_dir"
    fi

    # Detect NDK (CI provides ANDROID_NDK_HOME)
    local ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
    if [ -z "$ndk" ]; then
        warn "NDK not set, skipping ozz build (stub animation will be used)"
        return
    fi
    local tc="$ndk/build/cmake/android.toolchain.cmake"

    for ABI in $ABIS; do
        local build_dir="$src_dir/build/$ABI"
        info "Building ozz-animation for $ABI..."

        cmake -S "$src_dir" -B "$build_dir" \
            -DCMAKE_TOOLCHAIN_FILE="$tc" \
            -DANDROID_ABI="$ABI" \
            -DANDROID_PLATFORM=android-26 \
            -DANDROID_STL=c++_shared \
            -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
            -DOZZ_BUILD_SIMD=ON \
            -DOZZ_BUILD_UNIT_TESTS=OFF \
            -DOZZ_BUILD_SAMPLES=OFF \
            -DOZZ_BUILD_DATA=OFF \
            -G Ninja \
            -DCMAKE_INSTALL_PREFIX="$out_dir"

        cmake --build "$build_dir" --parallel "$JOBS"
        cmake --install "$build_dir" 2>/dev/null || {
            # Manual install
            mkdir -p "$out_dir/lib/$ABI" "$out_dir/include"
            find "$build_dir" -name '*.so' -exec cp {} "$out_dir/lib/$ABI/" \;
            cp -r "$src_dir/include/"* "$out_dir/include/"
        }
        ok "ozz-animation $ABI — built"
    done

    touch "$cache_key"
}

# ══════════════════════════════════════════════════════════════════════════
# 4. Filament — download prebuilt
# ══════════════════════════════════════════════════════════════════════════
setup_filament() {
    local version="1.53.2"
    local out_dir="$THIRD_PARTY_DIR/filament"
    local cache_key="$THIRD_PARTY_DIR/.filament_done"

    if [ -f "$cache_key" ]; then ok "Filament already downloaded"; return; fi

    mkdir -p "$out_dir"

    # Map our ABIs to Filament arch names
    for ABI in $ABIS; do
        local fabi
        case "$ABI" in
            arm64-v8a)  fabi="aarch64";;
            armeabi-v7a) fabi="armv7";;
            x86_64)     fabi="x86_64";;
        esac

        local filename="filament-$version-android-$fabi.tar.gz"
        local url="https://github.com/google/filament/releases/download/v$version/$filename"
        local target_dir="$out_dir/lib/$ABI"

        mkdir -p "$target_dir"
        info "Downloading Filament $ABI..."

        if curl -fsSL -o "/tmp/$filename" "$url"; then
            # Create temp dir for extraction
            local tmp_dir="/tmp/filament-$ABI-extract"
            rm -rf "$tmp_dir" && mkdir -p "$tmp_dir"

            tar -xzf "/tmp/$filename" -C "$tmp_dir"

            # Extract .so files
            find "$tmp_dir" -name '*.so' -exec cp {} "$target_dir/" \; 2>/dev/null || true

            # Extract headers
            find "$tmp_dir" -name '*.h' -exec cp {} "$out_dir/include/" \; 2>/dev/null || true
            # Also include dirs
            if [ -d "$tmp_dir/include" ]; then
                cp -r "$tmp_dir/include/"* "$out_dir/include/" 2>/dev/null || true
            fi

            ok "Filament $ABI — downloaded & extracted"
            rm -f "/tmp/$filename"
            rm -rf "$tmp_dir"
        else
            warn "Filament prebuilt not found for $ABI — stub renderer will be used"
        fi
    done

    touch "$cache_key"
}

# ══════════════════════════════════════════════════════════════════════════
# 5. Nakama — download prebuilt
# ══════════════════════════════════════════════════════════════════════════
setup_nakama() {
    local version="3.1.0"
    local out_dir="$THIRD_PARTY_DIR/nakama-cpp"
    local src_dir="$THIRD_PARTY_DIR/nakama-cpp-src"
    local cache_key="$THIRD_PARTY_DIR/.nakama_done"

    if [ -f "$cache_key" ]; then ok "Nakama SDK already set up"; return; fi

    mkdir -p "$out_dir/lib" "$out_dir/include"

    # Clone source for headers (small — just headers)
    if [ ! -d "$src_dir/.git" ]; then
        info "Cloning Nakama C++ client v$version (headers only)..."
        git clone --depth=1 --branch "v$version" \
            "https://github.com/heroiclabs/nakama-cpp.git" "$src_dir"
    fi

    # Copy headers
    cp -r "$src_dir/include/"* "$out_dir/include/" 2>/dev/null || true
    ok "Nakama headers — copied"

    # Download prebuilt binaries
    for ABI in $ABIS; do
        local target_dir="$out_dir/lib/$ABI"
        mkdir -p "$target_dir"

        local filename="nakama-cpp-$version-android-$ABI.zip"
        local url="https://github.com/heroiclabs/nakama-cpp/releases/download/v$version/$filename"

        info "Downloading Nakama SDK $ABI..."
        if curl -fsSL -o "/tmp/$filename" "$url"; then
            unzip -o "/tmp/$filename" -d "/tmp/nakama-extract" 2>/dev/null
            find "/tmp/nakama-extract" -name '*.so' -exec cp {} "$target_dir/" \; 2>/dev/null || true
            ok "Nakama SDK $ABI — downloaded"
            rm -f "/tmp/$filename"
        else
            warn "Nakama prebuilt not found for $ABI — stub networking will be used"
        fi
    done

    touch "$cache_key"
}

# ══════════════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════════════
echo ""
echo "═══ Lux Engine — CI Third-Party Setup ═══"
echo "  Build type: $BUILD_TYPE"
echo "  ABIs:       $ABIS"
echo ""

setup_miniaudio
setup_libsodium
setup_ozz
setup_filament
setup_nakama

# Summary
echo ""
echo "═══ Setup Complete ═══"
echo "Third-party directory: $THIRD_PARTY_DIR"
echo ""
echo "tree -L 3 $THIRD_PARTY_DIR (when available):"
find "$THIRD_PARTY_DIR" -type f -name '*.so' -o -name '*.h' -o -name '*.hpp' | sort | head -40
echo ""

# Count what we got
echo "Library status:"
for lib in miniaudio libsodium ozz-animation filament nakama-cpp; do
    found=$(find "$THIRD_PARTY_DIR/$lib" -name '*.so' 2>/dev/null | wc -l)
    headers=$(find "$THIRD_PARTY_DIR/$lib" -name '*.h' -o -name '*.hpp' 2>/dev/null | wc -l)
    echo "  $lib: $found .so files, $headers header files"
done
