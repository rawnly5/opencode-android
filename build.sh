#!/bin/bash
set -e

echo "=================================="
echo "  OpenCode Android Build Script"
echo "=================================="

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo ""
echo "[1/4] Building opencode assets..."
bash build_assets.sh

echo ""
echo "[2/4] Checking Android SDK..."
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    echo "WARNING: ANDROID_HOME or ANDROID_SDK_ROOT not set."
    echo "Please set one of them to point to your Android SDK."
fi

echo ""
echo "[3/4] Building APK..."
chmod +x gradlew
./gradlew assembleRelease

echo ""
echo "[4/4] Build complete!"
echo ""
echo "APK location: app/build/outputs/apk/release/"
echo "Install with: adb install app/build/outputs/apk/release/app-release.apk"
echo ""
echo "APK size: $(du -h app/build/outputs/apk/release/app-release.apk 2>/dev/null | cut -f1 || echo 'N/A')"
echo "=================================="
