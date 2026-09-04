#!/usr/bin/env bash
# build-rust-host.sh — Build libvialen_core.dylib for the macOS host JVM.
# Called by the Gradle `buildRustHost` task before unit tests run.
# Output: rust/vialen-core/target/release/libvialen_core.dylib
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CRATE_DIR="$ROOT_DIR/rust/vialen-core"
TOOLCHAIN="$(awk -F '"' '/^[[:space:]]*channel[[:space:]]*=/{print $2}' "$ROOT_DIR/rust-toolchain.toml")"

# Locate rustup — same resolution order as build-rust-android.sh
RUSTUP_BIN="${RUSTUP:-$(command -v rustup 2>/dev/null || true)}"

# Homebrew-installed rustup fallback (macOS common path)
if [[ -z "$RUSTUP_BIN" || ! -x "$RUSTUP_BIN" ]]; then
    for candidate in \
        /opt/homebrew/bin/rustup \
        /usr/local/bin/rustup \
        "$HOME/.cargo/bin/rustup"; do
        if [[ -x "$candidate" ]]; then
            RUSTUP_BIN="$candidate"
            break
        fi
    done
fi

if [[ -z "$RUSTUP_BIN" || ! -x "$RUSTUP_BIN" ]]; then
    echo "ERROR: rustup not found. Cannot build host Rust library." >&2
    exit 1
fi

CARGO_BIN="$("$RUSTUP_BIN" which --toolchain "$TOOLCHAIN" cargo)"
RUSTC_BIN="$("$RUSTUP_BIN" which --toolchain "$TOOLCHAIN" rustc)"
TOOLCHAIN_BIN_DIR="$(dirname "$CARGO_BIN")"

echo "==> Building host macOS libvialen_core.dylib"
echo "    toolchain: $TOOLCHAIN"
echo "    cargo:     $CARGO_BIN"
echo "    rustc:     $RUSTC_BIN"

# Put the toolchain's bin dir in PATH so cargo can exec rustc
export PATH="$TOOLCHAIN_BIN_DIR:$PATH"
export RUSTC="$RUSTC_BIN"

"$CARGO_BIN" build \
    --manifest-path "$CRATE_DIR/Cargo.toml" \
    --locked \
    --release

DYLIB="$CRATE_DIR/target/release/libvialen_core.dylib"
if [[ ! -f "$DYLIB" ]]; then
    echo "ERROR: expected output not found: $DYLIB" >&2
    exit 1
fi

echo "==> Host build OK: $DYLIB"
ls -lh "$DYLIB"
