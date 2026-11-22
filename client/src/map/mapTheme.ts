// Dark mode for the swisstopo light base map: recoloured in place (no style reload, so our sources, layers and
// markers stay). Same palette as the passenger view and staff map (src/main/resources/static/common.js).
import type { Map as MapLibreMap } from "maplibre-gl";
import type { Theme } from "../theme";

type Paint = Record<string, unknown>;
type PaintKey = Parameters<MapLibreMap["setPaintProperty"]>[1];
type Layer = { id: string; type: string; paint?: Paint };

function darkPaint(l: Layer): Paint {
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

/** Colours of our own layers per theme: layer id -> paint property -> colour. */
const OWN: Record<Theme, Record<string, Paint>> = {
  light: { "network-halo": { "line-color": "#ffffff" }, "network-line": { "line-color": "#444444" }, "v-alt-casing": { "line-color": "#ffffff" }, "v-route-casing": { "line-color": "#ffffff" } },
  dark: { "network-halo": { "line-color": "#0b0e11" }, "network-line": { "line-color": "#9aa6b8" }, "v-alt-casing": { "line-color": "#17191c" }, "v-route-casing": { "line-color": "#17191c" } },
};

/** Remembers the light paint of the base map's layers; call once on load, before adding our own layers. */
export function captureBase(map: MapLibreMap): Map<string, Layer> {
  return new Map((map.getStyle().layers as Layer[]).map((l) => [l.id, { id: l.id, type: l.type, paint: { ...(l.paint ?? {}) } }]));
}

export function applyMapTheme(map: MapLibreMap, base: Map<string, Layer>, theme: Theme) {
  for (const l of base.values()) {
    if (!map.getLayer(l.id)) continue;
    const light = l.paint ?? {};
    const dark = darkPaint(l);
    const target: Paint = theme === "dark" ? { ...light, ...dark } : { ...light };
    // back to light: properties only the dark theme sets go back to their defaults
    if (theme === "light") for (const k of Object.keys(dark)) if (!(k in light)) target[k] = undefined;
    for (const [k, v] of Object.entries(target)) {
      if (k.endsWith("-transition")) continue;
      try {
        map.setPaintProperty(l.id, k as PaintKey, v as never);
      } catch {
        /* property not valid for this layer type */
      }
    }
  }
  for (const [id, paint] of Object.entries(OWN[theme])) {
    if (!map.getLayer(id)) continue;
    for (const [k, v] of Object.entries(paint)) map.setPaintProperty(id, k as PaintKey, v as never);
  }
}
