# Handoff — NEXT SESSION: build the iOS app (on the MacBook)

**Written 2026-09-25 from the Windows PC.** Everything below is on branch **`native-android`**
(pushed to GitHub). The next session runs **on the Mac** (`claude` started in the repo there) so it
can drive `xcodebuild`/Xcode. This session could not compile iOS — it ran on Windows.

## Goal
Compile, sign, and run the iOS app on a real iPhone, and confirm the **native background nap fire**
works (screen off). The Android build is a secondary target (Android Studio).

## Start-of-session setup (Mac)
```bash
git clone https://github.com/G384092023/norisugoshi-train-tracker.git
cd norisugoshi-train-tracker && git checkout native-android && npm install
npm run cap:open:ios      # = cap:www + cap sync ios + open Xcode
```
Keep the clone at a plain ASCII path (e.g. `~/norisugoshi`). The original dev path contains `課題`
(non-ASCII), which broke the Android Gradle build; avoid it on the Mac too.

## ⚠️ REQUIRED Xcode steps (or the native layer silently no-ops)
Capacitor 8 iOS = **Swift Package Manager, NO CocoaPods / no `pod install`.**
1. **Add the 6 Swift files to the App target.** In Xcode, right-click the **App** group →
   *Add Files to "App"…* → select from `ios/App/App/`: `BleManager.swift`, `StimulusPattern.swift`,
   `StimulusLimiter.swift`, `TrackingManager.swift`, `NorisugoshiTrackerPlugin.swift`,
   `MainViewController.swift` → tick the **App** target. Verify each file's Target Membership = App.
   (They are on disk + in git, but Xcode only compiles files listed in the target.)
2. **Signing & Capabilities → + Capability → Background Modes** → tick **Location updates** +
   **Uses Bluetooth LE accessories**. Set your **Team** for signing.
3. `Main.storyboard` already sets the VC class to `MainViewController` (registers the plugin) — no action.
4. On device first run: Settings → General → VPN & Device Management → trust the dev cert; and
   **allow "Always" Location + Bluetooth** when prompted (Always location is the background spine).

If `NorisugoshiTracker` isn't compiled in (step 1 skipped), the app still runs but `_NT` is null and
nap falls back to the **foreground JS timer** (screen must stay on). That's the #1 thing to check if
background firing doesn't work.

## Architecture (what to keep in your head)
- **`_NT` = the `NorisugoshiTracker` native plugin (Swift + Java)** OWNS: BLE connect/write, pattern
  playback, the safety limiter, the delivery oracle, and the background nap. The community
  `@capacitor-community/bluetooth-le` (`_BLE`) is now only the **device picker** on native
  (`requestDevice` → deviceId → `_NT.bleConnect`); `_BLE` is still the full path on **web**.
  All `_NT` branches in `index.html` are `IS_NATIVE`-gated; web behavior is unchanged.
- **iOS background = location keep-alive spine + `bluetooth-central`** (no foreground service on iOS).
  Nap: JS resolves the pattern (policy + per-mode isolation) → `_NT.napArm({delayMs,patternId,strength,
  channel,refireMs,maxFires})` → native `TrackingManager` fires + re-fires with the screen off +
  schedules a local-notification backstop. On fire it emits **`napFired {firedAt}`** → JS
  `onNativeNapFired` times `responseSec` from `firedAt`; 起きた → `_NT.napAck()`.
- **Android background = foreground service + `setAlarmClock`** (Doze/EMUI-proof). Nap uses the
  Phase-2b `NapAlarm` (relaunch) → JS `napFire()` → `_NT.firePattern` (native BLE). Java auto-compiles.
- **Oracle:** native emits a **`delivered`** event → `deliveredPulses` + `skinContact` on the row.
- **Exposures** are still counted in JS (per-mode isolation: `nap` vs `train` buckets in
  `norisugoshi_pattern_state`); native only fires. Recorded on a successful fire.

## Files
- iOS Swift: `ios/App/App/{BleManager,StimulusPattern,StimulusLimiter,TrackingManager,
  NorisugoshiTrackerPlugin,MainViewController}.swift`; `Info.plist` (location+BLE modes + strings);
  `Base.lproj/Main.storyboard` (VC class). Nap lives in `TrackingManager` (search `napArm`).
- Android Java: `android/app/src/main/java/com/norisugoshi/app/{BleManager,NorisugoshiTracker,
  StimulusLimiter,StimulusPattern,TrackingService,MainActivity,NapAlarmPlugin,NapAlarmReceiver}.java`;
  `AndroidManifest.xml`.
- Web: `index.html` — `initPlugins` (the `_NT` listeners), `connectBLE_native`, `blePlayPattern`,
  `bleSend`, `startNap`/`onNativeNapFired`/`napWake`/`cancelNap`, `stimSelectPattern` (policies incl.
  `scheduled`), `exportLog`.
- Guides: `BUILD_IOS.md` (authoritative), `NATIVE_ANDROID.md`.

## Likely first-build fixes (blind-written Swift/Java — expect a few)
- Swift files not in target (step 1) → "cannot find NorisugoshiTrackerPlugin in scope" or plugin not
  registering. iOS deployment target / API availability (`setShowWhenLocked` etc. are Android — iOS
  side is separate). Check `StimulusPattern.byId`, `BleManager.playPattern`, `cancelPattern` exist as
  called by the new `TrackingManager` nap extension.
- `_NT.napArm` params: sent as numbers from JS; Swift reads `getDouble`/`getInt`. `delayMs`/`refireMs`
  are milliseconds.
- Community `_BLE.requestDevice` on iOS returns a deviceId that `_NT.bleConnect` retrieves via
  `retrievePeripherals(withIdentifiers:)`. If connect fails, that handoff is the suspect.

## On-device verification checklist (after it runs)
1. Connect the Pavlok (picker shows only "Pavlok…"), pill goes Connected — no false "failed" prompt.
2. Settings → test a **zap** → the band fires (not just vibrate).
3. **Nap, screen off:** arm a short nap (e.g. 3 min), lock the screen, phone down → it fires the zap
   in the background; tap 起きた → responseSec logs from the fire.
4. Export → **仮眠CSV** has the row with `deliveredPulses`/`skinContact` populated (zap), `awakeLocal`
   in JST, `patternPolicy`, per-mode `exposures`.
5. Survey after a nap = sleepState(deep/light/none) + eval (no alert-timing, no "awake" option).
6. `scheduled` policy: single ×10 → the 5 varying ×2.

## Study status (unchanged, for context)
Instrument work; data analysis is separate. R1/R2 CSVs analyzed earlier (see memories
`respondent2-state-not-reset`, `nap-mode-feature`, `native-build-status`, `zap-habituation-study`).
Participant CSVs/pptx are intentionally **git-ignored / untracked** — never commit them.
