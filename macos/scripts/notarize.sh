#!/usr/bin/env bash
# Packages a Developer-ID-signed build/WiFiBridge.app into a DMG, notarizes it
# with Apple, staples the tickets, and checks Gatekeeper accepts the result.
#
# Requires (macOS): WB_SIGN_IDENTITY, ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH (.p8)
# Output: build/WiFiBridge-<version>.dmg and build/WiFiBridge-<version>-macos.zip
set -euo pipefail
cd "$(dirname "$0")/.."

: "${WB_SIGN_IDENTITY:?}" "${ASC_KEY_ID:?}" "${ASC_ISSUER_ID:?}" "${ASC_KEY_PATH:?}"
VERSION="${WB_VERSION_NAME:-0.1.0}"
APP="build/WiFiBridge.app"
DMG="build/WiFiBridge-${VERSION}.dmg"
ZIP="build/WiFiBridge-${VERSION}-macos.zip"
NOTARY=(--key "$ASC_KEY_PATH" --key-id "$ASC_KEY_ID" --issuer "$ASC_ISSUER_ID")

notarize() {
  local out id status
  out="$(xcrun notarytool submit "$1" "${NOTARY[@]}" --wait --output-format json)"
  echo "$out"
  id="$(plutil -extract id raw - <<<"$out")"
  status="$(plutil -extract status raw - <<<"$out")"
  if [[ "$status" != "Accepted" ]]; then
    xcrun notarytool log "$id" "${NOTARY[@]}" || true
    echo "Notarization of $1 ended with status: $status" >&2
    exit 1
  fi
}

# 1. Notarize the app itself (zip upload), staple, so the zip download works too.
rm -f "$ZIP"
ditto -c -k --keepParent "$APP" build/upload.zip
notarize build/upload.zip
rm build/upload.zip
xcrun stapler staple "$APP"
ditto -c -k --keepParent "$APP" "$ZIP"

# 2. DMG with an Applications shortcut; sign, notarize, staple.
STAGE="$(mktemp -d)"
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
rm -f "$DMG"
hdiutil create -volname "WiFi Bridge" -srcfolder "$STAGE" -ov -format UDZO "$DMG"
rm -rf "$STAGE"
codesign --force --timestamp -s "$WB_SIGN_IDENTITY" "$DMG"
notarize "$DMG"
xcrun stapler staple "$DMG"

# 3. Prove Gatekeeper accepts both, as a downloaded (quarantined) file would be.
xcrun stapler validate "$APP"
xcrun stapler validate "$DMG"
spctl --assess --type execute --verbose=4 "$APP"
spctl --assess --type open --context context:primary-signature --verbose=4 "$DMG"

echo "Notarized: $DMG $ZIP"
