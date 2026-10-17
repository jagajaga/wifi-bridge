# wifi-bridge — Android

Kotlin / Jetpack Compose app that keeps an encrypted vault of Wi-Fi networks and
answers a paired Mac over BLE (the phone is the GATT peripheral). The wire format is
[`../docs/protocol.md`](../docs/protocol.md); the code is tested against
[`../docs/test-vectors.json`](../docs/test-vectors.json).

- `applicationId` `dev.wifibridge`, minSdk 29 (Android 10), target/compile SDK 35.
- No Google Play Services and no network permission. QR decoding is ZXing, on the device.

## Build

Prerequisites: JDK 17 and an Android SDK with platform `android-35` and build-tools `35.0.0`.

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties   # gitignored
./gradlew :app:testDebugUnitTest                   # JVM unit tests (load ../docs/test-vectors.json)
./gradlew :app:assembleDebug                       # -> app/build/outputs/apk/debug/app-debug.apk
```

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed with the local debug key. Uninstalling the app destroys the vault:
its key is in the Android Keystore, and backup is turned off on purpose.

## Using it

1. **Turn the bridge on.** Open wifi-bridge and switch on *Answer nearby Macs over
   Bluetooth*. This starts a foreground service (it keeps a "listening" notification)
   that advertises the wifi-bridge service UUID and nothing else (no device name).
   *Start when the phone boots* restarts it after a reboot.
2. **Pair a Mac.** On the Mac, open pairing (it shows a `WBPAIR1` QR). On the phone tap
   **Pair a Mac** and scan it with the camera. The phone holds that pairing for
   10 minutes; the Mac finishes pairing over Bluetooth during that time. The Mac then
   appears under *Paired Macs* with **Share automatically** (on by default) and **Unpair**.
   - Share automatically **on**: when the Mac asks, the phone answers with no prompt.
   - Share automatically **off**: the phone posts a "Share Wi-Fi password?" notification
     with **Share** / **Deny**. No answer within 60 s sends `timeout`.
3. **Add networks** (the app cannot read Android's own saved passwords):
   - **Samsung One UI:** Settings → Connections → Wi-Fi → tap the gear next to the network →
     **QR code** (or *Share*) → **Share** (or *Save as image*, then share it from Gallery) →
     choose **Add to wifi-bridge**. The app decodes the QR offline, then shows a review
     screen (name, security, password hidden behind **Show**). Nothing is saved until you tap **Save**.
     Sharing several images at once works too (one card per image).
   - **Pixel / stock Android:** Settings → Network & internet → Internet → gear → **Share** →
     screenshot the QR and share the screenshot to wifi-bridge.
   - **Text:** share text to *Add to wifi-bridge*. A `WIFI:T:…;S:…;P:…;;` payload is parsed;
     any other text is treated as a password and you type the network name (prefilled with
     the current Wi-Fi name when Android lets the app read it — that needs location).
   - **Manual:** *Add network* in the app; each network has *Edit* and *Delete*.
   - **Shizuku import — TODO, not implemented.** The design ([../docs/design.md](../docs/design.md)
     §4) proposes an optional one-shot "Import from system" using Shizuku. Whether a
     shell-UID caller can actually read pre-shared keys has not been checked; spike it first.

## Permissions the user will see

| When | Permission | Why |
|------|-----------|-----|
| Android 12+ (API 31+), turning the bridge on | **Nearby devices** (`BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`) | advertise + run the GATT server |
| Android 10–11 (API 29–30), turning the bridge on | **Location** (`ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`) | Bluetooth LE on these versions. `BLUETOOTH` / `BLUETOOTH_ADMIN` are install-time. |
| Android 13+ (API 33+), turning the bridge on | **Notifications** (`POST_NOTIFICATIONS`) | the Share/Deny prompt. If refused, requests from Macs with *Share automatically* off time out. |
| Pair a Mac | **Camera** (asked by the scanner screen) | scan the pairing QR |
| install-time, no prompt | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `RECEIVE_BOOT_COMPLETED`, `ACCESS_WIFI_STATE` | service type `connectedDevice`, start on boot, read current SSID |

## Layout

```
app/src/main/java/dev/wifibridge/
  protocol/   pure Kotlin (no android.* imports; a test enforces it)
    WbCrypto.kt     X25519 + HKDF + ChaCha20-Poly1305 (Tink subtle), proof (javax.crypto HMAC), AADs
    Messages.kt     message types, encode/decode, INFO value, error codes
    Framing.kt      FIRST/LAST frames, chunk = MTU-3-1 (min 19), reassembly with 64 KiB cap
    TxQueue.kt      one notification in flight, next on onNotificationSent, per-device MTU
    PhoneEngine.kt  pairing state machine + sync handling (exact SSID match / all)
    WifiQr.kt       WIFI: payload parser; PairingQr.kt WBPAIR1 parser
    Models.kt, Stores.kt  JSON shapes (kotlinx.serialization), pending/paired records
  qr/QrImageDecoder.kt   pure ZXing decode over (IntArray argb, width, height)
  data/          Tink AEAD keyset wrapped by an Android Keystore master key; encrypted files for
                 identity, vault, peers; non-secret switches in SharedPreferences
  ble/           foreground service, GATT server + advertiser adapter, approval notifications, boot receiver
  ui/            Compose: main screen, share-import review screen
```

Storage note: design.md mentions Jetpack DataStore; this uses Keystore-wrapped Tink AEAD
over small atomic files instead (same at-rest property, fewer dependencies).

## What is tested, and what is not

**Tested on the JVM** (`./gradlew :app:testDebugUnitTest`): every value in
`test-vectors.json` — keys, K from both sides, INFO, pairing QR, proof, PAIR_ACCEPT bytes,
opening/re-creating PAIR_CONFIRM, SYNC_REQUEST, SYNC_RESPONSE byte for byte (explicit
nonce via Tink's `InsecureNonceChaCha20Poly1305`), tampered box and wrong-AAD rejection,
all framing cases (split and reassemble), all `wifiQr` cases; the pairing state machine
and sync matching; notification flow control; and QR decoding of synthetic
Samsung-style images (EC level H, round logo over the centre, low-contrast, inverted,
transparent, oversized).

**Not verified — needs a real phone and Mac:**

- Nothing in `ble/` has run. GATT server, advertising, MTU handling, long reads of INFO,
  prepared writes, CCCD subscription, and `onNotificationSent` pacing compile, but
  no device has exercised them. Interop with the macOS app is untested.
- Peripheral mode varies by phone (`bluetoothLeAdvertiser` can be null). The app reports
  it but cannot fix it.
- Bluetooth turned off and on while the service runs is not handled. Turn the bridge off
  and on again.
- Android Keystore / Tink `AndroidKeysetManager` persistence, including behaviour after
  a reboot or OS update.
- Camera pairing scan (zxing-android-embedded `CaptureActivity`).
- Approval notifications: the Share/Deny actions, the 60 s timeout, and behaviour with
  notifications blocked.
- Start on boot. A `connectedDevice` foreground service started from `BOOT_COMPLETED`
  is expected to be allowed on Android 14/15, but that has not been checked on a device.
- Real Samsung "Share network" PNGs. The decoder is tested only on synthetic look-alikes.
- Doze / battery-optimisation effects on a long-running advertiser.
- Reading the current SSID for the text-share prefill (needs location permission and
  location turned on; the import screen does not request it).
