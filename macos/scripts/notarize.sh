#!/usr/bin/env bash
# Notarizes a Developer-ID-signed build/WiFiBridge.app, staples it, packages it
# (scripts/package.sh), notarizes + staples the DMG, and checks Gatekeeper
# accepts both.
#
# Requires (macOS): WB_SIGN_IDENTITY, ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH (.p8)
set -euo pipefail
cd "$(dirname "$0")/.."

: "${WB_SIGN_IDENTITY:?}" "${ASC_KEY_ID:?}" "${ASC_ISSUER_ID:?}" "${ASC_KEY_PATH:?}"
VERSION="${WB_VERSION_NAME:-0.1.0}"
APP="build/WiFiBridge.app"
DMG="build/WiFiBridge-${VERSION}.dmg"
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

ditto -c -k --keepParent "$APP" build/upload.zip
notarize build/upload.zip
rm build/upload.zip
xcrun stapler staple "$APP"

scripts/package.sh            # zip now contains the stapled app; DMG is signed
notarize "$DMG"
xcrun stapler staple "$DMG"

xcrun stapler validate "$APP"
xcrun stapler validate "$DMG"
spctl --assess --type execute --verbose=4 "$APP"
spctl --assess --type open --context context:primary-signature --verbose=4 "$DMG"
echo "Notarized $DMG"
