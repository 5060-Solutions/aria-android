#!/bin/bash
# Build aria-mobile-core for Android targets and generate UniFFI Kotlin bindings.
#
# Prerequisites:
#   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
#   Install Android NDK and set ANDROID_NDK_HOME
#
# Usage:
#   ./build-rust.sh [debug|release]

set -euo pipefail

MODE="${1:-release}"
RUST_DIR="../aria-mobile-core"
OUT_DIR="app/src/main/kotlin/com/solutions5060/aria/bridge/uniffi"
JNI_DIR="app/src/main/jniLibs"

cd "$(dirname "$0")"

echo "=== Building aria-mobile-core for Android ($MODE) ==="

# Ensure NDK is available
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    echo "Error: ANDROID_NDK_HOME not set"
    echo "Install the Android NDK and set ANDROID_NDK_HOME"
    exit 1
fi

CARGO_FLAGS=""
TARGET_DIR="debug"
if [ "$MODE" = "release" ]; then
    CARGO_FLAGS="--release"
    TARGET_DIR="release"
fi

# Configure cargo for Android cross-compilation
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/aarch64-linux-android24-clang"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$TOOLCHAIN/armv7a-linux-androideabi24-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$TOOLCHAIN/x86_64-linux-android24-clang"

# cc-rs needs CC set for each target so it finds the right compiler
export CC_aarch64_linux_android="$TOOLCHAIN/aarch64-linux-android24-clang"
export CC_armv7_linux_androideabi="$TOOLCHAIN/armv7a-linux-androideabi24-clang"
export CC_x86_64_linux_android="$TOOLCHAIN/x86_64-linux-android24-clang"
export AR_aarch64_linux_android="$TOOLCHAIN/llvm-ar"
export AR_armv7_linux_androideabi="$TOOLCHAIN/llvm-ar"
export AR_x86_64_linux_android="$TOOLCHAIN/llvm-ar"
export CXX_aarch64_linux_android="$TOOLCHAIN/aarch64-linux-android24-clang++"
export CXX_armv7_linux_androideabi="$TOOLCHAIN/armv7a-linux-androideabi24-clang++"
export CXX_x86_64_linux_android="$TOOLCHAIN/x86_64-linux-android24-clang++"
export ANDROID_NDK="$ANDROID_NDK_HOME"
export CMAKE_POLICY_VERSION_MINIMUM=3.5

# Build for all Android targets
echo "Building aarch64-linux-android (ARM64)..."
cargo build $CARGO_FLAGS --manifest-path "$RUST_DIR/Cargo.toml" \
    --target aarch64-linux-android

echo "Building armv7-linux-androideabi (ARMv7)..."
cargo build $CARGO_FLAGS --manifest-path "$RUST_DIR/Cargo.toml" \
    --target armv7-linux-androideabi

echo "Building x86_64-linux-android (x86_64 emulator)..."
cargo build $CARGO_FLAGS --manifest-path "$RUST_DIR/Cargo.toml" \
    --target x86_64-linux-android

# Copy .so files to jniLibs
echo "Copying shared libraries to jniLibs..."
mkdir -p "$JNI_DIR/arm64-v8a" "$JNI_DIR/armeabi-v7a" "$JNI_DIR/x86_64"

cp "$RUST_DIR/target/aarch64-linux-android/$TARGET_DIR/libaria_mobile_core.so" \
   "$JNI_DIR/arm64-v8a/"
cp "$RUST_DIR/target/armv7-linux-androideabi/$TARGET_DIR/libaria_mobile_core.so" \
   "$JNI_DIR/armeabi-v7a/"
cp "$RUST_DIR/target/x86_64-linux-android/$TARGET_DIR/libaria_mobile_core.so" \
   "$JNI_DIR/x86_64/"

# Generate UniFFI Kotlin bindings
echo "Generating UniFFI Kotlin bindings..."
mkdir -p "$OUT_DIR"
cargo run --manifest-path "$RUST_DIR/Cargo.toml" \
    --features uniffi/cli -- \
    uniffi-bindgen generate "$RUST_DIR/src/aria_mobile.udl" \
    --language kotlin \
    --out-dir "$OUT_DIR"

echo "=== Done ==="
echo "JNI libraries: $JNI_DIR/"
echo "Kotlin bindings: $OUT_DIR/"
echo ""
echo "The UniFFI-generated Kotlin replaces the stub types in AriaMobileCore.kt"
