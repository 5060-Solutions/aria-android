#!/bin/bash
# Build aria-mobile-core for Android targets and generate UniFFI Kotlin bindings.
#
# Prerequisites:
#   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
#   Install Android NDK and set ANDROID_NDK_HOME
#   IMPORTANT: Use rustup's cargo, not Homebrew's. Run with:
#     PATH="$HOME/.cargo/bin:$PATH" ./build-rust.sh
#
# Usage:
#   ./build-rust.sh [debug|release]

set -euo pipefail

# Ensure we use rustup's cargo (has Android targets) not Homebrew's
if [[ "$(which cargo)" == "/opt/homebrew/bin/cargo" ]]; then
    if [ -x "$HOME/.cargo/bin/cargo" ]; then
        export PATH="$HOME/.cargo/bin:$PATH"
        echo "Switched to rustup cargo: $(which cargo) ($(cargo --version))"
    fi
fi

MODE="${1:-release}"
RUST_DIR="../aria-mobile-core"
OUT_DIR="app/src/main/kotlin/com/solutions5060/aria/bridge/uniffi"
JNI_DIR="app/src/main/jniLibs"

cd "$(dirname "$0")"

echo "=== Building aria-mobile-core for Android ($MODE) ==="

# Ensure NDK is available — auto-detect if not set
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    # Try to find NDK in standard Android SDK location
    SDK_NDK_DIR="$HOME/Library/Android/sdk/ndk"
    if [ -d "$SDK_NDK_DIR" ]; then
        ANDROID_NDK_HOME="$SDK_NDK_DIR/$(ls "$SDK_NDK_DIR" | sort -V | tail -1)"
        export ANDROID_NDK_HOME
        echo "Auto-detected NDK: $ANDROID_NDK_HOME"
    else
        echo "Error: ANDROID_NDK_HOME not set and NDK not found in $SDK_NDK_DIR"
        echo "Install the Android NDK and set ANDROID_NDK_HOME"
        exit 1
    fi
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
# UniFFI Kotlin bindings load the library as "uniffi_aria_mobile", so we must
# copy the cdylib (libaria_mobile_core.so) under BOTH names. Clean stale .so
# files first to prevent loading an outdated library.
echo "Copying shared libraries to jniLibs..."
mkdir -p "$JNI_DIR/arm64-v8a" "$JNI_DIR/armeabi-v7a" "$JNI_DIR/x86_64"

# Remove ALL old .so files to prevent stale library issues
find "$JNI_DIR" -name "*.so" -delete

for ABI_DIR in arm64-v8a armeabi-v7a x86_64; do
    case "$ABI_DIR" in
        arm64-v8a)      TARGET="aarch64-linux-android" ;;
        armeabi-v7a)    TARGET="armv7-linux-androideabi" ;;
        x86_64)         TARGET="x86_64-linux-android" ;;
    esac
    SRC="$RUST_DIR/target/$TARGET/$TARGET_DIR/libaria_mobile_core.so"
    cp "$SRC" "$JNI_DIR/$ABI_DIR/libaria_mobile_core.so"
    # UniFFI loads "uniffi_aria_mobile" — copy under that name too
    cp "$SRC" "$JNI_DIR/$ABI_DIR/libuniffi_aria_mobile.so"
    echo "  $ABI_DIR: $(ls -lh "$JNI_DIR/$ABI_DIR/libaria_mobile_core.so" | awk '{print $5}')"
done

# Generate UniFFI Kotlin bindings
# The aria-mobile-core crate has a uniffi-bindgen binary target
echo "Generating UniFFI Kotlin bindings..."
mkdir -p "$OUT_DIR"
cargo run --manifest-path "$RUST_DIR/Cargo.toml" \
    --bin uniffi-bindgen -- \
    generate "$RUST_DIR/src/aria_mobile.udl" \
    --language kotlin \
    --out-dir "$OUT_DIR"

echo "=== Done ==="
echo "JNI libraries: $JNI_DIR/"
echo "Kotlin bindings: $OUT_DIR/"
echo ""
echo "The UniFFI-generated Kotlin replaces the stub types in AriaMobileCore.kt"
