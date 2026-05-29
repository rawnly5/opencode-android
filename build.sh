#!/bin/bash
set -e

echo "=================================="
echo "  OpenCode Android Build Script"
echo "=================================="

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo ""
echo "[1/5] Installing Node.js dependencies for opencode..."
cd app/src/main/assets/nodejs-project
npm install --production --no-optional
cd "$SCRIPT_DIR"

echo ""
echo "[2/5] Checking Android SDK..."
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    echo "WARNING: ANDROID_HOME or ANDROID_SDK_ROOT not set."
    echo "Please set one of them to point to your Android SDK."
fi

echo ""
echo "[3/5] Setting up Gradle..."
if [ ! -f "gradlew" ]; then
    gradle wrapper --gradle-version 8.11.1
fi

echo ""
echo "[4/5] Building APK..."
chmod +x gradlew
./gradlew assembleRelease

echo ""
echo "[5/5] Build complete!"
echo ""
echo "APK location: app/build/outputs/apk/release/"
echo "Install with: adb install app/build/outputs/apk/release/app-release.apk"
echo ""
echo "NOTE: For first time setup, the app will run npm install"
echo "on device to fetch opencode dependencies. This requires"
echo "internet connection on first launch."
echo "=================================="
