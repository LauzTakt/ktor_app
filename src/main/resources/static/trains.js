// Every train drawn on one canvas above the map, interpolated between the 1 Hz fleet snapshots (60 fps).
// Stripped-down port of the sbb-twin viewer's viewer/src/trains.ts (no 3D, no simulated-train markers, light theme).

// SBB palette: green on time, orange late, blue at a station, red stopped on the line, grey cancelled, charcoal selected
export const COLOURS = { moving: "#00973b", late: "#f27e00", station: "#2d327d", stoppedOnLine: "#eb0000", cancelled: "#8d8d8d", selected: "#212121" };
// On the dark map the dark SBB blue and charcoal lose contrast: lighter equivalents there.
export const DARK_COLOURS = { ...COLOURS, station: "#8f9cff", cancelled: "#a3a9b1", selected: "#ffffff" };

/** Colour from observable facts only: moving / at a station / stopped on the open line, and lateness. */
export function colourOf(motion, delay, atStop, pal = COLOURS) {
  if (motion === "cancelled") return pal.cancelled;
  if (motion === "stopped" && !atStop) return pal.stoppedOnLine;
  if (delay >= 180) return pal.late;
  return motion === "moving" ? pal.moving : pal.station;
}

const lerp = (a, b, k) => [a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k];

export class TrainCanvas {
  constructor(map) {
    this.map = map;
    this.canvas = document.createElement("canvas");
    this.ctx = this.canvas.getContext("2d");
    this.trains = new Map();
    this.at = 0;
    this.selected = undefined;
    this.following = false;
    this.count = 0;
    this.theme = "light";
    this.canvas.className = "trains-canvas";
    map.getCanvasContainer().appendChild(this.canvas);
    const resize = () => {
      const r = map.getCanvas().getBoundingClientRect();
      const dpr = Math.min(2, devicePixelRatio || 1);
      this.canvas.width = r.width * dpr;
      this.canvas.height = r.height * dpr;
      this.canvas.style.width = `${r.width}px`;
      this.canvas.style.height = `${r.height}px`;
      this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    };
    map.on("resize", resize);
    resize();
    // Draw inside the map's own frame so markers move exactly with the map while panning or zooming.
    map.on("render", () => this.draw());
    const tick = () => {
      if (this.following && this.selected) {
        const p = this.position(this.selected);
        if (p) this.map.jumpTo({ center: p });
      }
      this.map.triggerRepaint();
      requestAnimationFrame(tick);
    };
    tick();
  }

  /** rows: [trainId, lat, lon, bearing, speedKmh, motion, delaySec, line, category, atStopId, continuity] */
  update(rows) {
    const k = this.k();
    const next = new Map();
    for (const [id, lat, lon, bearing, speed, state, delay, line, cat, atStop, continuity] of rows) {
      const old = this.trains.get(id);
      let from = [lon, lat];
      // interpolate from where it is drawn now, unless the train jumped (continuity changed)
      if (old && old.continuity === continuity) from = lerp(old.from, old.to, k);
      next.set(id, { id, from, to: [lon, lat], bearing, speed, state, delay, line, cat, atStop, continuity });
    }
    this.trains = next;
    this.at = performance.now();
    this.count = rows.length;
  }

  k() {
    return Math.min(1, (performance.now() - this.at) / 1000);
  }
  position(id) {
    const t = this.trains.get(id);
    return t ? lerp(t.from, t.to, this.k()) : undefined;
  }
  info(id) {
    return this.trains.get(id);
  }
  /** Closest train within 14 px of a screen point. */
  hit(x, y) {
    let best;
    let bestD = 14 * 14;
    const k = this.k();
    for (const t of this.trains.values()) {
      const p = this.map.project(lerp(t.from, t.to, k));
      const d = (p.x - x) ** 2 + (p.y - y) ** 2;
      if (d < bestD) {
        bestD = d;
        best = t.id;
      }
    }
    return best;
  }

  draw() {
    const { ctx, map } = this;
    ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
    const k = this.k();
    const zoom = map.getZoom();
    const size = zoom < 8 ? 4 : zoom < 10 ? 5 : zoom < 12 ? 6.5 : 8.5;
    const rot = map.getBearing();
    const labels = zoom >= 10.5;
    const view = map.getCanvas().getBoundingClientRect();
    ctx.font = '700 12px "Helvetica Neue", Arial, sans-serif';
    ctx.textBaseline = "middle";
    const draw = (t, selected) => {
      const p = map.project(lerp(t.from, t.to, k));
      if (p.x < -20 || p.y < -20 || p.x > view.width + 20 || p.y > view.height + 20) return;
      const r = selected ? size * 1.6 : size;
      ctx.save();
      ctx.translate(p.x, p.y);
      if (selected) {
        // pulsing sky-blue ring around a charcoal marker
        const pulse = (performance.now() % 1400) / 1400;
        ctx.beginPath();
        ctx.arc(0, 0, r * (2 + pulse * 2.4), 0, Math.PI * 2);
        ctx.strokeStyle = `rgba(0,121,199,${0.9 * (1 - pulse)})`;
        ctx.lineWidth = 3;
        ctx.stroke();
        ctx.beginPath();
        ctx.arc(0, 0, r * 2.1, 0, Math.PI * 2);
        ctx.fillStyle = "rgba(0,121,199,0.18)";
        ctx.fill();
      }
      ctx.rotate(((t.bearing - rot) * Math.PI) / 180);
      ctx.beginPath();
      if (t.state !== "moving") ctx.arc(0, 0, r * 0.95, 0, Math.PI * 2);
      else {
        ctx.moveTo(0, -r * 1.5);
        ctx.lineTo(r, r);
        ctx.lineTo(0, r * 0.45);
        ctx.lineTo(-r, r);
        ctx.closePath();
      }
      ctx.lineJoin = "round";
      ctx.strokeStyle = "rgba(0,0,0,0.45)";
      ctx.lineWidth = 4.2;
      ctx.stroke();
      ctx.strokeStyle = "#ffffff";
      ctx.lineWidth = 2;
      ctx.stroke();
      const pal = this.theme === "dark" ? DARK_COLOURS : COLOURS;
      ctx.fillStyle = selected ? pal.selected : colourOf(t.state, t.delay, t.atStop, pal);
      ctx.fill();
      ctx.restore();
      if (labels || selected) {
        // SBB-style tag: white pill, line in charcoal, delay in red
        const late = t.delay >= 60 ? ` +${Math.round(t.delay / 60)}′` : "";
        const lw = ctx.measureText(t.line).width;
        const dw = late ? ctx.measureText(late).width : 0;
        const x = p.x + r + 6;
        const dark = this.theme === "dark";
        // a dark pill when selected XOR dark theme, a light one otherwise
        const inverted = selected !== dark;
        ctx.fillStyle = inverted ? (dark ? "rgba(23,25,28,0.95)" : "#212121") : dark ? "#f1f2f4" : "rgba(255,255,255,0.95)";
        ctx.strokeStyle = dark ? "rgba(255,255,255,0.18)" : "rgba(0,0,0,0.18)";
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.roundRect(x, p.y - 9, lw + dw + 10, 18, 4);
        ctx.fill();
        ctx.stroke();
        ctx.fillStyle = inverted ? "#f1f2f4" : "#212121";
        ctx.fillText(t.line, x + 5, p.y + 0.5);
        if (late) {
          ctx.fillStyle = inverted ? "#ff8a8a" : "#eb0000";
          ctx.fillText(late, x + 5 + lw, p.y + 0.5);
        }
      }
    };
    for (const t of this.trains.values()) if (t.id !== this.selected) draw(t, false);
    const sel = this.selected && this.trains.get(this.selected);
    if (sel) draw(sel, true);
  }
}
