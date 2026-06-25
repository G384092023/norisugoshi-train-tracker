# 乗り過ごし防止 — Train Tracker

Pick the **line** and the **specific train you are riding**, choose **where you get off**,
and the app tracks that train live and **alerts you N stops before arrival** — notification,
vibration, beep, and (optional) a BLE haptic device.

Data comes from **ODPT** (公共交通オープンデータ / Open Data for Public Transportation).

---

## Run it

```bash
cd app
node server.js
```

Then open **http://localhost:3000** (works on your phone too if it's on the same Wi-Fi —
use the PC's LAN IP, e.g. `http://192.168.x.x:3000`).

No npm packages — Node's built-in modules only. Node 14+.

---

## Why do I need to run a server? (plain-English)

**Short answer:** the app has to fetch live train data from ODPT, and a web browser is
not allowed to do that directly. A small program on your own computer does it instead.

**The browser security wall (CORS).** A webpage is blocked from reading data from a
*different* website's API unless that API explicitly grants permission. ODPT doesn't
grant it, so a page that tries to call `api.odpt.org` directly just gets rejected. This
rule only applies to browsers — a normal program (Node.js) can fetch ODPT with no issue.

**So Node.js is a middleman** that runs on your machine:

```
 your browser  ──►  Node server (your PC)  ──►  api.odpt.org
   (the page)        server.js, port 3000        (live trains)
       ◄──────────────────  data  ◄──────────────────
```

It (1) fetches from ODPT, (2) hides the API key, and (3) **serves the webpage itself**.

**What `localhost:3000` means.**
- `localhost` = *your own computer* (the address `127.0.0.1`). Nothing leaves your machine.
- `3000` = a **port**, like a numbered door on your computer where the server is listening.
- So `http://localhost:3000` = "talk to the program running on my own computer at door 3000" —
  that's the Node server, handing you both the page and the data.

**Can I just double-click `index.html` instead? No.** Two reasons:
1. The page asks the server for its data (`/api/trains`, `/api/railways`). Those are
   *routes the Node server answers* — not files. With no server running, the line list
   never loads.
2. Double-clicking opens the file as `file://`, a different "origin" — which hits the
   exact CORS wall above.

The app is built so the **same server gives you both the page and the data** from one
address. So the rule is always: **start the server first, then open `http://localhost:3000`**
in your browser — never open the `.html` file directly.

| Action | Works? |
|--------|--------|
| `node server.js` running → open `http://localhost:3000` | ✅ Yes |
| Double-click `index.html` (opens `file:///…`) | ❌ No — no data |
| Server running → open `http://<PC-IP>:3000` on your phone (same Wi-Fi) | ✅ Yes |

---

## How it works

```
 browser (index.html)  ──►  server.js (proxy, :3000)  ──►  api.odpt.org
        ▲  same origin            adds CORS, hides key
        └────────────────────────────────────────────┘
```

A proxy is needed because the ODPT API does not send CORS headers, so a browser page
cannot call it directly. `server.js` also serves the page, so everything is same-origin.

### Flow
1. **On load** the app fetches *all* live trains + railway metadata and shows
   **only the lines that are actually running right now** (and for which we have a
   station list). Dead / unlicensed lines never appear.
2. **Pick your line** → it lists every live train on that line (train number,
   current position, bound-for). Match it to the in-car display.
3. **Pick the train you're on**, then **where you get off** + how many stops early to alert.
4. **Start** → the app polls every 25 s, finds *your* train by its train number,
   counts the stops to your station, and fires the alert when you're within range.

### If you picked the wrong train
You don't have to stop and start over. Two ways to switch:

- **Automatic** — when the tracked train is heading **away** from your stop, an inline
  **"trains going your way"** list appears with a wrong-way warning.
- **Anytime** — tap the **🔀 別の列車に変更 (wrong train? switch)** button in the tracking
  view, even while correctly on track (e.g. you boarded a different train than planned
  but still going the right way).

Either way you get the same list: every live train that *can still reach* your
destination, nearest to you first, each labelled with position, bound-for, and
stops-to-destination. **Tap one** to re-target tracking instantly — your destination and
alert settings are kept.

### If your train drops out of the ODPT feed
ODPT occasionally stops reporting a train for a poll or two (or it finishes its run).
The app keeps the **last known position** on screen and re-checks; if it's gone for
good, it offers the same one-tap replacement list so you can re-target the train you're
actually on without losing your destination setting.

### Endpoints (proxy)
| Route | ODPT call | Purpose |
|-------|-----------|---------|
| `GET /api/trains` | `odpt:Train` (all) | discover which lines are alive |
| `GET /api/trains?railway=X` | `odpt:Train?odpt:railway=X` | live trains on one line |
| `GET /api/railways` | `odpt:Railway` (cached 1 h) | station order + JP/EN titles |

---

## Notes on the data (important for the research)

- Position is **station-level** (`fromStation` / `toStation`) — no GPS between stops.
- Refresh is ~30–60 s on ODPT's side; the app polls every 25 s.
- `toStation` is `null` when a train is stopped at a station.
- **Spiral / loop lines** (e.g. 大江戸線 Oedo, where 都庁前 appears twice in the line
  order) are handled by *walking the station path* instead of subtracting indices,
  so stop counts stay correct around the loop.
- Lines that are live with this key right now: Toei Oedo / Asakusa / Mita / Shinjuku /
  Arakawa tram, and Yokohama Blue / Green. (Tokyo Metro & JR need separate licensing —
  they're filtered out automatically because there's no live train data for them.)

---

## BLE haptic device — Arduino Nano 33 (LED stand-in)

The app talks to an **Arduino Nano 33** over Bluetooth Low Energy. For now an **LED**
stands in for the haptic actuator (vibration motor / electrode), so you can see the
signal work end-to-end before the real hardware is built.

### What happens
On an alert the app writes a 1-byte command to the board; when the alert clears (you
arrive, switch trains, or stop), it writes the "off" byte:

| App event | Byte sent | Arduino does |
|-----------|-----------|--------------|
| Alert fires (N stops away) | `0x01` | blink LED 5× then hold **ON** |
| Arrived / alert cleared / stop | `0x00` | LED **OFF** |
| **Test** button | `0x01` then `0x00` after 1.5 s | quick LED blip |

### Flash the Arduino
1. Open **[`arduino/norisugoshi_ble/norisugoshi_ble.ino`](../arduino/norisugoshi_ble/norisugoshi_ble.ino)** in the Arduino IDE.
2. Install the board package (Boards Manager → *Arduino Mbed OS Nano Boards* for Nano 33
   BLE/Sense, or *Arduino SAMD* for Nano 33 IoT) and the **ArduinoBLE** library.
3. Select board + port → **Upload**. Open Serial Monitor (9600) to watch the logs.
4. By default it uses the **built-in LED** — no wiring needed. For an external LED:
   `D3 ──[220Ω]──▶|── GND`, then set `LED_PIN = 3` in the sketch.

### Connect from the app
1. Run the app and pick a line (Step 3 shows the BLE row).
2. Click **Connect** → choose **"Norisugoshi"** in the browser's device chooser.
3. Click **Test** — the LED should blip. You're linked.

> ⚠️ **Web Bluetooth needs a secure context (HTTPS or localhost).** Use the deployed
> `https://…onrender.com` URL on a phone, or `http://localhost:3000` on desktop. A phone
> on a plain `http://<PC-IP>:3000` address is **blocked**. See BLE troubleshooting below.

### UUIDs / protocol (must match on both sides)
```
Service        6e400001-b5a3-f393-e0a9-e50e24dcca9e
Command char.  6e400002-b5a3-f393-e0a9-e50e24dcca9e   (app writes 1 byte)
0x01 = alert (on) · 0x00 = clear (off)
```
They're defined at the top of `index.html` (`BLE_SERVICE` / `BLE_CMD`) and at the top of
the `.ino`. When the real haptic hardware is ready, just drive the actuator on `LED_PIN`
(or expand `0x01` into a multi-byte stimulation pattern) — the BLE plumbing stays the same.

The on-screen **response timer** (start = alert fired, stop = "I'm awake") logs reaction
times per session — useful for comparing stimulation methods in the experiment.

### BLE troubleshooting (real fixes we hit)
The **Connect** button shows the reason when it can't connect — use this table.

| Symptom | Cause | Fix |
|---------|-------|-----|
| Alert "this browser can't use Bluetooth" on **iPhone** | iOS Safari/Chrome have **no Web Bluetooth** (Apple policy — all iOS browsers use WebKit) | Open the site in the **Bluefy** browser app (App Store). |
| **Brave** (Android/desktop): Connect does nothing or "can't use Bluetooth" | Brave **disables Web Bluetooth by default** | Enable `brave://flags/#brave-web-bluetooth-api` → Relaunch. Or use Chrome/Edge. |
| **Android**: chooser empty / "no device found" | Android requires **Location** to scan BLE (a beacon-privacy rule — your location isn't used by the app) | Turn **Location services ON**, and allow the browser's Location / "Nearby devices" permission. Also confirm phone Bluetooth is ON. |
| Connect does nothing on a phone over `http://<PC-IP>:3000` | Web Bluetooth needs a **secure context (HTTPS)** | Use the deployed `https://…onrender.com` URL (localhost is also fine on desktop). |
| Chooser is empty even when all above is OK | Arduino not advertising | Check Serial Monitor shows `advertising as 'Norisugoshi'`; press RESET to restart advertising. |

Platform summary:

| Platform | Browser for BLE |
|----------|-----------------|
| Android | Chrome / Edge / **Brave (flag on)** + Location ON |
| Desktop | Chrome / Edge / Brave (flag on) |
| iPhone / iPad | **Bluefy** only (Safari/Chrome can't) |

> The vibration/notification alerts also need HTTPS on a phone, and the phone's own
> vibration is **Android-only** (iOS ignores the web Vibration API) — the external
> haptic device works on any BLE-capable setup regardless.

---

## Files
- `server.js` — proxy + static host (contains the ODPT key)
- `index.html` — the whole app (UI + logic), single file
- `start.bat` — double-click to launch the server + open the browser
- `../arduino/norisugoshi_ble/norisugoshi_ble.ino` — Arduino Nano 33 BLE firmware (LED stand-in)
