// Location (browser geolocation, refined fix), nearby trains and stations, and the live WebSocket.

import { $, publicApi as api } from "../common.js";
import { NEAR_M, clamp } from "./ui.js";

// ------------------------------------------------------------------ location

export const geo = { status: "idle", fix: null, promise: null, refined: null };

/**
 * Fast location: resolves with the FIRST usable fix (≤ 150 m, or the best one after 3 s) so the flow never waits for
 * nothing. The watch keeps running in the background: `refine()` resolves once two fixes ≥ 2 s apart give a speed and
 * heading (only needed to tell apart several trains close to the user). Null when location is unavailable.
 */
export function locate() {
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
export function haversine(lat1, lon1, lat2, lon2) {
  const R = 6371000, r = Math.PI / 180;
  const a = Math.sin(((lat2 - lat1) * r) / 2) ** 2 + Math.cos(lat1 * r) * Math.cos(lat2 * r) * Math.sin(((lon2 - lon1) * r) / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
}
export function bearing(lat1, lon1, lat2, lon2) {
  const r = Math.PI / 180;
  const y = Math.sin((lon2 - lon1) * r) * Math.cos(lat2 * r);
  const x = Math.cos(lat1 * r) * Math.sin(lat2 * r) - Math.sin(lat1 * r) * Math.cos(lat2 * r) * Math.cos((lon2 - lon1) * r);
  return ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
}
export const geoText = () => ({
  denied: "Location is blocked.",
  insecure: "Location needs a secure connection (HTTPS).",
  off: "Location is not available.",
}[geo.status] || "We could not find you.");

export const nearbyTrainsFor = (f) =>
  api(`/trains/nearby?lat=${f.lat}&lon=${f.lon}&radiusM=${Math.max(NEAR_M, Math.min(f.acc, 500)) | 0}&limit=5&ts=${f.ts}` +
    (f.speedKmh != null ? `&speedKmh=${clamp(f.speedKmh, 0, 500).toFixed(1)}` : "") +
    (f.headingDeg != null ? `&headingDeg=${f.headingDeg.toFixed(0)}` : ""));
export const nearbyStationsFor = (f) => api(`/stations/nearby?lat=${f.lat}&lon=${f.lon}&radiusM=${NEAR_M}&limit=3`);

// ------------------------------------------------------------------ live updates (WebSocket /api/v1/ws)

export const live = {
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
export function liveBadge(on) {
  const el = $("#live");
  el.classList.toggle("off", !on);
  el.textContent = on ? "Live" : "Reconnecting";
}
