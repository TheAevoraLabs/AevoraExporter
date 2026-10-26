#!/bin/bash
# Build the Aevora template APK from a pinned upstream commit.
# Usage: build-template.sh [upstream-commit] [debug|release] [test-mod-zip]
# Defaults: 088e8d6 (Launcher 9.0), debug, no bundled mod (normal-launcher fallback).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PIN="${1:-088e8d6}"
FLAVOR="${2:-debug}"
TEST_MOD="${3:-}"
UPSTREAM="$HOME/workspace/repos/launcher"
WORK="$ROOT/template/build/src"
OUT="$ROOT/out"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="${JAVA_HOME:-/home/hatch/jdks/jdk-25.0.4.1+1}"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/workspace/.gradle-home}"
export GRADLE_OPTS="${GRADLE_OPTS:--Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false}"
# Sandbox fix (verified 2026-10-26): Gradle daemon/client socket dispatch
# fails without preferIPv4Stack; 1024m also avoids the 2048m daemon profile.
GRADLE_ARGS="--no-daemon -Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true"
GRADLE="${GRADLE:-$HOME/gradle-9.5.0/bin/gradle}"

echo "[template] upstream pin: $PIN"
rm -rf "$WORK"
mkdir -p "$WORK" "$OUT"
# Fresh copy of upstream at the pinned commit, without touching the original clone.
(cd "$UPSTREAM" && git archive "$PIN" | tar -x -C "$WORK")
python3 "$ROOT/template/apply-overlay.py" "$WORK" "$ROOT/template/overlay"
# Sandbox SDK layout: ~/android-sdk has build-tools 37 and platform 36,
# and ~/android-sdk/ndk/28.2.13676358 is a symlink to the workspace SDK's
# NDK 28.2 (AGP's preferred version; r29 disagrees with it, see CXX1104).
printf 'sdk.dir=/home/hatch/android-sdk\nndk.dir=/home/hatch/android-sdk/ndk/28.2.13676358\n' > "$WORK/local.properties"
# Sandbox-only SDK downgrade: platform android-37 is not installed in
# either sandbox SDK, so compile/target 37 -> 36 for the sandbox build.
# Upstream values (37) are kept for a real build with a full SDK; the
# overlay code uses no API-37 calls, so this does not change behaviour.
sed -i 's/version = release(37)/version = release(36)/; s/targetSdk 37/targetSdk 36/' "$WORK/app/build.gradle"
# Sandbox toolchain: use the cached JDK 25, never auto-download one.
printf '\norg.gradle.java.installations.paths=/home/hatch/jdks/jdk-25.0.4.1+1\norg.gradle.java.installations.auto-download=false\n' >> "$WORK/gradle.properties"

if [ -n "$TEST_MOD" ]; then
  mkdir -p "$WORK/app/src/main/assets"
  cp "$TEST_MOD" "$WORK/app/src/main/assets/bundled_mod.zip"
  echo "[template] test bundled mod injected: $TEST_MOD"
fi

# Sandbox Maven relay (see template/apply-overlay.py): start it if needed.
if ! curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8899/google/ 2>/dev/null | grep -q "200\|404"; then
  echo "[template] starting sandbox Maven relay on 127.0.0.1:8899"
  nohup python3 "$HOME/workspace/build/maven_relay.py" 8899 > /tmp/maven_relay.log 2>&1 &
  for i in $(seq 1 20); do
    sleep 1
    if curl -s -o /dev/null http://127.0.0.1:8899/google/ 2>/dev/null; then break; fi
  done
fi

cd "$WORK"
if [ "$FLAVOR" = "release" ]; then
  "$GRADLE" $GRADLE_ARGS --offline assembleRelease 2>&1 | tail -30 || "$GRADLE" $GRADLE_ARGS assembleRelease 2>&1 | tail -30
  cp app/build/outputs/apk/release/app-release.apk "$OUT/template-$FLAVOR.apk"
else
  "$GRADLE" $GRADLE_ARGS --offline assembleDebug 2>&1 | tail -30 || "$GRADLE" $GRADLE_ARGS assembleDebug 2>&1 | tail -30
  cp app/build/outputs/apk/debug/app-debug.apk "$OUT/template-$FLAVOR.apk"
fi
echo "[template] built: $OUT/template-$FLAVOR.apk"
ls -l "$OUT/template-$FLAVOR.apk"
