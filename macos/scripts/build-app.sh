#!/usr/bin/env bash
# Builds WiFiBridge.app (release, ad-hoc signed, not sandboxed). macOS only.
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "build-app.sh must run on macOS (the app target uses CoreBluetooth/CoreWLAN/SwiftUI)." >&2
  exit 1
fi

VERSION="${WB_VERSION_NAME:-0.1.0}"
BUILD_NUMBER="${WB_VERSION_CODE:-1}"
# Universal binary (Apple silicon + Intel).
ARCHS=(--arch arm64 --arch x86_64)
swift build -c release --product WiFiBridge "${ARCHS[@]}"
BIN_DIR="$(swift build -c release "${ARCHS[@]}" --show-bin-path)"

APP="build/WiFiBridge.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN_DIR/WiFiBridge" "$APP/Contents/MacOS/WiFiBridge"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDevelopmentRegion</key><string>en</string>
  <key>CFBundleExecutable</key><string>WiFiBridge</string>
  <key>CFBundleIdentifier</key><string>dev.wifibridge.mac</string>
  <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
  <key>CFBundleName</key><string>WiFi Bridge</string>
  <key>CFBundleDisplayName</key><string>WiFi Bridge</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>${VERSION}</string>
  <key>CFBundleVersion</key><string>${BUILD_NUMBER}</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSUIElement</key><true/>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSBluetoothAlwaysUsageDescription</key>
  <string>WiFi Bridge talks to your paired Android phone over Bluetooth to receive Wi-Fi passwords.</string>
  <key>NSLocationWhenInUseUsageDescription</key>
  <string>macOS only reveals nearby Wi-Fi network names to apps with Location access. WiFi Bridge needs them to know which passwords to ask your phone for.</string>
  <key>NSLocationUsageDescription</key>
  <string>macOS only reveals nearby Wi-Fi network names to apps with Location access. WiFi Bridge needs them to know which passwords to ask your phone for.</string>
</dict>
</plist>
PLIST

codesign --force --deep -s - "$APP"

echo "Built $(pwd)/$APP"
echo "Run it with: open \"$(pwd)/$APP\""
