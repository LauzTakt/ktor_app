const root = document.querySelector("#watch-page-content");
const feedback = document.querySelector("#watch-feedback");
const params = new URLSearchParams(location.search);
const base = "/api/v1/trip-watches";
const actions = { boarded: "I’m on board", alighted: "I’ve got off", wait: "Wait for a departure" };
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
  if (!response.ok || data === null) throw new Error(data?.error || "Trip Watch is unavailable. Try again shortly.");
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
    feedback.textContent = error instanceof Error ? error.message : "Try again shortly.";
  } finally {
    element.disabled = false;
    element.removeAttribute("aria-busy");
    busy = false;
  }
}

function heading(label) {
  const title = node("h1", label);
  title.tabIndex = -1;
  return title;
}

function render(next, focus = false) {
  if (!focus && JSON.stringify(next) === JSON.stringify(watch)) return;
  watch = next;
  const title = heading(`${watch.origin} to ${watch.destination}`);
  const panel = node("section", "", "card watch-panel");
  panel.dataset.state = watch.kind;
  const status = node("div", "", "state");
  status.append(node("span", "", "dot"), node("span", watch.kind === "ended" ? "Watch ended" :
    watch.kind === "check" ? "Connection changed" : "Watching trip"));
  panel.append(status, node("h2", watch.instruction));
  if (watch.detail) panel.append(node("p", watch.detail, "lead"));
  if (watch.legCount > 1 && watch.kind !== "ended") {
    panel.append(node("p", `Leg ${watch.leg} of ${watch.legCount}`, "note"));
  }

  const controls = node("div", "", "watch-actions");
  for (const action of watch.actions.filter((item) => item !== "stop")) {
    controls.append(button(actions[action], async () => {
      render(await json(`/${watchId}`, { method: "PATCH", body: JSON.stringify({ action }) }), true);
    }, "btn primary"));
  }
  if (watch.kind === "check") controls.append(link("Find a connection", watch.alternativesUrl));
  if (watch.actions.includes("stop")) {
    controls.append(button("Stop watching", async () => {
      render(await json(`/${watchId}`, { method: "DELETE" }), true);
    }));
  }
  root.replaceChildren(title, panel);
  if (controls.childElementCount) root.append(controls);

  if (watch.apple || watch.google) {
    const wallet = node("section", "", "card watch-panel");
    wallet.append(node("h2", "Add to Wallet"), node("p", "Keep the next step handy at your boarding station.", "lead"));
    const links = node("div", "", "watch-actions");
    if (watch.apple) links.append(link("Add to Apple Wallet", `${base}/${watchId}/apple.pkpass`));
    if (watch.google) links.append(button("Add to Google Wallet", async () => {
      const result = await json(`/${watchId}/google`, { method: "POST" });
      location.assign(result.url);
    }));
    wallet.append(links);
    root.append(wallet);
  }
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
      const option = node("label", "", "watch-option");
      option.append(aboard, node("span", "I’m already on board"));
      root.replaceChildren(
        heading("Trip updates"),
        node("p", "Get the next step if your selected connection changes.", "lead"),
        option,
        button("Watch this trip", async () => {
          const saved = await json("", { method: "POST", body: JSON.stringify({ ...selection, onBoard: aboard.checked }) });
          location.replace(`/trip-watch.html?id=${encodeURIComponent(saved.id)}`);
        }, "btn primary block"),
      );
      return;
    }
    const watches = await json();
    const list = node("div", "", "list");
    for (const item of watches) {
      const row = link("", `/trip-watch.html?id=${encodeURIComponent(item.id)}`, "row tap watch-list-item");
      row.append(node("strong", `${item.origin} to ${item.destination}`), node("span", item.instruction));
      list.append(row);
    }
    root.replaceChildren(heading("Watched trips"));
    root.append(watches.length ? list : node("p", "Choose a connection to start watching a trip.", "lead"));
    root.append(link("Find a trip", "/", "btn primary block"));
  } catch (error) {
    root.replaceChildren(heading("Trip Watch"), node("p", error.message, "lead"), link("Find a trip", "/", "btn primary block"));
  } finally {
    root.setAttribute("aria-busy", "false");
  }
}

void boot();
