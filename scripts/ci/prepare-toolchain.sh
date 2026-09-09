#!/usr/bin/env bash
# CI only. Install explicit versions, record effective tools, export SDK paths.
set -euo pipefail
report="${1:?toolchain report path required}"
: "${ANDROID_HOME:?Android SDK runner path required}"
: "${GITHUB_ENV:?GitHub Actions environment file required}"
: "${GITHUB_PATH:?GitHub Actions path file required}"
command -v sdkmanager >/dev/null
command -v rustup >/dev/null

sdkmanager --install "platforms;android-35" "build-tools;35.0.1" "ndk;28.1.13356709"
rustup toolchain install 1.97.1 --profile minimal --target aarch64-linux-android
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.1.13356709"
export NDK="$ANDROID_NDK_HOME"
export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$ANDROID_HOME/build-tools/35.0.1:$PATH"
[[ "$(go env GOVERSION)" == go1.26.5 ]]
java -version 2>&1 | head -n 1 | grep -F '"25.0.2"' >/dev/null
rustup run 1.97.1 rustc --version | grep -E '^rustc 1\.97\.1 ' >/dev/null
rustup target list --toolchain 1.97.1 --installed | grep -Fx aarch64-linux-android >/dev/null
grep -Eq '^Pkg.Revision *= *28\.1\.13356709' "$ANDROID_NDK_HOME/source.properties"
grep -Eq '^Pkg.Revision *= *35\.0\.1' "$ANDROID_HOME/build-tools/35.0.1/source.properties"
test -f "$ANDROID_HOME/platforms/android-35/android.jar"
grep -Eq '^AndroidVersion.ApiLevel *= *35$' "$ANDROID_HOME/platforms/android-35/source.properties"
command -v llvm-readelf >/dev/null
command -v zipalign >/dev/null
command -v aapt2 >/dev/null
{
  go version
  java -version 2>&1
  rustup run 1.97.1 rustc --version --verbose
  rustup run 1.97.1 cargo --version
  rustup target list --toolchain 1.97.1 --installed
  cat "$ANDROID_NDK_HOME/source.properties"
  cat "$ANDROID_HOME/build-tools/35.0.1/source.properties"
  cat "$ANDROID_HOME/platforms/android-35/source.properties"
  aapt2 version
  llvm-readelf --version
} > "$report"
cat "$report"
printf 'ANDROID_NDK_HOME=%s\nNDK=%s\n' "$ANDROID_NDK_HOME" "$NDK" >> "$GITHUB_ENV"
printf '%s\n%s\n' "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin" "$ANDROID_HOME/build-tools/35.0.1" >> "$GITHUB_PATH"
