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

## ⚠️ Native background layer — REQUIRED Xcode steps (do these once)
This build now includes the proven native Swift layer (`NorisugoshiTracker` plugin) so BLE + the nap
fire run in the **background** (location keep-alive spine + `bluetooth-central`), like your old app.
Xcode does not auto-compile files just dropped in the folder — you must wire them once:

1. **Add the Swift files to the App target.** In Xcode's Project navigator, right-click the **App**
   group → *Add Files to "App"…* → select these (in `ios/App/App/`) and tick the **App** target:
   `BleManager.swift`, `StimulusPattern.swift`, `StimulusLimiter.swift`, `TrackingManager.swift`,
   `NorisugoshiTrackerPlugin.swift`, `MainViewController.swift`.
   (Verify each shows the App target under File inspector → Target Membership.)
2. **Signing & Capabilities → + Capability → Background Modes**, then tick **Location updates** and
   **Uses Bluetooth LE accessories**. (The Info.plist keys are already set; this flips the target's
   entitlement UI to match.)
3. The storyboard already points at `MainViewController` (which registers the plugin); no action.

If `NorisugoshiTracker` isn't registered (files not added to the target), the app still runs but the
nap falls back to the **foreground timer** — so if background firing doesn't work, check step 1 first.

## How iOS background firing works here (once the steps above are done)
iOS has no `AlarmManager`. Instead the native layer stays alive with the **location background mode**
(the keep-alive spine), fires the nap from **native Swift** (`TrackingManager.napArm` → a native timer
→ `BleManager.playPattern`, re-firing until 起きた), and schedules a **local-notification backstop** so
a killed process still wakes the sleeper. This is the same technique your old train-tracking build used
to survive the background. Device settings still matter: **keep "Always" location allowed** and, for the
most reliable overnight run, keep it on a charger. If the native files aren't added to the target (step 1),
it silently falls back to the foreground JS timer.

## Regenerating after web edits
Any change to `index.html` (or the other front-end files) needs a re-sync before rebuilding:
```bash
npm run cap:sync:ios
```
(`cap:sync:all` syncs both Android and iOS.)
