import { api } from "./common.js";
import { qrcodegen } from "./vendor/qrcodegen-v1.8.0.js";

const $ = (id) => document.getElementById(id);
const dialog = $("stationQrDialog");
const toggle = $("stationQrToggle");
const picker = $("stationQrPicker");
const input = $("stationQrSearch");
const results = $("stationQrResults");
const status = $("stationQrStatus");
const preview = $("stationQrPreview");
const print = $("stationQrPrint");
let trigger;
let searchTimer;
let search;

function cancelSearch() {
  clearTimeout(searchTimer);
  search?.abort();
}

function showPicker() {
  preview.hidden = true;
  picker.hidden = false;
}

function qrSvg(url) {
  const qr = qrcodegen.QrCode.encodeText(url, qrcodegen.QrCode.Ecc.MEDIUM);
  const border = 4; // Keep the four-module quiet zone around the printed code.
  const size = qr.size + 2 * border;
  let path = "";
  for (let y = 0; y < qr.size; y++) {
    for (let x = 0; x < qr.size; x++) {
      if (qr.getModule(x, y)) path += `M${x + border} ${y + border}h1v1h-1z`;
    }
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${size} ${size}" shape-rendering="crispEdges" aria-hidden="true">
    <rect width="${size}" height="${size}" fill="#fff"/>
    <path d="${path}" fill="#000"/>
  </svg>`;
}

/** Fill the printable sign. `param`/`value` go into the passenger link (`/?station=…` or `/?train=…`). */
function showSign({ kind, param, value, title, lead, note, label, file }) {
  cancelSearch();
  const passenger = new URL("/", location.origin);
  passenger.searchParams.set(param, value);
  const svg = qrSvg(passenger.href);

  $("stationQrTitle").textContent = `${kind} QR`;
  $("stationQrName").textContent = title;
  $("stationQrLead").textContent = lead;
  const noteEl = $("stationQrNote");
  noteEl.textContent = note ?? "";
  noteEl.hidden = !note;
  const code = $("stationQrCode");
  code.innerHTML = svg;
  code.setAttribute("aria-label", label);
  const download = $("stationQrDownload");
  download.href = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
  download.download = file;
  $("stationQrPassenger").href = passenger.href;
  // A train sign comes from the selected train: there is no train search to go back to.
  $("stationQrChange").hidden = kind !== "Station";

  picker.hidden = true;
  preview.hidden = false;
  print.focus();
}

function showStation(station) {
  showSign({
    kind: "Station",
    param: "station",
    value: station.stopId,
    title: station.name,
    lead: "Scan for travel updates from here",
    label: `Station QR code for ${station.name}`,
    file: `station-${station.stopId}.svg`,
  });
}

/** trainId = `{YYYYMMDD}_{trip}`: one run on one day, so the sign says which day it works for. */
function runDay(trainId) {
  const m = /^(\d{4})(\d{2})(\d{2})_/.exec(trainId);
  return m ? `${m[3]}.${m[2]}.${m[1]}` : null;
}

function showTrain(train) {
  const name = train.name || "Train";
  const day = runDay(train.trainId);
  showSign({
    kind: "Train",
    param: "train",
    value: train.trainId,
    title: train.to ? `${name} → ${train.to}` : name,
    lead: "Scan for live updates on this train",
    note: day ? `Valid for this run on ${day}` : null,
    label: `Train QR code for ${name}${train.to ? ` to ${train.to}` : ""}`,
    file: `train-${train.trainId.replace(/[^\w.-]+/g, "_")}.svg`,
  });
}

function stationRow(station) {
  const row = document.createElement("li");
  const choice = document.createElement("button");
  choice.type = "button";
  choice.className = "station-qr__choice";
  const name = document.createElement("span");
  name.textContent = station.name;
  const id = document.createElement("span");
  id.className = "muted";
  id.textContent = station.stopId;
  choice.append(name, id);
  choice.addEventListener("click", () => showStation(station));
  row.append(choice);
  return row;
}

input.addEventListener("input", () => {
  cancelSearch();
  results.replaceChildren();
  status.textContent = "";
  const query = input.value.trim();
  if (!query) return;
  searchTimer = setTimeout(async () => {
    search = new AbortController();
    const { signal } = search;
    status.textContent = "Searching…";
    try {
      const stations = await api(`/stations?q=${encodeURIComponent(query)}&limit=8`, { signal });
      if (signal.aborted) return;
      results.replaceChildren(...stations.map(stationRow));
      status.textContent = stations.length ? "" : "No stations found.";
    } catch {
      if (!signal.aborted) status.textContent = "Couldn’t load stations. Try searching again.";
    }
  }, 150);
});

picker.addEventListener("keydown", (event) => {
  if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
  const choices = [input, ...results.querySelectorAll("button")];
  const index = choices.indexOf(document.activeElement);
  const next = choices[index + (event.key === "ArrowDown" ? 1 : -1)];
  if (next) {
    event.preventDefault();
    next.focus();
  }
});

function openStationQr(button, station) {
  trigger = button;
  input.value = "";
  status.textContent = "";
  results.replaceChildren();
  $("stationQrTitle").textContent = "Station QR";
  showPicker();
  dialog.showModal();
  if (station) showStation(station);
  else input.focus();
}

function openTrainQr(button, train) {
  trigger = button;
  dialog.showModal();
  showTrain(train);
}

toggle.addEventListener("click", () => openStationQr(toggle));
$("map").addEventListener("click", (event) => {
  const button = event.target.closest("button[data-station-qr]");
  if (button) openStationQr(button, { stopId: button.dataset.stationQr, name: button.dataset.stationName });
});
// The train panel is re-rendered every 10 s, so listen on the panel, not the button.
$("trainPanel").addEventListener("click", (event) => {
  const button = event.target.closest("button[data-train-qr]");
  if (button) openTrainQr(button, { trainId: button.dataset.trainQr, name: button.dataset.trainName, to: button.dataset.trainTo });
});
$("stationQrClose").addEventListener("click", () => dialog.close());
dialog.addEventListener("click", (event) => {
  if (event.target === dialog) dialog.close();
});
dialog.addEventListener("close", () => {
  if (dialog.open) return;
  cancelSearch();
  (trigger?.isConnected ? trigger : toggle).focus();
});
$("stationQrChange").addEventListener("click", () => {
  showPicker();
  input.focus();
  input.select();
});
print.addEventListener("click", () => window.print());
