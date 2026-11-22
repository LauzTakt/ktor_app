// Every live train drawn on one canvas above the map, inside the map's own frame (so markers move exactly with the
// map), interpolated between snapshots. Colours follow docs/sbb-style.md §4 and never change when a train is
// highlighted: the highlight is a pulsing ring around it.
import type { Map as LibreMap } from "maplibre-gl";
import type { FleetRow } from "./feed";

type T = { id: string; from: [number, number]; to: [number, number]; bearing: number; motion: string; delay: number; line: string; atStop: string | null; continuity: number };
const TRAIN_COLOURS = { moving: "#00973b", late: "#f27e00", station: "#2d327d", stoppedOnLine: "#eb0000", cancelled: "#8d8d8d" };
function trainColour(motion: string, delay: number, atStop: string | null) {
  if (motion === "cancelled") return TRAIN_COLOURS.cancelled;
  if (motion === "stopped" && !atStop) return TRAIN_COLOURS.stoppedOnLine;
  if (delay >= 180) return TRAIN_COLOURS.late;
  return motion === "moving" ? TRAIN_COLOURS.moving : TRAIN_COLOURS.station;
}
const lerp = (a: [number, number], b: [number, number], k: number): [number, number] => [a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k];
const FONT = '700 12px "SBB Web", "Helvetica Neue", Arial, sans-serif';

export class TrainCanvas {
  private canvas = document.createElement("canvas");
  private ctx = this.canvas.getContext("2d")!;
  private trains = new Map<string, T>();
  private at = 0;
  private interval = 1000;
  /** trainId -> label of a highlighted train (VisualCommand "train") */
  highlights = new Map<string, { label: string; follow: boolean }>();
  /** While a route or a disruption is shown, the other trains step back. */
  dim = false;
  count = 0;

  constructor(private map: LibreMap) {
    this.canvas.className = "trains-canvas";
    map.getCanvasContainer().appendChild(this.canvas);
    const resize = () => {
      const r = map.getCanvas().getBoundingClientRect();
      const dpr = Math.min(2, devicePixelRatio || 1);
      this.canvas.width = Math.round(r.width * dpr);
      this.canvas.height = Math.round(r.height * dpr);
      this.canvas.style.width = `${r.width}px`;
      this.canvas.style.height = `${r.height}px`;
      this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    };
    map.on("resize", resize);
    resize();
    map.on("render", () => this.draw());
  }

  /** The ids of the running trains of a line ("IC8", "IC 8"). */
  idsOfLine(line: string) {
    const k = line.replace(/\s+/g, "").toUpperCase();
    return [...this.trains.values()].filter((t) => t.line.replace(/\s+/g, "").toUpperCase() === k).map((t) => t.id);
  }

  update(rows: FleetRow[], intervalMs: number) {
    const k = this.k();
    const next = new Map<string, T>();
    for (const [id, lat, lon, bearing, , motion, delay, line, , atStop, continuity] of rows) {
      if (typeof lat !== "number" || typeof lon !== "number") continue;
      const old = this.trains.get(id);
      // glide from where the train is drawn now, unless it jumped (continuity changed)
      const from: [number, number] = old && old.continuity === continuity ? lerp(old.from, old.to, k) : [lon, lat];
      next.set(id, { id, from, to: [lon, lat], bearing, motion, delay: delay ?? 0, line: line ?? "", atStop, continuity });
    }
    this.trains = next;
    this.at = performance.now();
    this.interval = intervalMs;
    this.count = next.size;
    this.map.triggerRepaint();
  }
  private k() {
    return Math.min(1, (performance.now() - this.at) / this.interval);
  }
  position(id: string) {
    const t = this.trains.get(id);
    return t ? lerp(t.from, t.to, this.k()) : undefined;
  }
  /** The train drawn closest to a screen point (a tap), within `px` pixels. */
  nearest(x: number, y: number, px = 22) {
    let best: { id: string; line: string; delay: number; d: number } | undefined;
    for (const t of this.trains.values()) {
      const p = this.map.project(lerp(t.from, t.to, this.k()) as [number, number]);
      const d = Math.hypot(p.x - x, p.y - y);
      if (d < px && (!best || d < best.d)) best = { id: t.id, line: t.line, delay: t.delay, d };
    }
    return best;
  }
  has(id: string) {
    return this.trains.has(id);
  }
  /** Something on the canvas moves by itself: the map should keep rendering frames. */
  animating() {
    return this.highlights.size > 0 || (this.map.getZoom() >= 10 && this.k() < 1);
  }
  /** Keep a followed train in view (called before each frame). */
  followStep() {
    for (const [id, h] of this.highlights) {
      if (!h.follow) continue;
      const p = this.position(id);
      if (p) this.map.setCenter(p);
      return;
    }
  }

  private draw() {
    const { ctx, map } = this;
    const view = map.getCanvas().getBoundingClientRect();
    ctx.clearRect(0, 0, view.width, view.height);
    const k = this.k();
    const zoom = map.getZoom();
    const size = zoom < 7 ? 2.4 : zoom < 8 ? 3 : zoom < 9 ? 3.8 : zoom < 11 ? 5 : zoom < 13 ? 6.5 : 8;
    const labels = zoom >= 11;
    ctx.font = FONT;
    ctx.textBaseline = "middle";
    const pulse = (performance.now() % 1600) / 1600;

    const marker = (t: T, p: { x: number; y: number }, r: number) => {
      ctx.save();
      ctx.translate(p.x, p.y);
      ctx.rotate(((t.bearing - map.getBearing()) * Math.PI) / 180);
      ctx.beginPath();
      if (t.motion !== "moving") ctx.arc(0, 0, r * 0.95, 0, Math.PI * 2);
      else {
        ctx.moveTo(0, -r * 1.5);
        ctx.lineTo(r, r);
        ctx.lineTo(0, r * 0.45);
        ctx.lineTo(-r, r);
        ctx.closePath();
      }
      ctx.lineJoin = "round";
      ctx.strokeStyle = "rgba(0,0,0,0.3)";
      ctx.lineWidth = r < 3.5 ? 2.6 : 3.6;
      ctx.stroke();
      ctx.strokeStyle = "#ffffff";
      ctx.lineWidth = r < 3.5 ? 1.1 : 1.5;
      ctx.stroke();
      ctx.fillStyle = trainColour(t.motion, t.delay, t.atStop);
      ctx.fill();
      ctx.restore();
    };
    const tag = (t: T, p: { x: number; y: number }, r: number) => {
      const late = t.delay >= 60 ? ` +${Math.round(t.delay / 60)}′` : "";
      const lw = ctx.measureText(t.line).width;
      const dw = late ? ctx.measureText(late).width : 0;
      const x = p.x + r + 6;
      ctx.fillStyle = "rgba(255,255,255,0.96)";
      ctx.strokeStyle = "rgba(0,0,0,0.16)";
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.roundRect(x, p.y - 9, lw + dw + 10, 18, 9);
      ctx.fill();
      ctx.stroke();
      ctx.fillStyle = "#212121";
      ctx.fillText(t.line, x + 5, p.y + 0.5);
      if (late) {
        ctx.fillStyle = "#eb0000";
        ctx.fillText(late, x + 5 + lw, p.y + 0.5);
      }
    };

    ctx.globalAlpha = this.dim ? 0.5 : 1;
    for (const t of this.trains.values()) {
      if (this.highlights.has(t.id)) continue;
      const p = map.project(lerp(t.from, t.to, k));
      if (p.x < -24 || p.y < -24 || p.x > view.width + 24 || p.y > view.height + 24) continue;
      marker(t, p, size);
      if (labels) tag(t, p, size);
    }
    ctx.globalAlpha = 1;
    // highlighted trains last, on top: a charcoal ring, a pulsing sky-blue ring, and a dark label pill
    for (const [id, h] of this.highlights) {
      const t = this.trains.get(id);
      if (!t) continue;
      const p = map.project(lerp(t.from, t.to, k));
      const r = Math.max(size, 6.5) * 1.25;
      ctx.beginPath();
      ctx.arc(p.x, p.y, r * (2.1 + pulse * 2.6), 0, Math.PI * 2);
      ctx.strokeStyle = `rgba(0,121,199,${0.85 * (1 - pulse)})`;
      ctx.lineWidth = 3;
      ctx.stroke();
      ctx.beginPath();
      ctx.arc(p.x, p.y, r * 2.05, 0, Math.PI * 2);
      ctx.fillStyle = "rgba(0,121,199,0.14)";
      ctx.fill();
      ctx.lineWidth = 2.5;
      ctx.strokeStyle = "#212121";
      ctx.stroke();
      marker(t, p, r);
      // the label hangs below the ring (station labels sit above their pins), kept inside the view
      const label = (h.label || t.line).replace(/\b(IC|IR|ICE|EC|RE|PE|IRE|SN|S|R)(\d{1,3})\b/g, "$1 $2");
      ctx.font = '700 13px "SBB Web", "Helvetica Neue", Arial, sans-serif';
      const w = ctx.measureText(label).width + 20;
      const x = Math.max(6, Math.min(view.width - w - 6, p.x - w / 2));
      const y = p.y + r * 2.05 + 9;
      ctx.fillStyle = "#212121";
      ctx.beginPath();
      ctx.moveTo(p.x - 6, y + 1);
      ctx.lineTo(p.x, y - 5);
      ctx.lineTo(p.x + 6, y + 1);
      ctx.fill();
      ctx.beginPath();
      ctx.roundRect(x, y, w, 26, 13);
      ctx.fill();
      ctx.fillStyle = "#ffffff";
      ctx.fillText(label, x + 10, y + 13.5);
      ctx.font = FONT;
    }
  }
}
