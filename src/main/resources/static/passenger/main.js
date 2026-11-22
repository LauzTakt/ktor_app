// Passenger view (`/`). Look and feel: docs/STYLE.md. All train / station / incident data comes from /api/v1.
//
// Flow:
//   First visit (no QR / link): Tikt's tour (onboarding.js) over a welcome screen, then the location step. "?" replays it.
//   QR: `/?train=<trainId>` -> on tracks · `/?station=<stopId>` -> pick destination · `/?stop=<stopId>` -> station view
//   Location: train within 200 m -> "Are you on this train?" (Yes / No / other trains nearby)
//             else station within 200 m -> "Are you at this station?" (Yes / No)
//             else (or No) -> browsing: pick origin and destination by text or on the map
//   On a train ("on tracks"): 3D train on a track, the whole route with the ETA of every stop, incidents on top.
//   At a station: pick the destination (text or map) -> trains from here to there, live.
//   Alerts (push.js): on a train, "Notify me" -> arrival at the chosen stop + its incidents; bell in the top bar ->
//             your alerts + follow a whole line (incidents only); `/?line=<line>` -> line status.

import { $, publicApi as api } from "../common.js";
import * as push from "../push.js";
import { startTour, tourSeen } from "../onboarding.js";
import { ILLUSTRATION, buzz, ic, restartBtn, setUrl, show, toast, tripWatchAvailable } from "./ui.js";
import { geo, live } from "./location.js";
import { browseScreen, connectionsScreen, destinationScreen, detectScreen, onTracksScreen, stationScreen } from "./screens.js";
import { alerts, alertsScreen, lineScreen } from "./alerts.js";

// ------------------------------------------------------------------ boot
restartBtn.addEventListener("click", () => { buzz(); setUrl({}); geo.promise = null; detectScreen({ back: true }); });

live.connect();
push.init({ onForeground: (n) => { buzz([20, 40, 20]); toast(n.body ? `${n.title} · ${n.body}` : n.title, 6000); } });
$("#alerts-btn").innerHTML = ic("bell");
$("#alerts-btn").addEventListener("click", () => { buzz(); const back = location.search; alertsScreen(() => routeFromUrl(back, { back: true })); });
tripWatchAvailable.then((available) => {
  $(".watched-trips-link").hidden = !available;
});
$("#tour-btn").innerHTML = ic("help");
$("#tour-btn").addEventListener("click", () => { buzz(); startTour(); });

// First visit without a QR / shared link: Tikt's tour first, so the location prompt comes after it is explained.
if (!location.search && !tourSeen()) {
  welcomeScreen();
  startTour({ onDone: () => routeFromUrl(location.search) });
} else routeFromUrl(location.search);

/** Calm screen behind the first-run tour (replaced by the location step when the tour ends). */
function welcomeScreen() {
  show((s) => {
    s.innerHTML = `
      <div class="illus-box">${ILLUSTRATION.train}</div>
      <h1>Welcome aboard</h1>
      <p class="lead">Live trains, delays and incidents across Switzerland, for your trip.</p>`;
  }, { restart: false });
}

function routeFromUrl(search, opts = {}) {
const params = new URLSearchParams(search);
const qrTrain = params.get("train");
const qrStation = params.get("station");
const qrLine = params.get("line");
const qrStop = params.get("stop");
if (qrTrain) {
  const from = params.get("from");
  const to = params.get("to");
  onTracksScreen(qrTrain, from && to ? { fromStopId: from, toStopId: to } : null);
}
else if (qrStop) {
  api(`/stations/${encodeURIComponent(qrStop)}`)
    .then((d) => stationScreen({ stopId: d.station.stopId, name: d.station.name }))
    .catch(() => browseScreen({ note: "Station code not recognised. Search by station name." }));
}
else if (qrLine) lineScreen(qrLine, () => alertsScreen(() => detectScreen({ back: true })));
else if (qrStation) {
  api(`/stations/${encodeURIComponent(qrStation)}`).then((d) => {
    const from = { stopId: d.station.stopId, name: d.station.name };
    const to = params.get("to");
    if (to) api(`/stations/${encodeURIComponent(to)}`).then((x) => connectionsScreen(from, { stopId: x.station.stopId, name: x.station.name })).catch(() => destinationScreen(from));
    else destinationScreen(from);
  }).catch(() => browseScreen({ note: "Station code not recognised. Search by station name." }));
} else detectScreen(opts);
}
