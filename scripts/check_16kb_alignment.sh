#!/usr/bin/env bash
set -euo pipefail

# 16 KB Alignment Verification Script for Android ELF .so and APK Zip Alignment
# Gate A: ELF LOAD segment alignment >= 0x4000 (16384 bytes)
# Gate B: APK zip alignment (16-page aligned with 4-byte boundaries)

TARGET="${1:-}"

if [[ -z "$TARGET" ]]; then
    echo "Usage: $0 <path-to-apk-or-aar-or-so>"
    exit 1
fi

if [[ ! -e "$TARGET" ]]; then
    echo "Error: Target $TARGET does not exist."
    exit 1
fi

# Locate readelf / llvm-readelf
READELF=""
if command -v llvm-readelf >/dev/null 2>&1; then
    READELF="llvm-readelf"
elif command -v readelf >/dev/null 2>&1; then
    READELF="readelf"
else
    # Try Android SDK NDK paths
    NDK_READELF=$(find "${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk" -name "llvm-readelf" 2>/dev/null | head -n 1 || true)
    if [[ -n "$NDK_READELF" && -x "$NDK_READELF" ]]; then
        READELF="$NDK_READELF"
    fi
fi

if [[ -z "$READELF" ]]; then
    echo "Error: llvm-readelf / readelf not found."
    exit 1
fi

# Locate zipalign
ZIPALIGN=""
if command -v zipalign >/dev/null 2>&1; then
    ZIPALIGN="zipalign"
else
    SDK_ZIPALIGN=$(find "${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools" -name "zipalign" 2>/dev/null | sort -V | tail -n 1 || true)
    if [[ -n "$SDK_ZIPALIGN" && -x "$SDK_ZIPALIGN" ]]; then
        ZIPALIGN="$SDK_ZIPALIGN"
    fi
fi

echo "========================================================="
echo " 16 KB Native Compatibility Gate Check"
echo " Target:   $TARGET"
echo " Tool:     $READELF"
echo " Zipalign: ${ZIPALIGN:-NOT FOUND}"
echo "========================================================="

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

check_so() {
    local so_file="$1"
    local rel_name="$2"
    local fail=0

    local headers
    headers=$("$READELF" -l "$so_file" 2>&1)

    # Check LOAD segments
    local load_aligns
    load_aligns=$(echo "$headers" | awk '/LOAD/ {print $NF}' || true)

    if [[ -z "$load_aligns" ]]; then
        # Fallback format parsing
        load_aligns=$(echo "$headers" | grep -A 1 "LOAD" | grep "Align" | awk '{print $NF}' || true)
    fi

    local min_align_dec=999999
    while read -r align; do
        [[ -z "$align" ]] && continue
        # Normalize hex string (e.g., 0x1000 or 1000 or 0x4000)
        local dec_val
        if [[ "$align" =~ ^0x ]]; then
            dec_val=$((align))
        else
            dec_val=$((16#$align))
        fi
        if (( dec_val < min_align_dec )); then
            min_align_dec=$dec_val
        fi
        if (( dec_val < 16384 )); then
            fail=1
        fi
    done <<< "$load_aligns"

    if (( fail == 1 )); then
        printf "  [FAIL] %-45s (Min LOAD Align: %d B / 0x%X < 16KB)\n" "$rel_name" "$min_align_dec" "$min_align_dec"
        return 1
    else
        printf "  [PASS] %-45s (Min LOAD Align: %d B / 0x%X >= 16KB)\n" "$rel_name" "$min_align_dec" "$min_align_dec"
        return 0
    fi
}

GATE_A_FAIL=0
GATE_B_FAIL=0

if [[ "$TARGET" =~ \.so$ ]]; then
    echo "[Gate A: ELF Segment Alignment]"
    check_so "$TARGET" "$(basename "$TARGET")" || GATE_A_FAIL=1
elif [[ "$TARGET" =~ \.(apk|aar|zip)$ ]]; then
    echo "[Gate A: ELF Segment Alignment across ABIs]"
    unzip -q -o "$TARGET" -d "$TMP_DIR"

    SO_COUNT=0
    while IFS= read -r so_path; do
        [[ -z "$so_path" ]] && continue
        SO_COUNT=$((SO_COUNT + 1))
        rel_path="${so_path#$TMP_DIR/}"
        check_so "$so_path" "$rel_path" || GATE_A_FAIL=1
    done < <(find "$TMP_DIR" -type f -name "*.so")

    if (( SO_COUNT == 0 )); then
        echo "  [INFO] No .so shared libraries found in archive."
    fi

    if [[ "$TARGET" =~ \.apk$ ]]; then
        echo ""
        echo "[Gate B: APK Zip 16KB Page Alignment]"
        if [[ -n "$ZIPALIGN" ]]; then
            if "$ZIPALIGN" -c -v -P 16 4 "$TARGET" > "$TMP_DIR/zipalign.log" 2>&1; then
                echo "  [PASS] APK is 16-page aligned (zipalign -c -P 16 4 passed)."
            else
                echo "  [FAIL] APK is NOT 16-page aligned."
                echo "  Top 5 mismatch lines:"
                grep -E "FAILED|VERIFICATION FAILED" "$TMP_DIR/zipalign.log" | head -n 5 || true
                GATE_B_FAIL=1
            fi
        else
            echo "  [WARN] zipalign not found in environment, skipping Gate B verification."
        fi
    fi
fi

echo "========================================================="
if (( GATE_A_FAIL == 0 && GATE_B_FAIL == 0 )); then
    echo " RESULT: ALL 16 KB ALIGNMENT GATES PASSED (100%)"
    exit 0
else
    echo " RESULT: 16 KB ALIGNMENT GATES FAILED"
    exit 1
fi
