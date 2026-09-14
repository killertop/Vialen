#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
# gomobile writes absolute replacement-module paths into build metadata, even
# with trimpath. Stage sources outside all personal home directories.
STAGE=$(mktemp -d /tmp/vialen-release-build.XXXXXX)
mkdir -p "$STAGE/Vialen"
for component in libcore core scripts buildScript; do
    (cd "$ROOT_DIR" && tar --exclude=.git --exclude=build --exclude=.gradle -cf - "$component") |
        (cd "$STAGE/Vialen" && tar -xf -)
done
for component in libneko sing-box sing-tun; do
    (cd "$ROOT_DIR/.." && tar --exclude=.git --exclude=build -cf - "$component") |
        (cd "$STAGE" && tar -xf -)
done
bash "$STAGE/Vialen/libcore/build.sh"
mkdir -p "$ROOT_DIR/app/libs"
cp "$STAGE/Vialen/app/libs/libcore.aar" "$ROOT_DIR/app/libs/libcore.aar"
printf 'Native sources retained for verification: %s\n' "$STAGE"
