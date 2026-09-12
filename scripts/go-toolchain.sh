#!/usr/bin/env bash
# Vialen Go Toolchain Pinning Configuration for sing-box 1.14.0 Core
set -euo pipefail

export REQUIRED_GO_VERSION="1.27.1"
export GOTOOLCHAIN="go${REQUIRED_GO_VERSION}"

echo "Configured GOTOOLCHAIN=${GOTOOLCHAIN}"
