// Shared UI of the passenger view: constants, formatting, icons, toast, theme button, screen transitions.

import { $, esc, LATE_SEC, onTheme, publicApi as api, reducedMotion, setTheme, time as hhmm, toggleTheme } from "../common.js";
import { tiktLoading } from "../tikt.js";
import { live } from "./location.js";
import { alerts } from "./alerts.js";

export const NEAR_M = 200;
export const THREE_URL = "https://unpkg.com/three@0.170.0/build/three.module.js";

// ------------------------------------------------------------------ helpers

export const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
export const buzz = (ms = 8) => navigator.vibrate?.(ms);
export const cssVar = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

export const tripWatchAvailable = api("/trip-watches/availability").then((config) => config.enabled).catch(() => false);
export const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };

// Lucide icons (one family, stroke 2), inlined.
export const ICONS = {
  train: '<path d="M8 3.1V7a4 4 0 0 0 8 0V3.1"/><path d="m9 15-1-1"/><path d="m15 15 1-1"/><path d="M9 19c-2.8 0-5-2.2-5-5v-4a8 8 0 0 1 16 0v4c0 2.8-2.2 5-5 5Z"/><path d="m8 19-2 3"/><path d="m16 19 2 3"/>',
  search: '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>',
  x: '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
  chevron: '<path d="m9 18 6-6-6-6"/>',
  back: '<path d="m12 19-7-7 7-7"/><path d="M19 12H5"/>',
  map: '<path d="M14.106 5.553a2 2 0 0 0 1.788 0l3.659-1.83A1 1 0 0 1 21 4.619v12.764a1 1 0 0 1-.553.894l-4.553 2.277a2 2 0 0 1-1.788 0l-4.212-2.106a2 2 0 0 0-1.788 0l-3.659 1.83A1 1 0 0 1 3 19.381V6.618a1 1 0 0 1 .553-.894l4.553-2.277a2 2 0 0 1 1.788 0z"/><path d="M15 5.764v15"/><path d="M9 3.236v15"/>',
  list: '<path d="M3 12h.01"/><path d="M3 18h.01"/><path d="M3 6h.01"/><path d="M8 12h13"/><path d="M8 18h13"/><path d="M8 6h13"/>',
  swap: '<path d="m21 16-4 4-4-4"/><path d="M17 20V4"/><path d="m3 8 4-4 4 4"/><path d="M7 4v16"/>',
  moon: '<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/>',
  chat: '<path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z"/>',
  bell: '<path d="M10.268 21a2 2 0 0 0 3.464 0"/><path d="M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326"/>',
  help: '<circle cx="12" cy="12" r="10"/><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/><path d="M12 17h.01"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.34 17.66-1.41 1.41"/><path d="m19.07 4.93-1.41 1.41"/>',
};
export const ic = (name, cls = "") => `<svg class="icon ${cls}" viewBox="0 0 24 24" aria-hidden="true">${ICONS[name]}</svg>`;

// Flat vector illustrations (SBB palette through CSS variables; no SBB pictograms).
export const ILLUSTRATION = {
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
};

export const lineLabel = (t, lg = false) => `<span class="line${lg ? " lg" : ""}">${esc(t?.line || t?.category || "Train")}</span>`;
export const distText = (m) => (m == null ? "" : m < 1000 ? `${Math.round(m / 10) * 10} m` : `${(m / 1000).toFixed(1)} km`);

/** `21:53 +2'` (delay in red, nothing when on time); cancelled: planned time struck through + "Cancelled". */
export function timeHtml(planned, expected, cancelled = false) {
  if (cancelled) return `<s class="planned time">${hhmm(planned)}</s> <span class="cancel">Cancelled</span>`;
  const delayMin = planned && expected ? Math.round((expected - planned) / 60_000) : 0;
  return `<span class="time">${hhmm(planned ?? expected)}</span>${delayMin >= 1 ? ` <span class="delay">+${delayMin}'</span>` : ""}`;
}

/** Observable train state (docs/STYLE.md §4): colour class + word. */
export function trainState(t) {
  const p = t?.position;
  if (t?.status === "cancelled") return { cls: "s-cancelled", word: "Cancelled", color: "--cancelled" };
  if (p?.motion === "stopped" && !p.atStopId) return { cls: "s-stopped", word: "Stopped on the line", color: "--red" };
  if ((t?.delaySec ?? 0) >= LATE_SEC) return { cls: "s-late", word: `Late +${Math.round(t.delaySec / 60)}'`, color: "--orange" };
  if (p?.motion === "stopped") return { cls: "s-station", word: "At a station", color: "--at-station" };
  return { cls: "s-moving", word: "Running", color: "--green" };
}
export const stateHtml = (t) => { const s = trainState(t); return `<span class="state ${s.cls}"><span class="dot"></span>${esc(s.word)}</span>`; };

export function untilText(ms) {
  if (!ms) return "";
  const d = ms - Date.now();
  if (d < 45_000) return "now";
  const min = Math.round(d / 60_000);
  return min < 60 ? `in ${min} min` : `in ${Math.floor(min / 60)} h ${String(min % 60).padStart(2, "0")}`;
}

export const SEVERITY = { critical: "Critical", major: "Major", minor: "Minor", info: "Information" };
export const alertHtml = (i) => `
  <div class="alert sev-${esc(i.severity || "info")}"><span class="dot"></span>
    <div><div class="at">${esc(i.title)}</div>
      <div class="ad">${esc(SEVERITY[i.severity] || "Information")}${i.where ? ` · ${esc(i.where)}` : ""}</div></div></div>`;

export function toast(msg, ms = 3500) {
  const t = document.createElement("div");
  t.className = "toast";
  t.setAttribute("role", "status");
  t.textContent = msg;
  document.body.append(t);
  setTimeout(() => t.remove(), ms);
}

// ------------------------------------------------------------------ theme (same key as the staff pages)

export const themeBtn = $("#theme");
onTheme((theme) => {
  const dark = theme === "dark";
  themeBtn.innerHTML = ic(dark ? "sun" : "moon");
  themeBtn.title = dark ? "Light mode" : "Dark mode";
  themeBtn.setAttribute("aria-label", themeBtn.title);
});
setTheme(document.documentElement.dataset.theme || "light", false); // the inline <head> script already picked it
themeBtn.addEventListener("click", toggleTheme);

// ------------------------------------------------------------------ screens (short cross-fade + slide)

export const stage = $("#stage");
export const restartBtn = $("#restart");
export let cleanups = [];
export const onLeave = (fn) => cleanups.push(fn);

export function show(build, { back = false, restart = true } = {}) {
  cleanups.forEach((fn) => { try { fn(); } catch { /* ignore */ } });
  cleanups = [];
  const old = stage.querySelector(".screen:not(.leave)");
  if (old && reducedMotion()) old.remove();
  else if (old) {
    old.inert = true;
    old.classList.remove("enter", "back");
    old.classList.add("leave");
    setTimeout(() => old.remove(), 220);
  }
  const s = document.createElement("section");
  s.className = `screen enter${back ? " back" : ""}`;
  stage.append(s);
  restartBtn.hidden = !restart;
  restartBtn.textContent = "Change trip";
  build(s);
  window.scrollTo({ top: 0, behavior: reducedMotion() ? "auto" : "smooth" });
  return s;
}

/** Keep the address shareable: `?train=` / `?station=` like the QR codes. Also tells the assistant what is open. */
export function setUrl(params) {
  const u = new URL(location.href);
  u.search = new URLSearchParams(Object.entries(params).filter(([, v]) => v)).toString();
  history.replaceState(null, "", u);
  const view = params.train ? { screen: "train", trainId: params.train, fromStopId: params.from, toStopId: params.to }
    : params.line ? { screen: "line", line: params.line, label: params.line }
    : params.stop ? { screen: "station", stopId: params.stop }
    : params.station && params.to ? { screen: "connections", fromStopId: params.station, toStopId: params.to }
    : params.station ? { screen: "destinations", stopId: params.station }
    : null;
  setView(view);
}

/**
 * What the passenger has open, for the travel assistant (assistant-launcher.js posts it to the /assistant/ frame,
 * which sends it with every question as `context.onScreen`). null = a generic screen (start, search, alerts).
 * Screens call it again with a `label` once they know the names.
 */
export function setView(view) {
  window.twinView = view;
  window.dispatchEvent(new CustomEvent("twin-view", { detail: view }));
}
export const labelView = (label) => { if (window.twinView && label && window.twinView.label !== label) setView({ ...window.twinView, label }); };

export const backBtn = (label = "Back") => `<button class="back" type="button" data-back>${ic("back")}${esc(label)}</button>`;
// Loading lists: Tikt rolling on his track above the skeleton rows (docs/STYLE.md "9. Mascot").
export const placeholders = (n = 3, caption = "Checking the live data…") =>
  (caption ? tiktLoading({ caption, mini: true }) : "") + Array.from({ length: n }, () => '<div class="placeholder"></div>').join("");
