// sync-www.mjs — copy the front-end into www/ (Capacitor's webDir) for the native build.
// The single source of truth stays the files at the app root (index.html, sw.js, …); www/ is a
// generated build folder (gitignored). Run via `npm run cap:www` before `npx cap sync`.
import { mkdirSync, copyFileSync, existsSync } from "node:fs";

const files = ["index.html", "sw.js", "manifest.webmanifest", "icon.svg"];
mkdirSync("www", { recursive: true });
for (const f of files) {
  if (existsSync(f)) { copyFileSync(f, `www/${f}`); console.log("copied", f); }
  else console.warn("skip (missing):", f);
}
console.log("www/ ready");
