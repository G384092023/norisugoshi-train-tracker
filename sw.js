/*
  Service worker — makes the app installable (PWA) and usable offline.
  Strategy: NETWORK-FIRST for the app shell (so you always get the latest version
  after a deploy), falling back to cache when offline. Live API data is never
  cached — it must always be fresh.

  Bump CACHE_VERSION whenever you want to force-clear old cached files.
*/
const CACHE_VERSION = "v1";
const CACHE = `norisugoshi-${CACHE_VERSION}`;
const SHELL = ["/", "/index.html", "/manifest.webmanifest", "/icon.svg"];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);

  // Live train data: always hit the network, never serve from cache.
  if (url.pathname.startsWith("/api/")) return;           // let the browser handle it
  if (event.request.method !== "GET") return;

  // App shell: try network (fresh), cache a copy, fall back to cache when offline.
  event.respondWith(
    fetch(event.request)
      .then((res) => {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(event.request, copy)).catch(() => {});
        return res;
      })
      .catch(() => caches.match(event.request))
  );
});
