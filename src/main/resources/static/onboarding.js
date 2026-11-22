// First-run tour of the passenger view, guided by "Loki", a small flat vector train (docs/STYLE.md "Mascot").
// Shown once per browser (localStorage), before the location prompt; the "?" button in the header replays it.
// Self-contained: `startTour({ onDone })` builds its own overlay and removes it when finished or skipped.

import { LOKI_SVG } from "./loki.js";

const SEEN_KEY = "twin-tour-v1";
const reducedMotion = () => matchMedia("(prefers-reduced-motion: reduce)").matches;

export const tourSeen = () => {
  try { return localStorage.getItem(SEEN_KEY) === "done"; } catch { return true; }
};
const markSeen = () => { try { localStorage.setItem(SEEN_KEY, "done"); } catch { /* private mode */ } };

// Mood per step: "hello" (waves the pantograph), "point" (looks at the target), "cheer" (brows up, big smile).
const STEPS = [
  { mood: "hello", title: "Hi, I'm Loki", text: "I'll show you around this app. Skip the tour any time." },
  { mood: "point", title: "I find your train for you", text: "When we start, your phone asks for your location. I use it only to spot a train or a station close to you. Say no and you can still pick your stations by hand." },
  { target: "#assistant-fab", mood: "point", title: "Talk to me", text: "Not sure which train to take? Tap here and ask me, by voice or by chat." },
  { target: "#alerts-btn", mood: "point", title: "Your alerts", text: "Follow a train or a line and I let you know about delays, incidents and when you reach your stop." },
  { target: ".watched-trips-link", mood: "point", title: "Watched trips", text: "Trips you save show up here, so you can check them again later." },
  { target: "#theme", mood: "point", title: "Light or dark", text: "Switch the colours here, whatever is easier on your eyes." },
  { target: "#tour-btn", mood: "point", title: "Need me again?", text: "Tap the question mark and I come back for another round." },
  { mood: "cheer", title: "All aboard!", text: "Let's find your train.", last: true },
];

const MASCOT_W = 84; // rendered width of the mascot in px (CSS .loki)

export function startTour({ onDone = () => {} } = {}) {
  if (document.querySelector(".tour")) return;
  // Targets can appear late (e.g. "Watched trips" after an API call): steps are filtered each time we move.
  const avail = () => STEPS.filter((s) => !s.target || visible(s.target));
  let steps = avail();
  let i = 0;
  let typing = null;

  const root = document.createElement("div");
  root.className = "tour";
  root.innerHTML = `
    <div class="tour-dim"></div>
    <div class="tour-spot off"></div>
    <div class="tour-guide" role="dialog" aria-modal="true" aria-labelledby="tour-title" aria-describedby="tour-text">
      <div class="tour-track"><div class="loki">${LOKI_SVG}</div><div class="tour-rail"></div></div>
      <div class="tour-bubble">
        <div class="tour-head"><span class="tour-count"></span><button class="tour-skip" type="button">Skip tour</button></div>
        <h2 id="tour-title"></h2>
        <p id="tour-text" aria-live="polite"></p>
        <div class="tour-foot">
          <button class="btn tour-back" type="button">Back</button>
          <button class="btn btn-primary tour-next" type="button">Next</button>
        </div>
      </div>
    </div>`;
  document.body.append(root);
  const q = (sel) => root.querySelector(sel);
  const guide = q(".tour-guide"), spot = q(".tour-spot"), loki = q(".loki");
  const lastFocus = document.activeElement;
  document.documentElement.classList.add("tour-open");

  // Loki starts off-screen on the left and drives in on the first step.
  loki.style.setProperty("--mx", `${-MASCOT_W - 40}px`);
  requestAnimationFrame(() => root.classList.add("in"));

  function render(dir = 1) {
    const s = steps[i];
    q(".tour-count").textContent = `${i + 1} of ${steps.length}`;
    q("#tour-title").textContent = s.title;
    q(".tour-back").hidden = i === 0;
    q(".tour-next").textContent = s.last ? "Find my train" : i === 0 ? "Show me" : "Next";
    q(".tour-skip").hidden = !!s.last;
    loki.dataset.mood = s.mood;
    loki.classList.remove("hop");
    void loki.offsetWidth; // restart the small "arrived" nod
    loki.classList.add("hop");
    type(s.text);
    place(s, dir);
    q(".tour-next").focus({ preventScroll: true });
  }

  // Text appears like Loki is saying it; Next while typing shows the rest at once.
  function type(text) {
    const p = q("#tour-text");
    clearInterval(typing?.id);
    if (reducedMotion()) { p.textContent = text; return; }
    let n = 0;
    loki.classList.add("talking");
    const id = setInterval(() => {
      n += 2;
      p.textContent = text.slice(0, n);
      if (n >= text.length) finishTyping();
    }, 24);
    typing = { id, text };
  }
  function finishTyping() {
    if (!typing) return false;
    clearInterval(typing.id);
    q("#tour-text").textContent = typing.text;
    typing = null;
    loki.classList.remove("talking");
    return true;
  }

  function place(s, dir) {
    const vw = innerWidth, vh = innerHeight, m = 12, pad = 6;
    const el = s.target && visible(s.target);
    guide.style.width = `${Math.min(360, vw - 2 * m)}px`;
    let x, y, mx, look = { x: 0, y: 0 };
    if (!el) {
      spot.classList.add("off");
      guide.dataset.side = "center";
      const g = guide.getBoundingClientRect();
      x = (vw - g.width) / 2;
      y = Math.max(m, (vh - g.height) / 2);
      mx = (g.width - MASCOT_W) / 2;
    } else {
      const r = el.getBoundingClientRect();
      const round = Math.abs(r.width - r.height) < 4;
      Object.assign(spot.style, {
        left: `${r.left - pad}px`, top: `${r.top - pad}px`,
        width: `${r.width + 2 * pad}px`, height: `${r.height + 2 * pad}px`,
        borderRadius: round ? "999px" : "12px",
      });
      spot.classList.remove("off");
      const below = r.top + r.height / 2 < vh / 2; // target in the upper half: guide goes under it
      guide.dataset.side = below ? "below" : "above";
      const g = guide.getBoundingClientRect();
      const cx = r.left + r.width / 2;
      x = Math.min(Math.max(m, cx - g.width / 2), vw - g.width - m);
      y = below ? r.bottom + pad + 8 : r.top - pad - 8 - g.height;
      y = Math.min(Math.max(m, y), vh - g.height - m);
      mx = Math.min(Math.max(0, cx - x - MASCOT_W / 2), g.width - MASCOT_W);
      look = { x: Math.sign(cx - (x + mx + MASCOT_W / 2)) * 2.5, y: below ? -2.5 : 2.5 };
    }
    guide.style.transform = `translate(${Math.round(x)}px, ${Math.round(y)}px)`;
    // Drive along the rail: face the direction of travel while moving, then stop and look at the target.
    const from = parseFloat(loki.style.getPropertyValue("--mx")) || 0;
    if (Math.abs(from - mx) > 4 && !reducedMotion()) {
      loki.classList.add("moving");
      loki.style.setProperty("--lx", `${Math.sign(mx - from) * 2.5 || dir * 2.5}px`);
      loki.style.setProperty("--ly", "0px");
      clearTimeout(loki._stop);
      loki._stop = setTimeout(() => {
        loki.classList.remove("moving");
        loki.style.setProperty("--lx", `${look.x}px`);
        loki.style.setProperty("--ly", `${look.y}px`);
      }, 520);
    } else {
      loki.style.setProperty("--lx", `${look.x}px`);
      loki.style.setProperty("--ly", `${look.y}px`);
    }
    loki.style.setProperty("--mx", `${Math.round(mx)}px`);
    guide.style.setProperty("--tail", `${Math.round(mx + MASCOT_W / 2)}px`);
  }

  function go(d) {
    if (d > 0 && finishTyping()) return; // first tap completes the sentence
    finishTyping();
    if (d > 0 && steps[i].last) return close(true);
    const cur = steps[i];
    steps = avail();
    const at = steps.indexOf(cur); // -1 if its target just vanished: stay around the same place
    i = Math.min(Math.max(0, (at < 0 ? i - (d > 0 ? 1 : 0) : at) + d), steps.length - 1);
    render(d);
  }

  function close(finished) {
    markSeen();
    finishTyping();
    removeEventListener("resize", onResize);
    removeEventListener("keydown", onKey, true);
    document.documentElement.classList.remove("tour-open");
    root.classList.remove("in");
    root.classList.add("out");
    if (finished && !reducedMotion()) loki.style.setProperty("--mx", `${innerWidth}px`); // drives off to the right
    setTimeout(() => root.remove(), reducedMotion() ? 0 : 420);
    if (lastFocus instanceof HTMLElement && document.contains(lastFocus)) lastFocus.focus({ preventScroll: true });
    onDone({ finished });
  }

  const onResize = () => place(steps[i], 0);
  const onKey = (e) => {
    if (e.key === "Escape") { e.preventDefault(); close(false); }
    else if (e.key === "ArrowRight") { e.preventDefault(); go(1); }
    else if (e.key === "ArrowLeft") { e.preventDefault(); go(-1); }
    else if (e.key === "Tab") { // keep focus inside the bubble
      const f = [...root.querySelectorAll("button:not([hidden])")];
      const at = f.indexOf(document.activeElement);
      e.preventDefault();
      f[(at + (e.shiftKey ? -1 : 1) + f.length) % f.length]?.focus();
    }
  };
  addEventListener("resize", onResize);
  addEventListener("keydown", onKey, true);
  q(".tour-next").onclick = () => go(1);
  q(".tour-back").onclick = () => go(-1);
  q(".tour-skip").onclick = () => close(false);
  q(".tour-dim").onclick = () => go(1);
  q(".loki").onclick = () => { loki.classList.remove("hop"); void loki.offsetWidth; loki.classList.add("hop"); navigator.vibrate?.(8); };

  render();
}

/** The element for a selector, if it is on screen (fixed elements have no offsetParent, so use client rects). */
function visible(sel) {
  const el = document.querySelector(sel);
  if (!el || el.closest("[hidden]") || !el.getClientRects().length) return null;
  const r = el.getBoundingClientRect();
  return r.width && r.height && r.bottom > 0 && r.top < innerHeight ? el : null;
}
