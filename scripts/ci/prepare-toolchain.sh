#!/usr/bin/env bash
# CI only. Install explicit versions, record effective tools, export SDK paths.
set -Eeuo pipefail
report="${1:?toolchain report path required}"
mkdir -p "$(dirname "$report")"
: > "$report"
exec > >(tee -a "$report") 2>&1
current_check="required environment"
trap 'status=$?; printf "[FAIL] %s (line %s, exit %s)\n" "$current_check" "$LINENO" "$status"; exit "$status"' ERR
check_value() {
  current_check="$1"
  printf '[CHECK] %s expected=%s actual=%s\n' "$1" "$2" "$3"
  [[ "$2" == "$3" ]]
}
property() {
  local key="$1" file="$2"
  current_check="read $key from $file"
  test -f "$file" || { printf '[FAIL] missing property file: %s\n' "$file" >&2; return 1; }
  sed -n "s/^$key[[:space:]]*=[[:space:]]*//p" "$file" | tr -d '\r'
}
: "${ANDROID_HOME:?Android SDK runner path required}"
: "${GITHUB_ENV:?GitHub Actions environment file required}"
: "${GITHUB_PATH:?GitHub Actions path file required}"
if ! command -v sdkmanager >/dev/null; then
  # GitHub's Android SDK can be installed without command-line tools on PATH.
  export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/tools/bin:$PATH"
fi
current_check="locate sdkmanager"
command -v sdkmanager || { echo "Android SDK command-line tools are missing" >&2; exit 1; }

current_check="install Android SDK 37.0, build-tools 36.0.0 and NDK 28.1.13356709"
printf '[CHECK] %s\n' "$current_check"
sdkmanager --install "platforms;android-37.0" "build-tools;36.0.0" "ndk;28.1.13356709"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.1.13356709"
export NDK="$ANDROID_NDK_HOME"
export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$ANDROID_HOME/build-tools/36.0.0:$PATH"
current_check="read Go version"
go_version=$(go env GOVERSION)
check_value "Go version" go1.27.1 "$go_version"
current_check="read Java version"
java_settings=$(java -XshowSettings:properties -version 2>&1)
java_version=$(printf '%s\n' "$java_settings" | sed -n 's/^[[:space:]]*java.version = //p')
check_value "Java version" 25.0.2 "$java_version"
check_value "NDK revision" 28.1.13356709 "$(property 'Pkg.Revision' "$ANDROID_NDK_HOME/source.properties")"
check_value "build-tools revision" 36.0.0 "$(property 'Pkg.Revision' "$ANDROID_HOME/build-tools/36.0.0/source.properties")"
check_value "SDK API level" 37.0 "$(property 'AndroidVersion.ApiLevel' "$ANDROID_HOME/platforms/android-37.0/source.properties")"
current_check="SDK android.jar exists"
printf '[CHECK] %s expected=present path=%s\n' "$current_check" "$ANDROID_HOME/platforms/android-37.0/android.jar"
test -f "$ANDROID_HOME/platforms/android-37.0/android.jar"
for tool in llvm-readelf zipalign aapt2; do
  current_check="locate $tool"
  printf '[CHECK] %s expected=executable actual=' "$current_check"
  command -v "$tool"
done
current_check="inspect binary tool versions"
aapt2 version
llvm-readelf --version
current_check="export toolchain paths"
printf 'ANDROID_NDK_HOME=%s\nNDK=%s\n' "$ANDROID_NDK_HOME" "$NDK" >> "$GITHUB_ENV"
printf '%s\n%s\n' "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin" "$ANDROID_HOME/build-tools/36.0.0" >> "$GITHUB_PATH"
printf '[PASS] All pinned toolchain checks completed\n'
