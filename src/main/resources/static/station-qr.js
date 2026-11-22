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

function showStation(station) {
  cancelSearch();
  const passenger = new URL("/", location.origin);
  passenger.searchParams.set("station", station.stopId);
  const svg = qrSvg(passenger.href);

  $("stationQrName").textContent = station.name;
  const code = $("stationQrCode");
  code.innerHTML = svg;
  code.setAttribute("aria-label", `Station QR code for ${station.name}`);
  const download = $("stationQrDownload");
  download.href = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
  download.download = `station-${station.stopId}.svg`;
  $("stationQrPassenger").href = passenger.href;

  picker.hidden = true;
  preview.hidden = false;
  print.focus();
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
  showPicker();
  dialog.showModal();
  if (station) showStation(station);
  else input.focus();
}

toggle.addEventListener("click", () => openStationQr(toggle));
$("map").addEventListener("click", (event) => {
  const button = event.target.closest("button[data-station-qr]");
  if (button) openStationQr(button, { stopId: button.dataset.stationQr, name: button.dataset.stationName });
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
