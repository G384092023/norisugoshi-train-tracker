# Native Android (Capacitor) — the free path

Goal: turn the existing web app into a **real installable Android app** that can keep
**tracking + driving the BLE haptic in the background** (screen off / other app open) —
the one thing the web version can't do. Everything here is **$0** for Android.

> You reuse ~all of the current code. The UI, tracking logic, direction handling,
> ETA/timetable, holiday calendar, alerts — all carry over unchanged. Only two parts
> change: **where data is fetched from**, and **how BLE is called**. The genuinely new
> work is the **background service** (Phase 2).

---

## Cost: $0 for Android
| Thing | Cost |
|-------|------|
| Capacitor, all plugins (npm) | Free / open-source |
| Android Studio + Android SDK | Free |
| Test on your own phone (USB install) | Free |
| Give the `.apk` to test participants | Free (sideload) |
| Google Play Store (optional, not needed) | $25 one-time |

No Mac needed (that's only for iOS). No server bill if you call ODPT directly (see Phase 1).

---

## Prerequisites (one-time, all free)
1. **Node.js** — already installed.
2. **Android Studio** — https://developer.android.com/studio (bundles the SDK + a JDK).
3. An **Android phone** with Developer Mode + USB debugging on (Settings → About → tap
   Build number 7×, then Developer options → USB debugging).

---

## Phase 1 — wrap the app as native (foreground works) · ~half a day

### 1. Put the web files in a clean folder
Create `www/` containing just the front-end: `index.html`, `sw.js`,
`manifest.webmanifest`, `icon.svg`. (Leave `server.js` out — native doesn't use it.)

### 2. Add Capacitor
```bash
npm install @capacitor/core @capacitor/cli @capacitor/android
npx cap init "乗り過ごし防止" "com.norisugoshi.app" --web-dir="www"
npx cap add android
```

### 3. Point data fetching at a real URL
In native there's no same-origin `/api`. Two options:

- **Easiest** — keep using your Render proxy. In `index.html` change:
  ```js
  const API = "";   // becomes:
  const API = window.Capacitor ? "https://norisugoshi-train-tracker.onrender.com" : "";
  ```
  (The proxy already sends `Access-Control-Allow-Origin: *`, so it works from the app.)

- **Better — drop the proxy entirely.** Native apps aren't bound by browser CORS, so the
  app can call `https://api.odpt.org` directly. Use the **CapacitorHttp** plugin (it routes
  `fetch` through native, no CORS), move the ODPT key into the app, and you no longer need
  Render, the keep-warm pinger, or `/healthz` at all. (Key sits in the APK — fine for a
  research prototype; rotate if you ever publish.)

### 4. Swap Web Bluetooth → native BLE plugin
`navigator.bluetooth` does **not** exist in Capacitor's WebView. Install the native plugin:
```bash
npm install @capacitor-community/bluetooth-le
npx cap sync
```
Then rewrite just the BLE module (`connectBLE` / `bleSend`) to its API. Rough mapping:
| Web Bluetooth (now) | `@capacitor-community/bluetooth-le` |
|---------------------|-------------------------------------|
| `navigator.bluetooth.requestDevice({filters})` | `BleClient.requestDevice({ services:[BLE_SERVICE] })` |
| `device.gatt.connect()` | `BleClient.connect(deviceId)` |
| `char.writeValue(bytes)` | `BleClient.write(deviceId, service, char, dataView)` |
Same UUIDs (`BLE_SERVICE` / `BLE_CMD`), same `0x01`/`0x00` bytes — the **Arduino sketch
doesn't change at all**.

### 5. Build & install on your phone
```bash
npx cap sync
npx cap open android      # opens Android Studio
```
In Android Studio: plug in the phone → press **Run** ▶ → the app installs. To hand out an
APK: **Build → Build APK(s)** → share the file. Free, no store.

**End of Phase 1:** a real app that does everything the web app does, runs full-screen with
native BLE, no browser, no cold-starts. Still pauses when backgrounded (same as web) — that's
Phase 2.

---

## Phase 2 — true background operation (the napping use case) · the real engineering

The hard requirement: keep **polling ODPT every ~25 s AND writing to the BLE device while the
app is backgrounded / the screen is off**. Android suspends a backgrounded WebView's JS, so
this needs a **foreground service** (a persistent "tracking your train" notification that
keeps the process alive).

Two ways, easiest first:

1. **Foreground-service plugin + keep-alive.** Add a plugin such as
   `@capawesome-team/capacitor-android-foreground-service` to start a foreground service while
   tracking. The `@capacitor-community/bluetooth-le` connection is held by the **native** BLE
   stack, so it survives backgrounding; the service keeps the app alive to run the poll loop.
   Add these to `AndroidManifest.xml`: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
   `POST_NOTIFICATIONS`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`.
   *Risk:* WebView JS timers can still be throttled on some Android versions even with a
   foreground service — test on real hardware.

2. **Native poll loop (most robust).** Move just the ~25 s poll + "near my stop? → write BLE"
   decision into a small **Kotlin foreground service**. The WebView handles UI; the service
   handles background tracking + the haptic. This is a bit of native code but it's the
   bullet-proof version for "buzz me while I nap." Still $0.

**Recommendation:** ship **Phase 1** first (proves the native app + native BLE end-to-end),
then tackle Phase 2 option 1; fall back to option 2 if background throttling bites.

---

## Phase 2b — background NAP FIRE (train-independent) · the sleep use-case

The web nap mode (`openNap`/`napFire`/`napRefire` in `index.html`) works only while the screen is
on — a foreground `setTimeout`. To fire reliably after the user is asleep (screen off, app
backgrounded), the **timer + the fire + the re-fire loop must move into native**, using the same
`AlarmManager.setAlarmClock` backstop that already survives Doze/EMUI (see memory
`emui-suppression-findings`).

**Design that keeps ALL study logic in JS — native only owns timing + BLE-in-background:**

1. **JS resolves the plan up front** (while the app is still foreground, at "start nap"):
   pattern selection (`stimSelectPattern`), the limiter check, strength, channel — then hands
   native the *resolved* payload via a small Capacitor plugin:
   ```
   Nap.schedule({
     fireAtMs,                 // now + delayMin*60000
     steps: [...],             // the chosen pattern's encoded steps (bytes) — native just writes them
     strength, channel,
     refireMs,                 // 0 = single fire; else re-fire interval (respect the 8s refractory floor)
     maxFires                  // = zapMaxPerRide (5) — the safety cap
   })
   ```
   This avoids re-implementing pattern selection, the limiter, and exposure counting in Kotlin.

2. **Native `NapScheduler`**: `AlarmManager.setAlarmClock(fireAtMs, …)` → a `BroadcastReceiver`
   starts a short **foreground service** `NapFireService` (type `connectedDevice`).

3. **`NapFireService.onFire()`**: record `firstFireAtMs = SystemClock.elapsedRealtime()`, write the
   pattern bytes over the **native** BLE stack (`@capacitor-community/bluetooth-le` holds the
   connection across backgrounding), and raise a **full-screen wake notification**
   (`setFullScreenIntent`, high importance, ongoing) whose action is 起きた.

4. **Re-fire loop**: a `Handler.postDelayed` every `refireMs`, re-writing the SAME bytes, until
   the user acks OR `fireCount == maxFires`. These are rescue repeats — they must NOT count as
   new exposures (JS handles the single exposure increment, step 6).

5. **Wake ack**: tapping 起きた (from the full-screen notification / a `showWhenLocked` activity)
   stamps `ackAtMs`; **`responseSec = (ackAtMs − firstFireAtMs)/1000`** — measured from the first
   fire, exactly like the web path. Stop the loop, clear the notification, `bleSend(CLEAR)`.

6. **Hand back to JS**: the plugin resolves the `Nap.schedule` promise (or fires an event) with
   `{ responseSec, fireCount, firstFireAtMs, ackAtMs }`. JS then does what it already does on a
   ride wake: increment the exposure ONCE (`stimRecordExposure(pat.id)`), build the record with
   `alertSource:"nap"`, blank train columns, `fireCount`, and open the survey wizard.

**Manifest additions (beyond Phase 2):** `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` (exact
alarm while asleep), `USE_FULL_SCREEN_INTENT` (the wake screen). Keep the Phase-2 BLE/foreground
perms.

**Why this split is safe:** the experiment's independent variables and safety limiter stay in the
audited JS; native gets only "write these bytes at these times and wake the user," which is the
one thing JS can't do in the background. The web `napFire`/`napRefire` remains the reference
behavior and the foreground fallback for desktop/quick tests.

---

## Permissions the user will be asked (Android 12+)
- **Nearby devices** (Bluetooth scan/connect) — for the haptic device
- **Notifications** — for the foreground-service notification + alerts
- **Location** — still required by Android to *scan* BLE (covered in the README); the app
  doesn't use your position

---

## What stays exactly the same
- The whole UI and all tracking logic in `index.html`
- The **Arduino sketch** (`arduino/norisugoshi_ble/…`) — unchanged
- BLE UUIDs and the `0x01`/`0x00` protocol
- The deployed **web app** keeps working for desktop/quick tests; native is additive

## iOS (for reference)
Same Capacitor project also builds for iOS, **but**: needs a **Mac** (or cloud macOS build)
and the **$99/yr Apple Developer Program** to put it on participants' iPhones. Do Android
first; add iOS only when the concept is proven and the budget's there.

---

## Day-to-day: debugging & redeploying vs the web app
Honest comparison so there are no surprises. It depends on **what** you change.

**Web-layer changes (HTML/CSS/JS — ~90% of edits: UI, tracking logic, ETA, alerts):**
- **Debugging = same as web.** Plug in the phone → open `chrome://inspect` on your PC →
  the *same* Chrome DevTools (console, breakpoints, network) on the app's WebView.
- **While developing:** `npx cap run android --livereload --external` hot-reloads edits in
  the app, just like web dev.
- **Shipping to users:** rebuild the APK **or** use OTA (below).

**Native-layer changes (Phase-2 service, plugins, permissions, AndroidManifest):**
- Need a full **Android Studio rebuild** (~30 s–2 min) + reinstall; debugged via Logcat.
- Change **rarely** — build the foreground service once, then mostly leave it alone.

| Task | Web app | Native Android |
|------|---------|----------------|
| See a web edit while developing | instant | instant (live-reload) |
| Debug web code | DevTools | **same DevTools** (remote) |
| Push a web-layer fix to users | `git push` → refresh | rebuild APK **or OTA** |
| Change native code | n/a | rebuild + reinstall APK |
| Debug native code | n/a | Android Studio / Logcat |

### Free OTA = most fixes stay almost as easy as `git push`
You don't need a new APK for web-layer tweaks. Capacitor supports **over-the-air updates**
of the web layer (HTML/JS) — installed apps pull the update on next launch, no reinstall,
no store review (app stores allow OTA of interpreted code). Free/open-source option:
**`@capgo/capacitor-updater`** (self-host or free tier). With it, a UI/logic fix is push →
auto-update, much like the web app. **Native** changes still need a new APK.

### Two habits that keep it painless
1. **Keep the web app as the fast playground** — prototype/debug a change there first
   (instant `git push`), then carry it into native. Native is *additive*, not a replacement.
2. **Touch native rarely** — the background service is "build once, mostly leave alone."

**Net:** with OTA, expect only mild extra friction (~10–20%) for typical web-layer changes,
plus a real-but-infrequent APK rebuild when you change native code.
