# Building the iOS app (Capacitor) — on your MacBook

The iOS app is the **same web app** (`index.html`) wrapped by Capacitor, exactly like Android.
Capacitor 8 uses **Swift Package Manager**, so there is **no `pod install`** — Xcode resolves the
plugin packages on open.

## Prerequisites (Mac)
- **macOS + Xcode** (from the App Store).
- **Node.js** (to run the `cap` CLI / regenerate web assets).
- An **Apple ID**; a free account can run on your own device for 7 days, the $99/yr Apple Developer
  Program is needed for longer installs / TestFlight.

## Steps
```bash
# 1. get the branch + deps
git clone https://github.com/G384092023/norisugoshi-train-tracker.git
cd norisugoshi-train-tracker
git checkout native-android
npm install

# 2. copy the web layer into the iOS project and open Xcode
npm run cap:open:ios        # = cap:www + cap sync ios + cap open ios
```
In Xcode:
1. Select the **App** target → **Signing & Capabilities** → pick your **Team** (fixes the signing error).
2. Plug in the iPhone, select it as the run destination, press **Run ▶**.
   (First run: on the phone, Settings → General → VPN & Device Management → trust your developer cert.)

To hand the build to a test participant you need TestFlight (paid program) or a direct cable install.

## What works on iOS
- The full web app, **native BLE** (`@capacitor-community/bluetooth-le`), the **survey**, **CSV export**
  (Filesystem + Share sheet), **haptics**, and the **skin-contact / proof-of-fire oracle**
  (`0a` reports on `0x2002`) — all cross-platform Capacitor plugins.
- BLE usage strings + `bluetooth-central` background mode are already set in `Info.plist`.

## iOS limitation — background nap fire (Phase 2b)
The Android background nap alarm (`NapAlarmPlugin`, exact `AlarmManager`) is **Android-only**. iOS has
no equivalent for running code (a BLE write) at a scheduled time while the app is suspended, so on iOS
**nap mode falls back to the foreground timer** (`_NAP` is null → `setTimeout`): it fires reliably only
while the app is open and the screen is on. Everything else behaves identically to Android.
A future iOS-specific approach (local notification + `bluetooth-central` background, with its Apple
constraints) would be a separate Swift plugin — not built.

## Regenerating after web edits
Any change to `index.html` (or the other front-end files) needs a re-sync before rebuilding:
```bash
npm run cap:sync:ios
```
(`cap:sync:all` syncs both Android and iOS.)
