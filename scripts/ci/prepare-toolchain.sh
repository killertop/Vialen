#!/usr/bin/env bash
# CI only. Install explicit versions, record effective tools, export SDK paths.
set -euo pipefail
report="${1:?toolchain report path required}"
: "${ANDROID_HOME:?Android SDK runner path required}"
: "${GITHUB_ENV:?GitHub Actions environment file required}"
: "${GITHUB_PATH:?GitHub Actions path file required}"
if ! command -v sdkmanager >/dev/null; then
  # GitHub's Android SDK can be installed without command-line tools on PATH.
  export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/tools/bin:$PATH"
fi
command -v sdkmanager >/dev/null || { echo "Android SDK command-line tools are missing" >&2; exit 1; }

sdkmanager --install "platforms;android-37.0" "build-tools;36.0.0" "ndk;28.1.13356709"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.1.13356709"
export NDK="$ANDROID_NDK_HOME"
export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$ANDROID_HOME/build-tools/36.0.0:$PATH"
[[ "$(go env GOVERSION)" == go1.27.1 ]]
java -version 2>&1 | head -n 1 | grep -F '"25.0.2"' >/dev/null
grep -Eq '^Pkg.Revision *= *28\.1\.13356709' "$ANDROID_NDK_HOME/source.properties"
grep -Eq '^Pkg.Revision *= *36\.0\.0' "$ANDROID_HOME/build-tools/36.0.0/source.properties"
test -f "$ANDROID_HOME/platforms/android-37.0/android.jar"
grep -Eq '^AndroidVersion.ApiLevel *= *37$' "$ANDROID_HOME/platforms/android-37.0/source.properties"
command -v llvm-readelf >/dev/null
command -v zipalign >/dev/null
command -v aapt2 >/dev/null
{
  go version
  java -version 2>&1
  cat "$ANDROID_NDK_HOME/source.properties"
  cat "$ANDROID_HOME/build-tools/36.0.0/source.properties"
  cat "$ANDROID_HOME/platforms/android-37.0/source.properties"
  aapt2 version
  llvm-readelf --version
} > "$report"
cat "$report"
printf 'ANDROID_NDK_HOME=%s\nNDK=%s\n' "$ANDROID_NDK_HOME" "$NDK" >> "$GITHUB_ENV"
printf '%s\n%s\n' "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin" "$ANDROID_HOME/build-tools/36.0.0" >> "$GITHUB_PATH"
