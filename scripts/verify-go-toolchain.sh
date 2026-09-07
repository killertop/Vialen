#!/usr/bin/env bash
# Verifies host Go vs Required Go 1.25.5 toolchain pinning
set -euo pipefail

REQUIRED_GO="go1.25.5"
HOST_GO=$(go env GOVERSION 2>/dev/null || go version | awk '{print $3}')

# Query effective version when GOTOOLCHAIN is enforced
EFFECTIVE_GO_OUTPUT=$(GOTOOLCHAIN="${REQUIRED_GO}" go version 2>&1)
EFFECTIVE_GO=$(echo "${EFFECTIVE_GO_OUTPUT}" | grep -o 'go[0-9]\+\.[0-9]\+\.[0-9]\+' | head -n1 || true)

echo "host go: ${HOST_GO}"
echo "required go: ${REQUIRED_GO}"
echo "effective go: ${EFFECTIVE_GO}"

if [ "${EFFECTIVE_GO}" = "${REQUIRED_GO}" ]; then
    echo "STATUS: PASS"
    exit 0
else
    echo "STATUS: FAIL - Failed to pin toolchain to ${REQUIRED_GO}" >&2
    exit 1
fi
