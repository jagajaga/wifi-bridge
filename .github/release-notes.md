## Install

**Android (10+)** — download `wifi-bridge-*.apk` and open it on the phone
(allow "install unknown apps" for your browser or file manager when asked).

**macOS (14+)** — download `WiFiBridge-*-macos.zip`, unzip, move
`WiFiBridge.app` to Applications. The app is ad-hoc signed, not notarized, so
the first launch needs: right-click → Open, or

```sh
xattr -dr com.apple.quarantine /Applications/WiFiBridge.app
```

Then: pair from the Mac menu ("Pair a phone…") by scanning the QR with the
Android app, and add networks on the phone by sharing the Wi-Fi QR image from
Settings → Wi-Fi → network → Share. See the README for details.
