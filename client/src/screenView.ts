// What the passenger has open in the passenger view behind the assistant panel (the page that embeds /assistant/ in an
// iframe, static/assistant-launcher.js). The parent posts {type: "twin-view", view} on every screen change; we ask for
// it once at start, because the first post may come before this module listens. Standalone (no parent): always null.
import type { ScreenView } from "./contract";

let current: ScreenView | null = null;
const listeners = new Set<(v: ScreenView | null) => void>();

const clean = (v: unknown): ScreenView | null => {
  if (!v || typeof v !== "object") return null;
  const o = v as Record<string, unknown>;
  const s = (k: string) => (typeof o[k] === "string" && (o[k] as string).trim() ? (o[k] as string).trim().slice(0, 200) : null);
  const screen = s("screen");
  if (!screen) return null;
  return { screen, label: s("label"), trainId: s("trainId"), line: s("line"), stopId: s("stopId"), fromStopId: s("fromStopId"), toStopId: s("toStopId") };
};

if (typeof window !== "undefined" && window.parent !== window) {
  window.addEventListener("message", (e) => {
    if (e.origin !== location.origin || e.source !== window.parent) return;
    const d = e.data as { type?: string; view?: unknown } | null;
    if (d?.type !== "twin-view") return;
    const next = clean(d.view);
    if (JSON.stringify(next) === JSON.stringify(current)) return;
    current = next;
    listeners.forEach((f) => f(current));
  });
  window.parent.postMessage({ type: "twin-view-request" }, location.origin);
}

export const screenView = () => current;

export function onScreenView(f: (v: ScreenView | null) => void): () => void {
  listeners.add(f);
  return () => listeners.delete(f);
}

/** One line for the voice agent ("[App] …"). */
export function describeScreen(v: ScreenView): string {
  const what = v.label ? `${v.label}` : "";
  switch (v.screen) {
    case "train": return `the train ${what || ""} (train id ${v.trainId})`.replace(/\s+/g, " ");
    case "line": return `the status page of line ${v.line}`;
    case "station": return `the departure board of ${what || "a station"} (stop id ${v.stopId})`;
    case "destinations": return `the "where to?" list from ${what || "a station"} (stop id ${v.stopId})`;
    case "connections": return `the trains ${what || "between two stations"} (from stop ${v.fromStopId} to stop ${v.toStopId})`;
    default: return what || v.screen;
  }
}
