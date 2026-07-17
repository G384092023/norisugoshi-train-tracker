/**
 * 乗り過ごし防止 — ODPT proxy + static host
 * ============================================================================
 * WHAT THIS FILE IS
 *   A tiny web server that runs on YOUR computer (via Node.js). It has two jobs:
 *     1. Serve the web page (index.html) to your browser.
 *     2. Act as a "middleman" between your browser and the ODPT train API.
 *
 * WHY A MIDDLEMAN IS NEEDED (the #1 thing to understand when debugging)
 *   The ODPT API (api.odpt.org) does NOT send "CORS" headers. CORS is a browser
 *   security rule: a web page is blocked from reading data from a *different*
 *   website unless that website says "it's allowed." ODPT doesn't say that, so a
 *   browser page calling ODPT directly is rejected.
 *   BUT — that rule only applies to browsers. A normal program (this Node server)
 *   can fetch ODPT with no problem. So the browser talks to THIS server, and this
 *   server talks to ODPT on its behalf, then adds the CORS header the browser wants.
 *
 *   browser  ──►  this server (localhost:3000)  ──►  api.odpt.org
 *      ◄────────────────  data + CORS header  ◄──────────
 *
 * THE ROUTES THIS SERVER ANSWERS
 *   GET /              -> the web page (index.html)
 *   GET /api/trains            -> every live train (used to find which lines are running)
 *   GET /api/trains?railway=X  -> live trains on one line, e.g. odpt.Railway:Toei.Oedo
 *   GET /api/railways          -> line info + station order (cached 1 hour)
 *
 * RUN IT
 *   node server.js        then open  http://localhost:3000
 * ============================================================================
 */

// --- Node's built-in modules (no "npm install" needed) ----------------------
const http  = require("http");   // create OUR server (plain HTTP, for the browser)
const https = require("https");  // make outgoing calls to ODPT (which uses https)
const fs    = require("fs");     // read index.html from disk
const path  = require("path");   // build a safe file path to index.html

// --- Configuration ----------------------------------------------------------
// The ODPT consumer key. Every request to ODPT must include it. It lives in the
// server (not in index.html) so it isn't exposed in the page source.
// In production (Render etc.) set the ODPT_CONSUMER_KEY environment variable; the
// hard-coded value below is just a local fallback so `node server.js` works as-is.
// NOTE: if you make the GitHub repo PUBLIC, remove this fallback and rotate the key.
// TWO data sources, merged (measured 2026-07-03 — they are COMPLEMENTS, not duplicates):
//  · center    (api.odpt.org)           — Toei, TokyoMetro, … (the original 94 railways)
//  · challenge (api-challenge.odpt.org) — ODPT Challenge 2026: adds Tobu, Seibu, Tokyu, …
// ⚠️ Keys are HOST-BOUND: the challenge key 403s on api.odpt.org and vice versa — a key
// always travels with its base URL. Every /api/* route queries BOTH and merges, so losing
// one source (e.g. the challenge key expires after the challenge) degrades gracefully.
const SOURCES = [
  {
    name: "center",
    base: process.env.ODPT_BASE || "https://api.odpt.org/api/v4",
    key:  process.env.ODPT_CONSUMER_KEY ||
          "arwj9iz974nhl46zetqae38etsuiv6bqr0u2pff6df4k99b3eswib8ule0sw3i4x",
  },
  {
    name: "challenge",   // valid during ODPT Challenge 2026 (https://challenge2026.odpt.org)
    base: process.env.ODPT_CHALLENGE_BASE || "https://api-challenge.odpt.org/api/v4",
    key:  process.env.ODPT_CHALLENGE_KEY ||
          "if7p3zh1sfcud2jsjcidppa9vg099j9ks6bxnuq8lyz9tv7ycf5vyoqz8mxw9qnu",
  },
];

// The "port" (door number) this server listens on. `process.env.PORT` lets you
// override it, e.g.  PORT=4000 node server.js  — otherwise it defaults to 3000.
const PORT = process.env.PORT || 3000;

// --- Simple cache for railway metadata --------------------------------------
// Line names and station order almost never change, so there's no reason to ask
// ODPT for them on every page load. We keep the last response in memory and reuse
// it for an hour (TTL = "time to live"). `at` is the timestamp it was fetched.
let railwayCache = { data: null, at: 0 };
const RAILWAY_TTL = 60 * 60 * 1000; // 1 hour, in milliseconds
// Station records per railway (for the published odpt:stationCode). Same idea as the
// railway cache — station numbering changes about never — but keyed per line, since the
// app only ever asks for the line the user picked.
const stationCache = new Map(); // railway id -> { data, at }

/**
 * fetchOdpt(url) — make ONE GET request to ODPT and return the raw text body.
 *
 * Returns a Promise so the caller can `await` it. We collect the response in
 * chunks (that's how Node streams data: small pieces arrive via "data" events,
 * and "end" fires when the whole body has arrived).
 *
 * If ODPT replies with an error status (400+), we reject so the caller's
 * try/catch can turn it into a 502 for the browser.
 */
function fetchOdpt(url) {
  return new Promise((resolve, reject) => {
    https
      .get(url, (r) => {
        let body = "";
        r.on("data", (chunk) => (body += chunk));   // accumulate each piece
        r.on("end", () => {                          // whole response received
          if (r.statusCode >= 400) {
            // .slice(0,200) keeps the log line short if ODPT returns a big error page
            return reject(new Error(`ODPT HTTP ${r.statusCode}: ${body.slice(0, 200)}`));
          }
          resolve(body);
        });
      })
      .on("error", reject); // network-level failure (DNS, no internet, etc.)
  });
}

/**
 * fetchMerged(pathAndQuery) — ask EVERY source for the same resource and concat
 * the arrays. `pathAndQuery` is e.g. "odpt:Train?odpt:railway=…" (no key — each
 * source appends its own). One source failing is tolerated (logged, skipped);
 * only if ALL fail do we throw, so the app keeps working when the challenge
 * key expires or one host has an outage.
 */
async function fetchMerged(pathAndQuery) {
  const sep = pathAndQuery.includes("?") ? "&" : "?";
  const results = await Promise.allSettled(SOURCES.map((s) =>
    fetchOdpt(`${s.base}/${pathAndQuery}${sep}acl:consumerKey=${s.key}`)
  ));
  const merged = [];
  let okCount = 0;
  results.forEach((r, i) => {
    if (r.status === "fulfilled") {
      try { merged.push(...JSON.parse(r.value)); okCount++; }
      catch (e) { console.warn(`  ! ${SOURCES[i].name}: bad JSON (${e.message})`); }
    } else {
      console.warn(`  ! ${SOURCES[i].name}: ${r.reason.message.slice(0, 120)}`);
    }
  });
  if (okCount === 0) throw new Error("all ODPT sources failed");
  return merged;
}

/**
 * sendJson(res, status, payload) — reply to the browser with JSON.
 * `payload` may already be a JSON string (straight from ODPT) or a JS object;
 * we only stringify the object case so we don't double-encode ODPT's text.
 */
function sendJson(res, status, payload) {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(typeof payload === "string" ? payload : JSON.stringify(payload));
}

/**
 * The static files we serve (the page + PWA assets). `__dirname` is the folder
 * THIS file lives in, so they're found whether you launch from this folder or via
 * the start.bat double-click. Each entry maps a URL path to [filename, MIME type].
 */
const STATIC = {
  "/":                     ["index.html",          "text/html; charset=utf-8"],
  "/index.html":           ["index.html",          "text/html; charset=utf-8"],
  "/manifest.webmanifest": ["manifest.webmanifest","application/manifest+json; charset=utf-8"],
  "/sw.js":                ["sw.js",               "application/javascript; charset=utf-8"],
  "/icon.svg":             ["icon.svg",            "image/svg+xml"],
};

function serveFile(res, filename, contentType) {
  const file = path.join(__dirname, filename);
  fs.readFile(file, (err, buf) => {
    if (err) {
      res.writeHead(404, { "Content-Type": "text/plain" });
      res.end(`${filename} not found next to server.js`);
      return;
    }
    const headers = { "Content-Type": contentType };
    // Allow the service worker to control the whole site (scope "/").
    if (filename === "sw.js") headers["Service-Worker-Allowed"] = "/";
    res.writeHead(200, headers);
    res.end(buf);
  });
}

// --- The server: this function runs for EVERY incoming request --------------
// `req` = what the browser asked for; `res` = what we send back.
const server = http.createServer(async (req, res) => {
  // These three headers are the CORS "permission" the browser wants. With them,
  // the browser is happy to read our responses. (Allowing "*" = any origin is
  // fine here because this only ever runs locally on your own machine.)
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");

  // Browsers sometimes send a "preflight" OPTIONS request first to check CORS.
  // We just answer 204 (No Content) — "yes, you're allowed."
  if (req.method === "OPTIONS") {
    res.writeHead(204);
    return res.end();
  }

  // Parse the requested path + query string. The 2nd arg is a base so relative
  // URLs like "/api/trains?railway=..." parse correctly.
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const now = () => new Date().toLocaleTimeString(); // for tidy console logs

  // try/catch: if ODPT or anything else throws, we send one clean 502 instead
  // of crashing the server.
  try {
    // ---- ROUTE: health check ----------------------------------------------
    // A tiny endpoint for uptime pingers (UptimeRobot / cron-job.org) to keep a
    // free host awake. We return 200 with an EMPTY body — success status that every
    // monitor accepts, and no body for any of them to mis-report as "too large".
    if (url.pathname === "/healthz") {
      res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" });
      return res.end();
    }

    // ---- ROUTE: live trains -----------------------------------------------
    if (url.pathname === "/api/trains") {
      // Optional ?railway=... filter. If present, we ask ODPT for just that line;
      // if absent, ODPT returns trains on ALL lines (the app uses that to discover
      // which lines are currently running).
      const railway = url.searchParams.get("railway") || "";
      const trains = await fetchMerged(
        "odpt:Train" + (railway ? `?odpt:railway=${encodeURIComponent(railway)}` : ""));
      console.log(`[${now()}] /api/trains${railway ? " " + railway : " (all)"} -> ${trains.length} trains (merged)`);
      return sendJson(res, 200, trains);
    }

    // ---- ROUTE: a train's scheduled timetable -----------------------------
    // /api/timetable?railway=X&train=NUMBER&calendar=odpt.Calendar:Weekday
    // Returns the planned per-station times for that train, so the app can show
    // a scheduled ETA at the destination (and add the live delay on top).
    if (url.pathname === "/api/timetable") {
      const railway  = url.searchParams.get("railway")  || "";
      const train    = url.searchParams.get("train")    || "";
      const calendar = url.searchParams.get("calendar") || "";
      const qs = [
        railway  && `odpt:railway=${encodeURIComponent(railway)}`,
        train    && `odpt:trainNumber=${encodeURIComponent(train)}`,
        calendar && `odpt:calendar=${encodeURIComponent(calendar)}`,
      ].filter(Boolean).join("&");
      const tt = await fetchMerged("odpt:TrainTimetable" + (qs ? `?${qs}` : ""));
      console.log(`[${now()}] /api/timetable ${railway} ${train} ${calendar} -> ${tt.length} (merged)`);
      return sendJson(res, 200, tt);
    }

    // ---- ROUTE: stations on one line (cached per railway) ------------------
    // /api/stations?railway=X -> odpt:Station records. The app reads the REAL
    // per-station code (odpt:stationCode, e.g. Tokyo on the Utsunomiya line =
    // JU01). Published codes beat deriving lineCode+index, which goes wrong
    // exactly where numbering doesn't cover the whole line (JU stops at Omiya;
    // Sengakuji carries no KK number at all).
    if (url.pathname === "/api/stations") {
      const railway = url.searchParams.get("railway") || "";
      if (!railway) return sendJson(res, 400, { error: "railway required" });
      const hit = stationCache.get(railway);
      if (hit && Date.now() - hit.at < RAILWAY_TTL) {
        console.log(`[${now()}] /api/stations ${railway} (cache)`);
        return sendJson(res, 200, hit.data);
      }
      const merged = await fetchMerged(`odpt:Station?odpt:railway=${encodeURIComponent(railway)}`);
      // both hosts may describe the same station; keep the record that has a stationCode
      const byId = new Map();
      for (const s of merged) {
        const id = s["owl:sameAs"];
        const cur = byId.get(id);
        if (!cur || (!cur["odpt:stationCode"] && s["odpt:stationCode"])) byId.set(id, s);
      }
      const stations = [...byId.values()];
      stationCache.set(railway, { data: JSON.stringify(stations), at: Date.now() });
      console.log(`[${now()}] /api/stations ${railway} -> ${stations.length} (merged+deduped)`);
      return sendJson(res, 200, stationCache.get(railway).data);
    }

    // ---- ROUTE: railway metadata (cached) ---------------------------------
    if (url.pathname === "/api/railways") {
      // Serve from cache if it's fresh (younger than RAILWAY_TTL).
      if (railwayCache.data && Date.now() - railwayCache.at < RAILWAY_TTL) {
        console.log(`[${now()}] /api/railways (cache)`);
        return sendJson(res, 200, railwayCache.data);
      }
      // Cache empty or stale: fetch fresh from BOTH sources, dedupe by railway id.
      // When both sources describe the same railway, keep the record that actually
      // has odpt:stationOrder — the app can't track a line without it (measured: the
      // challenge host serves Toei/Metro rows WITHOUT stationOrder; center has them).
      const merged = await fetchMerged("odpt:Railway");
      const hasOrder = (r) => ((r || {})["odpt:stationOrder"] || []).length > 0;
      const byId = new Map();
      for (const r of merged) {
        const id = r["owl:sameAs"];
        const cur = byId.get(id);
        if (!cur || (!hasOrder(cur) && hasOrder(r))) byId.set(id, r);
      }
      const railways = [...byId.values()];
      railwayCache = { data: JSON.stringify(railways), at: Date.now() };
      console.log(`[${now()}] /api/railways -> ${railways.length} railways (merged+deduped, refreshed)`);
      return sendJson(res, 200, railwayCache.data);
    }

    // ---- ROUTE: the web page + PWA assets ---------------------------------
    if (STATIC[url.pathname]) {
      const [filename, contentType] = STATIC[url.pathname];
      return serveFile(res, filename, contentType);
    }

    // ---- Anything else: not found -----------------------------------------
    res.writeHead(404, { "Content-Type": "text/plain" });
    res.end("Not found");
  } catch (e) {
    // 502 "Bad Gateway" = our upstream (ODPT) failed. The browser app shows this
    // as a fetch error. Check the console line below to see the real cause.
    console.error(`[${now()}] ERROR`, e.message);
    sendJson(res, 502, { error: e.message });
  }
});

// --- Start listening --------------------------------------------------------
// Until this is called, the server isn't accepting connections. The callback
// runs once it's ready. If port 3000 is already taken you'll get EADDRINUSE
// here — stop the other server, or run with a different PORT.
server.listen(PORT, () => {
  console.log(`\n  乗り過ごし防止 — train tracker`);
  console.log(`  Open:  http://localhost:${PORT}\n`);
});
