#!/usr/bin/env bash
# 手机插上并授权 USB 调试后运行：安装 APK + 跑真机测试 + 抓几张截图
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-toolchain/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
APK="${1:-$HOME/Desktop/pocketagent_0.1_20261030/PocketAgent-0.1.0.apk}"

echo "等待设备…（手机上点“允许 USB 调试”）"
"$ADB" wait-for-device
"$ADB" shell getprop ro.product.model
"$ADB" shell getprop ro.build.version.release

echo "安装 $APK"
"$ADB" install -r "$APK"

echo "启动 App"
"$ADB" shell am start -W -n app.pocketagent.android/.MainActivity
sleep 3

echo "抓首页截图"
"$ADB" exec-out screencap -p > /tmp/pocketagent_home.png

echo "跑仪器测试（Keystore + Compose UI）"
cd "$(dirname "$0")/.."
"$HOME/android-toolchain/gradle-8.9/bin/gradle" :app:connectedDebugAndroidTest --console=plain
