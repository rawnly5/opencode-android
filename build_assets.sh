#!/usr/bin/env bash
set -euo pipefail
# Builds opencode assets for the Android APK.
# Downloads opencode-linux-arm64 binary + glibc for ARM64, patches binary
# to use bundled glibc, and outputs to app/src/main/assets/opencode/

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/app/src/main/assets/opencode"
GLIBC_DIR="$OUTPUT_DIR/glibc"
OPENCODE_VERSION="1.15.12"
UBUNTU_VERSION="jammy"
GLIBC_DEB_URL="http://ports.ubuntu.com/ubuntu-ports/pool/main/g/glibc/libc6_2.35-0ubuntu3_arm64.deb"

setup_patchelf() {
  if command -v patchelf &>/dev/null; then
    PATCHELF=$(command -v patchelf)
    return
  fi
  # Download static patchelf
  local url="https://github.com/NixOS/patchelf/releases/download/0.18.0/patchelf-0.18.0-x86_64.tar.gz"
  local tmpdir=$(mktemp -d)
  curl -sL "$url" | tar -xzf - -C "$tmpdir"
  PATCHELF=$(find "$tmpdir" -name patchelf -type f)
  export PATCHELF
}

echo "==> Building opencode assets for Android"

mkdir -p "$OUTPUT_DIR" "$GLIBC_DIR"

# Step 1: Download opencode-linux-arm64 binary
echo "==> Downloading opencode-linux-arm64 v${OPENCODE_VERSION}..."
TMP_OPENCODE=$(mktemp -d)
curl -sL "https://registry.npmjs.org/opencode-linux-arm64/-/opencode-linux-arm64-${OPENCODE_VERSION}.tgz" \
  -o "$TMP_OPENCODE/opencode.tgz"
tar -xzf "$TMP_OPENCODE/opencode.tgz" -C "$TMP_OPENCODE"
BINARY_SRC="$TMP_OPENCODE/package/bin/opencode"

if [ ! -f "$BINARY_SRC" ]; then
  echo "ERROR: opencode binary not found in package"
  exit 1
fi
echo "  Binary size: $(du -h "$BINARY_SRC" | cut -f1)"

# Step 2: Download glibc for ARM64
echo "==> Downloading glibc for ARM64..."
TMP_GLIBC=$(mktemp -d)
curl -sL "$GLIBC_DEB_URL" -o "$TMP_GLIBC/libc6_arm64.deb"
dpkg-deb -x "$TMP_GLIBC/libc6_arm64.deb" "$TMP_GLIBC/extracted/"
GLIBC_SRC="$TMP_GLIBC/extracted/lib/aarch64-linux-gnu"

# Copy needed glibc .so files
for lib in ld-linux-aarch64.so.1 libc.so.6 libm.so.6 libpthread.so.0 \
           libdl.so.2 librt.so.1 libresolv.so.2 libnss_files.so.2 \
           libnss_compat.so.2 libnss_dns.so.2 libutil.so.1; do
  if [ -f "$GLIBC_SRC/$lib" ]; then
    cp "$GLIBC_SRC/$lib" "$GLIBC_DIR/$lib"
  fi
done
echo "  glibc files: $(ls -1 "$GLIBC_DIR" | wc -l)"

# Step 3: Patch binary interpreter
echo "==> Patching opencode binary..."
setup_patchelf
cp "$BINARY_SRC" "$OUTPUT_DIR/opencode"
chmod +x "$OUTPUT_DIR/opencode"

"$PATCHELF" --set-interpreter "./glibc/ld-linux-aarch64.so.1" "$OUTPUT_DIR/opencode"
"$PATCHELF" --set-rpath '$ORIGIN/glibc' "$OUTPUT_DIR/opencode"

echo "  Interpreter: $("$PATCHELF" --print-interpreter "$OUTPUT_DIR/opencode")"
echo "  RPATH: $("$PATCHELF" --print-rpath "$OUTPUT_DIR/opencode")"

# Cleanup
rm -rf "$TMP_OPENCODE" "$TMP_GLIBC"

echo ""
echo "==> Done! Assets ready at:"
du -sh "$OUTPUT_DIR/opencode" "$GLIBC_DIR"
echo ""
echo "  Binary size: $(du -h "$OUTPUT_DIR/opencode" | cut -f1)"
echo "  glibc size: $(du -sh "$GLIBC_DIR" | cut -f1)"
echo "  Total: $(du -sh "$OUTPUT_DIR" | cut -f1)"
