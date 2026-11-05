#!/usr/bin/env bash
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-$HOME/android-toolchain/jdk-17.0.21+9/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-toolchain/sdk}"
GRADLE="$HOME/android-toolchain/gradle-8.9/bin/gradle"

cd "$(dirname "$0")"

case "${1:-assemble}" in
  test)   "$GRADLE" :app:testDebugUnitTest --console=plain ;;
  device) "$GRADLE" :app:connectedDebugAndroidTest --console=plain ;;
  apk)    "$GRADLE" :app:assembleDebug --console=plain
          ls -lh app/build/outputs/apk/debug/app-debug.apk ;;
  *)      "$GRADLE" :app:assembleDebug --console=plain ;;
esac
