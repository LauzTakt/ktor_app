// Alerts (push notifications, push.js): follow a train or a line, the alerts list, line status.

import { $, esc, publicApi as api, t } from "../common.js";
import * as push from "../push.js";
import { alertHtml, backBtn, buzz, debounce, ic, lineLabel, onLeave, placeholders, setUrl, show, stateHtml, timeHtml, toast } from "./ui.js";
import { onTracksScreen } from "./screens.js";

// ------------------------------------------------------------------ 6. alerts (push notifications, push.js)

/** Subscriptions of this device, cached for the session (refreshed after every change). */
export const alerts = {
  subs: null,
  async load(force = false) {
    if (!this.subs || force) this.subs = await push.list().catch(() => this.subs || []);
    return this.subs;
  },
  forTrain(id) { return this.subs?.find((x) => x.kind === "train" && x.trainId === id); },
  forLine(line) { return this.subs?.find((x) => x.kind === "line" && x.line?.toLowerCase() === line?.toLowerCase()); },
};

export const subText = (x) => x.kind === "line" ? t("Incidents on this line")
  : x.stopName ? t("Arrival at {stop} and incidents", { stop: x.stopName }) : t("Incidents on this train");

const trainsRunning = (n) => (n === 1 ? t("1 train running") : t("{n} trains running", { n }));
const incidentCount = (n) => (n === 1 ? t("1 incident") : t("{n} incidents", { n }));

/** "Notify me" card on the train screen. [ctx] returns { trainId, train, stops } once the train is loaded. */
export function followCard(el, ctx) {
  const draw = () => {
    const c = ctx();
    if (!c) { el.hidden = true; return; }
    el.hidden = false;
    const sub = alerts.forTrain(c.trainId);
    el.innerHTML = sub
      ? `<span class="fic on">${ic("bell")}</span><div class="main"><div class="t">${esc(t("Alerts on"))}</div><div class="m">${esc(subText(sub))}</div></div>
         <button class="btn" type="button" data-edit>${esc(t("Change"))}</button>`
      : `<span class="fic">${ic("bell")}</span><div class="main"><div class="t">${esc(t("Get alerts"))}</div><div class="m">${esc(t("Before your stop, and when an incident starts or ends."))}</div></div>
         <button class="btn btn-primary" type="button" data-edit>${esc(t("Notify me"))}</button>`;
    $("[data-edit]", el).onclick = () => { buzz(); followSheet(c, refresh); };
  };
  const refresh = () => alerts.load(true).then(draw);
  draw();
  return { refresh };
}

/** Bottom sheet: pick the stop where you get off (or incidents only), optionally follow the whole line too. */
export function followSheet({ trainId, train, stops }, done) {
  const current = alerts.forTrain(trainId);
  const line = train.line;
  const lineSub = line ? alerts.forLine(line) : null;
  const sup = push.support();
  const ahead = stops.filter((x) => (x.state === "next" || x.state === "upcoming") && !x.skipped);
  const chosen = current ? (current.stopId || "") : (ahead.at(-1)?.stopId || "");
  const opt = (value, title, meta) => `<label class="row tap${value === chosen ? " selected" : ""}">
      <input class="radio radio-sm" type="radio" name="stop" value="${esc(value)}"${value === chosen ? " checked" : ""}>
      <span class="main"><span class="t">${esc(title)}</span><span class="m">${meta}</span></span></label>`;

  const back = document.createElement("div");
  back.className = "sheet-backdrop";
  back.innerHTML = `<div class="sheet" role="dialog" aria-modal="true" aria-labelledby="sheet-t">
    <h2 id="sheet-t">${t("Alerts for {train} to {dest}", { train: esc(line || train.category || t("this train")), dest: esc(train.headsign || train.destination?.name || "?") })}</h2>
    ${sup.ok ? `
      <p class="note">${esc(t("Where do you get off? You get an alert about {n} min before arriving, and whenever an incident on this train starts or ends.", { n: Math.round(push.leadSec / 60) }))}</p>
      <div class="list stop-pick">
        ${ahead.map((x) => opt(x.stopId, x.name, t("Arrival {time}", { time: timeHtml(x.plannedArr ?? x.plannedDep, x.expectedArr ?? x.expectedDep) }))).join("")}
        ${opt("", t("Incidents only"), esc(t("No arrival alert")))}
      </div>
      ${line && !lineSub ? `<label class="check"><input class="checkbox checkbox-sm" type="checkbox" id="sheet-line"> ${t("Also follow line {line} (incidents only)", { line: esc(line) })}</label>` : ""}
      <div class="pair"><button class="btn" type="button" data-close>${esc(t("Cancel"))}</button><button class="btn btn-primary" type="button" id="sheet-ok">${esc(current ? t("Save") : t("Turn on"))}</button></div>
      ${current ? `<button class="btn btn-block" type="button" id="sheet-off">${esc(t("Turn off alerts for this train"))}</button>` : ""}`
    : `<p class="lead">${esc(sup.reason)}</p><div class="actions"><button class="btn btn-block" type="button" data-close>${esc(t("Close"))}</button></div>`}
  </div>`;
  document.body.append(back);
  const close = () => { back.classList.add("leave"); setTimeout(() => back.remove(), 200); document.removeEventListener("keydown", onKey); };
  const onKey = (e) => { if (e.key === "Escape") close(); };
  document.addEventListener("keydown", onKey);
  back.addEventListener("click", (e) => { if (e.target === back || e.target.closest("[data-close]")) close(); });
  back.querySelectorAll('input[name="stop"]').forEach((r) => r.addEventListener("change", () => {
    back.querySelectorAll(".stop-pick .row").forEach((row) => row.classList.toggle("selected", row.contains(r) && r.checked));
  }));
  $(".stop-pick .row.selected", back)?.scrollIntoView({ block: "center" });
  (back.querySelector("#sheet-ok") || back.querySelector("[data-close]")).focus({ preventScroll: true });

  const busy = (btn, on, label) => { btn.disabled = on; if (label) btn.textContent = label; };
  const ok = $("#sheet-ok", back);
  if (ok) ok.onclick = async () => {
    const stopId = back.querySelector('input[name="stop"]:checked')?.value || null;
    busy(ok, true, t("Turning on…"));
    try {
      await push.follow({ trainId, stopId });
      if ($("#sheet-line", back)?.checked) await push.follow({ line });
      buzz(15);
      const stop = ahead.find((x) => x.stopId === stopId);
      toast(stop ? t("Alerts on. We will tell you before {stop}.", { stop: stop.name }) : t("Alerts on for this train."));
      close();
      done();
    } catch (e) {
      toast(e.message || t("Could not turn alerts on."), 6000);
      busy(ok, false, current ? t("Save") : t("Turn on"));
    }
  };
  const off = $("#sheet-off", back);
  if (off) off.onclick = async () => {
    busy(off, true, t("Turning off…"));
    try { await push.unfollow(current.id); toast(t("Alerts off for this train.")); close(); done(); }
    catch (e) { toast(e.message || t("Could not turn alerts off.")); busy(off, false, t("Turn off alerts for this train")); }
  };
}

/** Bell in the top bar: what this device follows, and "follow a line". [onBack] returns to the previous screen. */
export function alertsScreen(onBack) {
  setUrl({});
  show((s) => {
    const sup = push.support();
    s.innerHTML = `
      ${backBtn()}
      <h1>${esc(t("Your alerts"))}</h1>
      <p class="lead">${esc(t("Trains and lines you follow. Alerts arrive even when this page is closed."))}</p>
      ${sup.ok ? "" : `<p class="note warnbox">${esc(sup.reason)}</p>`}
      <div class="list" id="subs">${placeholders(2, null)}</div>
      <h2>${esc(t("Follow a line"))}</h2>
      <p class="note">${esc(t("Get an alert when an incident starts or ends anywhere on a line."))}</p>
      <label class="input search">${ic("search")}<input id="lq" type="search" placeholder="${esc(t("Line, e.g. IC 1 or S3"))}" autocomplete="off" enterkeyhint="search" aria-label="${esc(t("Search a line"))}"></label>
      <div class="list" id="lines">${placeholders(3, t("Loading the lines…"))}</div>
      <div class="actions" id="test-box" hidden><button class="btn btn-block" type="button" id="test">${esc(t("Send a test alert"))}</button></div>`;
    $("[data-back]", s).onclick = () => onBack();
    const self = () => alertsScreen(onBack);

    const drawSubs = (subs) => {
      $("#test-box", s).hidden = !push.enabled();
      $("#subs", s).innerHTML = subs.length ? subs.map((x) => `
        <div class="row">
          <button class="main linkish" type="button" data-open="${esc(x.id)}"><span class="t">${esc(x.label)}</span><span class="m">${esc(subText(x))}</span></button>
          <button class="btn btn-ghost btn-circle icon-btn" type="button" data-off="${esc(x.id)}" title="${esc(t("Stop following"))}" aria-label="${esc(t("Stop following {name}", { name: x.label }))}">${ic("x")}</button>
        </div>`).join("")
        : `<p class="empty">${esc(t("You follow nothing yet. Open your train and tap Notify me, or follow a line below."))}</p>`;
      $("#subs", s).querySelectorAll("[data-off]").forEach((b) => (b.onclick = async () => {
        b.disabled = true;
        try { await push.unfollow(b.dataset.off); buzz(); alerts.load(true).then(drawSubs); }
        catch (e) { toast(e.message || t("Could not remove it.")); b.disabled = false; }
      }));
      $("#subs", s).querySelectorAll("[data-open]").forEach((b) => (b.onclick = () => {
        const x = subs.find((y) => y.id === b.dataset.open);
        buzz(15);
        if (x.kind === "train") onTracksScreen(x.trainId); else lineScreen(x.line, self);
      }));
    };
    alerts.load(true).then(drawSubs);

    let all = [];
    const drawLines = () => {
      const q = $("#lq", s).value.trim().toLowerCase().replace(/\s+/g, "");
      const hits = all.filter((l) => !q || l.line.toLowerCase().replace(/\s+/g, "").includes(q)).slice(0, 30);
      $("#lines", s).innerHTML = hits.length ? hits.map((l) => `
        <button class="row" type="button" data-line="${esc(l.line)}">${lineLabel(l)}
          <span class="main"><span class="t">${esc(t("Line {line}", { line: l.line }))}</span><span class="m">${esc(trainsRunning(l.trains))}${l.incidents ? ` · <span class="warn">${esc(incidentCount(l.incidents))}</span>` : ` · ${esc(t("No incidents"))}`}</span></span>
          ${alerts.forLine(l.line) ? `<span class="side">${ic("bell")}</span>` : ""}${ic("chevron", "chev")}</button>`).join("")
        : `<p class="empty">${esc(all.length ? t("No line in service matches.") : t("No line in service right now."))}</p>`;
      $("#lines", s).querySelectorAll("[data-line]").forEach((b) => (b.onclick = () => { buzz(15); lineScreen(b.dataset.line, self); }));
    };
    api("/lines").then((l) => { all = l; drawLines(); }).catch(() => { $("#lines", s).innerHTML = `<p class="empty">${esc(t("Lines could not be loaded."))}</p>`; });
    $("#lq", s).addEventListener("input", debounce(drawLines, 120));

    $("#test", s).onclick = async (e) => {
      e.target.disabled = true;
      try {
        const r = await push.test();
        toast(r.result === "ok" ? t("Test alert sent. It should arrive in a few seconds.") : t("The test alert could not be delivered."));
      } catch (err) { toast(err.message || t("Could not send the test alert.")); }
      e.target.disabled = false;
    };
  }, { restart: false });
}

/** Line status: incidents concerning the line and its trains in service; follow it for incident alerts. */
export function lineScreen(line, onBack) {
  setUrl({ line });
  show((s) => {
    s.innerHTML = `
      ${backBtn()}
      <div class="step">${esc(t("Line status"))}</div>
      <h1 class="line-h"><span id="lbl"></span><span id="lname">${esc(t("Line {line}", { line }))}</span></h1>
      <p class="lead" id="lsum">&nbsp;</p>
      <div class="follow card" id="follow"><div class="main"><div class="m">&nbsp;</div></div></div>
      <h2>${esc(t("Incidents"))}</h2>
      <div class="list" id="linc">${placeholders(1, t("Checking this line…"))}</div>
      <h2>${esc(t("Trains in service"))}</h2>
      <div class="list" id="ltr">${placeholders(3, null)}</div>`;
    $("[data-back]", s).onclick = () => onBack();
    let name = line;

    const drawFollow = () => {
      const sub = alerts.forLine(name);
      const el = $("#follow", s);
      el.innerHTML = sub
        ? `<span class="fic on">${ic("bell")}</span><div class="main"><div class="t">${esc(t("Following this line"))}</div><div class="m">${esc(t("Alerts when an incident starts or ends."))}</div></div>
           <button class="btn" type="button" id="lf">${esc(t("Stop"))}</button>`
        : `<span class="fic">${ic("bell")}</span><div class="main"><div class="t">${esc(t("Follow this line"))}</div><div class="m">${esc(t("Alerts when an incident starts or ends."))}</div></div>
           <button class="btn btn-primary" type="button" id="lf">${esc(t("Follow"))}</button>`;
      $("#lf", el).onclick = async (e) => {
        e.target.disabled = true;
        try {
          if (sub) { await push.unfollow(sub.id); toast(t("You no longer follow line {line}.", { line: name })); }
          else { await push.follow({ line: name }); buzz(15); toast(t("Following line {line}.", { line: name })); }
          await alerts.load(true);
        } catch (err) { toast(err.message || t("Could not change it."), 6000); }
        drawFollow();
      };
    };

    const draw = (d) => {
      name = d.line;
      $("#lbl", s).innerHTML = lineLabel(d, true);
      const heads = [...new Set(d.trains.map((x) => x.headsign).filter(Boolean))];
      $("#lname", s).textContent = t("to {dest}", { dest: heads.length > 2 ? t("several destinations") : heads.join(" / ") || "?" });
      $("#lsum", s).innerHTML = d.incidents.length
        ? `<span class="warn">${esc(incidentCount(d.incidents.length))}</span> · ${esc(trainsRunning(d.trains.length))}`
        : `${esc(t("No incidents"))} · ${esc(trainsRunning(d.trains.length))}`;
      $("#linc", s).innerHTML = d.incidents.length ? d.incidents.map(alertHtml).join("") : `<p class="empty">${esc(t("Nothing to report on this line."))}</p>`;
      $("#ltr", s).innerHTML = d.trains.map((tr) => `
        <button class="row" type="button" data-id="${esc(tr.trainId)}">${lineLabel(tr)}
          <span class="main"><span class="t">${esc(t("to {dest}", { dest: tr.headsign || tr.destination?.name || "?" }))}</span>
            <span class="m">${tr.currentStop ? esc(t("At {stop}", { stop: tr.currentStop.name })) : tr.nextStop ? esc(t("Next: {stop}", { stop: tr.nextStop.name })) : ""}</span></span>
          <span class="side">${stateHtml(tr)}</span></button>`).join("");
      $("#ltr", s).querySelectorAll("[data-id]").forEach((b) => (b.onclick = () => { buzz(15); onTracksScreen(b.dataset.id); }));
    };
    const refresh = () => api(`/lines/${encodeURIComponent(line)}`).then(draw).catch((e) => {
      if (e.status === 404) {
        $("#lsum", s).textContent = t("No train is running on this line right now.");
        $("#linc", s).innerHTML = ""; $("#ltr", s).innerHTML = "";
      } else toast(t("Connection problem. Retrying…"));
    });
    alerts.load().then(drawFollow);
    refresh();
    const poll = setInterval(refresh, 30_000);
    onLeave(() => clearInterval(poll));
  }, { restart: true });
}
