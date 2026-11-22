import { qrcodegen } from "./vendor/qrcodegen-v1.8.0.js";
import { t } from "./common.js";

document.title = t("Trip Watch · Takt");

const root = document.querySelector("#watch-page-content");
const feedback = document.querySelector("#watch-feedback");
const params = new URLSearchParams(location.search);
const base = "/api/v1/trip-watches";
const actions = { boarded: t("I’m on board"), alighted: t("I’ve got off"), wait: t("Wait for a departure") };
let watchId = params.get("id");
let watch = null;
let busy = false;

function node(tag, label, classes) {
  const element = document.createElement(tag);
  if (label) element.textContent = label;
  if (classes) element.className = classes;
  return element;
}

function link(label, href, classes = "btn") {
  const element = node("a", label, classes);
  element.href = href;
  return element;
}

function button(label, handler, classes = "btn") {
  const element = node("button", label, classes);
  element.type = "button";
  element.addEventListener("click", () => void transact(element, handler));
  return element;
}

async function json(path = "", options = {}) {
  const response = await fetch(`${base}${path}`, {
    ...options,
    headers: { Accept: "application/json", ...(options.body ? { "Content-Type": "application/json" } : {}) },
  });
  const data = await response.json().catch(() => null);
  if (!response.ok || data === null) throw new Error(data?.error || t("Trip Watch is unavailable. Try again shortly."));
  return data;
}

async function transact(element, fn) {
  if (busy) return;
  busy = true;
  feedback.textContent = "";
  element.disabled = true;
  element.setAttribute("aria-busy", "true");
  try {
    await fn();
  } catch (error) {
    feedback.textContent = error instanceof Error ? error.message : t("Try again shortly.");
  } finally {
    element.disabled = false;
    element.removeAttribute("aria-busy");
    busy = false;
  }
}

function heading(label, classes) {
  const title = node("h1", label, classes);
  title.tabIndex = -1;
  return title;
}

const svgNs = "http://www.w3.org/2000/svg";

function svg(viewBox, classes, paths) {
  const element = document.createElementNS(svgNs, "svg");
  element.setAttribute("viewBox", viewBox);
  element.setAttribute("class", classes);
  element.setAttribute("aria-hidden", "true");
  for (const [d, attributes = {}] of paths) {
    const path = document.createElementNS(svgNs, "path");
    path.setAttribute("d", d);
    for (const [name, value] of Object.entries(attributes)) path.setAttribute(name, value);
    element.append(path);
  }
  return element;
}

// Front view of a train, the glyph Wallet draws between origin and destination on a train boarding pass.
const trainGlyph = () => svg("0 0 46 60", "pass-glyph", [
  ["M12 4h22a6 6 0 0 1 6 6v27a6 6 0 0 1-6 6H12a6 6 0 0 1-6-6V10a6 6 0 0 1 6-6z" +
    "M16.5 7h13a1.5 1.5 0 0 1 1.5 1.5v1a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 15 9.5v-1A1.5 1.5 0 0 1 16.5 7z" +
    "M12 14h22a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H12a2 2 0 0 1-2-2V16a2 2 0 0 1 2-2z" +
    "M10 35.5a2.6 2.6 0 1 0 5.2 0a2.6 2.6 0 1 0-5.2 0z" +
    "M30.8 35.5a2.6 2.6 0 1 0 5.2 0a2.6 2.6 0 1 0-5.2 0z", { "fill-rule": "evenodd" }],
  ["M14.8 44.5 6.3 58.5M31.2 44.5 39.7 58.5M10.5 49.8h25", { fill: "none", stroke: "currentColor", "stroke-width": "4.5" }],
]);

const walletIcon = () => svg("0 0 24 24", "icon", [
  ["M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1"],
  ["M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4"],
]);

function qrCode(text) {
  const qr = qrcodegen.QrCode.encodeText(text, qrcodegen.QrCode.Ecc.MEDIUM);
  let d = "";
  for (let y = 0; y < qr.size; y++) {
    for (let x = 0; x < qr.size; x++) if (qr.getModule(x, y)) d += `M${x} ${y}h1v1h-1z`;
  }
  const code = svg(`0 0 ${qr.size} ${qr.size}`, "pass-qr", [[d]]);
  code.setAttribute("shape-rendering", "crispEdges");
  return code;
}

function passField(label, value, classes) {
  const field = node("div", "", classes);
  if (label) field.append(node("span", label, "pass-label"));
  field.append(node("span", value, "pass-value"));
  return field;
}

/** HTML replica of the Wallet pass: the same face (`watch.pass`) that Apple and Google Wallet show. */
function passPreview(watch) {
  const face = watch.pass;
  const card = node("div", "", "pass-card");
  card.dataset.state = watch.kind;

  const head = node("div", "", "pass-head");
  const logo = node("div", "", "pass-logo");
  logo.append(node("span", "", "pass-bar"), node("span", face.brand, "pass-brand"));
  head.append(logo, passField(t("Departure:"), face.departure, "pass-header-field"));

  const route = node("div", "", "pass-primary");
  route.setAttribute("aria-label", t("{from} to {to}", { from: face.origin, to: face.destination }));
  route.append(node("span", face.origin, "pass-station"), trainGlyph(), node("span", face.destination, "pass-station"));

  const details = node("div", "", "pass-secondary");
  for (const value of [face.line, face.changes, face.date]) details.append(node("span", value));

  const code = node("div", "", "pass-code");
  const url = new URL(face.url, location.origin).href;
  const qr = qrCode(url);
  qr.setAttribute("aria-hidden", "false");
  qr.setAttribute("role", "img");
  qr.setAttribute("aria-label", t("QR code that opens this trip page"));
  code.append(qr, node("span", face.notice, "pass-alt"));

  card.append(head, route, details, passField(t("Next step"), face.nextStep, "pass-aux"), code);
  const pass = node("section", "", "pass");
  pass.setAttribute("aria-label", t("Wallet pass"));
  pass.append(card);
  return pass;
}

function render(next, focus = false) {
  if (!focus && JSON.stringify(next) === JSON.stringify(watch)) return;
  watch = next;
  const title = heading(t("{from} to {to}", { from: watch.origin, to: watch.destination }), "sr-only");
  const panel = node("section", "", "card watch-panel");
  panel.dataset.state = watch.kind;
  const status = node("div", "", "state");
  status.append(node("span", "", "dot"), node("span", watch.kind === "ended" ? t("Watch ended") :
    watch.kind === "check" ? t("Connection changed") : t("Watching trip")));
  // The next step is on the pass above: the panel adds the live detail, or what the watch does.
  panel.append(status, node("p", watch.detail || (watch.kind === "ended" ? t("Updates have stopped for this trip.") :
    t("Your next step updates here and on the pass if this connection changes.")), "lead"));
  if (watch.legCount > 1 && watch.kind !== "ended") {
    panel.append(node("p", t("Leg {leg} of {count}", { leg: watch.leg, count: watch.legCount }), "note"));
  }

  const controls = node("div", "", "watch-actions");
  for (const action of watch.actions.filter((item) => item !== "stop")) {
    controls.append(button(actions[action], async () => {
      render(await json(`/${watchId}`, { method: "PATCH", body: JSON.stringify({ action }) }), true);
    }, "btn btn-primary"));
  }
  if (watch.kind === "check") controls.append(link(t("Find a connection"), watch.alternativesUrl));
  if (watch.actions.includes("stop")) {
    controls.append(button(t("Stop watching"), async () => {
      render(await json(`/${watchId}`, { method: "DELETE" }), true);
    }));
  }
  root.replaceChildren(title, passPreview(watch));
  if (watch.apple || watch.google) {
    const wallet = node("div", "", "wallet-buttons");
    if (watch.apple) {
      const apple = link(t("Add to Apple Wallet"), `${base}/${watchId}/apple.pkpass`, "btn wallet");
      apple.prepend(walletIcon());
      wallet.append(apple);
    }
    if (watch.google) {
      const google = button(t("Add to Google Wallet"), async () => {
        const result = await json(`/${watchId}/google`, { method: "POST" });
        location.assign(result.url);
      }, "btn wallet");
      google.prepend(walletIcon());
      wallet.append(google);
    }
    root.append(wallet);
  }
  root.append(panel);
  if (controls.childElementCount) root.append(controls);
  if (focus) title.focus();
}

async function boot() {
  try {
    if (watchId) {
      render(await json(`/${encodeURIComponent(watchId)}`));
      setInterval(() => {
        if (!busy && watch?.kind !== "ended" && !document.hidden) {
          void json(`/${encodeURIComponent(watchId)}`).then((value) => {
            if (!busy) render(value);
          }).catch(() => {});
        }
      }, 15_000);
      return;
    }
    const journeyId = params.get("journeyId");
    const trainId = params.get("trainId");
    if (journeyId || trainId) {
      const selection = journeyId ? { journeyId } :
        { trainId, fromStopId: params.get("fromStopId"), toStopId: params.get("toStopId") };
      const aboard = document.createElement("input");
      aboard.type = "checkbox";
      aboard.className = "checkbox checkbox-sm";
      const option = node("label", "", "watch-option");
      option.append(aboard, node("span", t("I’m already on board")));
      root.replaceChildren(
        heading(t("Trip updates")),
        node("p", t("Get the next step if your selected connection changes."), "lead"),
        option,
        button(t("Watch this trip"), async () => {
          const saved = await json("", { method: "POST", body: JSON.stringify({ ...selection, onBoard: aboard.checked }) });
          location.replace(`/trip-watch.html?id=${encodeURIComponent(saved.id)}`);
        }, "btn btn-primary btn-block"),
      );
      return;
    }
    const watches = await json();
    const list = node("div", "", "list");
    for (const item of watches) {
      const row = link("", `/trip-watch.html?id=${encodeURIComponent(item.id)}`, "row tap watch-list-item");
      row.append(node("strong", t("{from} to {to}", { from: item.origin, to: item.destination })), node("span", item.instruction));
      list.append(row);
    }
    root.replaceChildren(heading(t("Watched trips")));
    root.append(watches.length ? list : node("p", t("Choose a connection to start watching a trip."), "lead"));
    root.append(link(t("Find a trip"), "/", "btn btn-primary btn-block"));
  } catch (error) {
    root.replaceChildren(heading(t("Trip Watch")), node("p", error.message, "lead"), link(t("Find a trip"), "/", "btn btn-primary btn-block"));
  } finally {
    root.setAttribute("aria-busy", "false");
  }
}

void boot();
