// The screens of the passenger flow: detect, are-you-here questions, station, destination, browse, connections, on tracks.

import { $, esc, LATE_SEC, publicApi as api, reducedMotion, time as hhmm } from "../common.js";
import { lokiLoading } from "../loki.js";
import { ILLUSTRATION, NEAR_M, alertHtml, backBtn, buzz, clamp, distText, ic, labelView, lineLabel, onLeave, placeholders, restartBtn, setUrl, setView, show, stateHtml, timeHtml, toast, tripWatchAvailable, untilText } from "./ui.js";
import { geo, geoText, live, locate, nearbyStationsFor, nearbyTrainsFor } from "./location.js";
import { stationPicker } from "./stations.js";
import { alerts, followCard } from "./alerts.js";
import { trainGone, trainScene } from "./train-scene.js";

// ------------------------------------------------------------------ 1. detect (location)

export function detectScreen(opts = {}) {
  setView(null);
  show((s) => {
    s.innerHTML = `
      <div class="loki-hero">${lokiLoading({ caption: "Finding where you are…", sub: `I look for a train or a station within ${NEAR_M} m of you.`, mood: "think" })}</div>
      <div class="actions"><button class="btn block" id="skip">Just browsing</button></div>`;
    let alive = true;
    onLeave(() => (alive = false));
    $("#skip", s).onclick = () => { alive = false; browseScreen(); };
    (async () => {
      const minShow = new Promise((r) => setTimeout(r, 1200)); // let the illustration breathe, no longer
      const fix = await locate();
      let trains = fix ? (await nearbyTrainsFor(fix).catch(() => [])).filter((t) => t.distanceM <= NEAR_M) : [];
      // Several trains close by and no speed/heading yet: wait (≤ 3 s) for a second fix to tell them apart.
      if (trains.length > 1 && fix.headingDeg == null) {
        const refined = await Promise.race([geo.refined, new Promise((r) => setTimeout(() => r(null), 3000))]);
        if (refined?.headingDeg != null) trains = (await nearbyTrainsFor(refined).catch(() => trains)).filter((t) => t.distanceM <= NEAR_M);
      }
      await minShow;
      if (!alive) return;
      if (!fix) return browseScreen({ note: `${geoText()} Ask the travel assistant, or choose where you start and where you go.`, noLocation: true });
      if (trains.length) return askTrainScreen(trains);
      return askStationOrBrowse(fix);
    })();
  }, { ...opts, restart: false });
}

export async function askStationOrBrowse(fix) {
  const stations = fix ? await nearbyStationsFor(fix).catch(() => []) : [];
  if (stations.length) return askStationScreen(stations[0]);
  browseScreen({ note: "No train or station close to you. Choose where you start and where you go." });
}

// ------------------------------------------------------------------ 2a. are you on this train?

export function askTrainScreen(candidates) {
  setView(null);
  const [best, ...others] = candidates;
  show((s) => {
    const t = best.train;
    s.innerHTML = `
      <div class="illus-box">${ILLUSTRATION.train}</div>
      <h1>Are you on this train?</h1>
      <div class="card ask">
        <div class="head">${lineLabel(t, true)}<div class="main"><div class="t">to ${esc(t.headsign || t.destination?.name || "?")}</div>
          <div class="m">${esc(t.currentStop ? `At ${t.currentStop.name}` : t.nextStop ? `Next stop: ${t.nextStop.name}` : "")} · ${distText(best.distanceM)} from you</div></div></div>
        <div>${stateHtml(t)}</div>
      </div>
      <div class="pair"><button class="btn" id="no">No</button><button class="btn primary" id="yes">Yes</button></div>
      ${others.length ? `<h2>Other trains near you</h2><div class="list stagger">${others.map((o, i) => `
        <button class="row" type="button" data-id="${esc(o.train.trainId)}" style="--i:${i}">${lineLabel(o.train)}
          <span class="main"><span class="t">to ${esc(o.train.headsign || "?")}</span><span class="m">${distText(o.distanceM)} from you</span></span>
          <span class="chev">${ic("chevron")}</span></button>`).join("")}</div>` : ""}`;
    $("#yes", s).onclick = () => { buzz(15); onTracksScreen(t.trainId); };
    $("#no", s).onclick = () => { buzz(); askStationOrBrowse(geo.fix); };
    s.querySelectorAll("[data-id]").forEach((el) => (el.onclick = () => { buzz(15); onTracksScreen(el.dataset.id); }));
  });
}

// ------------------------------------------------------------------ 2b. are you at this station?

export function askStationScreen(near) {
  setView(null);
  show((s) => {
    s.innerHTML = `
      <div class="illus-box">${ILLUSTRATION.station}</div>
      <h1>Are you at ${esc(near.station.name)}?</h1>
      <p class="lead">${distText(near.distanceM)} from you · ${near.trainsUpcoming} train${near.trainsUpcoming === 1 ? "" : "s"} coming</p>
      <div class="pair"><button class="btn" id="no">No</button><button class="btn primary" id="yes">Yes</button></div>`;
    $("#yes", s).onclick = () => { buzz(15); destinationScreen(near.station); };
    $("#no", s).onclick = () => { buzz(); browseScreen(); };
  });
}

// ------------------------------------------------------------------ 3. at a station: where to?

export function destinationScreen(from) {
  setUrl({ station: from.stopId });
  labelView(from.name);
  show((s) => {
    s.innerHTML = `
      ${backBtn("Start again")}
      <div class="step-row"><span class="step">At ${esc(from.name)}</span><button class="link" type="button" id="info">Station info${ic("chevron")}</button></div>
      <div class="deps-head"><h2>Next departures</h2><span class="note" id="deps-note"></span></div>
      <div class="deps card" id="deps">${placeholders(3, "Looking for departures…")}</div>
      <h1>Where are you going?</h1>
      <div id="picker"></div>`;
    $("[data-back]", s).onclick = () => restartBtn.click();
    $("#info", s).onclick = () => { buzz(15); stationScreen(from, () => destinationScreen(from)); };
    departuresBoard($("#deps", s), $("#deps-note", s), from, { onTrain: (id) => onTracksScreen(id, null, () => destinationScreen(from)) });
    api(`/stations/${encodeURIComponent(from.stopId)}/destinations?limit=500`).catch(() => []).then((dests) => {
      const reachable = new Map(dests.map((d) => [d.station.stopId, { trains: d.trains, nextArrival: d.nextArrival }]));
      onLeave(stationPicker($("#picker", s), {
        placeholder: "Destination", reachable,
        onPick: (to) => { buzz(15); connectionsScreen(from, to); },
      }));
    });
  });
}

/**
 * Next departures at [from] (station screen, above the destination picker): time + delay, line, destination,
 * countdown, and a mark on trains with an incident. Tapping a row opens it: incidents, the next stops with their
 * ETA, and a link to the train. Live: refreshed every 20 s and right away when an incident changes.
 */
/**
 * [onTrain] (optional): open this train. [direct] = a tap on a row opens the train right away instead of expanding it.
 * [onIncident] (optional): called when an incident at the station changes (same WebSocket subscription).
 * [onEntries] (optional): called with the board entries after every refresh.
 */
export function departuresBoard(box, note, from, { onTrain = (id) => onTracksScreen(id), direct = false, onIncident = null, onEntries = null } = {}) {
  const SHOW = 5, MAX = 12;
  const open = new Set();          // expanded trainIds (kept across refreshes)
  const details = new Map();       // trainId -> { stops } (fetched on first expand)
  let entries = [], showAll = false, fails = 0;

  const countdown = (e) => {
    if (e.status === "cancelled") return '<span class="warn">Does not run</span>';
    if (e.atStation) return "<b>At the platform</b>";
    return untilText(e.time) === "now" ? "<b>Leaving now</b>" : esc(untilText(e.time));
  };
  const stopsHtml = (e) => {
    const d = details.get(e.trainId);
    if (!d) return '<div class="placeholder"></div>';
    const k = d.stops.findIndex((x) => x.stopId === from.stopId && x.state !== "passed");
    const next = d.stops.slice(k + 1).filter((x) => !x.skipped);
    if (!next.length) return "";
    const pick = next.length > 4 ? [...next.slice(0, 3), null, next.at(-1)] : next;
    return `<ol class="dep-stops">${pick.map((x) => x ? `<li><span class="n">${esc(x.name)}</span>
        <span class="tm">${timeHtml(x.plannedArr ?? x.plannedDep, x.expectedArr ?? x.expectedDep, e.status === "cancelled")}</span></li>`
      : `<li class="gap"><span class="n">${next.length - 4} more stop${next.length - 4 === 1 ? "" : "s"}</span></li>`).join("")}</ol>`;
  };

  const draw = () => {
    if (!entries.length) {
      box.innerHTML = `<p class="empty">No departures in the next 2 hours from trains already running.</p>`;
      return;
    }
    const list = showAll ? entries : entries.slice(0, SHOW);
    box.innerHTML = list.map((e) => {
      const n = e.incidents.length;
      const isOpen = open.has(e.trainId);
      const cancelled = e.status === "cancelled";
      return `<div class="dep${isOpen ? " open" : ""}${cancelled ? " cancelled" : ""}">
        <button class="dep-row" type="button" data-id="${esc(e.trainId)}"${direct ? "" : ` aria-expanded="${isOpen}"`}>
          <span class="dep-time">${timeHtml(e.plannedTime, e.time, cancelled)}</span>
          <span class="dep-main">
            <span class="dep-l1">${lineLabel(e)}<span class="h">${esc(e.headsign || e.destination?.name || "?")}</span></span>
            <span class="dep-l2">${countdown(e)}${n ? ` · <span class="inc"><span class="dot"></span>${n} incident${n === 1 ? "" : "s"}</span>` : ""}</span>
          </span>
          ${ic("chevron", "chev")}
        </button>
        ${isOpen && !direct ? `<div class="dep-more">
          ${n ? `<div class="list">${e.incidents.map(alertHtml).join("")}</div>` : ""}
          ${stopsHtml(e)}
          <button class="btn" type="button" data-train="${esc(e.trainId)}">Follow this train</button>
        </div>` : ""}
      </div>`;
    }).join("") + (entries.length > SHOW
      ? `<button class="dep-all" type="button">${showAll ? "Fewer departures" : `${entries.length - SHOW} more departure${entries.length - SHOW === 1 ? "" : "s"}`}</button>` : "");

    box.querySelectorAll(".dep-row").forEach((b) => (b.onclick = () => {
      const id = b.dataset.id;
      if (direct) { buzz(15); onTrain(id); return; }
      buzz(8);
      if (open.has(id)) open.delete(id); else {
        open.add(id);
        if (!details.has(id)) api(`/trains/${encodeURIComponent(id)}/stops`).then((stops) => { details.set(id, { stops }); draw(); }).catch(() => {});
      }
      draw();
    }));
    box.querySelectorAll("[data-train]").forEach((b) => (b.onclick = () => { buzz(15); onTrain(b.dataset.train); }));
    const all = $(".dep-all", box);
    if (all) all.onclick = () => { showAll = !showAll; draw(); };
  };

  const refresh = () => api(`/stations/${encodeURIComponent(from.stopId)}/board?type=departures&windowMin=120&limit=${MAX}&includeSkipped=false`)
    .then((b) => {
      fails = 0;
      entries = b.entries;
      onEntries?.(entries);
      const late = entries.filter((e) => (e.delaySec ?? 0) >= LATE_SEC).length;
      const inc = entries.filter((e) => e.incidents.length).length;
      note.textContent = [late && `${late} late`, inc && `${inc} with incidents`].filter(Boolean).join(" · ");
      details.clear();     // stop times change: refetch for the open rows
      open.forEach((id) => api(`/trains/${encodeURIComponent(id)}/stops`).then((stops) => { details.set(id, { stops }); draw(); }).catch(() => {}));
      draw();
    })
    .catch(() => { if (++fails === 1 && !entries.length) box.innerHTML = '<p class="empty">Departures could not be loaded. Retrying…</p>'; });
  refresh();
  const poll = setInterval(refresh, 20_000);
  const clock = setInterval(draw, 15_000);       // countdowns
  onLeave(() => { clearInterval(poll); clearInterval(clock); });
  onLeave(live.subscribe("station", from.stopId, { frequencySec: 3600, windowMin: 120 }, (m) => {
    if (m.type === "incident" && m.event !== "active") { refresh(); onIncident?.(m); }
  }));
}

// ------------------------------------------------------------------ 3a. station view: next departures + incidents

/**
 * Station info: incidents at the station (collapsible, only when there are some) and the next departures. Tap a
 * departure to open the train. [onBack] returns to the previous screen (default: start again).
 */
export function stationScreen(st, onBack = null) {
  setUrl({ stop: st.stopId });
  labelView(st.name);
  const self = () => stationScreen(st, onBack);
  show((s) => {
    s.innerHTML = `
      ${backBtn(onBack ? "Back" : "Start again")}
      <div class="step">Station</div>
      <h1>${esc(st.name)}</h1>
      <p class="lead" id="ssum">&nbsp;</p>
      <details class="incidents card" id="sinc" hidden>
        <summary><span class="dot"></span><span class="t" id="sinc-t"></span>${ic("chevron", "chev")}</summary>
        <div class="list" id="sinc-list"></div>
      </details>
      <div class="deps-head"><h2>Next departures</h2><span class="note" id="deps-note"></span></div>
      <div class="deps card" id="deps">${placeholders(4, "Looking for departures…")}</div>
      <div class="actions"><button class="btn primary block" id="plan">Plan a trip from here</button></div>`;
    $("[data-back]", s).onclick = () => (onBack ? onBack() : restartBtn.click());
    $("#plan", s).onclick = () => { buzz(15); destinationScreen(st); };

    // Incidents = those at the station + those of the trains leaving from it (one row each, worst first).
    const rank = { critical: 0, major: 1, minor: 2, info: 3 };
    let detail = null, trainInc = [];
    const draw = () => {
      const d = detail;
      if (!d) return;
      st = { stopId: d.station.stopId, name: d.station.name };
      const all = new Map([...d.incidents, ...trainInc].map((i) => [i.id, i]));
      const inc = [...all.values()].sort((a, b) => (rank[a.severity] ?? 4) - (rank[b.severity] ?? 4));
      const n = inc.length;
      $("#ssum", s).innerHTML = [
        d.trainsPresent ? `<b>${d.trainsPresent}</b> train${d.trainsPresent === 1 ? "" : "s"} at the station` : "",
        `<b>${d.trainsUpcoming}</b> train${d.trainsUpcoming === 1 ? "" : "s"} coming`,
        n ? "" : "No incidents",
      ].filter(Boolean).join(" · ");
      const box = $("#sinc", s);
      box.hidden = !n;
      if (!n) return;
      box.className = `incidents card sev-${esc(inc[0].severity || "info")}`;
      $("#sinc-t", s).textContent = `${n} incident${n === 1 ? "" : "s"} at this station or its trains`;
      $("#sinc-list", s).innerHTML = inc.map(alertHtml).join("");
    };
    const refresh = () => api(`/stations/${encodeURIComponent(st.stopId)}`).then((d) => { detail = d; draw(); }).catch((e) => {
      if (e.status === 404) $("#ssum", s).textContent = "This station is not known.";
    });
    refresh();
    const poll = setInterval(refresh, 30_000);
    onLeave(() => clearInterval(poll));
    departuresBoard($("#deps", s), $("#deps-note", s), st, {
      direct: true,
      onTrain: (id) => onTracksScreen(id, null, self),
      onIncident: (m) => { if (m.event === "created") { buzz([20, 40, 20]); toast(m.incident.title); } refresh(); },
      onEntries: (entries) => { trainInc = entries.flatMap((e) => e.incidents); draw(); },
    });
  });
}

/** Search a station by name or on the map, then open its station view. */
export function stationSearchScreen(onBack) {
  setUrl({});
  show((s) => {
    s.innerHTML = `
      ${backBtn()}
      <h1>Station departures</h1>
      <p class="lead">Find a station to see its next departures and incidents.</p>
      <div id="picker"></div>`;
    $("[data-back]", s).onclick = () => onBack();
    onLeave(stationPicker($("#picker", s), {
      placeholder: "Station",
      onPick: (st) => { buzz(15); stationScreen(st, () => stationSearchScreen(onBack)); },
    }));
    setTimeout(() => $("#picker input", s)?.focus({ preventScroll: true }), 250);
  });
}

// ------------------------------------------------------------------ 3b. just browsing: origin + destination

/**
 * The travel assistant card is always on top. [noLocation]: we could not locate the user, so it is the main action
 * (red button, no picker opened at once).
 */
export function browseScreen({ note = null, back = false, noLocation = false } = {}) {
  setView(null);
  const pick = { from: null, to: null };
  const hint = { from: "Where do you start?", to: "Where do you go?" };
  show((s) => {
    const field$ = (f) => `<label class="od-field" data-f="${f}"><span class="k">${f === "from" ? "From" : "To"}</span>
      <input class="v" type="text" placeholder="${hint[f]}" aria-label="${hint[f]}" autocomplete="off" enterkeyhint="search" /></label>`;
    s.innerHTML = `
      <div class="illus-box small">${ILLUSTRATION.search}</div>
      <h1>Plan a trip</h1>
      <p class="lead">${esc(note || "Choose where you start and where you go.")}</p>
      <div class="assistant-card card">
        <span class="fic">${ic("chat")}</span>
        <div class="main"><div class="t">Ask the travel assistant</div>
          <div class="m">Tell it where you are and where you go, by voice or chat. It finds your train.</div></div>
        <button class="btn${noLocation ? " primary" : ""} block" type="button" id="ask">Ask the assistant</button>
      </div>
      <h2>Or plan it yourself</h2>
      <div class="od card">
        ${field$("from")}
        <button class="icon-btn swap" type="button" title="Swap" aria-label="Swap">${ic("swap")}</button>
        ${field$("to")}
      </div>
      <button class="link" type="button" id="board">Departures and incidents at a station${ic("chevron")}</button>
      <div id="picker"></div>
      <div class="actions"><button class="btn${noLocation ? "" : " primary"} block" id="go" disabled>Show trains</button></div>`;
    const inputOf = (f) => $(`.od-field[data-f="${f}"] input`, s);
    let field = "from";
    let close = null;
    // syncText: write the picked station names into the fields (not while the user is typing).
    const render = (syncText = false) => {
      s.querySelectorAll(".od-field").forEach((el) => {
        const f = el.dataset.f, inp = $("input", el);
        el.classList.toggle("active", f === field && !!close);
        if (syncText) inp.value = pick[f]?.name ?? "";
        inp.classList.toggle("set", !!pick[f] && inp.value === pick[f].name);
      });
      $("#go", s).disabled = !(pick.from && pick.to && pick.from.stopId !== pick.to.stopId);
    };
    const closePicker = () => { close?.(); close = null; $("#picker", s).innerHTML = ""; render(); };
    const openPicker = async () => {
      close?.();
      close = () => {}; // marks the picker as open while `reachable` loads
      const f = field;
      const reachable = f === "to" && pick.from
        ? new Map((await api(`/stations/${encodeURIComponent(pick.from.stopId)}/destinations?limit=500`).catch(() => [])).map((d) => [d.station.stopId, d]))
        : null;
      if (f !== field) return; // the user moved to the other field meanwhile
      close = stationPicker($("#picker", s), {
        input: inputOf(f), reachable,
        onPick: (st) => {
          buzz();
          pick[f] = st;
          render(true);
          const next = !pick.from ? "from" : !pick.to ? "to" : null;
          if (next) { field = next; render(); inputOf(next).focus({ preventScroll: true }); openPicker(); }
          else { inputOf(f).blur(); closePicker(); }
        },
      });
      render();
    };
    onLeave(() => close?.());
    s.querySelectorAll(".od-field").forEach((el) => {
      const f = el.dataset.f, inp = $("input", el);
      inp.addEventListener("focus", () => {
        if (field === f && close) return;
        field = f;
        inp.select();
        openPicker();
      });
      inp.addEventListener("input", () => { pick[f] = null; render(); });
    });
    $(".swap", s).onclick = () => {
      [pick.from, pick.to] = [pick.to, pick.from];
      render(true);
      if (close) openPicker(); // the destination list depends on the origin
    };
    $("#go", s).onclick = () => { buzz(15); connectionsScreen(pick.from, pick.to); };
    $("#board", s).onclick = () => { buzz(15); stationSearchScreen(() => browseScreen({ note, back: true, noLocation })); };
    $("#ask", s).onclick = () => {
      buzz(15);
      const fab = $("#assistant-fab");
      if (fab?.getAttribute("aria-expanded") !== "true") fab?.click();
    };
    render();
    // Without location the assistant comes first; on a phone the keyboard would cover the assistant card.
    if (!noLocation && !matchMedia("(pointer: coarse)").matches) {
      openPicker();
      setTimeout(() => inputOf("from")?.focus({ preventScroll: true }), 250);
    }
  }, { back });
}

// ------------------------------------------------------------------ 4. trains from A to B (live)

export function connectionsScreen(from, to) {
  setUrl({ station: from.stopId, to: to.stopId });
  labelView(`${from.name} to ${to.name}`);
  show((s) => {
    s.innerHTML = `
      ${backBtn()}
      <div class="step">From ${esc(from.name)}</div>
      <h1>Trains to ${esc(to.name)}</h1>
      <div id="alerts" class="list"></div>
      <div class="list" id="list">${placeholders(3, "Finding trains for you…")}</div>
      <p class="note">Live data. Only trains already in service are shown.</p>`;
    $("[data-back]", s).onclick = () => destinationScreen(from);
    const list = $("#list", s);
    let firstDraw = true;
    const draw = (data) => {
      const alerts = new Map();
      data.entries.forEach((c) => c.incidents.forEach((i) => alerts.set(i.id, i)));
      $("#alerts", s).innerHTML = [...alerts.values()].map(alertHtml).join("");
      if (!data.entries.length) {
        list.innerHTML = `<p class="empty">No train running now goes to ${esc(to.name)} from here. Trains are shown once they start their run.</p>`;
        return;
      }
      list.classList.toggle("stagger", firstDraw);
      firstDraw = false;
      list.innerHTML = data.entries.map((c, i) => {
        const t = c.train;
        return `<button class="row conn" type="button" data-id="${esc(t.trainId)}" style="--i:${Math.min(i, 10)}">
          <div class="dep">${timeHtml(c.plannedDepartureTime, c.departureTime, t.status === "cancelled")}<small data-until="${c.departureTime || ""}">${c.atStation ? "Now at the station" : untilText(c.departureTime)}</small></div>
          <div class="l1">${lineLabel(t)}<span class="h">to ${esc(t.headsign || "?")}</span></div>
          <div class="r">${stateHtml(t)}</div>
          <div class="l2">Arrival ${hhmm(c.arrivalTime)}${c.durationMin != null ? ` · ${c.durationMin} min` : ""} · ${c.stopsBetween ? `${c.stopsBetween} stop${c.stopsBetween > 1 ? "s" : ""}` : "Direct"}${c.incidents.length ? ` · <span class="warn">${c.incidents.length} incident${c.incidents.length > 1 ? "s" : ""}</span>` : ""}</div>
        </button>`;
      }).join("");
      list.querySelectorAll(".conn").forEach((el) => (el.onclick = () => {
        buzz(15);
        onTracksScreen(el.dataset.id, { fromStopId: from.stopId, toStopId: to.stopId }, () => connectionsScreen(from, to));
      }));
    };
    const refresh = () => api(`/stations/${encodeURIComponent(from.stopId)}/connections?to=${encodeURIComponent(to.stopId)}&windowMin=240&limit=12`)
      .then(draw).catch(() => toast("Connection problem. Retrying…"));
    refresh();
    const poll = setInterval(refresh, 20_000);
    const clock = setInterval(() => list.querySelectorAll("[data-until]").forEach((el) => {
      if (el.dataset.until && !el.textContent.startsWith("Now")) el.textContent = untilText(+el.dataset.until);
    }), 5_000);
    onLeave(() => { clearInterval(poll); clearInterval(clock); });
    onLeave(live.subscribe("station", from.stopId, { frequencySec: 3600, windowMin: 30 }, (m) => {
      if (m.type === "incident" && m.event !== "active") { buzz([20, 40, 20]); toast(m.incident.title); refresh(); }
    }));
  });
}

// ------------------------------------------------------------------ 5. on tracks (on a train)

export function onTracksScreen(trainId, trip = null, onBack = null) {
  setUrl(trip ? { train: trainId, from: trip.fromStopId, to: trip.toStopId } : { train: trainId });
  const self = () => onTracksScreen(trainId, trip, onBack);
  show((s) => {
    s.innerHTML = `
      ${onBack ? backBtn() : ""}
      <div id="alerts" class="list"></div>
      <div class="scene card"><canvas id="scene" aria-hidden="true"></canvas>
        <div class="scene-cap"><span id="line"></span><div class="main"><div class="t" id="to"></div><div class="m" id="where"></div></div><span id="state"></span></div>
      </div>
      <div class="nextstop card" id="next"></div>
      <div class="follow card" id="follow"></div>
      ${trip ? '<a id="watch-train" class="btn block watch-entry" hidden>Watch this trip</a>' : ''}
      <h2>Route</h2>
      <div class="route" id="route">${placeholders(4, "Loading the route…")}</div>
      <p class="note">Tap a stop to see its departures and incidents.</p>`;
    if (onBack) $("[data-back]", s).onclick = () => onBack();
    $("#route", s).addEventListener("click", (e) => {
      const b = e.target.closest("[data-stop]");
      if (b) { buzz(15); stationScreen({ stopId: b.dataset.stop, name: b.dataset.name }, self); }
    });
    if (trip) tripWatchAvailable.then((available) => {
      const link = $("#watch-train", s);
      if (!available || !link?.isConnected) return;
      link.href = `/trip-watch.html?${new URLSearchParams({ trainId, ...trip })}`;
      link.hidden = false;
    });
    const incidents = new Map();
    let last = null;
    const scene = trainScene($("#scene", s));
    onLeave(() => scene.then((x) => x?.dispose()));

    const drawAlerts = () => { $("#alerts", s).innerHTML = [...incidents.values()].map(alertHtml).join(""); };
    const follow = followCard($("#follow", s), () => last && { trainId, train: last.train, stops: last.stops });

    const draw = (train, stops) => {
      last = { train, stops };
      const cancelled = train.status === "cancelled";
      $("#line", s).innerHTML = lineLabel(train, true);
      $("#to", s).textContent = `to ${train.headsign || train.destination?.name || "?"}`;
      if (window.twinView?.trainId === trainId) labelView(`${train.line || train.category || "Train"} to ${train.headsign || train.destination?.name || "?"}`);
      $("#state", s).innerHTML = stateHtml(train);
      const standing = train.position?.motion === "stopped" && train.position?.atStopId;
      scene.then((x) => x?.update({ speedKmh: standing ? 0 : train.position?.speedKmh ?? 0, atStation: standing ? (train.currentStop?.name || " ") : null }));

      const k = stops.findIndex((x) => x.state === "current" || x.state === "next");
      const cur = k >= 0 ? stops[k] : null;
      const prev = k > 0 ? stops[k - 1] : null;
      const where = !cur ? "Run completed"
        : cur.state === "current" ? `At ${cur.name}` : prev ? `Between ${prev.name} and ${cur.name}` : `Before ${cur.name}`;
      const whereEl = $("#where", s);
      if (whereEl.textContent !== where) {
        const changed = !!whereEl.textContent;
        whereEl.textContent = where;
        if (changed && !reducedMotion()) { whereEl.classList.remove("swap"); void whereEl.offsetWidth; whereEl.classList.add("swap"); }
      }

      // Next stop + ETA
      const next = cur && cur.state === "current" ? stops[k + 1] : cur;
      $("#next", s).innerHTML = next
        ? `<div class="k">Next stop</div><div class="nrow"><b>${esc(next.name)}</b><span class="eta-big">${untilText(next.expectedArr ?? next.plannedArr) || "–"}</span></div>
           <div class="m">${timeHtml(next.plannedArr ?? next.plannedDep, next.expectedArr ?? next.expectedDep, cancelled)}</div>`
        : `<div class="k">This train has reached ${esc(train.destination?.name || "its destination")}</div>`;

      // Whole route; the train sits between the last stop left and the next one.
      let progress = 0.5;
      if (cur && prev && cur.state === "next") {
        const dep = prev.expectedDep ?? prev.plannedDep, arr = cur.expectedArr ?? cur.plannedArr;
        if (dep && arr && arr > dep) progress = clamp((Date.now() - dep) / (arr - dep), 0.05, 0.95);
      }
      const html = stops.map((x, i) => {
        const t = x.expectedArr ?? x.expectedDep ?? x.plannedArr ?? x.plannedDep;
        const cls = `stop ${x.state}${i === 0 ? " first" : ""}${i === stops.length - 1 ? " last" : ""}${x.skipped ? " skipped" : ""}`;
        const between = i === k && x.state === "next" && prev
          ? `<div class="between"><span class="pin-train" style="--p:${progress}">${ic("train")}</span></div>` : "";
        return `${between}<button class="${cls}" type="button" data-stop="${esc(x.stopId)}" data-name="${esc(x.name)}">
          <span class="n">${esc(x.name)}${x.skipped ? ' <span class="nostop">Does not stop</span>' : ""}</span>
          <span class="tm"><span>${x.skipped ? "" : timeHtml(x.plannedArr ?? x.plannedDep, x.expectedArr ?? x.expectedDep, cancelled)}</span>
            ${x.state === "passed" || x.skipped ? "" : `<small>${x.state === "current" ? "Now" : untilText(t)}</small>`}</span></button>`;
      }).join("");
      const box = $("#route", s);
      if (box.dataset.sig !== html) {
        const first = !box.dataset.sig;
        box.dataset.sig = html;
        box.innerHTML = html;
        const here = box.querySelector(".between, .stop.current");
        if (first && here) box.scrollTo({ top: here.offsetTop - box.clientHeight / 2, behavior: reducedMotion() ? "auto" : "smooth" });
      }
    };

    api(`/trains/${encodeURIComponent(trainId)}`).then((d) => {
      d.incidents.forEach((i) => incidents.set(i.id, i));
      drawAlerts();
      draw(d.train, d.stops);
      follow.refresh();
    }).catch((e) => { if (e.status === 404) trainGone(s); });

    onLeave(live.subscribe("train", trainId, { frequencySec: 3 }, (m) => {
      if (m.type === "train_update") draw(m.data.train, m.data.stops);
      else if (m.type === "incident") {
        if (m.event === "removed" || m.event === "missing") incidents.delete(m.incident.id);
        else {
          if (!incidents.has(m.incident.id) && m.event !== "active") { buzz([20, 40, 20]); toast(m.incident.title); }
          incidents.set(m.incident.id, m.incident);
        }
        drawAlerts();
      } else if (m.type === "train_ended") trainGone(s);
    }));
    const tick = setInterval(() => last && draw(last.train, last.stops), 15_000);
    onLeave(() => clearInterval(tick));
  });
}
