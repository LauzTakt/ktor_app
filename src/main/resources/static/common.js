// Shared helpers for every page: passenger view, staff pages (map + report), Trip Watch, the onboarding tour.
// Plain ES module, no build. Keep page-specific code in the page's own module.

export const SEVERITY_COLOURS = { info: "#0079c7", minor: "#f27e00", major: "#eb0000", critical: "#a20013" };
export const CATEGORIES = {
  rolling_stock: "Train defect (rolling stock)",
  infrastructure: "Track / overhead line",
  signalling: "Signal / points / interlocking",
  station: "Station problem",
  person: "Person / medical / police",
  obstacle: "Obstacle on the track",
  weather: "Weather",
  other: "Other",
};

const ESC = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
/** Escape untrusted text (feed and user input) before putting it into HTML. */
export const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ESC[c]);

/** `document.querySelector`. */
export const $ = (sel, root = document) => root.querySelector(sel);

/** The user asked for less motion: no smooth scrolls, no animations. */
export const reducedMotion = () => matchMedia("(prefers-reduced-motion: reduce)").matches;

/** SBB punctuality threshold: a train is "late" from 3 min on (same as the server and the React client). */
export const LATE_SEC = 180;

export const TZ = "Europe/Zurich";
const hm = new Intl.DateTimeFormat("en-GB", { timeZone: TZ, hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
const dhm = new Intl.DateTimeFormat("de-CH", { timeZone: TZ, day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" });
const hms = new Intl.DateTimeFormat("de-CH", { timeZone: TZ, hour: "2-digit", minute: "2-digit", second: "2-digit" });
/** `HH:mm`, Swiss time; "–" when unknown. */
export const time = (ms) => (ms ? hm.format(ms) : "–");
export const dateTime = (ms) => (ms ? dhm.format(ms) : "–");
export const clock = (ms) => (ms ? hms.format(ms) : "–");

/** "+3′" for 3 min late, "" when on time (< 1 min). */
export const delayText = (sec) => (sec == null || Math.abs(sec) < 60 ? "" : `${sec > 0 ? "+" : "−"}${Math.round(Math.abs(sec) / 60)}′`);

/**
 * `/api/v1` JSON call. `init.json` is sent as the body; 204 -> null. A failure throws an Error with `status` and the
 * API's error `code` (docs/API.md). [onUnauthorized] runs on 401.
 */
async function request(path, init, onUnauthorized) {
  const body = init.json !== undefined ? JSON.stringify(init.json) : init.body;
  const r = await fetch(`/api/v1${path}`, {
    ...init,
    headers: { Accept: "application/json", ...(body !== undefined ? { "Content-Type": "application/json" } : {}) },
    body,
  });
  if (r.status === 204) return null;
  const j = await r.json().catch(() => ({}));
  if (r.status === 401) onUnauthorized?.();
  if (!r.ok) throw Object.assign(new Error(j?.error?.message ?? `HTTP ${r.status}`), { status: r.status, code: j?.error?.code });
  return j;
}

/** API call from a staff page: an expired or missing session goes to the login page. */
export const api = (path, init = {}) => request(path, init, toLogin);

/** API call from a public page (passenger view, alerts). */
export const publicApi = (path, init = {}) => request(path, init, null);

export function severityBadge(sev) {
  return `<span class="badge" style="background:${SEVERITY_COLOURS[sev] ?? "#767676"}">${esc(sev ?? "?")}</span>`;
}

// ---------------------------------------------------------------- staff session (pages are staff-only, see auth/Auth.kt)

export function toLogin() {
  location.href = `/login.html?next=${encodeURIComponent(location.pathname + location.search)}`;
}

/** Shows "<user> · Log out" in the top bar. The session itself is an HttpOnly cookie set by POST /api/v1/auth/login. */
export async function initStaffSession() {
  const nav = document.querySelector(".topbar nav");
  try {
    const me = await api("/auth/me");
    const who = document.createElement("span");
    who.className = "who";
    who.textContent = me.username;
    const out = document.createElement("button");
    out.className = "btn";
    out.type = "button";
    out.textContent = "Log out";
    out.addEventListener("click", async () => {
      await fetch("/api/v1/auth/logout", { method: "POST" }).catch(() => {});
      toLogin();
    });
    nav?.prepend(who, " ", out, " ");
  } catch {
    /* api() already redirects on 401 */
  }
}

// ---------------------------------------------------------------- theme (light / dark), shared by every page

const THEME_KEY = "twin-theme";
/** Saved choice, else the system preference. */
export function currentTheme() {
  return document.documentElement.dataset.theme ?? (localStorage.getItem(THEME_KEY) || (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light"));
}
const themeListeners = new Set();
/** Calls [fn] with the new theme on every change (maps recolour themselves). Returns the function that stops it. */
export function onTheme(fn) {
  themeListeners.add(fn);
  return () => themeListeners.delete(fn);
}
/** Applies [theme] (`light` | `dark`) to the page and tells the listeners; [save] remembers it for every page. */
export function setTheme(theme, save = true) {
  document.documentElement.dataset.theme = theme;
  if (save) localStorage.setItem(THEME_KEY, theme);
  const btn = document.getElementById("themeToggle");
  if (btn) {
    btn.textContent = theme === "dark" ? "☀ Light" : "☾ Dark";
    btn.title = theme === "dark" ? "Switch to light mode" : "Switch to dark mode";
  }
  themeListeners.forEach((fn) => fn(theme));
}
/** The other theme, saved. */
export const toggleTheme = () => setTheme(currentTheme() === "dark" ? "light" : "dark");

/** Wires the #themeToggle button of a staff page. */
export function initTheme() {
  setTheme(currentTheme());
  document.getElementById("themeToggle")?.addEventListener("click", () => setTheme(currentTheme() === "dark" ? "light" : "dark"));
}

// ---------------------------------------------------------------- base map + rail network (as in the sbb-twin viewer)

const LIGHT_STYLE = "https://vectortiles.geo.admin.ch/styles/ch.swisstopo.lightbasemap.vt/style.json";
// The base map draws railways in red at high zoom; we draw our own rail network and red means a disruption: hide them.
const HIDDEN = /^(tunnel_|l1_|l2_)?public_transport(_aerialway)?$/;
let original = new Map(); // light paint of every base layer
let baseIds = [];

/** swisstopo light vector map; recoloured in place for dark mode by [applyTheme]. */
export async function baseStyle() {
  const style = await fetch(LIGHT_STYLE).then((r) => r.json());
  original = new Map(style.layers.map((l) => [l.id, { ...(l.paint ?? {}) }]));
  baseIds = style.layers.map((l) => l.id);
  for (const l of style.layers) if (HIDDEN.test(l.id)) l.layout = { ...(l.layout ?? {}), visibility: "none" };
  style.transition = { duration: 400, delay: 0 };
  return style;
}

function darkPaint(l) {
  const id = l.id.toLowerCase();
  const water = /water|lake|river|see|fluss|gewaesser/.test(id);
  if (l.type === "background") return { "background-color": "#101418" };
  // no outline colour: outlines also trace tile edges and draw a grid of lines over the map
  if (l.type === "fill") return { "fill-color": water ? "#0e2233" : /forest|wood|wald/.test(id) ? "#122019" : /building/.test(id) ? "#1d232b" : "#151a20" };
  if (l.type === "line") return { "line-color": water ? "#16324a" : /boundary|border|grenze/.test(id) ? "#46505e" : "#232a33" };
  if (l.type === "symbol") return { "text-color": water ? "#5d8bb0" : "#9aa3ae", "text-halo-color": "#101418", "text-halo-width": 1.2 };
  if (l.type === "raster") return { "raster-opacity": 0.18 };
  return {};
}

/** Rail network: Swiss lines (BAV) and lines abroad (OpenStreetMap), from the sbb-twin repository (data/). */
export function addRail(map, beforeId) {
  map.addSource("abroad", { type: "geojson", data: "/data/abroad-lines.geojson", attribution: "© OpenStreetMap contributors" });
  map.addLayer({ id: "rail-abroad-halo", type: "line", source: "abroad", layout: { "line-join": "round", "line-cap": "round" }, paint: { "line-color": "#ffffff", "line-width": ["interpolate", ["linear"], ["zoom"], 6, 2.2, 10, 3.4, 14, 5.6], "line-opacity": 0.8 } }, beforeId);
  map.addLayer({ id: "rail-abroad", type: "line", source: "abroad", layout: { "line-join": "round", "line-cap": "round" }, paint: { "line-color": "#767676", "line-width": ["interpolate", ["linear"], ["zoom"], 6, ["match", ["get", "usage"], "main", 0.9, 0.6], 10, ["match", ["get", "usage"], "main", 1.6, 1.1], 14, 2.6], "line-opacity": 0.85 } }, beforeId);
  map.addSource("network", { type: "geojson", data: "/data/network-lines.geojson", attribution: "© BAV" });
  map.addLayer({ id: "network-halo", type: "line", source: "network", layout: { "line-join": "round", "line-cap": "round" }, paint: { "line-color": "#ffffff", "line-width": ["interpolate", ["linear"], ["zoom"], 6, 2.6, 10, 4, 14, 6.5], "line-opacity": 0.9 } }, beforeId);
  map.addLayer({ id: "network", type: "line", source: "network", layout: { "line-join": "round", "line-cap": "round" }, paint: { "line-color": "#444444", "line-width": ["interpolate", ["linear"], ["zoom"], 6, 1, 10, 1.8, 14, 3.2], "line-opacity": 0.95 } }, beforeId);
}

/** Per-theme colours of our own map layers. */
export const MAP_COLOURS = {
  light: { rail: "#444444", abroad: "#767676", halo: "#ffffff", stationFill: "#ffffff", stationStroke: "#212121", label: "#212121", labelHalo: "rgba(255,255,255,0.95)", reportFill: "rgba(255,255,255,0.85)",
    pickStroke: "#444444", pickOtherStroke: "#bdbdbd" },
  dark: { rail: "#9aa6b8", abroad: "#6f7a8a", halo: "#0b0e11", stationFill: "#17191c", stationStroke: "#d2d6db", label: "#d2d6db", labelHalo: "#101418", reportFill: "rgba(23,25,28,0.9)",
    pickStroke: "#d2d6db", pickOtherStroke: "#5a6069" },
};

/** Recolour the base map and our layers for a theme, in place (no style reload: sources and trains stay). */
export function applyTheme(map, theme) {
  const style = map.getStyle();
  if (!style) return;
  for (const l of style.layers) {
    if (!baseIds.includes(l.id)) continue;
    const base = original.get(l.id) ?? {};
    const dark = darkPaint(l);
    const target = theme === "dark" ? { ...base, ...dark } : { ...base };
    // back to light: properties only the dark theme sets go back to their defaults
    if (theme === "light") for (const k of Object.keys(dark)) if (!(k in base)) target[k] = undefined;
    for (const [k, v] of Object.entries(target)) {
      if (k.endsWith("-transition")) continue;
      try {
        map.setPaintProperty(l.id, k, v);
      } catch {
        /* property not valid for this layer type */
      }
    }
  }
  const c = MAP_COLOURS[theme];
  const set = (layer, prop, v) => map.getLayer(layer) && map.setPaintProperty(layer, prop, v);
  set("network", "line-color", c.rail);
  set("network-halo", "line-color", c.halo);
  set("rail-abroad", "line-color", c.abroad);
  set("rail-abroad-halo", "line-color", c.halo);
  set("stations", "circle-color", c.stationFill);
  set("stations", "circle-stroke-color", c.stationStroke);
  set("station-labels", "text-color", c.label);
  set("station-labels", "text-halo-color", c.labelHalo);
  set("report-ring", "circle-color", c.reportFill);
  set("pick-point", "circle-stroke-color", c.halo);
  // passenger view: the station picker map
  for (const id of ["st", "st-other"]) set(id, "circle-color", c.stationFill);
  set("st", "circle-stroke-color", c.pickStroke);
  set("st-other", "circle-stroke-color", c.pickOtherStroke);
  set("st-label", "text-color", c.label);
  set("st-label", "text-halo-color", c.labelHalo);
}

/** [[minLon, minLat], [maxLon, maxLat]] of any GeoJSON geometry, or null. */
export function bboxOf(geometry) {
  if (!geometry) return null;
  let b = null;
  const walk = (c) => {
    if (typeof c[0] === "number") {
      b = b ? [[Math.min(b[0][0], c[0]), Math.min(b[0][1], c[1])], [Math.max(b[1][0], c[0]), Math.max(b[1][1], c[1])]] : [[c[0], c[1]], [c[0], c[1]]];
    } else c.forEach(walk);
  };
  walk(geometry.coordinates ?? []);
  return b;
}

/** Ago text for a past timestamp. */
export function ago(ms) {
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  return `${Math.floor(s / 3600)} h ${Math.round((s % 3600) / 60)} min ago`;
}
