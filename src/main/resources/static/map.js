// Staff overview: the live twin on a map (trains, stations, twin incidents, staff reports).
// Data comes from this app (which reads the sbb-twin MQTT broker): WebSocket /ws/fleet + REST /api/v1.
import { TrainCanvas } from "./trains.js";
import { addRail, api, initStaffSession, applyTheme, baseStyle, bboxOf, initTheme, onTheme, CATEGORIES, clock, delayText, esc, ago, SEVERITY_COLOURS, severityBadge, time } from "./common.js";

const $ = (id) => document.getElementById(id);
const empty = { type: "FeatureCollection", features: [] };

const map = new maplibregl.Map({
  container: "map",
  style: await baseStyle(),
  center: [8.23, 46.82],
  zoom: 7.4,
  minZoom: 6,
  attributionControl: { compact: true },
});
map.addControl(new maplibregl.NavigationControl({ showCompass: true }), "bottom-right");
map.addControl(new maplibregl.ScaleControl(), "bottom-right");
await new Promise((r) => (map.loaded() ? r() : map.once("load", r)));

// ---------------------------------------------------------------- layers (bottom to top)

addRail(map);
map.addSource("incidents", { type: "geojson", data: empty });
map.addLayer({ id: "incident-glow", type: "line", source: "incidents", filter: ["in", ["geometry-type"], ["literal", ["LineString", "MultiLineString"]]], paint: { "line-color": ["get", "colour"], "line-width": ["interpolate", ["linear"], ["zoom"], 7, 9, 12, 18], "line-opacity": 0.3, "line-blur": 3 } });
map.addLayer({ id: "incident-line", type: "line", source: "incidents", filter: ["in", ["geometry-type"], ["literal", ["LineString", "MultiLineString"]]], layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": ["get", "colour"], "line-width": ["interpolate", ["linear"], ["zoom"], 7, 3.5, 12, 6] } });
map.addLayer({ id: "incident-point", type: "circle", source: "incidents", filter: ["in", ["geometry-type"], ["literal", ["Point", "MultiPoint"]]], paint: { "circle-radius": 11, "circle-color": "rgba(0,0,0,0)", "circle-stroke-color": ["get", "colour"], "circle-stroke-width": 3 } });

map.addSource("stations", { type: "geojson", data: empty });
map.addLayer({ id: "stations", type: "circle", source: "stations", minzoom: 9, paint: { "circle-radius": ["interpolate", ["linear"], ["zoom"], 9, 2.5, 13, 5], "circle-color": "#ffffff", "circle-stroke-color": "#212121", "circle-stroke-width": ["interpolate", ["linear"], ["zoom"], 9, 1, 13, 1.8] } });
map.addLayer({ id: "station-labels", type: "symbol", source: "stations", minzoom: 11, layout: { "text-field": ["get", "name"], "text-font": ["Frutiger Neue Regular"], "text-size": 12, "text-offset": [0, 0.9], "text-anchor": "top", "text-optional": true, "text-padding": 4 }, paint: { "text-color": "#212121", "text-halo-color": "rgba(255,255,255,0.95)", "text-halo-width": 1.8 } });

map.addSource("reports", { type: "geojson", data: empty });
map.addLayer({ id: "report-ring", type: "circle", source: "reports", paint: { "circle-radius": ["interpolate", ["linear"], ["zoom"], 6, 7, 12, 12], "circle-color": "rgba(255,255,255,0.85)", "circle-stroke-color": ["get", "colour"], "circle-stroke-width": 3 } });
map.addLayer({ id: "report-mark", type: "symbol", source: "reports", layout: { "text-field": "!", "text-font": ["Frutiger Neue Condensed Bold"], "text-size": ["interpolate", ["linear"], ["zoom"], 6, 11, 12, 16], "text-allow-overlap": true, "text-ignore-placement": true }, paint: { "text-color": ["get", "colour"] } });

const trains = new TrainCanvas(map);

onTheme((theme) => {
  applyTheme(map, theme);
  trains.theme = theme;
});
initTheme();
initStaffSession();

// ---------------------------------------------------------------- live fleet (WebSocket)

let twinTs = 0;
function connect() {
  const ws = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws/fleet`);
  ws.onopen = () => setConn("online", "live");
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.type !== "tick") return;
    twinTs = m.ts;
    trains.update(m.trains);
    $("trainCount").textContent = m.trains.length;
    $("lateCount").textContent = m.trains.filter((r) => r[6] >= 180).length;
    $("twinClock").textContent = clock(m.ts);
    if (trains.selected && !trains.info(trains.selected)) selectTrain(undefined); // left service
  };
  ws.onclose = () => {
    setConn("offline", "reconnecting…");
    setTimeout(connect, 1500);
  };
}
function setConn(cls, text) {
  const el = $("conn");
  el.className = `pill ${cls}`;
  el.textContent = text;
}
connect();

// Broker health: the socket may be up while our app has lost the broker or the twin went silent.
async function refreshStatus() {
  try {
    const s = await api("/status");
    if (!s.mqttConnected) setConn("warn", "broker offline");
    else if (s.twin && !s.twin.online) setConn("warn", "twin offline");
    else if ($("conn").classList.contains("warn")) setConn("online", "live");
  } catch {
    /* socket state already tells */
  }
}
refreshStatus();
setInterval(refreshStatus, 10_000);

// ---------------------------------------------------------------- stations, incidents, reports

api("/stations?limit=5000").then((list) => {
  map.getSource("stations").setData({
    type: "FeatureCollection",
    features: list.filter((s) => s.lat != null && s.lon != null).map((s) => ({ type: "Feature", properties: { id: s.stopId, name: s.name }, geometry: { type: "Point", coordinates: [s.lon, s.lat] } })),
  });
});

let incidents = [];
let reports = [];

async function refreshIncidents() {
  incidents = await api("/incidents?geometry=true").catch(() => incidents);
  map.getSource("incidents").setData({
    type: "FeatureCollection",
    features: incidents.filter((i) => i.geometry).map((i) => ({ type: "Feature", properties: { id: i.id, colour: SEVERITY_COLOURS[i.severity] ?? "#eb0000" }, geometry: i.geometry })),
  });
  $("incCount").textContent = incidents.length;
  $("incList").innerHTML = incidents.length
    ? incidents
        .map(
          (i) => `<li data-inc="${esc(i.id)}">
            <div class="row">${severityBadge(i.severity)} <b>${esc(i.title)}</b></div>
            <div class="muted">${esc(i.where ?? i.scope?.kind ?? "")} · since ${time(i.createdAt)}</div></li>`,
        )
        .join("")
    : `<li class="empty">No active incident.</li>`;
}

async function refreshReports() {
  reports = await api("/reports?status=open").catch(() => reports);
  map.getSource("reports").setData({
    type: "FeatureCollection",
    features: reports.filter((r) => r.lat != null && r.lon != null).map((r) => ({ type: "Feature", properties: { id: r.id, colour: SEVERITY_COLOURS[r.severity] ?? "#f27e00" }, geometry: { type: "Point", coordinates: [r.lon, r.lat] } })),
  });
  $("repCount").textContent = reports.length;
  $("repList").innerHTML = reports.length
    ? reports
        .map(
          (r) => `<li data-rep="${esc(r.id)}">
            <div class="row">${severityBadge(r.severity)} <b>${esc(r.title)}</b></div>
            <div class="muted">${esc(CATEGORIES[r.category] ?? r.category)} · ${esc(r.where)}</div>
            <div class="muted">${ago(r.createdAt)}${r.reporter ? ` · by ${esc(r.reporter)}` : ""}</div></li>`,
        )
        .join("")
    : `<li class="empty">No open report.</li>`;
}
refreshIncidents();
refreshReports();
setInterval(refreshIncidents, 15_000);
setInterval(refreshReports, 10_000);

$("incList").addEventListener("click", (e) => {
  const id = e.target.closest("[data-inc]")?.dataset.inc;
  const inc = incidents.find((i) => i.id === id);
  const b = inc && bboxOf(inc.geometry);
  if (b) map.fitBounds(b, { padding: 120, maxZoom: 13 });
});
$("repList").addEventListener("click", (e) => {
  const id = e.target.closest("[data-rep]")?.dataset.rep;
  const r = reports.find((x) => x.id === id);
  if (r?.lat != null) {
    map.flyTo({ center: [r.lon, r.lat], zoom: Math.max(map.getZoom(), 12) });
    showReportPopup(r);
  }
});

// ---------------------------------------------------------------- selection

let popup;
function closePopup() {
  popup?.remove();
  popup = undefined;
}

map.on("click", (e) => {
  closePopup();
  const id = trains.hit(e.point.x, e.point.y);
  if (id) return selectTrain(id);
  const box = [[e.point.x - 6, e.point.y - 6], [e.point.x + 6, e.point.y + 6]];
  const rep = map.queryRenderedFeatures(box, { layers: ["report-ring"] })[0];
  if (rep) return showReportPopup(reports.find((r) => r.id === rep.properties.id));
  const st = map.queryRenderedFeatures(box, { layers: ["stations"] })[0];
  if (st) return showStationPopup(st.properties.id, st.properties.name, st.geometry.coordinates);
  const inc = map.queryRenderedFeatures(box, { layers: ["incident-line", "incident-point"] })[0];
  if (inc) return showIncidentPopup(incidents.find((i) => i.id === inc.properties.id), e.lngLat);
  selectTrain(undefined);
});
map.on("mousemove", (e) => {
  const box = [[e.point.x - 6, e.point.y - 6], [e.point.x + 6, e.point.y + 6]];
  const over = trains.hit(e.point.x, e.point.y) || map.queryRenderedFeatures(box, { layers: ["stations", "report-ring", "incident-line", "incident-point"] }).length;
  map.getCanvas().style.cursor = over ? "pointer" : "";
});
// Right click anywhere: report an incident at that point.
map.on("contextmenu", (e) => {
  closePopup();
  popup = new maplibregl.Popup({ closeButton: true })
    .setLngLat(e.lngLat)
    .setHTML(`<a class="btn primary" href="/report.html?scope=point&lat=${e.lngLat.lat.toFixed(5)}&lon=${e.lngLat.lng.toFixed(5)}">Report an incident here</a>`)
    .addTo(map);
});

function showReportPopup(r) {
  if (!r) return;
  closePopup();
  popup = new maplibregl.Popup({ maxWidth: "320px" })
    .setLngLat([r.lon, r.lat])
    .setHTML(
      `<div class="pop"><div class="row">${severityBadge(r.severity)} <b>${esc(r.title)}</b></div>
       <div class="muted">${esc(CATEGORIES[r.category] ?? r.category)} · ${esc(r.where)}</div>
       ${r.description ? `<p>${esc(r.description)}</p>` : ""}
       <div class="muted">Reported ${time(r.createdAt)}${r.reporter ? ` by ${esc(r.reporter)}` : ""}</div>
       <a href="/report.html#${esc(r.id)}">Open in reports</a></div>`,
    )
    .addTo(map);
}

function showIncidentPopup(i, at) {
  if (!i) return;
  popup = new maplibregl.Popup({ maxWidth: "320px" })
    .setLngLat(at)
    .setHTML(
      `<div class="pop"><div class="row">${severityBadge(i.severity)} <b>${esc(i.title)}</b></div>
       <div class="muted">${esc(i.where ?? "")}${i.reasonCode ? ` · ${esc(i.reasonCode)}` : ""}</div>
       ${i.description ? `<p>${esc(i.description)}</p>` : ""}
       <div class="muted">Since ${time(i.createdAt)} · twin incident</div></div>`,
    )
    .addTo(map);
}

async function showStationPopup(stopId, name, coords) {
  popup = new maplibregl.Popup({ maxWidth: "340px" })
    .setLngLat(coords)
    .setHTML(`<div class="pop"><b>${esc(name)}</b><div class="muted">loading departures…</div></div>`)
    .addTo(map);
  const p = popup;
  const board = await api(`/stations/${encodeURIComponent(stopId)}/board?windowMin=60&limit=8`).catch(() => null);
  if (p !== popup) return;
  const rows = (board?.entries ?? [])
    .map((e) => `<tr><td>${time(e.plannedTime)}</td><td class="late">${esc(delayText(e.delaySec))}</td><td><b>${esc(e.line ?? "")}</b></td><td>${esc(e.headsign ?? e.destination?.name ?? "")}</td></tr>`)
    .join("");
  p.setHTML(
    `<div class="pop"><b>${esc(name)}</b>
     <table class="board">${rows || `<tr><td class="muted">No departures in the next hour.</td></tr>`}</table>
     <a class="btn primary small" href="/report.html?scope=station&stopId=${encodeURIComponent(stopId)}">Report an incident here</a></div>`,
  );
}

let trainTimer;
async function selectTrain(id) {
  trains.selected = id;
  trains.following = false;
  clearInterval(trainTimer);
  const panel = $("trainPanel");
  if (!id) {
    panel.hidden = true;
    return;
  }
  panel.hidden = false;
  panel.innerHTML = `<h2>Train</h2><div class="muted">loading…</div>`;
  const render = async () => {
    const d = await api(`/trains/${encodeURIComponent(id)}`).catch(() => null);
    if (trains.selected !== id) return;
    if (!d) {
      panel.innerHTML = `<h2>Train</h2><div class="muted">No details for this train yet.</div>`;
      return;
    }
    const t = d.train;
    const live = trains.info(id);
    const ahead = d.stops.filter((s) => s.state !== "passed").slice(0, 8);
    panel.innerHTML = `
      <h2>${esc(t.line ?? "Train")} ${esc(t.number ?? "")} <button class="x" id="closeTrain" title="Close">×</button></h2>
      <div class="big">→ ${esc(t.headsign ?? t.destination?.name ?? "")}</div>
      <div class="muted">${esc(t.operator ?? "")} · from ${esc(t.origin?.name ?? "?")}</div>
      <div class="kv">
        <span>Delay</span><b class="${(t.delaySec ?? 0) >= 180 ? "late" : ""}">${t.delaySec == null ? "–" : delayText(t.delaySec) || "on time"}</b>
        <span>Speed</span><b>${live ? Math.round(live.speed) + " km/h" : "–"}</b>
        <span>Now</span><b>${esc(t.currentStop ? "at " + t.currentStop.name : t.nextStop ? "towards " + t.nextStop.name : "–")}</b>
        <span>Status</span><b>${esc(t.status)}</b>
      </div>
      ${d.incidents.length ? `<div class="warnbox">${d.incidents.map((i) => `${severityBadge(i.severity)} ${esc(i.title)}`).join("<br>")}</div>` : ""}
      <table class="stops">${ahead
        .map(
          (s) => `<tr class="${s.state}"><td>${time(s.plannedArr ?? s.plannedDep)}</td><td class="late">${esc(delayText(s.arrDelaySec ?? s.depDelaySec))}</td><td>${s.skipped ? `<s>${esc(s.name)}</s>` : esc(s.name)}</td></tr>`,
        )
        .join("")}</table>
      <div class="actions">
        <button class="btn" id="follow">${trains.following ? "Stop following" : "Follow"}</button>
        <a class="btn primary" href="/report.html?scope=train&trainId=${encodeURIComponent(id)}">Report an incident</a>
      </div>`;
    $("closeTrain").onclick = () => selectTrain(undefined);
    $("follow").onclick = (ev) => {
      trains.following = !trains.following;
      ev.target.textContent = trains.following ? "Stop following" : "Follow";
    };
  };
  await render();
  trainTimer = setInterval(render, 10_000);
}
