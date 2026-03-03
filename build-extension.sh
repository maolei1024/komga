#!/bin/bash
# Build script for komga-gorse extension APK
# This script copies the extension source to extensions-source and builds the APK
# 
# Prerequisites:
# - Android SDK installed
# - extensions-source cloned at ~/code/extensions-source
# - Java 17+ installed
#
# Usage: ./build-extension.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KOMGA_DIR="$SCRIPT_DIR"
EXT_SOURCE_DIR="${EXT_SOURCE_DIR:-$HOME/code/extensions-source}"

echo "=== Building komga-gorse extension ==="

# 1. Copy extension source to extensions-source
echo "Copying extension source..."
rm -rf "$EXT_SOURCE_DIR/src/all/komgagorse"
cp -r "$KOMGA_DIR/komgagorse-extension" "$EXT_SOURCE_DIR/src/all/komgagorse"

# 2. Build the APK
echo "Building APK..."
cd "$EXT_SOURCE_DIR"
./gradlew :src:all:komgagorse:assembleDebug

# 3. Find the built APK
APK_PATH=$(find "$EXT_SOURCE_DIR/src/all/komgagorse/build" -name "*.apk" | head -1)

if [ -z "$APK_PATH" ]; then
    echo "ERROR: APK not found!"
    exit 1
fi

echo "APK built: $APK_PATH"

# 4. Copy APK to repo directory
APK_NAME="tachiyomi-all.komga-gorse-v1.apk"
mkdir -p "$KOMGA_DIR/repo/apk"
cp "$APK_PATH" "$KOMGA_DIR/repo/apk/$APK_NAME"
echo "APK copied to: $KOMGA_DIR/repo/apk/$APK_NAME"

# 5. Generate index.min.json with correct source IDs
# Source IDs are computed at runtime by the extension. We use placeholder IDs here.
# The actual source IDs will be computed by Mihon when the extension is loaded.
echo "Extension built successfully!"
echo ""
echo "Next steps:"
echo "1. Commit the built APK: git add repo/ && git commit -m '添加 komga-gorse 扩展 APK'"
echo "2. Push to GitHub"
echo "3. In Mihon, add extension repo URL:"
echo "   https://raw.githubusercontent.com/maolei1024/komga/master/repo"
