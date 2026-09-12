#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT_DIR/scripts/go-toolchain.sh"
cd "$ROOT_DIR/core"
mkdir -p build
go build -trimpath -o build/core-host ./cmd/core-host
