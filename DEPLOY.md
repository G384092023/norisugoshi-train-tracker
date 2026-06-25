# Deploy & install as a phone app

This puts the app on the internet with **HTTPS**, which is what makes BLE,
notifications, and vibration work on a phone. We use **Render** (free, runs Node,
free HTTPS). The app is a normal Node app, so any Node host works the same way.

> ℹ️ Why HTTPS matters: Web Bluetooth / Notifications only run in a "secure context".
> `localhost` counts as secure (desktop dev), but a phone needs a real `https://` URL.

---

## 0. One-time accounts
- A **GitHub** account — https://github.com
- A **Render** account — https://render.com (sign up with GitHub)

---

## 1. Put the code on GitHub (keep the repo **Private**)

The git repo is already initialized in this `app/` folder (just the app — none of the
research PDFs). To publish it:

1. On GitHub: **New repository** → name it e.g. `norisugoshi-train-tracker` →
   **set it to Private** → *Create* (don't add a README, it already exists).
2. Copy the commands GitHub shows under *"…or push an existing repository"*, which are:
   ```bash
   git remote add origin https://github.com/<you>/norisugoshi-train-tracker.git
   git branch -M main
   git push -u origin main
   ```
   Run them from inside this `app/` folder (PowerShell). Done — code is on GitHub.

   *(No command line for git? Install GitHub Desktop, "Add existing repository" →
   point it at this `app` folder → Publish repository → keep "Private" checked.)*

> 🔒 **Keep it Private.** The ODPT key has a fallback value in `server.js`. A private
> repo keeps it hidden. If you ever make it Public, delete that fallback line and
> rotate the key.

---

## 2. Deploy on Render

**Option A — Blueprint (uses `render.yaml`, fewer clicks):**
1. Render dashboard → **New +** → **Blueprint**.
2. Connect your GitHub and pick the repo. Render reads `render.yaml`.
3. It will ask for the `ODPT_CONSUMER_KEY` value → paste the key
   (`arwj9iz9...sw3i4x`) → **Apply**.

**Option B — Manual Web Service:**
1. Render dashboard → **New +** → **Web Service** → connect the repo.
2. Settings:
   - **Runtime:** Node
   - **Build Command:** `npm install`
   - **Start Command:** `node server.js`
   - **Instance Type:** Free
3. **Environment** → add variable `ODPT_CONSUMER_KEY` = the key → **Create Web Service**.

Wait ~1–2 min for the first build. You'll get a URL like
`https://norisugoshi-train-tracker.onrender.com`.

---

## 3. Test it
1. Open the URL on your **computer** first → the 7 live lines should load.
   (If they don't, open `…onrender.com/api/trains` directly — if that errors, the ODPT
   key isn't reaching the server; re-check the env var.)
2. Open the URL on your **phone** (any network, not just your Wi-Fi now).
3. **Install it:** Android Chrome → menu → *Add to Home screen* (or an install prompt).
   It launches fullscreen with the train icon, like a native app.

---

## 4. Then the BLE part (on the deployed HTTPS URL)
- **Android Chrome:** Connect → *Norisugoshi* → Test works, because it's HTTPS now. 🎉
- **iPhone/Safari:** Web Bluetooth is **not supported by iOS Safari at all** — the rest
  of the app works, but the BLE haptic must be tested on Android (or a desktop).

---

## Things to know about the free tier
- **Cold start:** a free Render service **sleeps after ~15 min idle**; the next visit
  takes ~30–60 s to wake up. Fine for testing; upgrade later if you want it always-on.
- **Public proxy:** anyone with the URL can use your proxy (and your ODPT quota). OK for
  a prototype. We can add a simple password/guard later if needed.
- **Redeploys are automatic:** every `git push` to `main` triggers a new deploy. You do
  **not** need to "finalize" first — iterate freely.

---

## Keeping it awake (avoid the cold-start "not found")

A free Render service **sleeps after ~15 min idle** and takes ~30–60 s to wake. The app
also **auto-retries** failed fetches, so Render's occasional transient 404s heal
themselves — but to skip the cold-start wait entirely, ping it on a schedule.

There's a tiny endpoint for exactly this: **`/healthz`** (returns `ok`, no ODPT call).

**Set up a free pinger (cron-job.org):**
1. Sign up at https://cron-job.org → log in → **Create cronjob**.
2. Settings:
   | Field | Value |
   |-------|-------|
   | URL | `https://norisugoshi-train-tracker.onrender.com/healthz` |
   | Schedule | Every **10 minutes** |
   | Time zone | Asia/Tokyo |
   | Hours | 7 – 23 (your active hours) |
   | Method | GET |
3. Enable → Save.

**Free-tier math:** Render gives **750 instance-hours/month**, shared across your free
services. Pinging ~16 h/day ≈ 496 h/month — well inside free. (Even 24/7 ≈ 744 h fits,
but only if this is your *only* free service.) Ping only the hours you use it for safety
margin.

> Occasional "failed" entries in cron-job.org are just Render's transient 404s — the
> request still wakes the instance, so ignore them (or turn off that job's notifications).

---

## Updating after a change
```bash
git add .
git commit -m "describe the change"
git push
```
Render redeploys in a minute or two. (If a cached old version sticks on your phone,
the service worker is network-first so a refresh gets the latest; a hard reload or
reinstall clears it for sure.)
