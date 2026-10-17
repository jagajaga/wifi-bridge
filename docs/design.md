# Design

## Problem

A Mac with no network connection needs the password for a nearby Wi-Fi network
that the user's Android phone already knows. Apple solves this between iPhone and
Mac because it owns both OSes. We have to do it with public APIs.

## Hard constraint: Android does not expose saved Wi-Fi passwords

Since Android 10, `WifiManager.getConfiguredNetworks()` returns nothing useful to
ordinary apps, and credentials are never returned. Only system / device-owner
apps can read them. So the phone app keeps **its own encrypted vault** of
networks, populated by the user.

### Ways a network gets into the vault

1. **Share an image (primary).** Android's own network-share screen — e.g. Samsung
   One UI *Wi-Fi → network → Share network*, Pixel *Share* — renders a QR code
   and lets you share it as an image. wifi-bridge registers as an
   `ACTION_SEND` / `ACTION_SEND_MULTIPLE` target for `image/*`, decodes the QR
   **on-device** (ZXing or bundled ML Kit barcode — no network calls), and
   parses the standard Wi-Fi QR payload:

   ```
   WIFI:T:WPA;S:<ssid>;P:<password>;H:<true|false>;;
   ```

   Escaping per spec: `\` `;` `,` `:` `"` are backslash-escaped inside values.
   `T` may be `WPA`, `WEP`, `SAE`, `nopass`, or absent.

2. **Share text.** Registered for `text/plain`. If the text is a `WIFI:` payload,
   parse it; otherwise treat it as a password and ask for the SSID (prefilled
   with the phone's current SSID when location permission allows reading it).

3. **Paste / type** in the app.

4. **Shizuku import (optional, on demand only).** A manual "Import from system"
   button that uses Shizuku once and then releases it. Never required, never
   running in the background.
   - *Mechanism (unverified):* reading credentials with shell-level privileges
     via `WifiManager.getPrivilegedConfiguredNetworks()` or `cmd wifi`. Whether
     the shell UID is allowed to read pre-shared keys on the user's Android
     version has not been tested; treat this path as a spike before relying on it.

The vault is stored with Jetpack DataStore encrypted by an Android Keystore key.

## Transport: BLE

Neither device has network connectivity in common, so the channel is BLE.

- **Mac = central**, **phone = peripheral** (GATT server). Android supports
  peripheral mode via `BluetoothLeAdvertiser`; macOS CoreBluetooth is a
  well-supported central.
- The phone advertises a custom 128-bit service UUID while the app's foreground
  service is running (low-power advertising; a persistent notification is
  required by Android for a foreground service).
- The Mac connects, writes a **request** characteristic `{ssid, nonce}`, and
  subscribes to a **response** characteristic.
- The phone posts a notification: "Share password for *ssid* with *Mac name*?"
  On **Share**, it writes the encrypted credential to the response
  characteristic. On **Deny** or timeout, it writes a refusal.

Alternative considered: Mac advertises, phone scans. Rejected for v1 because
background BLE scanning on Android is more heavily throttled than advertising.

## Pairing and encryption

BLE link-layer pairing is not relied on. App-level:

1. Mac shows a QR containing its X25519 public key + a device id.
2. Phone scans it. The QR also carries a one-time pairing secret; the phone
   proves it scanned the QR by returning an HMAC over its own public key, and
   the Mac proves it holds its private key with an encrypted confirm message.
   No codes to compare. Exact bytes in [protocol.md](protocol.md).
3. Each request/response is sealed with a key derived from X25519 + HKDF, using
   ChaCha20-Poly1305 (macOS: CryptoKit; Android: Tink). The request nonce is
   echoed inside the response so a captured response cannot be replayed.

Only paired Macs get a prompt; requests from unknown devices are ignored.

## Mac side

- Menu-bar app (SwiftUI `MenuBarExtra`).
- Lists visible networks with `CWWiFiClient.shared().interface()?.scanForNetworks`.
  macOS 14+ redacts SSIDs unless the app has Location Services permission, so the
  app requests it.
- On user pick (or when the interface has no SSID and a known network is in
  range), sends the BLE request.
- Joins with `CWInterface.associate(to:password:)`.
- Optionally the user can let macOS remember the network as usual afterwards.

## Out of scope for v1

- Mac → Android direction.
- Enterprise (802.1X) networks.
- Multiple phones per Mac (data model should not preclude it).

## Decisions

- Minimum versions: Android 10+ (API 29) and macOS 14+.
- **Automatic sync.** When the Mac's Wi-Fi is on but not associated, it first
  tries passwords it has already cached in its Keychain from earlier syncs; if
  none apply it asks the phone over BLE for the SSIDs it can see, caches the
  answer and joins. The phone answers without interaction when the Mac's
  *Share automatically* setting is on (the default); otherwise it prompts.
  The menu also offers "Sync all networks from phone" to pre-fill the cache.
- Shizuku import is deferred: it is a manual, on-demand extra, never required.
- Wire protocol: [protocol.md](protocol.md). Both sides test against
  `test-vectors.json`, generated by an independent Python implementation
  (`scripts/gen_test_vectors.py`).
