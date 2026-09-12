#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR/libcore"
source "$ROOT_DIR/scripts/go-toolchain.sh"
source "$ROOT_DIR/buildScript/lib/core/get_source_env.sh"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.1.13356709}"
export GOPATH="${GOPATH:-$(go env GOPATH)}"
export PATH="$JAVA_HOME/bin:$GOPATH/bin:$PATH"
for tool in gomobile gobind; do
    tool_info=$(go version -m "$GOPATH/bin/$tool")
    case "$tool_info" in
        *"golang.org/x/mobile"*"$GOMOBILE_VERSION"*"$GOMOBILE_SUM"*) ;;
        *) echo "$tool must be built from the pinned official gomobile module" >&2; exit 1 ;;
    esac
done
# A fresh output directory avoids stale generated bindings without deleting files.
mkdir -p "$ROOT_DIR/libcore/build"
# Keep retained generated cgo sources outside the production module's ./... test scope.
printf 'module vialen.local/build-artifacts\n\ngo 1.27.1\n' > "$ROOT_DIR/libcore/build/go.mod"
BUILD_DIR=$(mktemp -d "$ROOT_DIR/libcore/build/android.XXXXXX")
mkdir -p "$BUILD_DIR/cache"
TMPDIR="$BUILD_DIR/cache" "$GOPATH/bin/gomobile" bind -work -v -target=android/arm64 -androidapi 24 \
    -o "$BUILD_DIR/libcore.aar" -trimpath \
    -ldflags='-s -w -X github.com/sagernet/sing-box/constant.Version=1.14.0 -extldflags "-Wl,-z,max-page-size=16384"' \
    -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls' .
mkdir -p "$ROOT_DIR/app/libs"
cp "$BUILD_DIR/libcore.aar" "$ROOT_DIR/app/libs/libcore.aar"
printf '%s\n' "$BUILD_DIR" > "$ROOT_DIR/libcore/build/current.txt"
echo "Installed $ROOT_DIR/app/libs/libcore.aar"
