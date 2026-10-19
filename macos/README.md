# wifi-bridge — macOS

Menu-bar app that gets Wi-Fi passwords from your paired Android phone over
Bluetooth LE and joins the network, automatically, when the Mac is offline.
Wire format: [../docs/protocol.md](../docs/protocol.md).

## Layout

| Path | What |
|------|------|
| `Sources/WiFiBridgeCore/` | All protocol logic, platform independent (swift-crypto): keys, seal/open, framing, messages, pairing QR, proof, sync JSON, and the pairing / sync client state machines over an abstract `FrameLink`. |
| `Tests/WiFiBridgeCoreTests/` | Tests against `../docs/test-vectors.json`, plus the state machines driven by an in-memory fake phone. |
| `Sources/WiFiBridge/` | The app: SwiftUI `MenuBarExtra`, CoreBluetooth central, CoreWLAN, CoreLocation, Keychain. macOS only (declared under `#if os(macOS)` in `Package.swift`). Swift 5 language mode; the core is Swift 6. |
| `scripts/build-app.sh` | Builds the universal `build/WiFiBridge.app`; signs with Developer ID + hardened runtime when `WB_SIGN_IDENTITY` is set, else ad-hoc. |
| `scripts/notarize.sh` | Notarizes and staples the app and a DMG, then checks Gatekeeper accepts both (needs `WB_SIGN_IDENTITY`, `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_PATH`). |
| `scripts/import-signing-cert.sh` | CI helper: imports the Developer ID `.p12` into a temporary keychain. |
| `WiFiBridge.entitlements` | Hardened-runtime Location entitlement (needed to see SSIDs). |

## Build

On a Mac with Xcode 16+ (or a Swift 6 toolchain) and macOS 14+:

```sh
cd macos
scripts/build-app.sh          # -> macos/build/WiFiBridge.app
open build/WiFiBridge.app
```

A local build without `WB_SIGN_IDENTITY` is ad-hoc signed and not sandboxed;
released builds are Developer ID signed and notarized. To sign locally:
`WB_SIGN_IDENTITY="Developer ID Application: …" scripts/build-app.sh`, then
`scripts/notarize.sh`. Tests (any OS):

```sh
swift test
```

## First run: permissions

1. **Bluetooth** — macOS asks when the app starts. Required to talk to the phone.
   If you said no: System Settings › Privacy & Security › Bluetooth.
2. **Location** — macOS 14+ hides Wi-Fi network names (SSIDs) from apps without
   Location Services access, so without it the app sees no networks. The app asks
   at first launch; the menu shows the status and a button to ask again. If
   denied: System Settings › Privacy & Security › Location Services.
3. **Keychain** — passwords, the Mac's private key and `macId` are stored in your
   login keychain. Released builds keep a stable Developer ID signature, so this
   is silent across updates. Ad-hoc local builds get a new signature on every
   rebuild, and macOS may ask "WiFi Bridge wants to use your confidential
   information stored in … keychain" — choose *Always Allow*.

## Pairing

1. On the Mac: menu › **Pair a phone…**. A window shows a QR code (valid 10 min).
2. On the phone: open wifi-bridge and scan the QR code. Keep the app's BLE
   service running.
3. The Mac scans for phones advertising the wifi-bridge service, asks each one,
   checks its proof, and finishes when the phone answers `PAIR_DONE`. The window
   says *Paired*. Phones that have not scanned this code (or give a wrong proof)
   are skipped.

Paired phones (public id + public key only) are stored in
`~/Library/Application Support/WiFiBridge/phones.json`. Unpair from the menu.

## How auto-sync behaves

Every 20 s, and shortly after CoreWLAN reports a link or power change, if
**Auto-sync** is on, Wi-Fi is powered on and not associated:

1. Scan nearby networks (strongest first).
2. Try networks whose password is already cached in the Keychain
   (service `dev.wifibridge.network`, account = SSID). A join that fails is not
   retried with the same password for 2 minutes.
3. Otherwise, ask the phone: BLE scan for the service, connect, read INFO, check
   the phone is paired, send the visible SSIDs, receive and verify the response
   (including the nonce echo), cache what came back, and join the strongest one.
   Finding, connecting and sending the request has a 15 s budget; once the
   request is written the Mac waits up to 75 s for the answer, so a phone with
   *Share automatically* off has its full 60 s Share / Deny prompt. The phone is
   not asked again within 2 minutes unless a visible SSID has not been asked
   about in that time.

**Sync all networks from phone** asks for every network in the phone's vault and
caches them all (and joins one if the Mac is offline). **Cached networks** lists
what is cached; *Forget* removes one from the Keychain.

The menu-bar icon is `wifi` when associated, `wifi.slash` when not, and a sync
arrow while busy.

## Unverified

Read this before trusting the app.

- **The app target has never been compiled.** It was written on Linux, where
  CoreBluetooth, CoreWLAN, CoreLocation, Security and SwiftUI do not exist. The
  only check it has had is a type-check against hand-written stubs of those
  frameworks, written from memory — which proves the Swift is self-consistent
  given *my* idea of Apple's signatures, not that the signatures are right.
  SwiftUI views and the QR rendering were not type-checked at all. Expect a few
  compile fixes on first build.
- **No BLE behaviour has been exercised**: scanning, connecting, GATT discovery,
  notifications, write responses, MTU, timeouts, reconnects — none of it has run
  against a real phone or even a real Mac.
- **No CoreWLAN behaviour has been exercised**: whether `scanForNetworks` returns
  SSIDs with Location granted to an ad-hoc signed, unsandboxed app; whether
  `associate(to:password:)` works without admin prompts; whether
  `wlanChannel() != nil` is a correct "associated" test when SSIDs are redacted;
  whether the CoreWLAN event delegate callbacks fire.
- **Keychain** behaviour (ACL prompts with ad-hoc signatures, `kSecMatchLimitAll`
  listing) is untested.
- **Location prompt**: whether `requestWhenInUseAuthorization` shows a prompt
  for an `LSUIElement` app on macOS 14/15 is untested.
- **Chunk size**: the spec says chunk = negotiated max value length − 1. The app
  uses `maximumWriteValueLength(for: .withoutResponse)` (ATT MTU − 3, a single
  ATT write) rather than `.withResponse` (which reports 512 on Apple platforms and
  would make CoreBluetooth use prepared/long writes the phone must reassemble).
  Writes are still sent with response. Interop with the Android GATT server is
  untested.
- Hidden networks returned by the phone are cached but cannot be joined
  automatically (they never appear in a scan).

What *is* verified: `swift test` on Linux — the core reproduces
`docs/test-vectors.json` byte for byte (session key, INFO, QR, proof,
PAIR_REQUEST/ACCEPT/CONFIRM, SYNC_REQUEST, SYNC_RESPONSE, framing), and the
pairing/sync state machines behave against an in-memory fake phone. On macOS
`import Crypto` re-exports CryptoKit, so the core on a Mac runs Apple's
implementation, which these tests have not run against.
