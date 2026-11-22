// A trip with changes picked on "Best routes" (/api/v1/routes): its trains in order, which one the passenger is on.
// Kept in localStorage so a reload or a notification tap (`/?train=…&trip=…&leg=…`) comes back to the same trip.

import { esc, t, time as hhmm } from "../common.js";
import { lineLabel } from "./ui.js";

const KEY = "twin-trip";

/** From a RouteDto: { id, fromStopId, toStopId, toName, legs: [{ trainId, line, category, headsign, from, to }], leg }. */
export function tripFromRoute(r, from, to) {
  const stop = (s, dep) => ({ stopId: s.stopId, name: s.name, time: s.expectedTime ?? s.plannedTime, planned: s.plannedTime, dep });
  return {
    id: r.journeyId,
    fromStopId: from.stopId, toStopId: to.stopId, toName: to.name,
    legs: r.legs.map((l) => ({ trainId: l.trainId, line: l.line, category: l.category, headsign: l.headsign, from: stop(l.from, true), to: stop(l.to, false) })),
    leg: Math.max(0, r.legs.findIndex((l) => !l.departed)),
    savedAt: Date.now(),
  };
}

export function saveTrip(t) {
  try { localStorage.setItem(KEY, JSON.stringify(t)); } catch { /* private mode */ }
}

/** The saved trip [id] if it is still today's (12 h). */
export function loadTrip(id) {
  try {
    const t = JSON.parse(localStorage.getItem(KEY) || "null");
    return t && t.id === id && Array.isArray(t.legs) && Date.now() - (t.savedAt || 0) < 12 * 3_600_000 ? t : null;
  } catch { return null; }
}

/** The next trains for the push subscription (server moves on to each at its change station). */
export const pushLegs = (t, from) => t.legs.slice(from).map((l) => ({
  trainId: l.trainId, line: l.line, headsign: l.headsign,
  fromStopId: l.from.stopId, fromName: l.from.name, toStopId: l.to.stopId, toName: l.to.name, departure: l.from.time,
}));

const lineName = (l) => (l.line || l.category || t("train")).replace(/^([A-Z]+)(\d)/, "$1 $2");
/** "the IR 16 to Zürich HB at 12:40" */
export const takeText = (l) => {
  const v = { line: lineName(l), dest: l.headsign, time: l.from.time ? hhmm(l.from.time) : "" };
  if (l.headsign) return l.from.time ? t("the {line} to {dest} at {time}", v) : t("the {line} to {dest}", v);
  return l.from.time ? t("the {line} at {time}", v) : t("the {line}", v);
};

/** The path at the top of the train screen: every train of the trip, the changes between them, the current one marked. */
export function pathHtml(trip) {
  const n = trip.legs.length;
  const items = trip.legs.map((l, i) => {
    const state = i < trip.leg ? "done" : i === trip.leg ? "now" : "next";
    const change = i < n - 1 ? (() => {
      const next = trip.legs[i + 1];
      const min = l.to.time && next.from.time ? Math.round((next.from.time - l.to.time) / 60_000) : null;
      const station = esc(l.to.name);
      return `<li class="chg${i < trip.leg ? " done" : ""}">${min != null ? t("Change at {station} · {n} min", { station, n: min }) : t("Change at {station}", { station })}</li>`;
    })() : "";
    return `<li class="leg ${state}"><button type="button" data-leg="${i}"${state === "now" ? ' aria-current="step"' : ""}>
        ${lineLabel(l)}<span class="tp"><b>${esc(l.from.name)}</b> ${hhmm(l.from.time)}<span class="ar">→</span>${esc(l.to.name)} ${hhmm(l.to.time)}</span>
      </button></li>${change}`;
  }).join("");
  const dest = esc(trip.toName || trip.legs[n - 1].to.name);
  const head = n - 1 === 1 ? t("Your trip to {dest} · 1 change", { dest }) : t("Your trip to {dest} · {n} changes", { dest, n: n - 1 });
  return `<div class="k">${head}</div><ol>${items}</ol>`;
}
