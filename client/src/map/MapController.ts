// The map: swisstopo light base map, our rail network, the live trains, and the renderers of the backend's visual
// commands (ASSISTANT_CONTRACT.md §2): route, section, train, station, timer, focus, clear.
import * as maplibregl from "maplibre-gl";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?url";
import type { GeoJSONSource, LngLatBoundsLike, StyleSpecification } from "maplibre-gl";
import type { Feature, FeatureCollection, Geometry } from "geojson";
import type { AnyGeoJSON, VisualCommand } from "../contract";
import { sbbText } from "../format";
import { t } from "../i18n";
import { FleetFeed } from "./feed";
import { TrainCanvas } from "./trains";
import { applyMapTheme, captureBase } from "./mapTheme";
import { currentTheme, onTheme } from "../theme";

(maplibregl as unknown as { setWorkerUrl?: (u: string) => void }).setWorkerUrl?.(workerUrl);

const STYLE = "https://vectortiles.geo.admin.ch/styles/ch.swisstopo.lightbasemap.vt/style.json";
const SWITZERLAND: LngLatBoundsLike = [
  [5.95, 45.82],
  [10.49, 47.81],
];
const COLOURS = { red: "#eb0000", orange: "#f27e00", green: "#00973b", blue: "#2d327d" } as const;
const SECTION_LAYERS = ["v-section-line", "v-section-dash", "v-section-glow"] as const;
const empty = (): FeatureCollection => ({ type: "FeatureCollection", features: [] });

export type Timer = { label: string; until: number };
type ReplayMap = { style: StyleSpecification; bounds: LngLatBoundsLike };
type BoardingPin = { label: string; coordinates: [number, number] };
type Line = [number, number][];
type Route = { id: string; label: string; legs: { line?: string | null; trainId?: string | null; lines: Line[] }[] };

/** Every line of a GeoJSON value, whatever its wrapping. */
function linesOf(g: AnyGeoJSON | Geometry | null | undefined): Line[] {
  if (!g) return [];
  switch (g.type) {
    case "FeatureCollection":
      return g.features.flatMap((f) => linesOf(f.geometry));
    case "Feature":
      return linesOf(g.geometry);
    case "LineString":
      return [g.coordinates as Line];
    case "MultiLineString":
      return g.coordinates as Line[];
    case "GeometryCollection":
      return g.geometries.flatMap((x) => linesOf(x));
    default:
      return [];
  }
}
function midpoint(lines: Line[]): [number, number] | undefined {
  const longest = lines.reduce<Line | undefined>((a, b) => (!a || b.length > a.length ? b : a), undefined);
  if (!longest?.length) return undefined;
  let total = 0;
  const seg: number[] = [];
  for (let i = 1; i < longest.length; i++) {
    const d = Math.hypot(longest[i][0] - longest[i - 1][0], longest[i][1] - longest[i - 1][1]);
    seg.push(d);
    total += d;
  }
  let run = 0;
  for (let i = 0; i < seg.length; i++) {
    if (run + seg[i] >= total / 2) {
      const k = seg[i] ? (total / 2 - run) / seg[i] : 0;
      return [longest[i][0] + (longest[i + 1][0] - longest[i][0]) * k, longest[i][1] + (longest[i + 1][1] - longest[i][1]) * k];
    }
    run += seg[i];
  }
  return longest[0];
}
/** A two-point line over a few kilometres is a stand-in for missing track geometry, not track. */
function straight(l: Line) {
  return l.length === 2 && Math.hypot(l[1][0] - l[0][0], (l[1][1] - l[0][1]) * 1.5) > 0.05;
}
function nearest(lines: Line[], c: [number, number]): [number, number] | undefined {
  let best: [number, number] | undefined;
  let bd = Infinity;
  const k = Math.cos((c[1] * Math.PI) / 180);
  for (const l of lines)
    for (const p of l) {
      const d = ((p[0] - c[0]) * k) ** 2 + (p[1] - c[1]) ** 2;
      if (d < bd) (bd = d), (best = p);
    }
  return best ?? midpoint(lines);
}
function el(cls: string, html = "") {
  const d = document.createElement("div");
  d.className = cls;
  d.innerHTML = html;
  return d;
}
const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);

// "ant path": the dashes of the white flow line step forward along the route
const FLOW: number[][] = [
  [0, 4, 3], [0.5, 4, 2.5], [1, 4, 2], [1.5, 4, 1.5], [2, 4, 1], [2.5, 4, 0.5], [3, 4, 0],
  [0, 0.5, 3, 3.5], [0, 1, 3, 3], [0, 1.5, 3, 2.5], [0, 2, 3, 2], [0, 2.5, 3, 1.5], [0, 3, 3, 1], [0, 3.5, 3, 0.5],
];

export type DrawnItem = { key: string; kind: string; label: string; colour: string; hidden: boolean };
export type Pick = { kind: "section" | "route" | "station" | "point" | "train"; id: string; label: string; lon: number; lat: number; legs?: string[]; delay?: number };

function keyOf(v: VisualCommand): string {
  switch (v.type) {
    case "route":
      return `route:${v.id}`;
    case "section":
      return `section:${v.id}`;
    case "train":
      return `train:${v.trainId}`;
    case "station":
      return `station:${v.stopId ?? v.name}`;
    case "line":
      return `line:${v.line.replace(/\s+/g, "").toUpperCase()}`;
    case "track_train":
      return `train:${v.trainId}`;
    case "label":
      return `label:${v.id}`;
    case "timer":
      return "timer";
    default:
      return `${v.type}:${Math.random()}`;
  }
}
const pretty = (s: string) => s.replace(/ - /g, " – ").replace(/\b(IC|IR|ICE|EC|RE|PE|IRE|SN|S|R|REN|EV)(\d{1,3})\b/g, "$1 $2");
/** Fixed colour meaning: red interrupted, orange limited, green recommended, grey alternative, blue a place. */
function describe(v: VisualCommand): { kind: string; label: string; colour: string } {
  switch (v.type) {
    case "route":
      return v.style === "alternative" ? { kind: t("Alternative"), label: pretty(v.label ?? t("Route")), colour: "#767676" } : { kind: t("Recommended"), label: pretty(v.label ?? t("Route")), colour: "#00973b" };
    case "section":
      return { kind: t(v.colour === "orange" ? "Limited" : v.colour === "green" ? "Resumed" : v.colour === "blue" ? "Section" : "Interrupted"), label: pretty(v.label ?? t("Section")), colour: COLOURS[v.colour ?? "red"] ?? COLOURS.red };
    case "train":
      return { kind: t("Train"), label: pretty(v.label ?? t("Train")), colour: "#2d327d" };
    case "station":
      return { kind: t(v.role === "destination" ? "Destination" : v.role === "origin" ? "Start" : v.role === "incident" ? "Affected station" : v.role === "transfer" ? "Change" : "Station"), label: v.name, colour: v.role === "incident" ? "#eb0000" : "#2d327d" };
    case "line":
      return { kind: t("Line"), label: pretty(v.label ?? v.line), colour: "#2d327d" };
    case "label":
      return { kind: t("Note"), label: v.text, colour: "#686868" };
    case "track_train":
      return { kind: t("Tracking"), label: pretty(v.label ?? t("Your train")), colour: "#2d327d" };
    default:
      return { kind: v.type, label: v.type, colour: "#767676" };
  }
}

export class MapController {
  readonly map: maplibregl.Map;
  readonly trains: TrainCanvas;
  private feed: FleetFeed;
  private ready = false;
  private queue: VisualCommand[][] = [];
  private routes = new Map<string, Route>();
  private selected: string | null = null;
  private sections = new Map<string, { colour: string; label: string; lines: Line[]; dashed: boolean }>();
  private markers: maplibregl.Marker[] = [];
  private sectionMarkers: maplibregl.Marker[] = [];
  private flowStep = 0;
  private lastPulse = 0;
  private drawStart = 0;
  private lastSelected: string | null = null;
  private lastFlow = 0;
  padding: () => { top: number; bottom: number; left: number; right: number } = () => ({ top: 80, bottom: 80, left: 24, right: 24 });
  onTimer: (t: Timer | null) => void = () => {};
  onFleet: (count: number, source: "live" | "poll") => void = () => {};
  /** The drawn picture changed (the legend lists it). */
  onChange: (items: DrawnItem[]) => void = () => {};
  /** The passenger tapped something on the map. */
  onPick: (p: Pick) => void = () => {};
  /** The assistant asked to track a train live. */
  onTrack: (t: { trainId: string; stopId: string; label?: string | null }) => void = () => {};
  /** What is drawn now, in order; every answer replaces it unless it is appended. */
  private picture: { key: string; cmd: VisualCommand; hidden: boolean }[] = [];
  private history: { key: string; cmd: VisualCommand; hidden: boolean }[][] = [];
  private frame = 0;
  private boarding?: BoardingPin;
  private boardingMarker?: maplibregl.Marker;

  constructor(container: HTMLElement, private readonly replay?: ReplayMap) {
    this.map = new maplibregl.Map({
      container,
      style: replay?.style ?? STYLE,
      bounds: replay?.bounds ?? SWITZERLAND,
      fitBoundsOptions: { padding: 24 },
      // room to the south: on a phone Switzerland sits in the top half, above the sheet
      maxBounds: [
        [2.0, 41.0],
        [14.5, 50.0],
      ],
      minZoom: replay ? 10 : 6,
      maxZoom: 17,
      attributionControl: false,
      dragRotate: false,
      pitchWithRotate: false,
      touchPitch: false,
      fadeDuration: 200,
    });
    this.map.touchZoomRotate.disableRotation();
    if (import.meta.env.DEV) (window as unknown as { __map: maplibregl.Map }).__map = this.map;
    const credit = replay ? "© swisstopo · Route data: Canton of Zürich and ZVV" : "© swisstopo";
    this.map.addControl(new maplibregl.AttributionControl({ compact: true, customAttribution: credit }), "bottom-left");
    // start folded into its "i" button: the text opens on tap and never covers a label
    this.map.once("load", () => this.map.getContainer().querySelector(".maplibregl-ctrl-attrib")?.classList.remove("maplibregl-compact-show"));
    this.trains = new TrainCanvas(this.map);
    this.feed = new FleetFeed((rows, interval, source) => {
      this.trains.update(rows, interval);
      this.onFleet(rows.length, source);
    });
    // dark mode: recolour as soon as the style is parsed (no white flash while tiles load), and on every change
    this.map.once("style.load", () => {
      this.baseLayers = captureBase(this.map); // the base map's light paint, before our layers exist
      this.recolour();
      this.unsubscribeTheme = onTheme(() => this.recolour());
    });
    this.map.on("load", () => this.setup());
    this.map.on("moveend", () => this.placeLabels());
    if (!replay) this.loop();
  }

  private baseLayers: ReturnType<typeof captureBase> | null = null;
  private unsubscribeTheme?: () => void;
  private recolour() {
    if (this.baseLayers) applyMapTheme(this.map, this.baseLayers, currentTheme());
  }

  /** Keep pin labels on screen: near the left or right edge a label leans inwards instead of centring. */
  private placeLabels() {
    const w = this.map.getContainer().clientWidth;
    for (const mk of this.markers) {
      const label = mk.getElement().querySelector<HTMLElement>(".pin-label");
      if (!label) continue;
      const x = this.map.project(mk.getLngLat()).x;
      const half = label.offsetWidth / 2 + 8;
      label.dataset.lean = x < half ? "right" : x > w - half ? "left" : "";
    }
  }

  private setup() {
    const m = this.map;
    // our layers go under the base map's labels, so town and station names stay readable
    const labels = m.getStyle().layers.find((l) => l.type === "symbol")?.id;
    if (!this.replay) {
      m.addSource("network", { type: "geojson", data: "/data/network-lines.geojson", attribution: "© BAV" }); // ktor_app static, BAV lines
      m.addLayer({ id: "network-halo", type: "line", source: "network", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#ffffff", "line-width": ["interpolate", ["linear"], ["zoom"], 6, 2.6, 10, 4.4, 14, 7] } }, labels);
      m.addLayer({ id: "network-line", type: "line", source: "network", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#444444", "line-opacity": ["interpolate", ["linear"], ["zoom"], 10, 0.75, 14, 0.45], "line-width": ["interpolate", ["linear"], ["zoom"], 6, 0.8, 10, 1.6, 14, 2.6] } }, labels);
    }

    m.addSource("v-sections", { type: "geojson", data: empty() });
    m.addLayer({ id: "v-section-glow", type: "line", source: "v-sections", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": ["get", "colour"], "line-opacity": 0.38, "line-blur": ["interpolate", ["linear"], ["zoom"], 7, 6, 13, 12], "line-width": ["interpolate", ["linear"], ["zoom"], 7, 16, 13, 32] } }, labels);

    m.addSource("v-routes", { type: "geojson", data: empty(), lineMetrics: true });
    const alt = ["!", ["get", "sel"]] as never;
    const sel = ["get", "sel"] as never;
    m.addLayer({ id: "v-alt-casing", type: "line", source: "v-routes", filter: alt, layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#ffffff", "line-width": ["interpolate", ["linear"], ["zoom"], 7, 5, 13, 9] } }, labels);
    m.addLayer({ id: "v-alt-line", type: "line", source: "v-routes", filter: alt, layout: { "line-join": "round" }, paint: { "line-color": "#767676", "line-dasharray": [1.4, 1.1], "line-width": ["interpolate", ["linear"], ["zoom"], 7, 2.6, 13, 4.5] } }, labels);
    m.addLayer({ id: "v-route-casing", type: "line", source: "v-routes", filter: sel, layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#ffffff", "line-width": ["interpolate", ["linear"], ["zoom"], 7, 9, 13, 15] } }, labels);
    m.addLayer({ id: "v-route-line", type: "line", source: "v-routes", filter: sel, layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#00973b", "line-width": ["interpolate", ["linear"], ["zoom"], 7, 5, 13, 9] } }, labels);
    m.addLayer({ id: "v-route-flow", type: "line", source: "v-routes", filter: sel, layout: { "line-join": "round" }, paint: { "line-color": "#ffffff", "line-opacity": 0.85, "line-dasharray": FLOW[0], "line-width": ["interpolate", ["linear"], ["zoom"], 7, 1.6, 13, 2.8] } }, labels);
    // the affected track on top of any route through it: solid for what staff or the twin report, dashed for real SBB alerts
    const width = ["interpolate", ["linear"], ["zoom"], 7, 3.5, 13, 7] as never;
    m.addLayer({ id: "v-section-line", type: "line", source: "v-sections", filter: ["!", ["get", "dashed"]], layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": ["get", "colour"], "line-width": width } }, labels);
    m.addLayer({ id: "v-section-dash", type: "line", source: "v-sections", filter: ["get", "dashed"], layout: { "line-join": "round" }, paint: { "line-color": ["get", "colour"], "line-width": width, "line-dasharray": [1.6, 1.2] } }, labels);

    // taps: a section, a route
    for (const layer of SECTION_LAYERS)
      m.on("click", layer, (e) => {
        const f = e.features?.[0];
        if (f) this.onPick({ kind: "section", id: String(f.properties?.id), label: String(f.properties?.label ?? ""), lon: e.lngLat.lng, lat: e.lngLat.lat });
      });
    for (const layer of ["v-route-line", "v-alt-line"])
      m.on("click", layer, (e) => {
        const f = e.features?.[0];
        const r = f && this.routes.get(String(f.properties?.route));
        if (r) this.onPick({ kind: "route", id: r.id, label: r.label, legs: r.legs.map((l) => l.line ?? "").filter(Boolean), lon: e.lngLat.lng, lat: e.lngLat.lat });
      });
    // long press (or right click) anywhere: start or go here
    let press: number | undefined;
    const pick = (lng: number, lat: number) => this.onPick({ kind: "point", id: "", label: "", lon: lng, lat });
    m.on("contextmenu", (e) => pick(e.lngLat.lng, e.lngLat.lat));
    // a tap on a train (drawn on our own canvas, so hit-tested here)
    m.on("click", (e) => {
      if (m.queryRenderedFeatures(e.point, { layers: ["v-section-line", "v-section-dash", "v-route-line", "v-alt-line"].filter((l) => m.getLayer(l)) }).length) return;
      const t = this.trains.nearest(e.point.x, e.point.y);
      if (t) this.onPick({ kind: "train", id: t.id, label: t.line, lon: e.lngLat.lng, lat: e.lngLat.lat, delay: t.delay });
    });
    m.on("touchstart", (e) => {
      window.clearTimeout(press);
      if (e.originalEvent.touches.length === 1) press = window.setTimeout(() => pick(e.lngLat.lng, e.lngLat.lat), 600);
    });
    for (const ev of ["touchend", "touchmove", "movestart"] as const) m.on(ev, () => window.clearTimeout(press));

    this.recolour(); // our layers, now that they exist

    this.ready = true;
    this.map.fitBounds(this.replay?.bounds ?? SWITZERLAND, { padding: this.cameraPadding(), absolutePadding: Boolean(this.replay), duration: 0 });
    if (!this.replay) this.feed.start();
    for (const q of this.queue.splice(0)) this.apply(q);
    if (this.boarding) this.focusBoarding(this.boarding);
  }

  /** One animation loop: the route's flow, the highlighted trains' pulse, gliding trains when zoomed in. */
  private loop() {
    const frame = (now: number) => {
      this.frame = requestAnimationFrame(frame);
      if (!this.ready || document.hidden) return;
      let repaint = false;
      if (this.selected && now - this.lastFlow > 70) {
        this.lastFlow = now;
        this.flowStep = (this.flowStep + 1) % FLOW.length;
        this.map.setPaintProperty("v-route-flow", "line-dasharray", FLOW[this.flowStep]);
      }
      // interrupted sections breathe slowly
      if (this.sections.size && now - this.lastPulse > 90) {
        this.lastPulse = now;
        this.map.setPaintProperty("v-section-glow", "line-opacity", 0.22 + 0.2 * (0.5 + 0.5 * Math.sin(now / 700)));
      }
      // a new recommended route draws itself from origin to destination
      if (this.drawStart) {
        const k = Math.min(1, (now - this.drawStart) / 1100);
        const e = 1 - Math.pow(1 - k, 3);
        this.map.setPaintProperty("v-route-line", "line-gradient", k >= 1 ? ["literal", "#00973b"] as never : (["step", ["line-progress"], "#00973b", Math.max(0.001, e), "rgba(0,151,59,0)"] as never));
        if (k >= 1) this.drawStart = 0;
      }
      if (this.trains.animating()) {
        this.trains.followStep();
        repaint = true;
      }
      if (repaint) this.map.triggerRepaint();
    };
    this.frame = requestAnimationFrame(frame);
  }

  dispose() {
    cancelAnimationFrame(this.frame);
    this.feed.pause();
    this.unsubscribeTheme?.();
    this.map.remove();
  }

  /** Applies an answer's visuals. By default the answer REPLACES the picture (no stale sections or routes). */
  apply(visuals: VisualCommand[], append = false) {
    if (!this.ready) {
      this.queue.push(append ? [{ type: "remove", id: "\u0000append" }, ...visuals] : visuals);
      return;
    }
    if (visuals[0]?.type === "remove" && visuals[0].id === "\u0000append") {
      append = true;
      visuals = visuals.slice(1);
    }
    const drawn = visuals.filter((v) => v.type !== "focus" && v.type !== "clear");
    const replace = !append || visuals.some((v) => v.type === "clear");
    if (replace && (drawn.length || visuals.some((v) => v.type === "clear"))) {
      this.remember();
      this.picture = [];
    }
    for (const v of drawn) {
      if (v.type === "fit") continue;
      if (v.type === "remove") {
        this.picture = this.picture.filter((p) => p.key !== v.id && !p.key.endsWith(`:${v.id}`));
        continue;
      }
      const key = keyOf(v);
      const at = this.picture.findIndex((p) => p.key === key);
      if (at >= 0) this.picture[at] = { key, cmd: v, hidden: false };
      else this.picture.push({ key, cmd: v, hidden: false });
    }
    const focus = visuals.filter((v) => v.type === "focus").at(-1);
    const fit = visuals.filter((v): v is Extract<VisualCommand, { type: "fit" }> => v.type === "fit").at(-1);
    this.render();
    if (fit && !focus) {
      const pts: [number, number][] = [];
      for (const id of fit.trainIds ?? []) {
        const p = this.trains.position(id);
        if (p) pts.push(p as [number, number]);
      }
      for (const p of this.picture) {
        const c = p.cmd;
        if (c.type === "station" && (fit.stopIds ?? []).includes(c.stopId ?? "")) pts.push([c.lon, c.lat]);
        if (c.type === "section" && (fit.sectionIds ?? []).includes(c.id)) for (const l of this.sections.get(c.id)?.lines ?? []) pts.push(...(l as [number, number][]));
      }
      if (pts.length) {
        const xs = pts.map((q) => q[0]);
        const ys = pts.map((q) => q[1]);
        const pad = pts.length === 1 ? 0.03 : 0;
        this.focus = [Math.min(...xs) - pad, Math.min(...ys) - pad, Math.max(...xs) + pad, Math.max(...ys) + pad];
        this.fit(this.focus);
      }
      return;
    }
    if (focus?.type === "focus" && focus.bbox?.length === 4) {
      this.focus = this.withTrains(focus.bbox);
      this.fit(this.focus);
    }
  }

  private remember() {
    if (!this.picture.length) return;
    this.history.push(this.picture.map((p) => ({ ...p })));
    if (this.history.length > 12) this.history.shift();
  }

  /** Draws the picture from scratch. */
  private render() {
    this.reset();
    for (const p of this.picture) {
      if (p.hidden) continue;
      try {
        this.one(p.cmd);
      } catch (e) {
        console.warn("visual command failed", p.cmd.type, e);
      }
    }
    this.drawRoutes();
    this.drawSections();
    this.trains.dim = this.routes.size > 0 || this.sections.size > 0 || this.trains.highlights.size > 0;
    this.map.triggerRepaint();
    this.onChange(this.items());
  }

  items(): DrawnItem[] {
    return this.picture.filter((p) => p.cmd.type !== "timer").map((p) => ({ key: p.key, ...describe(p.cmd), hidden: p.hidden }));
  }
  /** Removes one item (the legend's x). */
  remove(key: string) {
    this.remember();
    this.picture = this.picture.filter((p) => p.key !== key);
    this.render();
  }
  toggle(key: string) {
    const p = this.picture.find((x) => x.key === key);
    if (!p) return;
    p.hidden = !p.hidden;
    this.render();
  }
  undo() {
    const prev = this.history.pop();
    if (!prev) return false;
    this.picture = prev;
    this.render();
    return true;
  }
  canUndo() {
    return this.history.length > 0;
  }

  private focus: [number, number, number, number] | null = null;
  /** Grow the frame to include a highlighted train when it is close by ("the train that's coming"). */
  private withTrains(b: [number, number, number, number]): [number, number, number, number] {
    let [w, s, e, n] = b;
    const dx = Math.max(0.15, e - w);
    const dy = Math.max(0.1, n - s);
    for (const id of this.trains.highlights.keys()) {
      const p = this.trains.position(id);
      if (!p || p[0] < w - dx * 0.6 || p[0] > e + dx * 0.6 || p[1] < s - dy * 0.6 || p[1] > n + dy * 0.6) continue;
      w = Math.min(w, p[0]);
      e = Math.max(e, p[0]);
      s = Math.min(s, p[1]);
      n = Math.max(n, p[1]);
    }
    return [w, s, e, n];
  }

  private one(v: VisualCommand) {
    switch (v.type) {
      case "clear":
        this.clear();
        return;
      case "focus":
        return;
      case "route": {
        const route: Route = { id: v.id, label: v.label ?? "", legs: (v.legs ?? []).map((l) => ({ line: l.line, trainId: l.trainId, lines: linesOf(l.geometry) })) };
        this.routes.set(v.id, route);
        if (v.style !== "alternative") this.selected = v.id;
        return;
      }
      case "section": {
        const colour = COLOURS[v.colour ?? "red"] ?? COLOURS.red;
        // real SBB alerts (the twin's ids start with "sa-") are dashed, as in every app on the twin
        this.sections.set(v.id, { colour, label: v.label ?? "", lines: linesOf(v.geometry), dashed: String(v.id).startsWith("sa-") });
        return;
      }
      case "train":
        this.trains.highlights.set(v.trainId, { label: v.label ?? "", follow: Boolean(v.follow) });
        this.map.triggerRepaint();
        return;
      case "station":
        this.station(v);
        return;
      case "timer":
        this.onTimer(v.until ? { label: v.label, until: v.until } : null);
        return;
      case "label": {
        if (!Number.isFinite(v.lon) || !Number.isFinite(v.lat)) return;
        const e = el("map-pill map-pill--section", `<span>${esc(v.text)}</span>`);
        this.markers.push(new maplibregl.Marker({ element: e, anchor: "bottom", offset: [0, -8] }).setLngLat([v.lon, v.lat]).addTo(this.map));
        return;
      }
      case "track_train":
        this.trains.highlights.set(v.trainId, { label: v.label ?? "", follow: false });
        this.onTrack(v);
        return;
      case "line":
        for (const id of this.trains.idsOfLine(v.line)) this.trains.highlights.set(id, { label: v.label ?? "", follow: false });
        return;
    }
  }

  /** The "Clear map" button: empties the picture (undo brings it back). */
  clear() {
    this.remember();
    this.picture = [];
    this.render();
  }

  private reset() {
    this.focus = null;
    this.trains.dim = false;
    this.routes.clear();
    this.selected = null;
    this.sections.clear();
    for (const mk of [...this.markers, ...this.sectionMarkers]) mk.remove();
    this.markers = [];
    this.sectionMarkers = [];
    this.trains.highlights.clear();
    this.onTimer(null);
  }

  private drawRoutes() {
    const features: Feature[] = [];
    // the selected route last, so it is drawn on top of the alternatives
    const order = [...this.routes.values()].sort((a, b) => Number(a.id === this.selected) - Number(b.id === this.selected));
    for (const r of order)
      for (const leg of r.legs)
        for (const line of leg.lines) if (line.length > 1) features.push({ type: "Feature", properties: { route: r.id, label: r.label, sel: r.id === this.selected }, geometry: { type: "LineString", coordinates: line } });
    (this.map.getSource("v-routes") as GeoJSONSource | undefined)?.setData({ type: "FeatureCollection", features });
    if (!this.replay && this.selected && this.selected !== this.lastSelected) this.drawStart = performance.now();
    this.lastSelected = this.selected;
  }

  private drawSections() {
    const features: Feature[] = [];
    for (const mk of this.sectionMarkers) mk.remove();
    this.sectionMarkers = [];
    let labelled = 0;
    for (const [sid, s] of this.sections) {
      for (const line of s.lines) if (line.length > 1 && !straight(line)) features.push({ type: "Feature", properties: { id: sid, label: s.label, colour: s.colour, dashed: s.dashed }, geometry: { type: "LineString", coordinates: line } });
      if (labelled >= 2 || !s.lines.some((l) => l.length > 1 && !straight(l))) continue;
      labelled++;
      const mid = this.focus ? nearest(s.lines, [(this.focus[0] + this.focus[2]) / 2, (this.focus[1] + this.focus[3]) / 2]) : midpoint(s.lines);
      if (mid && s.label) {
        const e = el("map-pill map-pill--section", `<i style="background:${s.colour}"></i><span>${esc(sbbText(s.label))}</span>`);
        this.sectionMarkers.push(new maplibregl.Marker({ element: e, anchor: "bottom", offset: [0, -10] }).setLngLat(mid).addTo(this.map));
      }
    }
    (this.map.getSource("v-sections") as GeoJSONSource | undefined)?.setData({ type: "FeatureCollection", features });
    if (this.replay) {
      for (const layer of SECTION_LAYERS) this.map.setLayoutProperty(layer, "visibility", features.length ? "visible" : "none");
    }
  }

  private station(v: Extract<VisualCommand, { type: "station" }>) {
    if (!Number.isFinite(v.lon) || !Number.isFinite(v.lat)) return;
    const role = v.role ?? "transfer";
    // one pin per station: a newer command for the same station replaces the older one
    const key = v.stopId ?? v.name;
    this.markers = this.markers.filter((mk) => {
      if ((mk.getElement() as HTMLElement).dataset.key !== key) return true;
      mk.remove();
      return false;
    });
    const e = el(`pin pin--${role}`, `<span class="pin-dot"></span><span class="pin-label">${esc(v.label || v.name)}</span>`);
    e.dataset.key = key;
    e.addEventListener("click", (ev) => {
      ev.stopPropagation();
      this.onPick({ kind: "station", id: v.stopId ?? "", label: v.name, lon: v.lon, lat: v.lat });
    });
    this.markers.push(new maplibregl.Marker({ element: e, anchor: "center" }).setLngLat([v.lon, v.lat]).addTo(this.map));
    requestAnimationFrame(() => this.placeLabels());
  }

  private cameraPadding() {
    const pad = this.padding();
    const view = this.map.getContainer().getBoundingClientRect();
    // keep the padding sane on small screens, or MapLibre refuses to fit
    if (pad.top + pad.bottom > view.height - 60) pad.bottom = Math.max(0, view.height - 60 - pad.top);
    return pad;
  }

  fit(bbox: [number, number, number, number], maxZoom = 13) {
    const [w, s, e, n] = bbox;
    if (![w, s, e, n].every(Number.isFinite)) return;
    this.map.fitBounds(
      [
        [w, s],
        [e, n],
      ],
      { padding: this.cameraPadding(), absolutePadding: Boolean(this.replay), duration: this.replay ? 700 : 1400, maxZoom, essential: !this.replay },
    );
  }

  focusBoarding(point: BoardingPin) {
    this.clearBoarding();
    this.boarding = point;
    if (!this.ready) return;
    const pin = el("pin pin--boarding", '<span class="pin-dot"></span><span class="pin-label"></span>');
    pin.querySelector(".pin-label")!.textContent = point.label;
    pin.setAttribute("role", "img");
    pin.setAttribute("aria-label", t("Boarding point · {place}", { place: point.label }));
    this.boardingMarker = new maplibregl.Marker({ element: pin, anchor: "center" }).setLngLat(point.coordinates).addTo(this.map);
    this.map.flyTo({ center: point.coordinates, zoom: 17, padding: this.cameraPadding(), duration: 850, essential: false });
  }

  clearBoarding() {
    this.boardingMarker?.remove();
    this.boardingMarker = undefined;
    this.boarding = undefined;
  }

  /** Frame a drawn route (an option the passenger tapped). */
  fitRoute(id: string) {
    const r = this.routes.get(id);
    if (!r) return;
    const pts = r.legs.flatMap((l) => l.lines.flat());
    if (!pts.length) return;
    const xs = pts.map((p) => p[0]);
    const ys = pts.map((p) => p[1]);
    this.fit([Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)]);
  }

  hasRoute(id: string) {
    return this.routes.has(id);
  }
  selectedRoute() {
    return this.selected;
  }
  /** Show one of the drawn routes as the chosen one; the others become alternatives. */
  selectRoute(id: string) {
    if (!this.routes.has(id)) return false;
    this.selected = id;
    this.drawRoutes();
    this.highlightFirstTrain(id);
    return true;
  }
  /** The ring moves to the first train of the chosen journey. */
  private highlightFirstTrain(id: string) {
    const leg = this.routes.get(id)?.legs.find((l) => l.trainId);
    if (!leg?.trainId) return;
    this.trains.highlights.clear();
    this.trains.highlights.set(leg.trainId, { label: `${t("Your train")}${leg.line ? ` · ${leg.line.replace(/^([A-Z]+)(\d)/, "$1 $2")}` : ""}`, follow: false });
    this.map.triggerRepaint();
  }
  /** Highlight (outline) a train and optionally follow it with the camera. */
  highlight(trainId: string, label: string, follow = false) {
    this.trains.highlights.set(trainId, { label, follow });
    this.map.triggerRepaint();
  }
  unhighlight(trainId: string) {
    if (this.picture.some((p) => p.key === `train:${trainId}`)) return;
    this.trains.highlights.delete(trainId);
    this.map.triggerRepaint();
  }
  trainPosition(trainId: string) {
    return this.trains.position(trainId);
  }

  hasHighlight() {
    return this.trains.highlights.size > 0;
  }
  /** Draw a journey that the backend listed but did not draw, from ktor_app's track geometry. */
  async showJourney(journeyId: string, label: string) {
    const r = await fetch(`/api/v1/geometry/journey/${encodeURIComponent(journeyId)}`);
    if (!r.ok) return false;
    const g = (await r.json()) as { legs?: { trainId?: string; line?: string; geometry: AnyGeoJSON }[] };
    this.routes.set(journeyId, { id: journeyId, label, legs: (g.legs ?? []).map((l) => ({ line: l.line, trainId: l.trainId, lines: linesOf(l.geometry) })) });
    this.selected = journeyId;
    this.drawRoutes();
    this.highlightFirstTrain(journeyId);
    return true;
  }

  private me?: maplibregl.Marker;
  /** The passenger's position (a sky-blue dot) and the nearest station. */
  showMe(lon: number, lat: number, station: { id: string; name: string; lon: number; lat: number }) {
    this.me?.remove();
    this.me = new maplibregl.Marker({ element: el("me", "<span></span>"), anchor: "center" }).setLngLat([lon, lat]).addTo(this.map);
    this.station({ type: "station", stopId: station.id, name: station.name, lon: station.lon, lat: station.lat, role: "origin", label: station.name });
    const pad = 0.03;
    this.fit([Math.min(lon, station.lon) - pad, Math.min(lat, station.lat) - pad * 0.7, Math.max(lon, station.lon) + pad, Math.max(lat, station.lat) + pad * 0.7], 13);
  }

  home() {
    this.map.fitBounds(SWITZERLAND, { padding: this.padding(), duration: 1200 });
  }

  flyTo(lon: number, lat: number, zoom = 12.5) {
    const pad = this.padding();
    this.map.flyTo({ center: [lon, lat], zoom, padding: pad, duration: 1400, essential: true });
  }
}
