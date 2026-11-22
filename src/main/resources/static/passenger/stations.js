// Station picker: text search over every station, or a MapLibre map.

import { $, addRail, applyTheme as applyMapTheme, baseStyle, currentTheme, esc, MAP_COLOURS, onTheme, publicApi as api, reducedMotion, time as hhmm } from "../common.js";
import { buzz, debounce, ic } from "./ui.js";

// ------------------------------------------------------------------ station picker (text or map)

export let stationsCache = null;
export const allStations = () => (stationsCache ??= api("/stations?limit=5000"));

/**
 * Text search + map, in tabs. `reachable` (optional) = Map stopId -> {trains, nextArrival} to rank and highlight
 * destinations reachable from an origin. Calls onPick({stopId, name}).
 */
export function stationPicker(container, { placeholder, reachable = null, onPick, input: external = null }) {
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

export async function stationMap(el, { reachable, onPick }) {
  while (!window.maplibregl) await new Promise((r) => setTimeout(r, 50));
  const map = new maplibregl.Map({ container: el, style: await baseStyle(), bounds: [[5.9, 45.8], [10.5, 47.85]], maxBounds: [[5.4, 45.55], [11.0, 48.05]], attributionControl: { compact: true } });
  const recolour = (theme) => applyMapTheme(map, theme);
  const offTheme = onTheme(recolour);
  map.on("load", async () => {
    addRail(map);
    recolour(document.documentElement.dataset.theme);
    const st = await allStations();
    map.addSource("st", { type: "geojson", data: { type: "FeatureCollection", features: st.filter((x) => x.lat != null).map((x) => ({
      type: "Feature", geometry: { type: "Point", coordinates: [x.lon, x.lat] },
      properties: { id: x.stopId, name: x.name, reach: reachable ? (reachable.get(x.stopId)?.trains ?? 0) : 1 },
    })) } });
    const colours = MAP_COLOURS[currentTheme()];
    const ringed = (minzoom, filter, r7, r12, stroke) => ({ type: "circle", source: "st", minzoom, filter, paint: {
      "circle-radius": ["interpolate", ["linear"], ["zoom"], 7, r7, 12, r12], "circle-color": colours.stationFill, "circle-stroke-width": 2, "circle-stroke-color": stroke } });
    // Reachable stations always; the others only when zoomed in (small, cement), so the map stays readable.
    map.addLayer({ id: "st-other", ...ringed(reachable ? 10 : 8, ["==", ["get", "reach"], 0], 2, 4, colours.pickOtherStroke) });
    map.addLayer({ id: "st", ...ringed(0, [">", ["get", "reach"], 0], 4, 8, colours.pickStroke) });
    if (reachable?.size) {
      const pts = st.filter((x) => reachable.has(x.stopId) && x.lat != null);
      const lons = pts.map((x) => x.lon), lats = pts.map((x) => x.lat);
      if (pts.length) map.fitBounds([[Math.min(...lons), Math.min(...lats)], [Math.max(...lons), Math.max(...lats)]], { padding: 40, maxZoom: 11, duration: reducedMotion() ? 0 : 700 });
    }
    map.addLayer({ id: "st-label", type: "symbol", source: "st", minzoom: 10, filter: [">", ["get", "reach"], 0],
      layout: { "text-field": ["get", "name"], "text-size": 12, "text-offset": [0, 1.1], "text-anchor": "top", "text-font": ["Frutiger Neue Bold"] },
      paint: { "text-color": colours.label, "text-halo-color": colours.labelHalo, "text-halo-width": 2 } });
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
  map.remove = () => { offTheme(); remove(); };
  return map;
}
