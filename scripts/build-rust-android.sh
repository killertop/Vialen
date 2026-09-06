#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CRATE_DIR="$ROOT_DIR/rust/vialen-core"
OUT_DIR="${1:-$ROOT_DIR/app/build/generated/rustJniLibs}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK_VERSION="${VIALEN_NDK_VERSION:-28.1.13356709}"
NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-$ANDROID_HOME/ndk/$NDK_VERSION}}"
RUSTUP_BIN="${RUSTUP:-$(command -v rustup || true)}"
TOOLCHAIN="$(awk -F '"' '/^[[:space:]]*channel[[:space:]]*=/{print $2}' "$ROOT_DIR/rust-toolchain.toml")"
API_LEVEL=21

if [[ -z "$RUSTUP_BIN" || ! -x "$RUSTUP_BIN" ]]; then
    echo "rustup is required to build the Vialen Rust JNI library" >&2
    exit 1
fi
if [[ ! -d "$NDK_DIR/toolchains/llvm/prebuilt" ]]; then
    echo "Android NDK not found: $NDK_DIR" >&2
    exit 1
fi

RUSTC_BIN="$($RUSTUP_BIN which --toolchain "$TOOLCHAIN" rustc)"
CARGO_BIN="$($RUSTUP_BIN which --toolchain "$TOOLCHAIN" cargo)"

PREBUILT_DIR="$(find "$NDK_DIR/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
BIN_DIR="$PREBUILT_DIR/bin"
TARGET_DIR="$CRATE_DIR/target"

TARGET="aarch64-linux-android"
LINKER="$BIN_DIR/${TARGET}${API_LEVEL}-clang"
if [[ ! -x "$LINKER" ]]; then
    echo "NDK linker not found: $LINKER" >&2
    exit 1
fi

# Replace generated output so libraries from previous multi-ABI builds cannot remain.
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/arm64-v8a"

env \
    CARGO_TARGET_DIR="$TARGET_DIR" \
    RUSTC="$RUSTC_BIN" \
    CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$LINKER" \
    RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384" \
    "$CARGO_BIN" build \
        --manifest-path "$CRATE_DIR/Cargo.toml" \
        --locked \
        --release \
        --target "$TARGET"

cp "$TARGET_DIR/$TARGET/release/libvialen_core.so" "$OUT_DIR/arm64-v8a/libvialen_core.so"
