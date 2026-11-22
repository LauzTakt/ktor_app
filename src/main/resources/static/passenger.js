// Passenger view (`/`). Look and feel: docs/STYLE.md. All train / station / incident data comes from /api/v1.
//
// Flow:
//   QR: `/?train=<trainId>` -> on tracks · `/?station=<stopId>` -> pick destination
//   Location: train within 200 m -> "Are you on this train?" (Yes / No / other trains nearby)
//             else station within 200 m -> "Are you at this station?" (Yes / No)
//             else (or No) -> browsing: pick origin and destination by text or on the map
//   On a train ("on tracks"): 3D train on a track, the whole route with the ETA of every stop, incidents on top.
//   At a station: pick the destination (text or map) -> trains from here to there, live.
//   Alerts (push.js): on a train, "Notify me" -> arrival at the chosen stop + its incidents; bell in the top bar ->
//             your alerts + follow a whole line (incidents only); `/?line=<line>` -> line status.

import { addRail, applyTheme as applyMapTheme, baseStyle } from "./common.js";
import * as push from "./push.js";

const TZ = "Europe/Zurich";
const THEME_KEY = "twin-theme"; // shared with the staff pages
const LATE_SEC = 180; // SBB punctuality threshold
const NEAR_M = 200;
const THREE_URL = "https://unpkg.com/three@0.170.0/build/three.module.js";

// ------------------------------------------------------------------ helpers

const $ = (sel, root = document) => root.querySelector(sel);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const buzz = (ms = 8) => navigator.vibrate?.(ms);
const cssVar = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;
const timeFmt = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", timeZone: TZ });
const hhmm = (ms) => (ms ? timeFmt.format(new Date(ms)) : "–");

async function api(path) {
  const r = await fetch(`/api/v1${path}`, { headers: { Accept: "application/json" } });
  if (!r.ok) {
    const err = new Error(`HTTP ${r.status}`);
    err.status = r.status;
    throw err;
  }
  return r.json();
}
const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };

// Lucide icons (one family, stroke 2), inlined.
const ICONS = {
  train: '<path d="M8 3.1V7a4 4 0 0 0 8 0V3.1"/><path d="m9 15-1-1"/><path d="m15 15 1-1"/><path d="M9 19c-2.8 0-5-2.2-5-5v-4a8 8 0 0 1 16 0v4c0 2.8-2.2 5-5 5Z"/><path d="m8 19-2 3"/><path d="m16 19 2 3"/>',
  pin: '<path d="M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0"/><circle cx="12" cy="10" r="3"/>',
  search: '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>',
  x: '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
  chevron: '<path d="m9 18 6-6-6-6"/>',
  back: '<path d="m12 19-7-7 7-7"/><path d="M19 12H5"/>',
  map: '<path d="M14.106 5.553a2 2 0 0 0 1.788 0l3.659-1.83A1 1 0 0 1 21 4.619v12.764a1 1 0 0 1-.553.894l-4.553 2.277a2 2 0 0 1-1.788 0l-4.212-2.106a2 2 0 0 0-1.788 0l-3.659 1.83A1 1 0 0 1 3 19.381V6.618a1 1 0 0 1 .553-.894l4.553-2.277a2 2 0 0 1 1.788 0z"/><path d="M15 5.764v15"/><path d="M9 3.236v15"/>',
  list: '<path d="M3 12h.01"/><path d="M3 18h.01"/><path d="M3 6h.01"/><path d="M8 12h13"/><path d="M8 18h13"/><path d="M8 6h13"/>',
  swap: '<path d="m21 16-4 4-4-4"/><path d="M17 20V4"/><path d="m3 8 4-4 4 4"/><path d="M7 4v16"/>',
  moon: '<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/>',
  bell: '<path d="M10.268 21a2 2 0 0 0 3.464 0"/><path d="M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.34 17.66-1.41 1.41"/><path d="m19.07 4.93-1.41 1.41"/>',
};
const ic = (name, cls = "") => `<svg class="icon ${cls}" viewBox="0 0 24 24" aria-hidden="true">${ICONS[name]}</svg>`;

// Flat vector illustrations (SBB palette through CSS variables; no SBB pictograms).
const ILLUSTRATION = {
  train: `<svg class="illus" viewBox="0 0 320 150" aria-hidden="true">
    <rect x="0" y="128" width="320" height="4" fill="var(--iron)"/>
    ${Array.from({ length: 16 }, (_, i) => `<rect x="${i * 20 + 4}" y="132" width="12" height="5" rx="1" fill="var(--cement)"/>`).join("")}
    <g class="illus-train">
      <path d="M40 60 h190 q38 0 52 34 l8 22 q2 8 -6 8 H40 q-8 0 -8 -8 V68 q0 -8 8 -8z" fill="var(--white)" stroke="var(--charcoal)" stroke-width="3"/>
      <rect x="32" y="104" width="258" height="9" fill="var(--red)"/>
      ${[52, 92, 132, 172].map((x) => `<rect x="${x}" y="72" width="30" height="20" rx="3" fill="var(--iron)"/>`).join("")}
      <path d="M218 72 h22 q22 0 32 22 h-54z" fill="var(--iron)"/>
      ${[70, 110, 200, 240].map((x) => `<circle cx="${x}" cy="126" r="7" fill="var(--charcoal)"/>`).join("")}
    </g>
  </svg>`,
  station: `<svg class="illus" viewBox="0 0 320 150" aria-hidden="true">
    <rect x="20" y="40" width="280" height="10" rx="2" fill="var(--charcoal)"/>
    ${[50, 160, 270].map((x) => `<rect x="${x - 3}" y="50" width="6" height="66" fill="var(--granite)"/>`).join("")}
    <rect x="110" y="62" width="100" height="24" rx="3" fill="var(--blue)"/>
    <rect x="124" y="71" width="72" height="6" rx="3" fill="var(--white)"/>
    <rect x="10" y="116" width="300" height="12" rx="2" fill="var(--silver)"/>
    <rect x="0" y="136" width="320" height="4" fill="var(--iron)"/>
    <g class="illus-person"><circle cx="232" cy="88" r="7" fill="var(--granite)"/><rect x="225" y="97" width="14" height="19" rx="6" fill="var(--granite)"/></g>
  </svg>`,
  search: `<svg class="illus" viewBox="0 0 320 150" aria-hidden="true">
    <path d="M30 120 C 90 120, 110 40, 170 50 S 260 110, 290 40" fill="none" stroke="var(--silver)" stroke-width="10" stroke-linecap="round"/>
    <path class="illus-route" d="M30 120 C 90 120, 110 40, 170 50 S 260 110, 290 40" fill="none" stroke="var(--charcoal)" stroke-width="4" stroke-linecap="round" stroke-dasharray="6 10"/>
    <circle cx="30" cy="120" r="10" fill="var(--white)" stroke="var(--charcoal)" stroke-width="4"/>
    <circle cx="290" cy="40" r="10" fill="var(--red)"/>
  </svg>`,
};

const lineLabel = (t, lg = false) => `<span class="line${lg ? " lg" : ""}">${esc(t?.line || t?.category || "Train")}</span>`;
const distText = (m) => (m == null ? "" : m < 1000 ? `${Math.round(m / 10) * 10} m` : `${(m / 1000).toFixed(1)} km`);

/** `21:53 +2'` (delay in red, nothing when on time); cancelled: planned time struck through + "Cancelled". */
function timeHtml(planned, expected, cancelled = false) {
  if (cancelled) return `<s class="planned time">${hhmm(planned)}</s> <span class="cancel">Cancelled</span>`;
  const delayMin = planned && expected ? Math.round((expected - planned) / 60_000) : 0;
  return `<span class="time">${hhmm(planned ?? expected)}</span>${delayMin >= 1 ? ` <span class="delay">+${delayMin}'</span>` : ""}`;
}

/** Observable train state (docs/STYLE.md §4): colour class + word. */
function trainState(t) {
  const p = t?.position;
  if (t?.status === "cancelled") return { cls: "s-cancelled", word: "Cancelled", color: "--cancelled" };
  if (p?.motion === "stopped" && !p.atStopId) return { cls: "s-stopped", word: "Stopped on the line", color: "--red" };
  if ((t?.delaySec ?? 0) >= LATE_SEC) return { cls: "s-late", word: `Late +${Math.round(t.delaySec / 60)}'`, color: "--orange" };
  if (p?.motion === "stopped") return { cls: "s-station", word: "At a station", color: "--at-station" };
  return { cls: "s-moving", word: "Running", color: "--green" };
}
const stateHtml = (t) => { const s = trainState(t); return `<span class="state ${s.cls}"><span class="dot"></span>${esc(s.word)}</span>`; };

function untilText(ms) {
  if (!ms) return "";
  const d = ms - Date.now();
  if (d < 45_000) return "now";
  const min = Math.round(d / 60_000);
  return min < 60 ? `in ${min} min` : `in ${Math.floor(min / 60)} h ${String(min % 60).padStart(2, "0")}`;
}

const SEVERITY = { critical: "Critical", major: "Major", minor: "Minor", info: "Information" };
const alertHtml = (i) => `
  <div class="alert sev-${esc(i.severity || "info")}"><span class="dot"></span>
    <div><div class="at">${esc(i.title)}</div>
      <div class="ad">${esc(SEVERITY[i.severity] || "Information")}${i.where ? ` · ${esc(i.where)}` : ""}</div></div></div>`;

function toast(msg, ms = 3500) {
  const t = document.createElement("div");
  t.className = "toast";
  t.setAttribute("role", "status");
  t.textContent = msg;
  document.body.append(t);
  setTimeout(() => t.remove(), ms);
}

// ------------------------------------------------------------------ theme (same key as the staff pages)

const themeBtn = $("#theme");
const themeListeners = new Set();
function applyTheme(theme) {
  document.documentElement.dataset.theme = theme;
  const dark = theme === "dark";
  themeBtn.innerHTML = ic(dark ? "sun" : "moon");
  themeBtn.title = dark ? "Light mode" : "Dark mode";
  themeBtn.setAttribute("aria-label", themeBtn.title);
  themeListeners.forEach((fn) => fn(theme));
}
applyTheme(document.documentElement.dataset.theme || "light");
themeBtn.addEventListener("click", () => {
  const next = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
  localStorage.setItem(THEME_KEY, next);
  applyTheme(next);
});

// ------------------------------------------------------------------ screens (short cross-fade + slide)

const stage = $("#stage");
const restartBtn = $("#restart");
let cleanups = [];
const onLeave = (fn) => cleanups.push(fn);

function show(build, { back = false, restart = true } = {}) {
  cleanups.forEach((fn) => { try { fn(); } catch { /* ignore */ } });
  cleanups = [];
  const old = stage.querySelector(".screen:not(.leave)");
  if (old) {
    old.classList.remove("enter", "back");
    old.classList.add("leave");
    setTimeout(() => old.remove(), 220);
  }
  const s = document.createElement("section");
  s.className = `screen enter${back ? " back" : ""}`;
  stage.append(s);
  restartBtn.hidden = !restart;
  build(s);
  window.scrollTo({ top: 0, behavior: reducedMotion ? "auto" : "smooth" });
  return s;
}

/** Keep the address shareable: `?train=` / `?station=` like the QR codes. */
function setUrl(params) {
  const u = new URL(location.href);
  u.search = new URLSearchParams(Object.entries(params).filter(([, v]) => v)).toString();
  history.replaceState(null, "", u);
}

restartBtn.addEventListener("click", () => { buzz(); setUrl({}); geo.promise = null; detectScreen({ back: true }); });

const backBtn = (label = "Back") => `<button class="back" type="button" data-back>${ic("back")}${esc(label)}</button>`;
const placeholders = (n = 3) => Array.from({ length: n }, () => '<div class="placeholder"></div>').join("");

// ------------------------------------------------------------------ location

const geo = { status: "idle", fix: null, promise: null, refined: null };

/**
 * Fast location: resolves with the FIRST usable fix (≤ 150 m, or the best one after 3 s) so the flow never waits for
 * nothing. The watch keeps running in the background: `refine()` resolves once two fixes ≥ 2 s apart give a speed and
 * heading (only needed to tell apart several trains close to the user). Null when location is unavailable.
 */
function locate() {
  if (geo.promise) return geo.promise;
  if (!("geolocation" in navigator)) { geo.status = "off"; return (geo.promise = Promise.resolve(null)); }
  if (!window.isSecureContext) { geo.status = "insecure"; return (geo.promise = Promise.resolve(null)); }
  geo.status = "busy";
  const fixes = [];
  let resolveFirst, resolveRefined, id = null;
  geo.refined = new Promise((r) => (resolveRefined = r));
  geo.promise = new Promise((r) => (resolveFirst = r));
  const toFix = (last, speedKmh, headingDeg) =>
    ({ lat: last.coords.latitude, lon: last.coords.longitude, acc: Math.round(last.coords.accuracy), speedKmh, headingDeg, ts: last.timestamp });
  const ok = (v) => v != null && !Number.isNaN(v);
  const motion = () => {
    const last = fixes[fixes.length - 1];
    let speedKmh = ok(last.coords.speed) ? last.coords.speed * 3.6 : null;
    let headingDeg = ok(last.coords.heading) ? last.coords.heading : null;
    const first = fixes.find((f) => last.timestamp - f.timestamp >= 1000);
    if (first && (speedKmh == null || headingDeg == null)) {
      const d = haversine(first.coords.latitude, first.coords.longitude, last.coords.latitude, last.coords.longitude);
      if (d > Math.max(first.coords.accuracy, last.coords.accuracy) * 0.5) {
        speedKmh ??= (d / ((last.timestamp - first.timestamp) / 1000)) * 3.6;
        headingDeg ??= bearing(first.coords.latitude, first.coords.longitude, last.coords.latitude, last.coords.longitude);
      } else speedKmh ??= 0;
    }
    return { last, speedKmh, headingDeg, known: speedKmh != null && (headingDeg != null || speedKmh < 10) };
  };
  const stop = () => { if (id != null) navigator.geolocation.clearWatch(id); id = null; };
  const giveFirst = () => {
    if (geo.status !== "busy") return;
    if (!fixes.length) { geo.status = "off"; resolveFirst(null); resolveRefined(null); stop(); return; }
    const best = fixes.reduce((a, b) => (b.coords.accuracy < a.coords.accuracy ? b : a));
    const m = motion();
    geo.fix = toFix(best, m.speedKmh, m.headingDeg);
    geo.status = "ok";
    resolveFirst(geo.fix);
    if (m.known) { resolveRefined(geo.fix); stop(); }
  };
  id = navigator.geolocation.watchPosition(
    (p) => {
      fixes.push(p);
      if (geo.status === "busy" && p.coords.accuracy <= 150) giveFirst();
      else if (geo.status === "ok") {
        const m = motion();
        if (m.known) { geo.fix = toFix(m.last, m.speedKmh, m.headingDeg); resolveRefined(geo.fix); stop(); }
      }
    },
    (e) => { if (geo.status === "busy") { geo.status = e.code === 1 ? "denied" : "off"; resolveFirst(null); resolveRefined(null); stop(); } },
    { enableHighAccuracy: true, maximumAge: 5000, timeout: 12000 },
  );
  setTimeout(giveFirst, 3000);
  setTimeout(() => { resolveRefined(geo.fix); stop(); }, 8000);
  return geo.promise;
}
function haversine(lat1, lon1, lat2, lon2) {
  const R = 6371000, r = Math.PI / 180;
  const a = Math.sin(((lat2 - lat1) * r) / 2) ** 2 + Math.cos(lat1 * r) * Math.cos(lat2 * r) * Math.sin(((lon2 - lon1) * r) / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
}
function bearing(lat1, lon1, lat2, lon2) {
  const r = Math.PI / 180;
  const y = Math.sin((lon2 - lon1) * r) * Math.cos(lat2 * r);
  const x = Math.cos(lat1 * r) * Math.sin(lat2 * r) - Math.sin(lat1 * r) * Math.cos(lat2 * r) * Math.cos((lon2 - lon1) * r);
  return ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
}
const geoText = () => ({
  denied: "Location is blocked.",
  insecure: "Location needs a secure connection (HTTPS).",
  off: "Location is not available.",
}[geo.status] || "We could not find you.");

const nearbyTrainsFor = (f) =>
  api(`/trains/nearby?lat=${f.lat}&lon=${f.lon}&radiusM=${Math.max(NEAR_M, Math.min(f.acc, 500)) | 0}&limit=5&ts=${f.ts}` +
    (f.speedKmh != null ? `&speedKmh=${clamp(f.speedKmh, 0, 500).toFixed(1)}` : "") +
    (f.headingDeg != null ? `&headingDeg=${f.headingDeg.toFixed(0)}` : ""));
const nearbyStationsFor = (f) => api(`/stations/nearby?lat=${f.lat}&lon=${f.lon}&radiusM=${NEAR_M}&limit=3`);

// ------------------------------------------------------------------ live updates (WebSocket /api/v1/ws)

const live = {
  ws: null, subs: new Map(), backoff: 1000, open: false,
  connect() {
    const ws = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/api/v1/ws`);
    this.ws = ws;
    ws.onopen = () => { this.open = true; this.backoff = 1000; liveBadge(true); for (const s of this.subs.values()) ws.send(JSON.stringify(s.msg)); };
    ws.onmessage = (e) => { let m; try { m = JSON.parse(e.data); } catch { return; } this.subs.get(`${m.topic}:${m.id}`)?.handler(m); };
    ws.onclose = () => { this.open = false; liveBadge(false); setTimeout(() => this.connect(), this.backoff); this.backoff = Math.min(this.backoff * 2, 15000); };
  },
  subscribe(topic, id, opts, handler) {
    const msg = { op: "subscribe", topic, id, ...opts };
    this.subs.set(`${topic}:${id}`, { msg, handler });
    if (this.open) this.ws.send(JSON.stringify(msg));
    return () => { this.subs.delete(`${topic}:${id}`); if (this.open) this.ws.send(JSON.stringify({ op: "unsubscribe", topic, id })); };
  },
};
function liveBadge(on) {
  const el = $("#live");
  el.classList.toggle("off", !on);
  el.textContent = on ? "Live" : "Reconnecting";
}

// ------------------------------------------------------------------ 1. detect (location)

function detectScreen(opts = {}) {
  show((s) => {
    s.innerHTML = `
      <div class="illus-box locating">${ILLUSTRATION.search}<span class="radar"></span></div>
      <h1>Finding where you are…</h1>
      <p class="lead">We look for a train or a station within ${NEAR_M} m of you.</p>
      <div class="actions"><button class="btn block" id="skip">Just browsing</button></div>`;
    let alive = true;
    onLeave(() => (alive = false));
    $("#skip", s).onclick = () => { alive = false; browseScreen(); };
    (async () => {
      const minShow = new Promise((r) => setTimeout(r, 1200)); // let the illustration breathe, no longer
      const fix = await locate();
      let trains = fix ? (await nearbyTrainsFor(fix).catch(() => [])).filter((t) => t.distanceM <= NEAR_M) : [];
      // Several trains close by and no speed/heading yet: wait (≤ 3 s) for a second fix to tell them apart.
      if (trains.length > 1 && fix.headingDeg == null) {
        const refined = await Promise.race([geo.refined, new Promise((r) => setTimeout(() => r(null), 3000))]);
        if (refined?.headingDeg != null) trains = (await nearbyTrainsFor(refined).catch(() => trains)).filter((t) => t.distanceM <= NEAR_M);
      }
      await minShow;
      if (!alive) return;
      if (!fix) return browseScreen({ note: `${geoText()} Choose where you start and where you go.` });
      if (trains.length) return askTrainScreen(trains);
      return askStationOrBrowse(fix);
    })();
  }, { ...opts, restart: false });
}

async function askStationOrBrowse(fix) {
  const stations = fix ? await nearbyStationsFor(fix).catch(() => []) : [];
  if (stations.length) return askStationScreen(stations[0]);
  browseScreen({ note: "No train or station close to you. Choose where you start and where you go." });
}

// ------------------------------------------------------------------ 2a. are you on this train?

function askTrainScreen(candidates) {
  const [best, ...others] = candidates;
  show((s) => {
    const t = best.train;
    s.innerHTML = `
      <div class="illus-box">${ILLUSTRATION.train}</div>
      <h1>Are you on this train?</h1>
      <div class="card ask">
        <div class="head">${lineLabel(t, true)}<div class="main"><div class="t">to ${esc(t.headsign || t.destination?.name || "?")}</div>
          <div class="m">${esc(t.currentStop ? `At ${t.currentStop.name}` : t.nextStop ? `Next stop: ${t.nextStop.name}` : "")} · ${distText(best.distanceM)} from you</div></div></div>
        <div>${stateHtml(t)}</div>
      </div>
      <div class="pair"><button class="btn" id="no">No</button><button class="btn primary" id="yes">Yes</button></div>
      ${others.length ? `<h2>Other trains near you</h2><div class="list stagger">${others.map((o, i) => `
        <button class="row" type="button" data-id="${esc(o.train.trainId)}" style="--i:${i}">${lineLabel(o.train)}
          <span class="main"><span class="t">to ${esc(o.train.headsign || "?")}</span><span class="m">${distText(o.distanceM)} from you</span></span>
          <span class="chev">${ic("chevron")}</span></button>`).join("")}</div>` : ""}`;
    $("#yes", s).onclick = () => { buzz(15); onTracksScreen(t.trainId); };
    $("#no", s).onclick = () => { buzz(); askStationOrBrowse(geo.fix); };
    s.querySelectorAll("[data-id]").forEach((el) => (el.onclick = () => { buzz(15); onTracksScreen(el.dataset.id); }));
  });
}

// ------------------------------------------------------------------ 2b. are you at this station?

function askStationScreen(near) {
  show((s) => {
    s.innerHTML = `
      <div class="illus-box">${ILLUSTRATION.station}</div>
      <h1>Are you at ${esc(near.station.name)}?</h1>
      <p class="lead">${distText(near.distanceM)} from you · ${near.trainsUpcoming} train${near.trainsUpcoming === 1 ? "" : "s"} coming</p>
      <div class="pair"><button class="btn" id="no">No</button><button class="btn primary" id="yes">Yes</button></div>`;
    $("#yes", s).onclick = () => { buzz(15); destinationScreen(near.station); };
    $("#no", s).onclick = () => { buzz(); browseScreen(); };
  });
}

// ------------------------------------------------------------------ station picker (text or map)

let stationsCache = null;
const allStations = () => (stationsCache ??= api("/stations?limit=5000"));

/**
 * Text search + map, in tabs. `reachable` (optional) = Map stopId -> {trains, nextArrival} to rank and highlight
 * destinations reachable from an origin. Calls onPick({stopId, name}).
 */
function stationPicker(container, { placeholder, reachable = null, onPick, input: external = null }) {
  container.innerHTML = `
    <div class="tabs" role="tablist">
      <button class="tab active" role="tab" data-tab="text">${ic("list")}Text</button>
      <button class="tab" role="tab" data-tab="map">${ic("map")}Map</button>
    </div>
    <div class="pane" data-pane="text">
      ${external ? "" : `<label class="search">${ic("search")}<input type="text" placeholder="${esc(placeholder)}" aria-label="${esc(placeholder)}" autocomplete="off" enterkeyhint="search" />
        <button class="clear" type="button" title="Clear" aria-label="Clear">${ic("x")}</button></label>`}
      <div class="list stagger results"></div>
    </div>
    <div class="pane" data-pane="map" hidden><div class="map"></div><p class="note">Tap a station on the map.</p></div>`;
  // `input` (optional): a text field owned by the caller (e.g. the From / To fields) instead of our own search box.
  const input = external ?? $("input", container), results = $(".results", container);
  let map = null;

  const rows = async () => {
    const q = input.value.trim().toLowerCase();
    let list;
    if (reachable && !q) {
      const st = await allStations();
      list = st.filter((x) => reachable.has(x.stopId)).sort((a, b) => reachable.get(b.stopId).trains - reachable.get(a.stopId).trains).slice(0, 12);
    } else if (q) list = await api(`/stations?q=${encodeURIComponent(q)}&limit=12`).catch(() => []);
    else list = [];
    results.innerHTML = list.length ? list.map((x, i) => {
      const r = reachable?.get(x.stopId);
      return `<button class="row" type="button" data-id="${esc(x.stopId)}" data-name="${esc(x.name)}" style="--i:${i}">
        <span class="main"><span class="t">${esc(x.name)}</span>${r ? `<span class="m">${r.trains} train${r.trains > 1 ? "s" : ""} going there${r.nextArrival ? ` · first arrival ${hhmm(r.nextArrival)}` : ""}</span>` : reachable ? '<span class="m">No direct train running now</span>' : ""}</span>
        <span class="chev">${ic("chevron")}</span></button>`;
    }).join("") : q ? '<p class="empty">No station matches.</p>' : "";
    results.querySelectorAll(".row").forEach((el) => (el.onclick = () => { el.classList.add("selected"); onPick({ stopId: el.dataset.id, name: el.dataset.name }); }));
  };
  const onInput = debounce(rows, 180);
  const onKey = (e) => { if (e.key === "Enter") { e.preventDefault(); $(".row", results)?.click(); } };
  input.addEventListener("input", onInput);
  input.addEventListener("keydown", onKey);
  const clear = $(".clear", container);
  if (clear) clear.onclick = () => { input.value = ""; rows(); input.focus(); };
  rows();

  container.querySelectorAll(".tab").forEach((tab) => (tab.onclick = async () => {
    buzz();
    container.querySelectorAll(".tab").forEach((t) => t.classList.toggle("active", t === tab));
    container.querySelectorAll(".pane").forEach((p) => (p.hidden = p.dataset.pane !== tab.dataset.tab));
    if (tab.dataset.tab === "map") {
      if (!map) map = await stationMap($(".map", container), { reachable, onPick });
      else map.resize();
    }
  }));
  return () => { input.removeEventListener("input", onInput); input.removeEventListener("keydown", onKey); map?.remove(); };
}

async function stationMap(el, { reachable, onPick }) {
  while (!window.maplibregl) await new Promise((r) => setTimeout(r, 50));
  const map = new maplibregl.Map({ container: el, style: await baseStyle(), bounds: [[5.9, 45.8], [10.5, 47.85]], maxBounds: [[5.4, 45.55], [11.0, 48.05]], attributionControl: { compact: true } });
  const recolour = (theme) => applyMapTheme(map, theme);
  themeListeners.add(recolour);
  map.on("load", async () => {
    addRail(map);
    recolour(document.documentElement.dataset.theme);
    const st = await allStations();
    map.addSource("st", { type: "geojson", data: { type: "FeatureCollection", features: st.filter((x) => x.lat != null).map((x) => ({
      type: "Feature", geometry: { type: "Point", coordinates: [x.lon, x.lat] },
      properties: { id: x.stopId, name: x.name, reach: reachable ? (reachable.get(x.stopId)?.trains ?? 0) : 1 },
    })) } });
    const ringed = (minzoom, filter, r7, r12, stroke) => ({ type: "circle", source: "st", minzoom, filter, paint: {
      "circle-radius": ["interpolate", ["linear"], ["zoom"], 7, r7, 12, r12], "circle-color": "#ffffff", "circle-stroke-width": 2, "circle-stroke-color": stroke } });
    // Reachable stations always; the others only when zoomed in (small, cement), so the map stays readable.
    map.addLayer({ id: "st-other", ...ringed(reachable ? 10 : 8, ["==", ["get", "reach"], 0], 2, 4, "#bdbdbd") });
    map.addLayer({ id: "st", ...ringed(0, [">", ["get", "reach"], 0], 4, 8, "#444444") });
    if (reachable?.size) {
      const pts = st.filter((x) => reachable.has(x.stopId) && x.lat != null);
      const lons = pts.map((x) => x.lon), lats = pts.map((x) => x.lat);
      if (pts.length) map.fitBounds([[Math.min(...lons), Math.min(...lats)], [Math.max(...lons), Math.max(...lats)]], { padding: 40, maxZoom: 11, duration: reducedMotion ? 0 : 700 });
    }
    map.addLayer({ id: "st-label", type: "symbol", source: "st", minzoom: 10, filter: [">", ["get", "reach"], 0],
      layout: { "text-field": ["get", "name"], "text-size": 12, "text-offset": [0, 1.1], "text-anchor": "top", "text-font": ["Frutiger Neue Bold"] },
      paint: { "text-color": "#212121", "text-halo-color": "#ffffff", "text-halo-width": 2 } });
    for (const id of ["st", "st-other"]) {
      map.on("mouseenter", id, () => (map.getCanvas().style.cursor = "pointer"));
      map.on("mouseleave", id, () => (map.getCanvas().style.cursor = ""));
    }
    map.on("click", ["st", "st-other"], (e) => {
      const p = e.features[0].properties;
      buzz();
      new maplibregl.Popup({ closeButton: false, offset: 10 }).setLngLat(e.lngLat)
        .setHTML(`<div class="pop"><b>${esc(p.name)}</b><button class="btn primary" type="button">Choose</button></div>`)
        .addTo(map).getElement().querySelector("button").onclick = () => onPick({ stopId: p.id, name: p.name });
    });
  });
  const remove = map.remove.bind(map);
  map.remove = () => { themeListeners.delete(recolour); remove(); };
  return map;
}

// ------------------------------------------------------------------ 3. at a station: where to?

function destinationScreen(from) {
  setUrl({ station: from.stopId });
  show((s) => {
    s.innerHTML = `
      ${backBtn("Start again")}
      <div class="step">At ${esc(from.name)}</div>
      <div class="deps-head"><h2>Next departures</h2><span class="note" id="deps-note"></span></div>
      <div class="deps card" id="deps">${placeholders(3)}</div>
      <h1>Where are you going?</h1>
      <div id="picker"></div>`;
    $("[data-back]", s).onclick = () => restartBtn.click();
    departuresBoard($("#deps", s), $("#deps-note", s), from);
    api(`/stations/${encodeURIComponent(from.stopId)}/destinations?limit=500`).catch(() => []).then((dests) => {
      const reachable = new Map(dests.map((d) => [d.station.stopId, { trains: d.trains, nextArrival: d.nextArrival }]));
      onLeave(stationPicker($("#picker", s), {
        placeholder: "Destination", reachable,
        onPick: (to) => { buzz(15); connectionsScreen(from, to); },
      }));
    });
  });
}

/**
 * Next departures at [from] (station screen, above the destination picker): time + delay, line, destination,
 * countdown, and a mark on trains with an incident. Tapping a row opens it: incidents, the next stops with their
 * ETA, and a link to the train. Live: refreshed every 20 s and right away when an incident changes.
 */
function departuresBoard(box, note, from) {
  const SHOW = 5, MAX = 12;
  const open = new Set();          // expanded trainIds (kept across refreshes)
  const details = new Map();       // trainId -> { stops } (fetched on first expand)
  let entries = [], showAll = false, fails = 0;

  const countdown = (e) => {
    if (e.status === "cancelled") return '<span class="warn">Does not run</span>';
    if (e.atStation) return "<b>At the platform</b>";
    return untilText(e.time) === "now" ? "<b>Leaving now</b>" : esc(untilText(e.time));
  };
  const stopsHtml = (e) => {
    const d = details.get(e.trainId);
    if (!d) return '<div class="placeholder"></div>';
    const k = d.stops.findIndex((x) => x.stopId === from.stopId && x.state !== "passed");
    const next = d.stops.slice(k + 1).filter((x) => !x.skipped);
    if (!next.length) return "";
    const pick = next.length > 4 ? [...next.slice(0, 3), null, next.at(-1)] : next;
    return `<ol class="dep-stops">${pick.map((x) => x ? `<li><span class="n">${esc(x.name)}</span>
        <span class="tm">${timeHtml(x.plannedArr ?? x.plannedDep, x.expectedArr ?? x.expectedDep, e.status === "cancelled")}</span></li>`
      : `<li class="gap"><span class="n">${next.length - 4} more stop${next.length - 4 === 1 ? "" : "s"}</span></li>`).join("")}</ol>`;
  };

  const draw = () => {
    if (!entries.length) {
      box.innerHTML = `<p class="empty">No departures in the next 2 hours from trains already running.</p>`;
      return;
    }
    const list = showAll ? entries : entries.slice(0, SHOW);
    box.innerHTML = list.map((e) => {
      const n = e.incidents.length;
      const isOpen = open.has(e.trainId);
      const cancelled = e.status === "cancelled";
      return `<div class="dep${isOpen ? " open" : ""}${cancelled ? " cancelled" : ""}">
        <button class="dep-row" type="button" data-id="${esc(e.trainId)}" aria-expanded="${isOpen}">
          <span class="dep-time">${timeHtml(e.plannedTime, e.time, cancelled)}</span>
          <span class="dep-main">
            <span class="dep-l1">${lineLabel(e)}<span class="h">${esc(e.headsign || e.destination?.name || "?")}</span></span>
            <span class="dep-l2">${countdown(e)}${n ? ` · <span class="inc"><span class="dot"></span>${n} incident${n === 1 ? "" : "s"}</span>` : ""}</span>
          </span>
          ${ic("chevron", "chev")}
        </button>
        ${isOpen ? `<div class="dep-more">
          ${n ? `<div class="list">${e.incidents.map(alertHtml).join("")}</div>` : ""}
          ${stopsHtml(e)}
          <button class="btn" type="button" data-train="${esc(e.trainId)}">Follow this train</button>
        </div>` : ""}
      </div>`;
    }).join("") + (entries.length > SHOW
      ? `<button class="dep-all" type="button">${showAll ? "Fewer departures" : `${entries.length - SHOW} more departure${entries.length - SHOW === 1 ? "" : "s"}`}</button>` : "");

    box.querySelectorAll(".dep-row").forEach((b) => (b.onclick = () => {
      const id = b.dataset.id;
      buzz(8);
      if (open.has(id)) open.delete(id); else {
        open.add(id);
        if (!details.has(id)) api(`/trains/${encodeURIComponent(id)}/stops`).then((stops) => { details.set(id, { stops }); draw(); }).catch(() => {});
      }
      draw();
    }));
    box.querySelectorAll("[data-train]").forEach((b) => (b.onclick = () => { buzz(15); onTracksScreen(b.dataset.train); }));
    const all = $(".dep-all", box);
    if (all) all.onclick = () => { showAll = !showAll; draw(); };
  };

  const refresh = () => api(`/stations/${encodeURIComponent(from.stopId)}/board?type=departures&windowMin=120&limit=${MAX}&includeSkipped=false`)
    .then((b) => {
      fails = 0;
      entries = b.entries;
      const late = entries.filter((e) => (e.delaySec ?? 0) >= LATE_SEC).length;
      const inc = entries.filter((e) => e.incidents.length).length;
      note.textContent = [late && `${late} late`, inc && `${inc} with incidents`].filter(Boolean).join(" · ");
      details.clear();     // stop times change: refetch for the open rows
      open.forEach((id) => api(`/trains/${encodeURIComponent(id)}/stops`).then((stops) => { details.set(id, { stops }); draw(); }).catch(() => {}));
      draw();
    })
    .catch(() => { if (++fails === 1 && !entries.length) box.innerHTML = '<p class="empty">Departures could not be loaded. Retrying…</p>'; });
  refresh();
  const poll = setInterval(refresh, 20_000);
  const clock = setInterval(draw, 15_000);       // countdowns
  onLeave(() => { clearInterval(poll); clearInterval(clock); });
  onLeave(live.subscribe("station", from.stopId, { frequencySec: 3600, windowMin: 120 }, (m) => {
    if (m.type === "incident" && m.event !== "active") refresh();
  }));
}

// ------------------------------------------------------------------ 3b. just browsing: origin + destination

function browseScreen({ note = null, back = false } = {}) {
  const pick = { from: null, to: null };
  const hint = { from: "Where do you start?", to: "Where do you go?" };
  show((s) => {
    const field$ = (f) => `<label class="od-field" data-f="${f}"><span class="k">${f === "from" ? "From" : "To"}</span>
      <input class="v" type="text" placeholder="${hint[f]}" aria-label="${hint[f]}" autocomplete="off" enterkeyhint="search" /></label>`;
    s.innerHTML = `
      <div class="illus-box small">${ILLUSTRATION.search}</div>
      <h1>Plan a trip</h1>
      <p class="lead">${esc(note || "Choose where you start and where you go.")}</p>
      <div class="od card">
        ${field$("from")}
        <button class="icon-btn swap" type="button" title="Swap" aria-label="Swap">${ic("swap")}</button>
        ${field$("to")}
      </div>
      <div id="picker"></div>
      <div class="actions"><button class="btn primary block" id="go" disabled>Show trains</button></div>`;
    const inputOf = (f) => $(`.od-field[data-f="${f}"] input`, s);
    let field = "from";
    let close = null;
    // syncText: write the picked station names into the fields (not while the user is typing).
    const render = (syncText = false) => {
      s.querySelectorAll(".od-field").forEach((el) => {
        const f = el.dataset.f, inp = $("input", el);
        el.classList.toggle("active", f === field && !!close);
        if (syncText) inp.value = pick[f]?.name ?? "";
        inp.classList.toggle("set", !!pick[f] && inp.value === pick[f].name);
      });
      $("#go", s).disabled = !(pick.from && pick.to && pick.from.stopId !== pick.to.stopId);
    };
    const closePicker = () => { close?.(); close = null; $("#picker", s).innerHTML = ""; render(); };
    const openPicker = async () => {
      close?.();
      close = () => {}; // marks the picker as open while `reachable` loads
      const f = field;
      const reachable = f === "to" && pick.from
        ? new Map((await api(`/stations/${encodeURIComponent(pick.from.stopId)}/destinations?limit=500`).catch(() => [])).map((d) => [d.station.stopId, d]))
        : null;
      if (f !== field) return; // the user moved to the other field meanwhile
      close = stationPicker($("#picker", s), {
        input: inputOf(f), reachable,
        onPick: (st) => {
          buzz();
          pick[f] = st;
          render(true);
          const next = !pick.from ? "from" : !pick.to ? "to" : null;
          if (next) { field = next; render(); inputOf(next).focus({ preventScroll: true }); openPicker(); }
          else { inputOf(f).blur(); closePicker(); }
        },
      });
      render();
    };
    onLeave(() => close?.());
    s.querySelectorAll(".od-field").forEach((el) => {
      const f = el.dataset.f, inp = $("input", el);
      inp.addEventListener("focus", () => {
        if (field === f && close) return;
        field = f;
        inp.select();
        openPicker();
      });
      inp.addEventListener("input", () => { pick[f] = null; render(); });
    });
    $(".swap", s).onclick = () => {
      [pick.from, pick.to] = [pick.to, pick.from];
      render(true);
      if (close) openPicker(); // the destination list depends on the origin
    };
    $("#go", s).onclick = () => { buzz(15); connectionsScreen(pick.from, pick.to); };
    render();
    openPicker();
    setTimeout(() => inputOf("from")?.focus({ preventScroll: true }), 250);
  }, { back });
}

// ------------------------------------------------------------------ 4. trains from A to B (live)

function connectionsScreen(from, to) {
  setUrl({ station: from.stopId, to: to.stopId });
  show((s) => {
    s.innerHTML = `
      ${backBtn()}
      <div class="step">From ${esc(from.name)}</div>
      <h1>Trains to ${esc(to.name)}</h1>
      <div id="alerts" class="list"></div>
      <div class="list" id="list">${placeholders()}</div>
      <p class="note">Live data. Only trains already in service are shown.</p>`;
    $("[data-back]", s).onclick = () => destinationScreen(from);
    const list = $("#list", s);
    let firstDraw = true;
    const draw = (data) => {
      const alerts = new Map();
      data.entries.forEach((c) => c.incidents.forEach((i) => alerts.set(i.id, i)));
      $("#alerts", s).innerHTML = [...alerts.values()].map(alertHtml).join("");
      if (!data.entries.length) {
        list.innerHTML = `<p class="empty">No train running now goes to ${esc(to.name)} from here. Trains are shown once they start their run.</p>`;
        return;
      }
      list.classList.toggle("stagger", firstDraw);
      firstDraw = false;
      list.innerHTML = data.entries.map((c, i) => {
        const t = c.train;
        return `<button class="row conn" type="button" data-id="${esc(t.trainId)}" style="--i:${Math.min(i, 10)}">
          <div class="dep">${timeHtml(c.plannedDepartureTime, c.departureTime, t.status === "cancelled")}<small data-until="${c.departureTime || ""}">${c.atStation ? "Now at the station" : untilText(c.departureTime)}</small></div>
          <div class="l1">${lineLabel(t)}<span class="h">to ${esc(t.headsign || "?")}</span></div>
          <div class="r">${stateHtml(t)}</div>
          <div class="l2">Arrival ${hhmm(c.arrivalTime)}${c.durationMin != null ? ` · ${c.durationMin} min` : ""} · ${c.stopsBetween ? `${c.stopsBetween} stop${c.stopsBetween > 1 ? "s" : ""}` : "Direct"}${c.incidents.length ? ` · <span class="warn">${c.incidents.length} incident${c.incidents.length > 1 ? "s" : ""}</span>` : ""}</div>
        </button>`;
      }).join("");
      list.querySelectorAll(".conn").forEach((el) => (el.onclick = () => { buzz(15); onTracksScreen(el.dataset.id); }));
    };
    const refresh = () => api(`/stations/${encodeURIComponent(from.stopId)}/connections?to=${encodeURIComponent(to.stopId)}&windowMin=240&limit=12`)
      .then(draw).catch(() => toast("Connection problem. Retrying…"));
    refresh();
    const poll = setInterval(refresh, 20_000);
    const clock = setInterval(() => list.querySelectorAll("[data-until]").forEach((el) => {
      if (el.dataset.until && !el.textContent.startsWith("Now")) el.textContent = untilText(+el.dataset.until);
    }), 5_000);
    onLeave(() => { clearInterval(poll); clearInterval(clock); });
    onLeave(live.subscribe("station", from.stopId, { frequencySec: 3600, windowMin: 30 }, (m) => {
      if (m.type === "incident" && m.event !== "active") { buzz([20, 40, 20]); toast(m.incident.title); refresh(); }
    }));
  });
}

// ------------------------------------------------------------------ 5. on tracks (on a train)

function onTracksScreen(trainId) {
  setUrl({ train: trainId });
  show((s) => {
    s.innerHTML = `
      <div id="alerts" class="list"></div>
      <div class="scene card"><canvas id="scene" aria-hidden="true"></canvas>
        <div class="scene-cap"><span id="line"></span><div class="main"><div class="t" id="to"></div><div class="m" id="where"></div></div><span id="state"></span></div>
      </div>
      <div class="nextstop card" id="next"></div>
      <div class="follow card" id="follow"></div>
      <h2>Route</h2>
      <div class="route" id="route">${placeholders(4)}</div>`;
    const incidents = new Map();
    let last = null;
    const scene = trainScene($("#scene", s));
    onLeave(() => scene.then((x) => x?.dispose()));

    const drawAlerts = () => { $("#alerts", s).innerHTML = [...incidents.values()].map(alertHtml).join(""); };
    const follow = followCard($("#follow", s), () => last && { trainId, train: last.train, stops: last.stops });

    const draw = (train, stops) => {
      last = { train, stops };
      const cancelled = train.status === "cancelled";
      $("#line", s).innerHTML = lineLabel(train, true);
      $("#to", s).textContent = `to ${train.headsign || train.destination?.name || "?"}`;
      $("#state", s).innerHTML = stateHtml(train);
      const standing = train.position?.motion === "stopped" && train.position?.atStopId;
      scene.then((x) => x?.update({ speedKmh: standing ? 0 : train.position?.speedKmh ?? 0, atStation: standing ? (train.currentStop?.name || " ") : null }));

      const k = stops.findIndex((x) => x.state === "current" || x.state === "next");
      const cur = k >= 0 ? stops[k] : null;
      const prev = k > 0 ? stops[k - 1] : null;
      $("#where", s).textContent = !cur ? "Run completed"
        : cur.state === "current" ? `At ${cur.name}` : prev ? `Between ${prev.name} and ${cur.name}` : `Before ${cur.name}`;

      // Next stop + ETA
      const next = cur && cur.state === "current" ? stops[k + 1] : cur;
      $("#next", s).innerHTML = next
        ? `<div class="k">Next stop</div><div class="nrow"><b>${esc(next.name)}</b><span class="eta-big">${untilText(next.expectedArr ?? next.plannedArr) || "–"}</span></div>
           <div class="m">${timeHtml(next.plannedArr ?? next.plannedDep, next.expectedArr ?? next.expectedDep, cancelled)}</div>`
        : `<div class="k">This train has reached ${esc(train.destination?.name || "its destination")}</div>`;

      // Whole route; the train sits between the last stop left and the next one.
      let progress = 0.5;
      if (cur && prev && cur.state === "next") {
        const dep = prev.expectedDep ?? prev.plannedDep, arr = cur.expectedArr ?? cur.plannedArr;
        if (dep && arr && arr > dep) progress = clamp((Date.now() - dep) / (arr - dep), 0.05, 0.95);
      }
      const html = stops.map((x, i) => {
        const t = x.expectedArr ?? x.expectedDep ?? x.plannedArr ?? x.plannedDep;
        const cls = `stop ${x.state}${i === 0 ? " first" : ""}${i === stops.length - 1 ? " last" : ""}${x.skipped ? " skipped" : ""}`;
        const between = i === k && x.state === "next" && prev
          ? `<div class="between"><span class="pin-train" style="--p:${progress}">${ic("train")}</span></div>` : "";
        return `${between}<div class="${cls}">
          <span class="n">${esc(x.name)}${x.skipped ? ' <span class="nostop">Does not stop</span>' : ""}</span>
          <span class="tm"><span>${x.skipped ? "" : timeHtml(x.plannedArr ?? x.plannedDep, x.expectedArr ?? x.expectedDep, cancelled)}</span>
            ${x.state === "passed" || x.skipped ? "" : `<small>${x.state === "current" ? "Now" : untilText(t)}</small>`}</span></div>`;
      }).join("");
      const box = $("#route", s);
      if (box.dataset.sig !== html) {
        const first = !box.dataset.sig;
        box.dataset.sig = html;
        box.innerHTML = html;
        const here = box.querySelector(".between, .stop.current");
        if (first && here) box.scrollTo({ top: here.offsetTop - box.clientHeight / 2, behavior: reducedMotion ? "auto" : "smooth" });
      }
    };

    api(`/trains/${encodeURIComponent(trainId)}`).then((d) => {
      d.incidents.forEach((i) => incidents.set(i.id, i));
      drawAlerts();
      draw(d.train, d.stops);
      follow.refresh();
    }).catch((e) => { if (e.status === 404) trainGone(s); });

    onLeave(live.subscribe("train", trainId, { frequencySec: 3 }, (m) => {
      if (m.type === "train_update") draw(m.data.train, m.data.stops);
      else if (m.type === "incident") {
        if (m.event === "removed" || m.event === "missing") incidents.delete(m.incident.id);
        else {
          if (!incidents.has(m.incident.id) && m.event !== "active") { buzz([20, 40, 20]); toast(m.incident.title); }
          incidents.set(m.incident.id, m.incident);
        }
        drawAlerts();
      } else if (m.type === "train_ended") trainGone(s);
    }));
    const tick = setInterval(() => last && draw(last.train, last.stops), 15_000);
    onLeave(() => clearInterval(tick));
  });
}

// ------------------------------------------------------------------ 6. alerts (push notifications, push.js)

/** Subscriptions of this device, cached for the session (refreshed after every change). */
const alerts = {
  subs: null,
  async load(force = false) {
    if (!this.subs || force) this.subs = await push.list().catch(() => this.subs || []);
    return this.subs;
  },
  forTrain(id) { return this.subs?.find((x) => x.kind === "train" && x.trainId === id); },
  forLine(line) { return this.subs?.find((x) => x.kind === "line" && x.line?.toLowerCase() === line?.toLowerCase()); },
};

const subText = (x) => x.kind === "line" ? "Incidents on this line"
  : x.stopName ? `Arrival at ${x.stopName} and incidents` : "Incidents on this train";

/** "Notify me" card on the train screen. [ctx] returns { trainId, train, stops } once the train is loaded. */
function followCard(el, ctx) {
  const draw = () => {
    const c = ctx();
    if (!c) { el.hidden = true; return; }
    el.hidden = false;
    const sub = alerts.forTrain(c.trainId);
    el.innerHTML = sub
      ? `<span class="fic on">${ic("bell")}</span><div class="main"><div class="t">Alerts on</div><div class="m">${esc(subText(sub))}</div></div>
         <button class="btn" type="button" data-edit>Change</button>`
      : `<span class="fic">${ic("bell")}</span><div class="main"><div class="t">Get alerts</div><div class="m">Before your stop, and when an incident starts or ends.</div></div>
         <button class="btn primary" type="button" data-edit>Notify me</button>`;
    $("[data-edit]", el).onclick = () => { buzz(); followSheet(c, refresh); };
  };
  const refresh = () => alerts.load(true).then(draw);
  draw();
  return { refresh };
}

/** Bottom sheet: pick the stop where you get off (or incidents only), optionally follow the whole line too. */
function followSheet({ trainId, train, stops }, done) {
  const current = alerts.forTrain(trainId);
  const line = train.line;
  const lineSub = line ? alerts.forLine(line) : null;
  const sup = push.support();
  const ahead = stops.filter((x) => (x.state === "next" || x.state === "upcoming") && !x.skipped);
  const chosen = current ? (current.stopId || "") : (ahead.at(-1)?.stopId || "");
  const opt = (value, title, meta) => `<label class="row tap${value === chosen ? " selected" : ""}">
      <input class="vh" type="radio" name="stop" value="${esc(value)}"${value === chosen ? " checked" : ""}><span class="radio"></span>
      <span class="main"><span class="t">${esc(title)}</span><span class="m">${meta}</span></span></label>`;

  const back = document.createElement("div");
  back.className = "sheet-backdrop";
  back.innerHTML = `<div class="sheet" role="dialog" aria-modal="true" aria-labelledby="sheet-t">
    <h2 id="sheet-t">Alerts for ${esc(line || train.category || "this train")} to ${esc(train.headsign || train.destination?.name || "?")}</h2>
    ${sup.ok ? `
      <p class="note">Where do you get off? You get an alert about ${Math.round(push.leadSec / 60)} min before arriving, and whenever an incident on this train starts or ends.</p>
      <div class="list stop-pick">
        ${ahead.map((x) => opt(x.stopId, x.name, `Arrival ${timeHtml(x.plannedArr ?? x.plannedDep, x.expectedArr ?? x.expectedDep)}`)).join("")}
        ${opt("", "Incidents only", "No arrival alert")}
      </div>
      ${line && !lineSub ? `<label class="check"><input type="checkbox" id="sheet-line"> Also follow line ${esc(line)} (incidents only)</label>` : ""}
      <div class="pair"><button class="btn" type="button" data-close>Cancel</button><button class="btn primary" type="button" id="sheet-ok">${current ? "Save" : "Turn on"}</button></div>
      ${current ? '<button class="btn block" type="button" id="sheet-off">Turn off alerts for this train</button>' : ""}`
    : `<p class="lead">${esc(sup.reason)}</p><div class="actions"><button class="btn block" type="button" data-close>Close</button></div>`}
  </div>`;
  document.body.append(back);
  const close = () => { back.classList.add("leave"); setTimeout(() => back.remove(), 200); document.removeEventListener("keydown", onKey); };
  const onKey = (e) => { if (e.key === "Escape") close(); };
  document.addEventListener("keydown", onKey);
  back.addEventListener("click", (e) => { if (e.target === back || e.target.closest("[data-close]")) close(); });
  back.querySelectorAll('input[name="stop"]').forEach((r) => r.addEventListener("change", () => {
    back.querySelectorAll(".stop-pick .row").forEach((row) => row.classList.toggle("selected", row.contains(r) && r.checked));
  }));
  $(".stop-pick .row.selected", back)?.scrollIntoView({ block: "center" });
  (back.querySelector("#sheet-ok") || back.querySelector("[data-close]")).focus({ preventScroll: true });

  const busy = (btn, on, label) => { btn.disabled = on; if (label) btn.textContent = label; };
  const ok = $("#sheet-ok", back);
  if (ok) ok.onclick = async () => {
    const stopId = back.querySelector('input[name="stop"]:checked')?.value || null;
    busy(ok, true, "Turning on…");
    try {
      await push.follow({ trainId, stopId });
      if ($("#sheet-line", back)?.checked) await push.follow({ line });
      buzz(15);
      const stop = ahead.find((x) => x.stopId === stopId);
      toast(stop ? `Alerts on. We will tell you before ${stop.name}.` : "Alerts on for this train.");
      close();
      done();
    } catch (e) {
      toast(e.message || "Could not turn alerts on.", 6000);
      busy(ok, false, current ? "Save" : "Turn on");
    }
  };
  const off = $("#sheet-off", back);
  if (off) off.onclick = async () => {
    busy(off, true, "Turning off…");
    try { await push.unfollow(current.id); toast("Alerts off for this train."); close(); done(); }
    catch (e) { toast(e.message || "Could not turn alerts off."); busy(off, false, "Turn off alerts for this train"); }
  };
}

/** Bell in the top bar: what this device follows, and "follow a line". [onBack] returns to the previous screen. */
function alertsScreen(onBack) {
  setUrl({});
  show((s) => {
    const sup = push.support();
    s.innerHTML = `
      ${backBtn()}
      <h1>Your alerts</h1>
      <p class="lead">Trains and lines you follow. Alerts arrive even when this page is closed.</p>
      ${sup.ok ? "" : `<p class="note warnbox">${esc(sup.reason)}</p>`}
      <div class="list" id="subs">${placeholders(2)}</div>
      <h2>Follow a line</h2>
      <p class="note">Get an alert when an incident starts or ends anywhere on a line.</p>
      <label class="search">${ic("search")}<input id="lq" type="search" placeholder="Line, e.g. IC 1 or S3" autocomplete="off" enterkeyhint="search" aria-label="Search a line"></label>
      <div class="list" id="lines">${placeholders(3)}</div>
      <div class="actions" id="test-box" hidden><button class="btn block" type="button" id="test">Send a test alert</button></div>`;
    $("[data-back]", s).onclick = () => onBack();
    const self = () => alertsScreen(onBack);

    const drawSubs = (subs) => {
      $("#test-box", s).hidden = !push.enabled();
      $("#subs", s).innerHTML = subs.length ? subs.map((x) => `
        <div class="row">
          <button class="main linkish" type="button" data-open="${esc(x.id)}"><span class="t">${esc(x.label)}</span><span class="m">${esc(subText(x))}</span></button>
          <button class="icon-btn" type="button" data-off="${esc(x.id)}" title="Stop following" aria-label="Stop following ${esc(x.label)}">${ic("x")}</button>
        </div>`).join("")
        : `<p class="empty">You follow nothing yet. Open your train and tap Notify me, or follow a line below.</p>`;
      $("#subs", s).querySelectorAll("[data-off]").forEach((b) => (b.onclick = async () => {
        b.disabled = true;
        try { await push.unfollow(b.dataset.off); buzz(); alerts.load(true).then(drawSubs); }
        catch (e) { toast(e.message || "Could not remove it."); b.disabled = false; }
      }));
      $("#subs", s).querySelectorAll("[data-open]").forEach((b) => (b.onclick = () => {
        const x = subs.find((y) => y.id === b.dataset.open);
        buzz(15);
        if (x.kind === "train") onTracksScreen(x.trainId); else lineScreen(x.line, self);
      }));
    };
    alerts.load(true).then(drawSubs);

    let all = [];
    const drawLines = () => {
      const q = $("#lq", s).value.trim().toLowerCase().replace(/\s+/g, "");
      const hits = all.filter((l) => !q || l.line.toLowerCase().replace(/\s+/g, "").includes(q)).slice(0, 30);
      $("#lines", s).innerHTML = hits.length ? hits.map((l) => `
        <button class="row" type="button" data-line="${esc(l.line)}">${lineLabel(l)}
          <span class="main"><span class="t">Line ${esc(l.line)}</span><span class="m">${l.trains} train${l.trains === 1 ? "" : "s"} running${l.incidents ? ` · <span class="warn">${l.incidents} incident${l.incidents === 1 ? "" : "s"}</span>` : " · No incidents"}</span></span>
          ${alerts.forLine(l.line) ? `<span class="side">${ic("bell")}</span>` : ""}${ic("chevron", "chev")}</button>`).join("")
        : `<p class="empty">${all.length ? "No line in service matches." : "No line in service right now."}</p>`;
      $("#lines", s).querySelectorAll("[data-line]").forEach((b) => (b.onclick = () => { buzz(15); lineScreen(b.dataset.line, self); }));
    };
    api("/lines").then((l) => { all = l; drawLines(); }).catch(() => { $("#lines", s).innerHTML = '<p class="empty">Lines could not be loaded.</p>'; });
    $("#lq", s).addEventListener("input", debounce(drawLines, 120));

    $("#test", s).onclick = async (e) => {
      e.target.disabled = true;
      try {
        const r = await push.test();
        toast(r.result === "ok" ? "Test alert sent. It should arrive in a few seconds." : "The test alert could not be delivered.");
      } catch (err) { toast(err.message || "Could not send the test alert."); }
      e.target.disabled = false;
    };
  }, { restart: false });
}

/** Line status: incidents concerning the line and its trains in service; follow it for incident alerts. */
function lineScreen(line, onBack) {
  setUrl({ line });
  show((s) => {
    s.innerHTML = `
      ${backBtn()}
      <div class="step">Line status</div>
      <h1 class="line-h"><span id="lbl"></span><span id="lname">Line ${esc(line)}</span></h1>
      <p class="lead" id="lsum">&nbsp;</p>
      <div class="follow card" id="follow"><div class="main"><div class="m">&nbsp;</div></div></div>
      <h2>Incidents</h2>
      <div class="list" id="linc">${placeholders(1)}</div>
      <h2>Trains in service</h2>
      <div class="list" id="ltr">${placeholders(3)}</div>`;
    $("[data-back]", s).onclick = () => onBack();
    let name = line;

    const drawFollow = () => {
      const sub = alerts.forLine(name);
      const el = $("#follow", s);
      el.innerHTML = sub
        ? `<span class="fic on">${ic("bell")}</span><div class="main"><div class="t">Following this line</div><div class="m">Alerts when an incident starts or ends.</div></div>
           <button class="btn" type="button" id="lf">Stop</button>`
        : `<span class="fic">${ic("bell")}</span><div class="main"><div class="t">Follow this line</div><div class="m">Alerts when an incident starts or ends.</div></div>
           <button class="btn primary" type="button" id="lf">Follow</button>`;
      $("#lf", el).onclick = async (e) => {
        e.target.disabled = true;
        try {
          if (sub) { await push.unfollow(sub.id); toast(`You no longer follow line ${name}.`); }
          else { await push.follow({ line: name }); buzz(15); toast(`Following line ${name}.`); }
          await alerts.load(true);
        } catch (err) { toast(err.message || "Could not change it.", 6000); }
        drawFollow();
      };
    };

    const draw = (d) => {
      name = d.line;
      $("#lbl", s).innerHTML = lineLabel(d, true);
      $("#lname", s).textContent = `to ${new Set(d.trains.map((t) => t.headsign).filter(Boolean)).size > 2 ? "several destinations" : [...new Set(d.trains.map((t) => t.headsign).filter(Boolean))].join(" / ") || "?"}`;
      $("#lsum", s).innerHTML = d.incidents.length
        ? `<span class="warn">${d.incidents.length} incident${d.incidents.length === 1 ? "" : "s"}</span> · ${d.trains.length} train${d.trains.length === 1 ? "" : "s"} running`
        : `No incidents · ${d.trains.length} train${d.trains.length === 1 ? "" : "s"} running`;
      $("#linc", s).innerHTML = d.incidents.length ? d.incidents.map(alertHtml).join("") : '<p class="empty">Nothing to report on this line.</p>';
      $("#ltr", s).innerHTML = d.trains.map((t) => `
        <button class="row" type="button" data-id="${esc(t.trainId)}">${lineLabel(t)}
          <span class="main"><span class="t">to ${esc(t.headsign || t.destination?.name || "?")}</span>
            <span class="m">${t.currentStop ? `At ${esc(t.currentStop.name)}` : t.nextStop ? `Next: ${esc(t.nextStop.name)}` : ""}</span></span>
          <span class="side">${stateHtml(t)}</span></button>`).join("");
      $("#ltr", s).querySelectorAll("[data-id]").forEach((b) => (b.onclick = () => { buzz(15); onTracksScreen(b.dataset.id); }));
    };
    const refresh = () => api(`/lines/${encodeURIComponent(line)}`).then(draw).catch((e) => {
      if (e.status === 404) {
        $("#lsum", s).textContent = "No train is running on this line right now.";
        $("#linc", s).innerHTML = ""; $("#ltr", s).innerHTML = "";
      } else toast("Connection problem. Retrying…");
    });
    alerts.load().then(drawFollow);
    refresh();
    const poll = setInterval(refresh, 30_000);
    onLeave(() => clearInterval(poll));
  }, { restart: true });
}

function trainGone(s) {
  setUrl({});
  s.innerHTML = `<div class="finish"><h1>This train has ended its run</h1><p class="lead">It is no longer in service.</p></div>
    <div class="actions"><button class="btn primary block" id="again">Start again</button></div>`;
  $("#again", s).onclick = () => restartBtn.click();
}

/**
 * Three.js scene: always the same train on the same straight track (not the real geometry). The train stays put; the
 * sleepers, poles and ground move under it at a pace that follows the real speed, so a stopped train stands still.
 */
async function trainScene(canvas) {
  let THREE;
  try { THREE = await import(THREE_URL); } catch { canvas.closest(".scene").classList.add("no3d"); return null; }
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(32, 2, 0.1, 200);
  camera.position.set(9, 6.4, 11);
  camera.lookAt(-0.5, 0.6, -2);

  const mat = (c) => new THREE.MeshLambertMaterial({ color: c });
  const palette = () => ({
    white: cssVar("--white"), milk: cssVar("--milk"), cloud: cssVar("--cloud"), iron: cssVar("--iron"), granite: cssVar("--granite"),
    cement: cssVar("--cement"), charcoal: cssVar("--charcoal"), red: cssVar("--red"),
  });
  let P = palette();
  scene.add(new THREE.HemisphereLight(0xffffff, 0x888888, 1.6));
  const sun = new THREE.DirectionalLight(0xffffff, 1.4);
  sun.position.set(6, 10, 8);
  scene.add(sun);

  const ground = new THREE.Mesh(new THREE.PlaneGeometry(80, 200), mat(P.milk));
  ground.rotation.x = -Math.PI / 2;
  scene.add(ground);
  const ballast = new THREE.Mesh(new THREE.BoxGeometry(3.2, 0.2, 200), mat(P.cloud));
  ballast.position.y = 0.1;
  scene.add(ballast);
  for (const x of [-0.72, 0.72]) {
    const rail = new THREE.Mesh(new THREE.BoxGeometry(0.1, 0.14, 200), mat(P.iron));
    rail.position.set(x, 0.33, 0);
    scene.add(rail);
  }
  const moving = new THREE.Group();
  scene.add(moving);
  const SPACING = 0.7, COUNT = 120;
  const sleeperMat = mat(P.granite);
  for (let i = 0; i < COUNT; i++) {
    const sl = new THREE.Mesh(new THREE.BoxGeometry(2.2, 0.08, 0.26), sleeperMat);
    sl.position.set(0, 0.24, -60 + i * SPACING);
    moving.add(sl);
  }
  const poleMat = mat(P.cement);
  for (let i = 0; i < 8; i++) {
    const pole = new THREE.Mesh(new THREE.BoxGeometry(0.14, 4.2, 0.14), poleMat);
    pole.position.set(-2.2, 2.1, -60 + i * 10.5);
    const arm = new THREE.Mesh(new THREE.BoxGeometry(2.4, 0.08, 0.08), poleMat);
    arm.position.set(1.15, 1.9, 0);
    pole.add(arm);
    moving.add(pole);
  }
  const wire = new THREE.Mesh(new THREE.BoxGeometry(0.03, 0.03, 200), mat(P.granite));
  wire.position.set(0, 4.0, 0);
  scene.add(wire);

  // Train: two coaches, flat SBB colours (white body, red band, dark windows), no logos.
  const train = new THREE.Group();
  const bodyMat = mat(P.white), bandMat = mat(P.red), winMat = mat(P.charcoal), roofMat = mat(P.cement);
  const coach = (z, cab) => {
    const g = new THREE.Group();
    const body = new THREE.Mesh(new THREE.BoxGeometry(1.9, 2.0, 7.2), bodyMat); body.position.y = 1.55; g.add(body);
    const band = new THREE.Mesh(new THREE.BoxGeometry(1.92, 0.22, 7.22), bandMat); band.position.y = 0.95; g.add(band);
    const roof = new THREE.Mesh(new THREE.BoxGeometry(1.7, 0.18, 7.0), roofMat); roof.position.y = 2.64; g.add(roof);
    for (let w = -2.6; w <= 2.6; w += 1.3) {
      const win = new THREE.Mesh(new THREE.BoxGeometry(1.94, 0.6, 0.85), winMat); win.position.set(0, 1.85, w); g.add(win);
    }
    if (cab) {
      const nose = new THREE.Mesh(new THREE.BoxGeometry(1.9, 1.3, 0.9), bodyMat); nose.position.set(0, 1.2, 4.0); g.add(nose);
      const wind = new THREE.Mesh(new THREE.BoxGeometry(1.6, 0.55, 0.92), winMat); wind.position.set(0, 2.05, 3.62); wind.rotation.x = -0.35; g.add(wind);
      const nband = new THREE.Mesh(new THREE.BoxGeometry(1.92, 0.22, 0.92), bandMat); nband.position.set(0, 0.95, 4.0); g.add(nband);
    }
    for (const bz of [-2.6, 2.6]) {
      const bogie = new THREE.Mesh(new THREE.BoxGeometry(1.5, 0.35, 1.6), winMat); bogie.position.set(0, 0.5, bz); g.add(bogie);
    }
    g.position.z = z;
    return g;
  };
  train.add(coach(3.9, true), coach(-3.5, false));
  scene.add(train);

  // Station (platform + small house + name sign), shown while the train stands at a stop. It slides in towards the
  // train when it arrives and moves away with the track when the train leaves.
  const station = new THREE.Group();
  const platMat = mat(P.cloud), wallMat = mat(P.white), roofMat2 = mat(P.iron), doorMat = mat(P.granite), trimMat = mat(P.cement);
  const box = (w, h, d, m, x, y, z) => { const o = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), m); o.position.set(x, y, z); station.add(o); return o; };
  // Platform along the far side of the track, with its white safety line
  box(3.4, 0.55, 26, platMat, -2.95, 0.28, 0);
  box(0.14, 0.02, 26, mat("#ffffff"), -1.55, 0.57, 0);
  // Canopy on slim columns (shows above the train roof)
  box(2.8, 0.14, 15, roofMat2, -2.7, 3.45, 0);
  box(2.84, 0.24, 0.1, trimMat, -2.7, 3.32, 7.5);
  for (const cz of [-6, -2, 2, 6]) box(0.12, 2.9, 0.12, doorMat, -3.2, 2.0, cz);
  // Station building behind the platform: two floors, windows, door, cornice
  box(4.2, 3.6, 9, wallMat, -7.0, 1.8, -3);
  box(4.6, 0.25, 9.4, roofMat2, -7.0, 3.7, -3);
  box(4.3, 0.12, 9.1, trimMat, -7.0, 1.95, -3);
  for (const wz of [-6.4, -4.8, -1.2, 0.4]) {
    box(0.06, 0.8, 0.9, winMat, -4.88, 1.15, wz);
    box(0.06, 0.8, 0.9, winMat, -4.88, 2.8, wz);
  }
  box(0.06, 1.7, 1.2, doorMat, -4.88, 0.85, -3);
  // Lamps and benches on the platform
  for (const lz of [-9, 9]) { box(0.08, 3.0, 0.08, doorMat, -3.9, 1.95, lz); box(0.5, 0.12, 0.25, trimMat, -3.7, 3.45, lz); }
  for (const bz of [-4.5, 4.5]) { box(0.5, 0.08, 1.8, doorMat, -3.8, 0.85, bz); box(0.08, 0.45, 1.8, doorMat, -4.05, 1.1, bz); }
  // Name sign hanging from the canopy edge, facing the camera
  const signCanvas = document.createElement("canvas"); signCanvas.width = 512; signCanvas.height = 96;
  const signTex = new THREE.CanvasTexture(signCanvas);
  signTex.colorSpace = THREE.SRGBColorSpace;
  const sign = new THREE.Mesh(new THREE.PlaneGeometry(3.4, 0.64), new THREE.MeshBasicMaterial({ map: signTex }));
  sign.position.set(-1.5, 4.05, 1.5); sign.rotation.y = 0.69; station.add(sign);
  box(0.05, 0.5, 0.05, doorMat, -1.5, 3.7, 0.4); box(0.05, 0.5, 0.05, doorMat, -1.5, 3.7, 2.6);
  const drawSign = (name) => {
    const g = signCanvas.getContext("2d");
    g.fillStyle = "#2d327d"; g.fillRect(0, 0, 512, 96);
    g.fillStyle = "#ffffff"; g.font = "700 64px 'SBB Web', Helvetica, Arial, sans-serif"; g.textBaseline = "middle";
    let txt = name || ""; while (g.measureText(txt).width > 472 && txt.length > 3) txt = txt.slice(0, -2) + "…";
    g.fillText(txt, 20, 52); signTex.needsUpdate = true;
  };
  station.visible = false;
  scene.add(station);
  let stationMode = "away"; // away | arriving | here | leaving
  let stationName = null;

  let speed = 0, target = 0, raf = 0, t0 = performance.now();
  const resize = () => {
    const w = canvas.clientWidth, h = canvas.clientHeight;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  };
  const ro = new ResizeObserver(resize);
  ro.observe(canvas);
  const frame = (now) => {
    const dt = Math.min(0.05, (now - t0) / 1000);
    t0 = now;
    speed += (target - speed) * Math.min(1, dt * 1.5); // ease in / out, no jumps
    const v = reducedMotion ? 0 : (speed / 3.6) * 0.18; // scaled down: readable, not dizzying
    moving.position.z = (moving.position.z + v * dt) % (SPACING * 15);
    if (stationMode === "arriving" || stationMode === "here") {
      station.position.z += (0 - station.position.z) * Math.min(1, dt * (reducedMotion ? 60 : 1.8)); // eases in, no bounce
      if (Math.abs(station.position.z) < 0.02) { station.position.z = 0; stationMode = "here"; }
    } else if (stationMode === "leaving") {
      station.position.z += Math.max(v, 4) * dt;
      if (station.position.z > 60) { station.visible = false; stationMode = "away"; }
    }
    train.position.y = speed > 1 && !reducedMotion ? Math.sin(now / 160) * 0.015 : 0;
    renderer.render(scene, camera);
    raf = requestAnimationFrame(frame);
  };
  raf = requestAnimationFrame(frame);

  const recolour = () => {
    P = palette();
    ground.material.color.set(P.milk); ballast.material.color.set(P.cloud); sleeperMat.color.set(P.granite);
    platMat?.color.set(P.cloud); wallMat?.color.set(P.white === "#17191c" ? "#d2d6db" : "#ffffff"); roofMat2?.color.set(P.iron);
    poleMat.color.set(P.cement); bodyMat.color.set(P.white === "#17191c" ? "#e8e9eb" : P.white); winMat.color.set("#2a2d33");
  };
  recolour();
  themeListeners.add(recolour);
  return {
    update({ speedKmh, atStation }) {
      target = clamp(speedKmh || 0, 0, 200);
      if (atStation && (stationMode === "away" || stationMode === "leaving" || stationName !== atStation)) {
        if (stationName !== atStation) drawSign(atStation);
        stationName = atStation;
        if (stationMode !== "here") { station.position.z = -45; station.visible = true; stationMode = "arriving"; }
      } else if (!atStation && (stationMode === "here" || stationMode === "arriving")) stationMode = "leaving";
    },
    dispose() {
      cancelAnimationFrame(raf);
      ro.disconnect();
      themeListeners.delete(recolour);
      renderer.dispose();
    },
  };
}

// ------------------------------------------------------------------ boot

live.connect();
push.init({ onForeground: (n) => { buzz([20, 40, 20]); toast(n.body ? `${n.title} · ${n.body}` : n.title, 6000); } });
$("#alerts-btn").innerHTML = ic("bell");
$("#alerts-btn").addEventListener("click", () => { buzz(); const back = location.search; alertsScreen(() => routeFromUrl(back, { back: true })); });
routeFromUrl(location.search);

function routeFromUrl(search, opts = {}) {
const params = new URLSearchParams(search);
const qrTrain = params.get("train");
const qrStation = params.get("station");
const qrLine = params.get("line");
if (qrTrain) onTracksScreen(qrTrain);
else if (qrLine) lineScreen(qrLine, () => alertsScreen(() => detectScreen({ back: true })));
else if (qrStation) {
  api(`/stations/${encodeURIComponent(qrStation)}`).then((d) => {
    const from = { stopId: d.station.stopId, name: d.station.name };
    const to = params.get("to");
    if (to) api(`/stations/${encodeURIComponent(to)}`).then((x) => connectionsScreen(from, { stopId: x.station.stopId, name: x.station.name })).catch(() => destinationScreen(from));
    else destinationScreen(from);
  }).catch(() => browseScreen({ note: "This station code is not known. Choose where you start and where you go." }));
} else detectScreen(opts);
}
