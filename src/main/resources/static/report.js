// Staff incident report: a form (POST /api/v1/reports) and the list of reports (resolve them when fixed).
import { addRail, api, initStaffSession, ago, applyTheme, baseStyle, initTheme, onTheme, CATEGORIES, dateTime, esc, severityBadge, t } from "./common.js";

const $ = (id) => document.getElementById(id);
const form = $("form");
const params = new URLSearchParams(location.search);
document.title = t("Takt · Report an incident");

$("category").innerHTML = Object.entries(CATEGORIES)
  .map(([k, v]) => `<option value="${k}">${esc(v)}</option>`)
  .join("");

// ---------------------------------------------------------------- location pickers

/** Picked location: {stopId, name, lat, lon} for stations, {trainId, label, lat, lon} for a train, {lat, lon} for a point. */
const picked = { a: null, b: null, train: null, point: null };

const scope = () => form.elements.scope.value;
function showScope() {
  const s = scope();
  for (const el of document.querySelectorAll("[data-scope]")) el.hidden = !el.dataset.scope.split(" ").includes(s);
  $("pickMap").classList.toggle("picking", s === "point");
  drawPick();
}
form.addEventListener("change", (e) => e.target.name === "scope" && showScope());

// Stations: server-side search as you type; the datalist value is "Name (stopId)" so the pick is unambiguous.
const stationLabel = (s) => `${s.name} (${s.stopId})`;
function stationInput(input, list, key) {
  let found = [];
  let t;
  input.addEventListener("input", () => {
    const hit = found.find((s) => stationLabel(s) === input.value);
    if (hit) {
      picked[key] = { stopId: hit.stopId, name: hit.name, lat: hit.lat, lon: hit.lon };
      drawPick(true);
      return;
    }
    picked[key] = null;
    drawPick();
    clearTimeout(t);
    const q = input.value.trim();
    if (q.length < 2) return;
    t = setTimeout(async () => {
      found = await api(`/stations?q=${encodeURIComponent(q)}&limit=15`).catch(() => []);
      list.innerHTML = found.map((s) => `<option value="${esc(stationLabel(s))}"></option>`).join("");
    }, 150);
  });
}
stationInput($("stationA"), $("stationsA"), "a");
stationInput($("stationB"), $("stationsB"), "b");

// Trains: every train in service, filtered by the browser's datalist.
let trainList = [];
const trainLabel = (t) => `${t.line ?? "?"} ${t.number ?? ""} → ${t.headsign ?? t.destination?.name ?? "?"} [${t.trainId}]`;
async function loadTrains() {
  const page = await api("/trains?limit=2000").catch(() => ({ items: [] }));
  trainList = page.items;
  $("trainsList").innerHTML = trainList.map((t) => `<option value="${esc(trainLabel(t))}"></option>`).join("");
}
$("trainSearch").addEventListener("input", (e) => {
  const t = trainList.find((x) => trainLabel(x) === e.target.value);
  picked.train = t ? { trainId: t.trainId, label: trainLabel(t), lat: t.position?.lat, lon: t.position?.lon } : null;
  drawPick(true);
});

// ---------------------------------------------------------------- mini map

initStaffSession();
initTheme(); // page colours now; the mini map follows once loaded
const pickMap = new maplibregl.Map({ container: "pickMap", style: await baseStyle(), center: [8.23, 46.82], zoom: 6.6, attributionControl: { compact: true } });
pickMap.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
await new Promise((r) => (pickMap.loaded() ? r() : pickMap.once("load", r)));
addRail(pickMap);
pickMap.addSource("pick", { type: "geojson", data: { type: "FeatureCollection", features: [] } });
pickMap.addLayer({ id: "pick-line", type: "line", source: "pick", paint: { "line-color": "#eb0000", "line-width": 4, "line-dasharray": [2, 1] } });
pickMap.addLayer({ id: "pick-point", type: "circle", source: "pick", filter: ["==", ["geometry-type"], "Point"], paint: { "circle-radius": 8, "circle-color": "#eb0000", "circle-stroke-color": "#fff", "circle-stroke-width": 3 } });
onTheme((theme) => applyTheme(pickMap, theme));
applyTheme(pickMap, document.documentElement.dataset.theme);
pickMap.on("click", (e) => {
  if (scope() !== "point") return;
  picked.point = { lat: +e.lngLat.lat.toFixed(5), lon: +e.lngLat.lng.toFixed(5) };
  drawPick();
});

/** Pick points for the current scope, drawn on the mini map; `fly` recentres on them. */
function drawPick(fly = false) {
  const s = scope();
  const pts = (s === "station" ? [picked.a] : s === "section" ? [picked.a, picked.b] : s === "train" ? [picked.train] : [picked.point]).filter((p) => p?.lat != null);
  const features = pts.map((p) => ({ type: "Feature", properties: {}, geometry: { type: "Point", coordinates: [p.lon, p.lat] } }));
  if (s === "section" && pts.length === 2) features.push({ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: pts.map((p) => [p.lon, p.lat]) } });
  pickMap.getSource("pick")?.setData({ type: "FeatureCollection", features });
  if (fly && pts.length === 1) pickMap.flyTo({ center: [pts[0].lon, pts[0].lat], zoom: 11 });
  if (fly && pts.length === 2) pickMap.fitBounds([[Math.min(pts[0].lon, pts[1].lon), Math.min(pts[0].lat, pts[1].lat)], [Math.max(pts[0].lon, pts[1].lon), Math.max(pts[0].lat, pts[1].lat)]], { padding: 60, maxZoom: 12 });
  $("whereText").textContent =
    s === "station" ? (picked.a ? `📍 ${picked.a.name}` : "") :
    s === "section" ? (picked.a && picked.b ? `📍 ${picked.a.name} – ${picked.b.name}` : "") :
    s === "train" ? (picked.train ? `🚆 ${picked.train.label}` : "") :
    picked.point ? `📍 ${picked.point.lat}, ${picked.point.lon}` : "";
}

// ---------------------------------------------------------------- prefill from the map page (?scope=…)

async function prefill() {
  const s = params.get("scope");
  if (!s) return;
  form.elements.scope.value = s;
  if (s === "station" && params.get("stopId")) {
    const st = await api(`/stations/${encodeURIComponent(params.get("stopId"))}`).then((d) => d.station).catch(() => null);
    if (st) {
      picked.a = { stopId: st.stopId, name: st.name, lat: st.lat, lon: st.lon };
      $("stationA").value = stationLabel(st);
    }
  } else if (s === "train" && params.get("trainId")) {
    const t = trainList.find((x) => x.trainId === params.get("trainId"));
    if (t) {
      picked.train = { trainId: t.trainId, label: trainLabel(t), lat: t.position?.lat, lon: t.position?.lon };
      $("trainSearch").value = trainLabel(t);
    }
  } else if (s === "point" && params.get("lat")) {
    picked.point = { lat: +params.get("lat"), lon: +params.get("lon") };
  }
  showScope();
  drawPick(true);
}

// ---------------------------------------------------------------- submit

const msg = (text, ok) => {
  $("msg").textContent = text;
  $("msg").className = ok ? "ok" : "err";
};

form.addEventListener("submit", async (e) => {
  e.preventDefault();
  const s = scope();
  const body = {
    title: form.elements.title.value,
    description: form.elements.description.value,
    category: form.elements.category.value,
    severity: form.elements.severity.value,
    reporter: form.elements.reporter.value || null,
    scope: s,
  };
  if (s === "station") {
    if (!picked.a) return msg(t("Pick a station from the list."));
    body.stopId = picked.a.stopId;
  } else if (s === "section") {
    if (!picked.a || !picked.b) return msg(t("Pick both stations from the lists."));
    body.stopId = picked.a.stopId;
    body.toStopId = picked.b.stopId;
  } else if (s === "train") {
    if (!picked.train) return msg(t("Pick a train from the list."));
    body.trainId = picked.train.trainId;
  } else {
    if (!picked.point) return msg(t("Click on the map to place the incident."));
    Object.assign(body, picked.point);
  }
  try {
    const r = await api("/reports", { method: "POST", json: body });
    msg(t("Report {id} sent. It is now on the staff map.", { id: r.id }), true);
    form.elements.title.value = "";
    form.elements.description.value = "";
    setFilter("open");
  } catch (err) {
    msg(err.message);
  }
});

// ---------------------------------------------------------------- list

const filterVal = () => document.querySelector("input[name=filter]:checked").value;
function setFilter(v) {
  document.querySelector(`input[name=filter][value=${v}]`).checked = true;
  loadReports();
}
$("filterSeg").addEventListener("change", loadReports);

async function loadReports() {
  const list = await api(`/reports?status=${filterVal()}`).catch(() => []);
  $("reports").innerHTML = list.length
    ? list
        .map(
          (r) => `<li id="${esc(r.id)}" class="${r.status}">
        <div class="row">${severityBadge(r.severity)} <b>${esc(r.title)}</b> <span class="muted right">${esc(r.id)}</span></div>
        <div class="muted">${esc(CATEGORIES[r.category] ?? r.category)} · ${esc(r.where)}</div>
        ${r.description ? `<p>${esc(r.description)}</p>` : ""}
        <div class="muted">${r.reporter ? t("Reported {time} ({ago}) by {name}", { time: dateTime(r.createdAt), ago: ago(r.createdAt), name: esc(r.reporter) }) : t("Reported {time} ({ago})", { time: dateTime(r.createdAt), ago: ago(r.createdAt) })}</div>
        ${r.status === "resolved"
          ? `<div class="resolved-note">✓ ${t("Resolved {time}", { time: dateTime(r.resolvedAt) })}${r.resolution ? `: ${esc(r.resolution)}` : ""}</div>`
          : `<div class="actions"><input class="input" aria-label="${t("Resolution note (optional)")}" placeholder="${t("Resolution note (optional)")}" data-note="${esc(r.id)}" /><button class="btn btn-sm" data-resolve="${esc(r.id)}">${t("Mark resolved")}</button></div>`}
      </li>`,
        )
        .join("")
    : `<li class="empty">${filterVal() === "open" ? t("No open report.") : filterVal() === "resolved" ? t("No resolved report.") : t("No report.")}</li>`;
  if (location.hash) document.getElementById(location.hash.slice(1))?.classList.add("flash");
}
$("reports").addEventListener("click", async (e) => {
  const id = e.target.dataset?.resolve;
  if (!id) return;
  const note = document.querySelector(`[data-note="${CSS.escape(id)}"]`)?.value;
  await api(`/reports/${encodeURIComponent(id)}/resolve`, { method: "POST", json: { resolution: note || null } }).catch((err) => alert(err.message));
  loadReports();
});

showScope();
await loadTrains();
await prefill();
loadReports();
setInterval(loadReports, 15_000);
