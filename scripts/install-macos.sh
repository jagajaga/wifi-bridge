#!/usr/bin/env bash
# Installs the latest WiFi Bridge for macOS:
#   curl -fsSL https://raw.githubusercontent.com/jagajaga/wifi-bridge/main/scripts/install-macos.sh | bash
#
# Files fetched with curl carry no quarantine flag, so the app opens normally.
# The download is checked against the release's checksums.txt.
# Env: WB_VERSION (tag, default: latest), WB_INSTALL_DIR (default: /Applications,
# falling back to ~/Applications if not writable), WB_NO_OPEN=1 to skip launching,
# WB_BASE_URL to install from another location (CI uses file://).
set -euo pipefail

REPO="jagajaga/wifi-bridge"
[[ "$(uname -s)" == "Darwin" ]] || { echo "WiFi Bridge for Mac needs macOS 14+." >&2; exit 1; }
major="$(sw_vers -productVersion | cut -d. -f1)"
(( major >= 14 )) || { echo "WiFi Bridge needs macOS 14 or newer (this is $(sw_vers -productVersion))." >&2; exit 1; }

if [[ -n "${WB_BASE_URL:-}" ]]; then        # testing: e.g. file:///path/to/dist
  base="$WB_BASE_URL"
elif [[ -n "${WB_VERSION:-}" ]]; then
  base="https://github.com/$REPO/releases/download/$WB_VERSION"
else
  base="https://github.com/$REPO/releases/latest/download"
fi

dest="${WB_INSTALL_DIR:-/Applications}"
if [[ -z "${WB_INSTALL_DIR:-}" && ! -w "$dest" ]]; then
  dest="$HOME/Applications"
fi
mkdir -p "$dest"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "Downloading WiFi Bridge…"
curl -fsSL "$base/WiFiBridge-macos.zip" -o "$tmp/WiFiBridge-macos.zip"
curl -fsSL "$base/checksums.txt" -o "$tmp/checksums.txt"

expected="$(awk '$2 == "WiFiBridge-macos.zip" {print $1}' "$tmp/checksums.txt")"
actual="$(shasum -a 256 "$tmp/WiFiBridge-macos.zip" | awk '{print $1}')"
if [[ -z "$expected" || "$expected" != "$actual" ]]; then
  echo "Checksum mismatch for WiFiBridge-macos.zip (expected ${expected:-none}, got $actual)." >&2
  exit 1
fi

ditto -x -k "$tmp/WiFiBridge-macos.zip" "$tmp/x"
pkill -x WiFiBridge 2>/dev/null || true
rm -rf "$dest/WiFiBridge.app"
ditto "$tmp/x/WiFiBridge.app" "$dest/WiFiBridge.app"
# Belt and braces: a copy from a quarantined source would inherit the flag.
xattr -dr com.apple.quarantine "$dest/WiFiBridge.app" 2>/dev/null || true

echo "Installed $dest/WiFiBridge.app"
if [[ "${WB_NO_OPEN:-}" != "1" ]]; then
  open "$dest/WiFiBridge.app"
fi
