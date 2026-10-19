#!/usr/bin/env bash
# Packages build/WiFiBridge.app as build/WiFiBridge-<version>-macos.zip and a
# drag-to-Applications build/WiFiBridge-<version>.dmg. Signs the DMG when
# WB_SIGN_IDENTITY is set. macOS only.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${WB_VERSION_NAME:-0.1.0}"
APP="build/WiFiBridge.app"
ZIP="build/WiFiBridge-${VERSION}-macos.zip"
DMG="build/WiFiBridge-${VERSION}.dmg"
[[ -d "$APP" ]] || { echo "missing $APP — run scripts/build-app.sh first" >&2; exit 1; }

rm -f "$ZIP" "$DMG"
ditto -c -k --keepParent "$APP" "$ZIP"

STAGE="$(mktemp -d)"
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
hdiutil create -volname "WiFi Bridge" -srcfolder "$STAGE" -ov -format UDZO "$DMG"
rm -rf "$STAGE"
if [[ -n "${WB_SIGN_IDENTITY:-}" ]]; then
  codesign --force --timestamp -s "$WB_SIGN_IDENTITY" "$DMG"
fi
echo "Packaged $ZIP $DMG"
