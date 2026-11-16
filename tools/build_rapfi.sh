#!/usr/bin/env bash
#
# Cross-compiles Rapfi (Gomoku/Renju engine) for Android arm64-v8a.
# Runs under Linux or WSL2. Rapfi builds with CMake (not `make`).
#
# Requirements:
#   - Android NDK (export ANDROID_NDK_HOME)
#   - cmake, ninja, git
#       Debian/Ubuntu:  sudo apt install cmake ninja-build git
#
# Result:
#   app/src/main/jniLibs/arm64-v8a/librapfi.so   ← binary (Android 10+ exec)
#   app/src/main/assets/engine/config.toml       ← config + NNUE weights
#   app/src/main/assets/engine/classical/
#   app/src/main/assets/engine/mix9svq/
#
# Usage:
#   export ANDROID_NDK_HOME=/path/to/android-ndk
#   ./tools/build_rapfi.sh

set -euo pipefail

NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to your Android NDK}"
RAPFI_DIR="${RAPFI_DIR:-/tmp/rapfi-src}"
API=26

if [ ! -d "$RAPFI_DIR" ]; then
    # --recursive also pulls the 'Networks' submodule with NNUE weights/config.
    git clone --recursive --depth 1 https://github.com/dotb1ue/rapfi "$RAPFI_DIR"
fi
cd "$RAPFI_DIR"

# Fix: don't link -lpthread on Android (bionic libc already provides pthread).
if ! grep -q 'CMAKE_SYSTEM_NAME STREQUAL "Android"' Rapfi/CMakeLists.txt; then
    sed -i 's|    if(WIN32)|    if(CMAKE_SYSTEM_NAME STREQUAL "Android")\n        # bionic libc already provides pthread; avoid -lpthread.\n        target_link_libraries(rapfi PRIVATE atomic)\n    elseif(WIN32)|' Rapfi/CMakeLists.txt
fi

TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"

cmake -S Rapfi -B build/android-arm64 -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM="android-$API" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DUSE_NEON=ON \
    -DUSE_NEON_DOTPROD=OFF \
    -DNO_COMMAND_MODULES=ON

cmake --build build/android-arm64

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# --- binary: disguised as a native library so Android 10+ allows exec() ---
JNI_DEST="$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI_DEST"
cp -f build/android-arm64/pbrain-rapfi "$JNI_DEST/librapfi.so"
echo "[OK] Binary copied to $JNI_DEST/librapfi.so"

# --- data files: config.toml + NNUE weights (copied at runtime by NativeExe) ---
ASSETS_DEST="$PROJECT_ROOT/app/src/main/assets/engine"
mkdir -p "$ASSETS_DEST"

if [ -f "$RAPFI_DIR/Networks/config-example/config.toml" ]; then
    cp -f "$RAPFI_DIR/Networks/config-example/config.toml" "$ASSETS_DEST/config.toml"
    # Fix paths: the Networks-submodule config uses flat filenames, but the
    # extracted asset tree has classical/ and mix9svq/ subdirectories.
    sed -i 's|binary_file = "model|binary_file = "classical/model|' "$ASSETS_DEST/config.toml"
    sed -i 's|weight_file\(_black\|_white\)\? = "mix9svq|weight_file\1 = "mix9svq/mix9svq|' "$ASSETS_DEST/config.toml"
    cp -rf "$RAPFI_DIR/Networks/classical" "$ASSETS_DEST/classical"
    cp -rf "$RAPFI_DIR/Networks/mix9svq" "$ASSETS_DEST/mix9svq"
    echo "[OK] NNUE weights + config copied to $ASSETS_DEST"
else
    echo "[WARN] Networks submodule not found; Rapfi will start without NNUE weights."
fi

# Remove any stale pbrain-rapfi left in assets/ from a previous incarnation.
rm -f "$ASSETS_DEST/pbrain-rapfi"

echo
echo "Done. Rebuild the APK (Build → Build APK) to package the engine."