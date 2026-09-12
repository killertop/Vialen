#!/usr/bin/env bash
# Install official binding tools into the normal Go tool directory, retaining backups.
set -euo pipefail
INIT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$INIT_DIR/../scripts/go-toolchain.sh"
source "$INIT_DIR/../buildScript/lib/core/get_source_env.sh"
export GOPATH="${GOPATH:-$(go env GOPATH)}"
TOOL_BIN="${GOPATH%%:*}/bin"
mkdir -p "$TOOL_BIN" "$INIT_DIR/build"
verified_tool() {
    local info
    [[ -x "$1" ]] || return 1
    info="$(go version -m "$1")" || return 1
    [[ "${info%%$'\n'*}" == *": go$REQUIRED_GO_VERSION" ]] || return 1
    [[ "$info" == *"golang.org/x/mobile"*$'\t'"$GOMOBILE_VERSION"$'\t'"$GOMOBILE_SUM"* ]]
}
if ! verified_tool "$TOOL_BIN/gomobile" || ! verified_tool "$TOOL_BIN/gobind"; then
    TOOL_STAGE="$(mktemp -d "$INIT_DIR/build/tool-init.XXXXXX")"
    mkdir -p "$TOOL_STAGE/bin"
    # Exact module version and checksum avoid a private fork and a separate old compiler.
    (
        cd "$TOOL_STAGE"
        GOWORK=off GOFLAGS= GOBIN="$TOOL_STAGE/bin" \
            go install "golang.org/x/mobile/cmd/gomobile@$GOMOBILE_VERSION" \
                       "golang.org/x/mobile/cmd/gobind@$GOMOBILE_VERSION"
    )
    verified_tool "$TOOL_STAGE/bin/gomobile"
    verified_tool "$TOOL_STAGE/bin/gobind"
    mkdir -p "$INIT_DIR/build/tool-backups"
    TOOL_BACKUP="$(mktemp -d "$INIT_DIR/build/tool-backups/tools.XXXXXX")"
    for tool in gomobile gobind; do
        if [[ -e "$TOOL_BIN/$tool" || -L "$TOOL_BIN/$tool" ]]; then
            mv "$TOOL_BIN/$tool" "$TOOL_BACKUP/$tool"
        fi
        cp -p "$TOOL_STAGE/bin/$tool" "$TOOL_BIN/$tool"
    done
    echo "Previous tools and staging retained at $TOOL_BACKUP and $TOOL_STAGE"
fi
verified_tool "$TOOL_BIN/gomobile"
verified_tool "$TOOL_BIN/gobind"
echo "Official gomobile/gobind ready: $GOMOBILE_VERSION, Go $REQUIRED_GO_VERSION"
# No OpenAL is used; gomobile bind works directly without destructive init/clean.
