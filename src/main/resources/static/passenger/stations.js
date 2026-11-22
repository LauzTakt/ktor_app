// Station picker: text search over every station, or a MapLibre map.

import { $, addRail, applyTheme as applyMapTheme, baseStyle, currentTheme, esc, MAP_COLOURS, onTheme, publicApi as api, reducedMotion, t, time as hhmm } from "../common.js";
import { buzz, debounce, ic } from "./ui.js";

// ------------------------------------------------------------------ station picker (text or map)

export let stationsCache = null;
export const allStations = () => (stationsCache ??= api("/stations?limit=5000"));
let pickerSequence = 0;

/**
 * Text search + map, in tabs. `reachable` (optional) = Map stopId -> {trains, nextArrival} to rank and highlight
 * destinations reachable from an origin. Calls onPick({stopId, name}).
 */
export function stationPicker(container, { placeholder, reachable = null, onPick, input: external = null }) {
  const id = `station-picker-${++pickerSequence}`;
  container.innerHTML = `
    <div class="tabs tabs-border" role="tablist" aria-label="${esc(t("Choose a station"))}">
      <button class="tab tab-active" type="button" role="tab" id="${id}-list" aria-controls="${id}-results" aria-selected="true" data-tab="text">${ic("list")}${esc(t("List"))}</button>
      <button class="tab" type="button" role="tab" id="${id}-map" aria-controls="${id}-map-panel" aria-selected="false" tabindex="-1" data-tab="map">${ic("map")}${esc(t("Map"))}</button>
    </div>
    <div class="pane" id="${id}-results" role="tabpanel" aria-labelledby="${id}-list" data-pane="text">
      ${external ? "" : `<label class="input search">${ic("search")}<input type="text" placeholder="${esc(placeholder)}" aria-label="${esc(placeholder)}" autocomplete="off" enterkeyhint="search" />
        <button class="clear" type="button" title="${esc(t("Clear"))}" aria-label="${esc(t("Clear"))}">${ic("x")}</button></label>`}
      <div class="list stagger results"></div>
    </div>
    <div class="pane" id="${id}-map-panel" role="tabpanel" aria-labelledby="${id}-map" data-pane="map" hidden><div class="map"></div><p class="note">${esc(t("Tap a station on the map."))}</p></div>`;
  // `input` (optional): a text field owned by the caller (e.g. the From / To fields) instead of our own search box.
  const input = external ?? $("input", container), results = $(".results", container);
  const controller = new AbortController();
  const { signal } = controller;
  let mapReady;

  const rows = async () => {
    const q = input.value.trim().toLowerCase();
    let list;
    if (reachable && !q) {
      const st = await allStations();
      list = st.filter((x) => reachable.has(x.stopId)).sort((a, b) => reachable.get(b.stopId).trains - reachable.get(a.stopId).trains).slice(0, 12);
    } else if (q) list = await api(`/stations?q=${encodeURIComponent(q)}&limit=12`).catch(() => []);
    else list = [];
    if (signal.aborted || q !== input.value.trim().toLowerCase()) return;
    results.innerHTML = list.length ? list.map((x, i) => {
      const r = reachable?.get(x.stopId);
      return `<button class="row" type="button" data-id="${esc(x.stopId)}" data-name="${esc(x.name)}" style="--i:${i}">
        <span class="main"><span class="t">${esc(x.name)}</span>${r ? `<span class="m">${esc(r.trains > 1 ? t("{n} trains going there", { n: r.trains }) : t("1 train going there"))}${r.nextArrival ? ` · ${esc(t("first arrival {time}", { time: hhmm(r.nextArrival) }))}` : ""}</span>` : reachable ? `<span class="m">${esc(t("No direct train running now"))}</span>` : ""}</span>
        <span class="chev">${ic("chevron")}</span></button>`;
    }).join("") : q ? `<p class="empty">${esc(t("No station matches."))}</p>` : "";
    results.querySelectorAll(".row").forEach((el) => (el.onclick = () => { el.classList.add("selected"); onPick({ stopId: el.dataset.id, name: el.dataset.name }); }));
  };
  const onInput = debounce(rows, 180);
  const onKey = (e) => { if (e.key === "Enter") { e.preventDefault(); $(".row", results)?.click(); } };
  input.addEventListener("input", onInput);
  input.addEventListener("keydown", onKey);
  const clear = $(".clear", container);
  if (clear) clear.onclick = () => { input.value = ""; rows(); input.focus(); };
  rows();

  const tabs = Array.from(container.querySelectorAll('[role="tab"]'));
  const [listTab, mapTab] = tabs;
  $(".tabs", container).onkeydown = (event) => {
    const otherTab = event.target === listTab ? mapTab : listTab;
    const target = { ArrowLeft: otherTab, ArrowRight: otherTab, Home: listTab, End: mapTab }[event.key];
    if (!target) return;
    event.preventDefault();
    target.focus();
    target.click();
  };
  tabs.forEach((tab) => (tab.onclick = async () => {
    buzz();
    tabs.forEach((t) => {
      t.classList.toggle("tab-active", t === tab);
      t.setAttribute("aria-selected", String(t === tab));
      t.tabIndex = t === tab ? 0 : -1;
    });
    container.querySelectorAll(".pane").forEach((p) => (p.hidden = p.dataset.pane !== tab.dataset.tab));
    if (tab === mapTab) {
      const pane = $('[data-pane="map"]', container);
      const canvas = $(".map", pane);
      const note = $(".note", pane);
      canvas.hidden = false;
      note.textContent = t("Tap a station on the map.");
      mapReady ??= stationMap(canvas, { reachable, onPick, signal }).catch(() => {
        mapReady = undefined;
        canvas.hidden = true;
        note.textContent = t("Map unavailable. Use the list to find a station.");
      });
      const map = await mapReady;
      if (!signal.aborted && !pane.hidden) map?.resize();
    }
  }));
  return () => {
    controller.abort();
    input.removeEventListener("input", onInput);
    input.removeEventListener("keydown", onKey);
  };
}

export async function stationMap(el, { reachable, onPick, signal }) {
  const style = await baseStyle();
  if (signal.aborted) return;
  const map = new maplibregl.Map({ container: el, style, bounds: [[5.9, 45.8], [10.5, 47.85]], maxBounds: [[5.4, 45.55], [11.0, 48.05]], attributionControl: { compact: true } });
  const recolour = (theme) => applyMapTheme(map, theme);
  const offTheme = onTheme(recolour);
  signal.addEventListener("abort", () => { offTheme(); map.remove(); }, { once: true });
  map.on("load", async () => {
    addRail(map);
    recolour(document.documentElement.dataset.theme);
    const st = await allStations();
    if (signal.aborted) return;
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
        .setHTML(`<div class="pop"><b>${esc(p.name)}</b><button class="btn btn-primary" type="button">${esc(t("Choose"))}</button></div>`)
        .addTo(map).getElement().querySelector("button").onclick = () => onPick({ stopId: p.id, name: p.name });
    });
  });
  return map;
}
