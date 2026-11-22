// The screens of the passenger flow: detect, are-you-here questions, station, destination, browse, connections, on tracks.

import { $, esc, LATE_SEC, publicApi as api, reducedMotion, t, time as hhmm } from "../common.js";
import { tiktLoading } from "../tikt.js";
import { ILLUSTRATION, NEAR_M, alertHtml, askAssistant, backBtn, buzz, clamp, distText, drawIncidents, ic, labelView, lineLabel, onLeave, placeholders, restartBtn, setUrl, setView, show, stateHtml, timeHtml, toast, googleWalletAvailable, tripWatchAvailable, untilText } from "./ui.js";
import { geo, geoText, live, locate, nearbyStationsFor, nearbyTrainsFor } from "./location.js";
import { stationPicker } from "./stations.js";
import { alerts, followCard } from "./alerts.js";
import { trainGone, trainScene } from "./train-scene.js";
import { pathHtml, pushLegs, saveTrip, takeText, tripFromRoute } from "./trip.js";
import * as push from "../push.js";

// ------------------------------------------------------------------ 1. detect (location)

export function detectScreen(opts = {}) {
  setView(null);
  show((s) => {
    s.innerHTML = `
      <div class="tikt-hero">${tiktLoading({ caption: t("Finding where you are…"), sub: t("I look for a train or a station within {m} m of you.", { m: NEAR_M }), mood: "think" })}</div>
      <div class="actions"><button class="btn btn-block" id="skip">${t("Just browsing")}</button></div>`;
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
      if (!fix) return browseScreen({ note: geoText(), noLocation: true });
      if (trains.length) return askTrainScreen(trains);
      return askStationOrBrowse(fix);
    })();
  }, { ...opts, restart: false });
}

export async function askStationOrBrowse(fix) {
  const stations = fix ? await nearbyStationsFor(fix).catch(() => []) : [];
  if (stations.length) return askStationScreen(stations[0]);
  browseScreen({ note: t("No train or station nearby.") });
}

// ------------------------------------------------------------------ 2a. are you on this train?

export function askTrainScreen(candidates) {
  setView(null);
  const [best, ...others] = candidates;
  show((s) => {
    const tr = best.train;
    s.innerHTML = `
      <div class="illus-box">${ILLUSTRATION.train}</div>
      <h1>${t("Are you on this train?")}</h1>
      <div class="card ask">
        <div class="head">${lineLabel(tr, true)}<div class="main"><div class="t">${t("to {dest}", { dest: esc(tr.headsign || tr.destination?.name || "?") })}</div>
          <div class="m">${esc(tr.currentStop ? t("At {stop}", { stop: tr.currentStop.name }) : tr.nextStop ? t("Next stop: {stop}", { stop: tr.nextStop.name }) : "")} · ${t("{distance} from you", { distance: distText(best.distanceM) })}</div></div></div>
        <div>${stateHtml(tr)}</div>
      </div>
      <div class="pair"><button class="btn" id="no">${t("No")}</button><button class="btn btn-primary" id="yes">${t("Yes")}</button></div>
      ${others.length ? `<h2>${t("Other trains near you")}</h2><div class="list stagger">${others.map((o, i) => `
        <button class="row" type="button" data-id="${esc(o.train.trainId)}" style="--i:${i}">${lineLabel(o.train)}
          <span class="main"><span class="t">${t("to {dest}", { dest: esc(o.train.headsign || "?") })}</span><span class="m">${t("{distance} from you", { distance: distText(o.distanceM) })}</span></span>
          <span class="chev">${ic("chevron")}</span></button>`).join("")}</div>` : ""}`;
    $("#yes", s).onclick = () => { buzz(15); onTracksScreen(tr.trainId); };
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
      <h1>${t("Are you at {station}?", { station: esc(near.station.name) })}</h1>
      <p class="lead">${t("{distance} from you", { distance: distText(near.distanceM) })} · ${near.trainsUpcoming === 1 ? t("1 train coming") : t("{n} trains coming", { n: near.trainsUpcoming })}</p>
      <div class="pair"><button class="btn" id="no">${t("No")}</button><button class="btn btn-primary" id="yes">${t("Yes")}</button></div>`;
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
      <div class="step-row"><span class="step">${t("At {stop}", { stop: esc(from.name) })}</span><button class="link" type="button" id="info">${t("Station info")}${ic("chevron")}</button></div>
      <div class="deps-head"><h2>${t("Next departures")}</h2><span class="note" id="deps-note"></span></div>
      <div class="deps card" id="deps">${placeholders(3, t("Looking for departures…"))}</div>
      <h1>${t("Where are you going?")}</h1>
      <div id="picker"></div>`;
    $("#info", s).onclick = () => { buzz(15); stationScreen(from, () => destinationScreen(from)); };
    departuresBoard($("#deps", s), $("#deps-note", s), from, { onTrain: (id) => onTracksScreen(id, null, () => destinationScreen(from)) });
    api(`/stations/${encodeURIComponent(from.stopId)}/destinations?limit=500`).catch(() => []).then((dests) => {
      const reachable = new Map(dests.map((d) => [d.station.stopId, { trains: d.trains, nextArrival: d.nextArrival }]));
      onLeave(stationPicker($("#picker", s), {
        placeholder: t("Destination"), reachable,
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
    if (e.status === "cancelled") return `<span class="warn">${t("Does not run")}</span>`;
    if (e.atStation) return `<b>${t("At the platform")}</b>`;
    return e.time && e.time - Date.now() < 45_000 ? `<b>${t("Leaving now")}</b>` : esc(untilText(e.time));
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
      : `<li class="gap"><span class="n">${next.length - 4 === 1 ? t("1 more stop") : t("{n} more stops", { n: next.length - 4 })}</span></li>`).join("")}</ol>`;
  };

  const draw = () => {
    if (!entries.length) {
      box.innerHTML = `<p class="empty">${t("No departures in the next 2 hours from trains already running.")}</p>`;
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
            <span class="dep-l2">${countdown(e)}${n ? ` · <span class="inc"><span class="dot"></span>${n === 1 ? t("1 incident") : t("{n} incidents", { n })}</span>` : ""}</span>
          </span>
          ${ic("chevron", "chev")}
        </button>
        ${isOpen && !direct ? `<div class="dep-more">
          ${n ? `<div class="list">${e.incidents.map(alertHtml).join("")}</div>` : ""}
          ${stopsHtml(e)}
          <button class="btn" type="button" data-train="${esc(e.trainId)}">${t("Follow this train")}</button>
        </div>` : ""}
      </div>`;
    }).join("") + (entries.length > SHOW
      ? `<button class="dep-all" type="button">${showAll ? t("Fewer departures") : entries.length - SHOW === 1 ? t("1 more departure") : t("{n} more departures", { n: entries.length - SHOW })}</button>` : "");

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
      note.textContent = [late && t("{n} late", { n: late }), inc && t("{n} with incidents", { n: inc })].filter(Boolean).join(" · ");
      details.clear();     // stop times change: refetch for the open rows
      open.forEach((id) => api(`/trains/${encodeURIComponent(id)}/stops`).then((stops) => { details.set(id, { stops }); draw(); }).catch(() => {}));
      draw();
    })
    .catch(() => { if (++fails === 1 && !entries.length) box.innerHTML = `<p class="empty">${t("Departures could not be loaded. Retrying…")}</p>`; });
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
      ${onBack ? backBtn() : ""}
      <div class="step">${t("Station")}</div>
      <h1>${esc(st.name)}</h1>
      <p class="lead" id="ssum">&nbsp;</p>
      <details class="incidents card" id="sinc" hidden>
        <summary><span class="dot"></span><span class="t" id="sinc-t"></span>${ic("chevron", "chev")}</summary>
        <div class="list" id="sinc-list"></div>
      </details>
      <div class="deps-head"><h2>${t("Next departures")}</h2><span class="note" id="deps-note"></span></div>
      <div class="deps card" id="deps">${placeholders(4, t("Looking for departures…"))}</div>
      <div class="actions"><button class="btn btn-primary btn-block" id="plan">${t("Plan a trip from here")}</button></div>`;
    if (onBack) $("[data-back]", s).onclick = onBack;
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
        d.trainsPresent ? (d.trainsPresent === 1 ? t("{n} train at the station", { n: "<b>1</b>" }) : t("{n} trains at the station", { n: `<b>${d.trainsPresent}</b>` })) : "",
        d.trainsUpcoming === 1 ? t("{n} train coming", { n: "<b>1</b>" }) : t("{n} trains coming", { n: `<b>${d.trainsUpcoming}</b>` }),
        n ? "" : t("No incidents"),
      ].filter(Boolean).join(" · ");
      const box = $("#sinc", s);
      box.hidden = !n;
      if (!n) return;
      box.className = `incidents card sev-${esc(inc[0].severity || "info")}`;
      $("#sinc-t", s).textContent = n === 1 ? t("1 incident at this station or its trains") : t("{n} incidents at this station or its trains", { n });
      $("#sinc-list", s).innerHTML = inc.map(alertHtml).join("");
    };
    const refresh = () => api(`/stations/${encodeURIComponent(st.stopId)}`).then((d) => { detail = d; draw(); }).catch((e) => {
      if (e.status === 404) $("#ssum", s).textContent = t("This station is not known.");
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
      <h1>${t("Station departures")}</h1>
      <p class="lead">${t("Find a station to see its next departures and incidents.")}</p>
      <div id="picker"></div>`;
    $("[data-back]", s).onclick = () => onBack();
    onLeave(stationPicker($("#picker", s), {
      placeholder: t("Station"),
      onPick: (st) => { buzz(15); stationScreen(st, () => stationSearchScreen(onBack)); },
    }));
    setTimeout(() => $("#picker input", s)?.focus({ preventScroll: true }), 250);
  });
}

// ------------------------------------------------------------------ 3b. just browsing: origin + destination

/** Without a location, offer voice or chat before asking for station names. */
export function browseScreen({ note = null, back = false, noLocation = false } = {}) {
  setView(null);
  const pick = { from: null, to: null };
  const hint = { from: t("Where do you start?"), to: t("Where do you go?") };
  show((s) => {
    const field$ = (f) => `<label class="od-field" data-f="${f}"><span class="k">${f === "from" ? t("From") : t("To")}</span>
      <input class="v" type="text" placeholder="${hint[f]}" aria-label="${hint[f]}" autocomplete="off" enterkeyhint="search" /></label>`;
    s.classList.add("planner");
    s.innerHTML = `
      <header class="planner-intro">
        <h1>${t("Plan a trip")}</h1>
        <p class="lead" hidden></p>
        <button class="btn" type="button" id="ask" aria-haspopup="dialog" aria-controls="assistant-panel">${ic("chat")}${t("Ask the travel assistant")}</button>
      </header>
      <div class="planner-route">
        <div class="od">
          ${field$("from")}
          <button class="btn btn-ghost btn-circle swap" type="button" title="${t("Swap stations")}" aria-label="${t("Swap stations")}">${ic("swap")}</button>
          ${field$("to")}
        </div>
        <div id="picker"></div>
        <button class="btn btn-block" id="go" disabled>${t("Show trains")}</button>
        <button class="station-link" type="button" id="board">${ic("list")} ${t("Station departures and incidents")} ${ic("chevron")}</button>
      </div>`;
    const inputOf = (f) => $(`.od-field[data-f="${f}"] input`, s);
    const ask = $("#ask", s), go = $("#go", s);
    const guidance = $(".planner-intro .lead", s);
    let field = "from";
    let close = null;
    let pickerRequest = 0;
    // syncText: write the picked station names into the fields (not while the user is typing).
    const render = (syncText = false) => {
      s.querySelectorAll(".od-field").forEach((el) => {
        const f = el.dataset.f, inp = $("input", el);
        el.classList.toggle("active", f === field && !!close);
        if (syncText) inp.value = pick[f]?.name ?? "";
        inp.classList.toggle("set", !!pick[f] && inp.value === pick[f].name);
      });
      go.disabled = !(pick.from && pick.to && pick.from.stopId !== pick.to.stopId);
      const message = go.disabled ? note : t("Results show direct trains already in service.");
      guidance.textContent = message ?? "";
      guidance.hidden = !message;
      const highlightAssistant = noLocation && go.disabled;
      ask.classList.toggle("btn-primary", highlightAssistant);
      ask.classList.toggle("btn-outline", !highlightAssistant);
      go.classList.toggle("btn-primary", !highlightAssistant);
    };
    const closePicker = () => { pickerRequest++; close?.(); close = null; $("#picker", s).innerHTML = ""; render(); };
    const openPicker = async () => {
      const request = ++pickerRequest;
      close?.();
      close = () => {}; // marks the picker as open while `reachable` loads
      const f = field;
      const reachable = f === "to" && pick.from
        ? new Map((await api(`/stations/${encodeURIComponent(pick.from.stopId)}/destinations?limit=500`).catch(() => [])).map((d) => [d.station.stopId, d]))
        : null;
      if (request !== pickerRequest) return;
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
    onLeave(closePicker);
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
    go.onclick = () => { buzz(15); connectionsScreen(pick.from, pick.to); };
    $("#board", s).onclick = () => { buzz(15); stationSearchScreen(() => browseScreen({ note, back: true, noLocation })); };
    restartBtn.textContent = t("Find nearby");
    const fab = $("#assistant-fab");
    const observer = new IntersectionObserver(([entry]) => fab.classList.toggle("has-inline-trigger", entry.isIntersecting));
    observer.observe(ask);
    onLeave(() => { observer.disconnect(); fab.classList.remove("has-inline-trigger"); });
    ask.onclick = () => {
      buzz(15);
      if (fab.getAttribute("aria-expanded") !== "true") fab.click();
    };
    render();
    // Without location the assistant comes first; on a phone the keyboard would cover the assistant action.
    if (!noLocation && !matchMedia("(pointer: coarse)").matches) {
      openPicker();
      setTimeout(() => inputOf("from")?.focus({ preventScroll: true }), 250);
    }
  }, { back });
}

// ------------------------------------------------------------------ 4. trains from A to B (live)

export function connectionsScreen(from, to) {
  setUrl({ station: from.stopId, to: to.stopId });
  labelView(t("{from} to {to}", { from: from.name, to: to.name }));
  show((s) => {
    s.innerHTML = `
      ${backBtn()}
      <div class="step">${t("From {station}", { station: esc(from.name) })}</div>
      <h1>${t("Trains to {station}", { station: esc(to.name) })}</h1>
      <div id="alerts" class="list"></div>
      <h2>${t("Best routes")}</h2>
      <div class="list" id="routes">${placeholders(2, t("Finding the best route…"))}</div>
      <p class="note">${t("Ranked by the arrival you can count on: live delays, incidents and changes included.")}</p>
      <h2>${t("Direct trains in service")}</h2>
      <div class="list" id="list">${placeholders(3, t("Finding trains for you…"))}</div>
      <p class="note">${t("Live data. Only trains already in service are shown.")}</p>`;
    $("[data-back]", s).onclick = () => destinationScreen(from);
    const list = $("#list", s);
    let firstDraw = true;
    const draw = (data) => {
      const alerts = new Map();
      data.entries.forEach((c) => c.incidents.forEach((i) => alerts.set(i.id, i)));
      $("#alerts", s).innerHTML = [...alerts.values()].map(alertHtml).join("");
      if (!data.entries.length) {
        list.innerHTML = `<p class="empty">${t("No train running now goes to {station} from here. Trains are shown once they start their run.", { station: esc(to.name) })}</p>`;
        return;
      }
      list.classList.toggle("stagger", firstDraw);
      firstDraw = false;
      list.innerHTML = data.entries.map((c, i) => {
        const tr = c.train;
        return `<button class="row conn" type="button" data-id="${esc(tr.trainId)}" style="--i:${Math.min(i, 10)}">
          <div class="dep">${timeHtml(c.plannedDepartureTime, c.departureTime, tr.status === "cancelled")}<small data-until="${c.departureTime || ""}">${c.atStation ? t("Now at the station") : untilText(c.departureTime)}</small></div>
          <div class="l1">${lineLabel(tr)}<span class="h">${t("to {dest}", { dest: esc(tr.headsign || "?") })}</span></div>
          <div class="r">${stateHtml(tr)}</div>
          <div class="l2">${t("Arrival {time}", { time: hhmm(c.arrivalTime) })}${c.durationMin != null ? ` · ${t("{n} min", { n: c.durationMin })}` : ""} · ${c.stopsBetween ? (c.stopsBetween > 1 ? t("{n} stops", { n: c.stopsBetween }) : t("1 stop")) : t("Direct")}${c.incidents.length ? ` · <span class="warn">${c.incidents.length > 1 ? t("{n} incidents", { n: c.incidents.length }) : t("1 incident")}</span>` : ""}</div>
        </button>`;
      }).join("");
      list.querySelectorAll(".conn").forEach((el) => (el.onclick = () => {
        buzz(15);
        onTracksScreen(el.dataset.id, { fromStopId: from.stopId, toStopId: to.stopId }, () => connectionsScreen(from, to));
      }));
    };
    const routesEl = $("#routes", s);
    let shownRoutes = [];
    const drawRoutes = (plan) => {
      shownRoutes = plan.routes;
      if (!plan.routes.length) {
        routesEl.innerHTML = `<p class="empty">${t("The planner found no route to {station} in the next hours.", { station: esc(to.name) })}</p>`;
        return;
      }
      routesEl.innerHTML = plan.routes.map((r, i) => routeHtml(r, i)).join("");
      routesEl.querySelectorAll(".route-opt").forEach((el) => (el.onclick = () => {
        const r = shownRoutes[+el.dataset.i];
        if (!r?.legs.length || r.legs.some((l) => !l.trainId)) return;
        buzz(15);
        // the whole trip: its trains in order, the first one still to come on screen
        const trip = tripFromRoute(r, from, to);
        saveTrip(trip);
        onTracksScreen(trip.legs[trip.leg].trainId, trip, () => connectionsScreen(from, to));
      }));
    };
    const refreshRoutes = () => api(`/routes?from=${encodeURIComponent(from.stopId)}&to=${encodeURIComponent(to.stopId)}&limit=4`)
      .then(drawRoutes)
      .catch(() => { if (!shownRoutes.length) routesEl.innerHTML = `<p class="empty">${t("The route planner is not available right now. The direct trains below are live.")}</p>`; });
    const refresh = () => api(`/stations/${encodeURIComponent(from.stopId)}/connections?to=${encodeURIComponent(to.stopId)}&windowMin=240&limit=12`)
      .then(draw).catch(() => toast(t("Connection problem. Retrying…")));
    refresh();
    refreshRoutes();
    const poll = setInterval(refresh, 20_000);
    const pollRoutes = setInterval(refreshRoutes, 60_000);
    onLeave(() => clearInterval(pollRoutes));
    const clock = setInterval(() => list.querySelectorAll("[data-until]").forEach((el) => {
      if (el.dataset.until && el.textContent !== t("Now at the station")) el.textContent = untilText(+el.dataset.until);
    }), 5_000);
    onLeave(() => { clearInterval(poll); clearInterval(clock); });
    onLeave(live.subscribe("station", from.stopId, { frequencySec: 3600, windowMin: 30 }, (m) => {
      if (m.type === "incident" && m.event !== "active") { buzz([20, 40, 20]); toast(m.incident.title); refresh(); refreshRoutes(); }
    }));
  });
}

const RISK = { low: t("No known problems"), medium: t("Some risk"), high: t("At risk") };

/** One planner route: times, legs as line badges, changes, the weightiest notes; the recommended one first. */
function routeHtml(r, i) {
  const first = r.legs[0];
  const legs = r.legs.map((l, k) => `${lineLabel(l)}${k < r.legs.length - 1 ? ` <span class="via">${esc(l.to.name)}${l.changeMin != null ? ` ${l.changeMin}'` : ""}</span> ` : ""}`).join(" ");
  const state = !r.feasible ? `<span class="warn">${t("Not possible")}</span>` : r.recommended ? `<span class="rec">${t("Recommended")}</span>` : "";
  const notes = r.notes.slice(0, 2).map((n) => `<span class="${r.feasible ? "" : "warn"}">${esc(n)}</span>`).join(" · ");
  return `<button class="row conn route-opt risk-${esc(r.risk)}${r.feasible ? "" : " off"}" type="button" data-i="${i}" style="--i:${Math.min(i, 10)}">
    <div class="dep">${timeHtml(first.from.plannedTime ?? r.plannedDeparture, r.departure, first.cancelled)}<small data-until="${r.departure}">${untilText(r.departure)}</small></div>
    <div class="l1">${legs}</div>
    <div class="r">${state}</div>
    <div class="l2">${t("Arrival {time}", { time: hhmm(r.arrival) })}${r.arrivalDelayMin >= 1 ? ` <span class="delay">+${r.arrivalDelayMin}'</span>` : ""} · ${t("{n} min", { n: r.durationMin })} · ${r.transfers ? (r.transfers > 1 ? t("{n} changes", { n: r.transfers }) : t("1 change")) : t("Direct")}${r.feasible ? ` · ${RISK[r.risk] || ""}` : ""}</div>
    ${r.why || notes ? `<div class="l3">${r.why ? esc(r.why) : notes}</div>` : ""}
  </button>`;
}

// ------------------------------------------------------------------ 5. on tracks (on a train)

export function onTracksScreen(trainId, trip = null, onBack = null) {
  // A trip with changes (trip.legs, trip.leg = the train on screen) or a single train ride (fromStopId/toStopId).
  const legs = trip?.legs?.length ? trip.legs : null;
  const leg = legs ? legs[trip.leg] : null;
  const nextLeg = legs ? legs[trip.leg + 1] : null;
  setUrl(leg ? { train: trainId, from: leg.from.stopId, to: leg.to.stopId, trip: trip.id, leg: String(trip.leg) }
    : trip ? { train: trainId, from: trip.fromStopId, to: trip.toStopId } : { train: trainId });
  const self = () => onTracksScreen(trainId, trip, onBack);
  show((s) => {
    s.innerHTML = `
      ${onBack ? backBtn() : ""}
      ${legs && legs.length > 1 ? `<nav class="trip-path card" id="path" aria-label="${t("Your trip")}">${pathHtml(trip)}</nav>` : ""}
      <div id="alerts" class="list"></div>
      <button class="btn btn-primary btn-block ask-alt" type="button" id="ask-alt" hidden>${ic("chat")} ${t("Ask Tikt about alternative routes")}</button>
      <div class="scene card"><canvas id="scene" aria-hidden="true"></canvas>
        <div class="scene-cap"><span id="line"></span><div class="main"><div class="t" id="to"></div><div class="m" id="where"></div></div><span id="state"></span></div>
      </div>
      <div class="nextstop card" id="next"></div>
      <div class="follow card" id="follow"></div>
      ${trip ? `<a id="watch-train" class="btn btn-block watch-entry" hidden>${t("Watch this trip")}</a>` : ''}
      <button id="gwallet" class="btn btn-block wallet-add" type="button" hidden>${ic("wallet")} ${t("Add to Google Wallet")}</button>
      <h2>${t("Route")}</h2>
      <div class="route" id="route">${placeholders(4, t("Loading the route…"))}</div>
      <p class="note">${t("Tap a stop to see its departures and incidents.")}</p>`;
    if (onBack) $("[data-back]", s).onclick = () => onBack();
    // another train of the trip: look at it (the trip moves on by itself at each change)
    $("#path", s)?.addEventListener("click", (e) => {
      const b = e.target.closest("[data-leg]");
      if (!b || +b.dataset.leg === trip.leg) return;
      buzz(15);
      const k = +b.dataset.leg;
      onTracksScreen(legs[k].trainId, { ...trip, leg: k }, onBack);
    });
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
    // Google Wallet (Android): a Trip Watch pass for this train, kept up to date; then Google's "save" page.
    googleWalletAvailable.then((ok) => { const b = $("#gwallet", s); if (ok && b?.isConnected) b.hidden = false; });
    $("#gwallet", s).onclick = async (e) => {
      const btn = e.currentTarget;
      if (btn.disabled) return;
      buzz(15);
      btn.disabled = true;
      try {
        location.assign(await addToGoogleWallet(walletSelection()));
      } catch (err) {
        toast(err.message || t("Google Wallet is unavailable. Try again shortly."));
      } finally {
        btn.disabled = false;
      }
    };
    // What the pass follows: the whole planned trip, this leg, or this train from its next stop to its terminus.
    const walletSelection = () => {
      if (legs?.length > 1 && trip.id) return [{ journeyId: trip.id }, { trainId, fromStopId: leg.from.stopId, toStopId: leg.to.stopId }];
      if (leg) return [{ trainId, fromStopId: leg.from.stopId, toStopId: leg.to.stopId }];
      if (trip) return [{ trainId, fromStopId: trip.fromStopId, toStopId: trip.toStopId }];
      const stops = last?.stops ?? [];
      const from = stops.find((x) => (x.state === "current" || x.state === "next") && !x.skipped);
      const to = [...stops].reverse().find((x) => !x.skipped);
      if (!from || !to || from.stopId === to.stopId) throw new Error(t("This train has no stop ahead to put in Wallet."));
      return [{ trainId, fromStopId: from.stopId, toStopId: to.stopId }];
    };
    const incidents = new Map();
    let last = null;
    const scene = trainScene($("#scene", s));
    onLeave(() => scene.then((x) => x?.dispose()));

    const drawAlerts = () => {
      drawIncidents($("#alerts", s), [...incidents.values()]);
      $("#ask-alt", s).hidden = !incidents.size || typeof window.askAssistant !== "function";
    };
    // With incidents: straight to the assistant, asking whether to change (it also gets this screen as context).
    $("#ask-alt", s).onclick = () => {
      buzz(15);
      const tr = last?.train;
      const name = tr ? t("the {line} to {dest}", { line: tr.line || tr.category || t("train"), dest: tr.headsign || tr.destination?.name || "?" }) : t("this train");
      const dest = trip?.toStopId && last?.stops.find((x) => x.stopId === trip.toStopId)?.name;
      const from = trip?.fromStopId && last?.stops.find((x) => x.stopId === trip.fromStopId)?.name;
      const titles = [...incidents.values()].slice(0, 3).map((i) => i.title).join("; ");
      const vars = { name, id: trainId, from, dest, titles };
      askAssistant(dest && from ? t("I'm on {name} (train id {id}), going from {from} to {dest}. It has incidents: {titles}. Are there alternative routes? Should I change trains, and where?", vars)
        : dest ? t("I'm on {name} (train id {id}), going to {dest}. It has incidents: {titles}. Are there alternative routes? Should I change trains, and where?", vars)
          : t("I'm on {name} (train id {id}). It has incidents: {titles}. Are there alternative routes? Should I change trains, and where?", vars));
    };
    const follow = followCard($("#follow", s), () => last && { trainId, train: last.train, stops: last.stops });

    // Trip with changes: a heads-up once the train has left the stop before the change, then on to the next train.
    let warned = false, moved = false;
    const changeStep = (stops) => {
      if (!leg || !nextLeg || moved) return;
      const from = stops.findIndex((x) => x.stopId === leg.from.stopId);
      const k = stops.findIndex((x, i) => i > from && x.stopId === leg.to.stopId);
      if (k < 0) return;
      const at = stops[k];
      if (at.state === "current" || at.state === "passed") {
        moved = true;
        buzz([30, 60, 30]);
        toast(t("Change here: take {train}.", { train: takeText(nextLeg) }), 6000);
        const next = { ...trip, leg: trip.leg + 1, savedAt: Date.now() };
        saveTrip(next);
        setTimeout(() => onTracksScreen(nextLeg.trainId, next, onBack), 1200);
      } else if (!warned && at.state === "next" && k > 0 && stops[k - 1].state === "passed") {
        warned = true;
        buzz([20, 40, 20]);
        toast(t("Next stop: {stop}. Change to {train}.", { stop: at.name, train: takeText(nextLeg) }), 7000);
      }
    };
    // Push (when this device has notifications on): the server sends "change at X" and follows the next trains itself.
    const followTrip = () => {
      if (!leg || !push.enabled() || trip.pushedLeg === trip.leg) return;
      push.follow({ trainId, stopId: leg.to.stopId, then: pushLegs(trip, trip.leg + 1) })
        .then(() => { trip.pushedLeg = trip.leg; saveTrip(trip); follow.refresh(); }).catch(() => {});
    };
    // A later train of the trip that has not started its run yet: not "gone", just not running yet.
    const waiting = () => {
      $("#line", s).innerHTML = lineLabel(leg, true);
      $("#to", s).textContent = t("to {dest}", { dest: leg.headsign || leg.to.name });
      $("#where", s).textContent = t("Not running yet");
      $("#next", s).innerHTML = `<div class="k">${trip.leg > 0 ? t("Your next train") : t("Your train")}</div>
        <div class="nrow"><b>${t("Departs {station}", { station: esc(leg.from.name) })}</b><span class="eta-big">${untilText(leg.from.time) || hhmm(leg.from.time)}</span></div>
        <div class="m">${timeHtml(leg.from.planned ?? leg.from.time, leg.from.time)} · ${t("live times as soon as it starts its run")}</div>`;
      $("#route", s).innerHTML = `<p class="empty">${t("Get off at {station} ({time}).", { station: esc(leg.to.name), time: hhmm(leg.to.time) })}</p>`;
    };

    const draw = (train, stops) => {
      last = { train, stops };
      changeStep(stops);
      const cancelled = train.status === "cancelled";
      $("#line", s).innerHTML = lineLabel(train, true);
      $("#to", s).textContent = t("to {dest}", { dest: train.headsign || train.destination?.name || "?" });
      if (window.twinView?.trainId === trainId) labelView(t("{line} to {dest}", { line: train.line || train.category || t("Train"), dest: train.headsign || train.destination?.name || "?" }));
      $("#state", s).innerHTML = stateHtml(train);
      const standing = train.position?.motion === "stopped" && train.position?.atStopId;
      scene.then((x) => x?.update({ speedKmh: standing ? 0 : train.position?.speedKmh ?? 0, atStation: standing ? (train.currentStop?.name || " ") : null }));

      const k = stops.findIndex((x) => x.state === "current" || x.state === "next");
      const cur = k >= 0 ? stops[k] : null;
      const prev = k > 0 ? stops[k - 1] : null;
      const where = !cur ? t("Run completed")
        : cur.state === "current" ? t("At {stop}", { stop: cur.name }) : prev ? t("Between {a} and {b}", { a: prev.name, b: cur.name }) : t("Before {stop}", { stop: cur.name });
      const whereEl = $("#where", s);
      if (whereEl.textContent !== where) {
        const changed = !!whereEl.textContent;
        whereEl.textContent = where;
        if (changed && !reducedMotion()) { whereEl.classList.remove("swap"); void whereEl.offsetWidth; whereEl.classList.add("swap"); }
      }

      // Next stop + ETA
      const next = cur && cur.state === "current" ? stops[k + 1] : cur;
      $("#next", s).innerHTML = next
        ? `<div class="k">${t("Next stop")}</div><div class="nrow"><b>${esc(next.name)}</b><span class="eta-big">${untilText(next.expectedArr ?? next.plannedArr) || "–"}</span></div>
           <div class="m">${timeHtml(next.plannedArr ?? next.plannedDep, next.expectedArr ?? next.expectedDep, cancelled)}</div>`
        : `<div class="k">${train.destination?.name ? t("This train has reached {station}", { station: esc(train.destination.name) }) : t("This train has reached its destination")}</div>`;

      // Whole route; the train sits between the last stop left and the next one.
      let progress = 0.5;
      if (cur && prev && cur.state === "next") {
        const dep = prev.expectedDep ?? prev.plannedDep, arr = cur.expectedArr ?? cur.plannedArr;
        if (dep && arr && arr > dep) progress = clamp((Date.now() - dep) / (arr - dep), 0.05, 0.95);
      }
      const html = stops.map((x, i) => {
        const tm = x.expectedArr ?? x.expectedDep ?? x.plannedArr ?? x.plannedDep;
        const cls = `stop ${x.state}${i === 0 ? " first" : ""}${i === stops.length - 1 ? " last" : ""}${x.skipped ? " skipped" : ""}`;
        const between = i === k && x.state === "next" && prev
          ? `<div class="between"><span class="pin-train" style="--p:${progress}">${ic("train")}</span></div>` : "";
        return `${between}<button class="${cls}" type="button" data-stop="${esc(x.stopId)}" data-name="${esc(x.name)}">
          <span class="n">${esc(x.name)}${x.skipped ? ` <span class="nostop">${t("Does not stop")}</span>` : ""}</span>
          <span class="tm"><span>${x.skipped ? "" : timeHtml(x.plannedArr ?? x.plannedDep, x.expectedArr ?? x.expectedDep, cancelled)}</span>
            ${x.state === "passed" || x.skipped ? "" : `<small>${x.state === "current" ? t("Now") : untilText(tm)}</small>`}</span></button>`;
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

    let retry = null;
    const load = () => api(`/trains/${encodeURIComponent(trainId)}`).then((d) => {
      d.incidents.forEach((i) => incidents.set(i.id, i));
      drawAlerts();
      draw(d.train, d.stops);
      follow.refresh();
      followTrip();
    }).catch((e) => {
      if (e.status !== 404) return;
      // trip train still to come (its run has not started): wait for it instead of saying it is gone
      if (leg && (leg.from.time ?? 0) > Date.now() - 30 * 60_000) { waiting(); retry = setTimeout(load, 15_000); }
      else trainGone(s);
    });
    load();
    onLeave(() => clearTimeout(retry));

    onLeave(live.subscribe("train", trainId, { frequencySec: 3 }, (m) => {
      if (m.type === "train_update") draw(m.data.train, m.data.stops);
      else if (m.type === "incident") {
        if (m.event === "removed" || m.event === "missing") incidents.delete(m.incident.id);
        else {
          if (!incidents.has(m.incident.id) && m.event !== "active") { buzz([20, 40, 20]); toast(m.incident.title); }
          incidents.set(m.incident.id, m.incident);
        }
        drawAlerts();
      } else if (m.type === "train_ended") {
        if (nextLeg && !moved) { moved = true; const next = { ...trip, leg: trip.leg + 1 }; saveTrip(next); onTracksScreen(nextLeg.trainId, next, onBack); }
        else trainGone(s);
      }
    }));
    const tick = setInterval(() => last && draw(last.train, last.stops), 15_000);
    onLeave(() => clearInterval(tick));
  });
}

/** Starts a Trip Watch for the first selection that resolves and returns its Google Wallet "save" URL. */
async function addToGoogleWallet(selections) {
  const call = async (path, body) => {
    const r = await fetch(`/api/v1/trip-watches${path}`, {
      method: "POST", credentials: "same-origin",
      headers: { Accept: "application/json", ...(body ? { "Content-Type": "application/json" } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw Object.assign(new Error(typeof j.error === "string" ? j.error : t("Google Wallet is unavailable. Try again shortly.")), { status: r.status });
    return j;
  };
  let watch = null, error = null;
  for (const sel of selections) {
    try { watch = await call("", sel); break; } catch (e) { error = e; if (e.status !== 409) break; }
  }
  if (!watch) throw error;
  return (await call(`/${encodeURIComponent(watch.id)}/google`)).url;
}
