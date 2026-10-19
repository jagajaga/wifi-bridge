#!/usr/bin/env bash
# CI helper: import a base64 .p12 into a fresh temporary keychain and export
# WB_SIGN_IDENTITY (the "Developer ID Application" identity hash) to $GITHUB_ENV.
# Usage: import-signing-cert.sh <keychain-path>   (env: CERT_P12_B64, CERT_PASSWORD,
#        optional CERT_TRUST_PEM for the CI self-test)
set -euo pipefail
KC="$1"
: "${CERT_P12_B64:?}" "${CERT_PASSWORD:?}"
KC_PW="$(uuidgen)"
security create-keychain -p "$KC_PW" "$KC"
security set-keychain-settings -lut 21600 "$KC"
security unlock-keychain -p "$KC_PW" "$KC"
P12="$(mktemp)"
echo "$CERT_P12_B64" | base64 -D > "$P12"
security import "$P12" -k "$KC" -P "$CERT_PASSWORD" -T /usr/bin/codesign -f pkcs12
rm -f "$P12"
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KC_PW" "$KC" >/dev/null
# shellcheck disable=SC2046
security list-keychains -d user -s "$KC" $(security list-keychains -d user | tr -d '"')
# CI self-test only: trust a self-signed root so its identity counts as valid.
if [[ -n "${CERT_TRUST_PEM:-}" ]]; then
  sudo security add-trusted-cert -d -r trustRoot -p codeSign -k "$KC" "$CERT_TRUST_PEM"
fi
ID="$(security find-identity -v -p codesigning "$KC" | awk '/"Developer ID Application/ {print $2; exit}')"
if [[ -z "$ID" ]]; then
  security find-identity -v -p codesigning "$KC"
  echo "::error::no valid 'Developer ID Application' identity in the p12" >&2
  exit 1
fi
echo "WB_SIGN_IDENTITY=$ID" >> "${GITHUB_ENV:-/dev/stdout}"
