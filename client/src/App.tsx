import { RotateCcw } from "lucide-react";
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import type { Arrival, AskContext, AskResponse, Brief, Option, SituationItem, VisualCommand } from "./contract";
import { HoldMusic } from "./hold";
import { onlyUnavailable, unavailableAnswer, unavailableIn } from "./scope";
import { hhmm } from "./format";
import { MapController, type DrawnItem, type Pick as MapPick, type Timer } from "./map/MapController";
import { MapLegend, PickCard, TrackChip, type PickInfo, type Tracking } from "./mapui";
import { Tikt } from "./Tikt";
import { CheckingPill, Dock, OptionsCard, SituationCard, TimerChip, Transcript, Welcome, type Item } from "./ui";
import { describeScreen, onScreenView, screenView } from "./screenView";
import { prefetchVoice, Voice, type AskBackendParams, type GetBriefParams, type VoiceStatus } from "./voice";
import { findJourneyOption, refreshWalkingPlan, type WalkingPlan } from "./walking";

type Located = { id: string; name: string; lon: number; lat: number; userLon: number; userLat: number };
type Cards = { id: number; situation: SituationItem[]; options: Option[]; summary: string | null; walking: WalkingPlan | null };
type DepartureTimer = Timer & { option?: Option };

function timerForOption(option: Option): DepartureTimer | null {
  if (!(option.departure > Date.now())) return null;
  const line = /^(?:Wait for the |Take the )?((?:IC|IR|ICE|EC|EN|NJ|TGV|RJX?|RE|PE|IRE|R|S|SN)\s?\d{0,3})\b/.exec(option.label)?.[1];
  return { label: line ? `${line} leaves in` : "Departure in", until: option.departure, option };
}

const sessionId = crypto.randomUUID?.() ?? String(Math.random()).slice(2);
const hold = new HoldMusic();
const log = (what: string, ms: number, extra = "") => console.info(`[timing] ${what} ${ms} ms${extra ? ` ${extra}` : ""}`);

// ---------- place briefs: preloaded before any slow model call, kept 20 s per place ----------
const briefs = new Map<string, { at: number; p: Promise<Brief | null> }>();
function fetchBrief(params: Record<string, string | null | undefined>): Promise<Brief | null> {
  const qs = new URLSearchParams(Object.entries(params).filter((e): e is [string, string] => Boolean(e[1]?.trim())));
  const key = qs.toString();
  if (!key) return Promise.resolve(null);
  const hit = briefs.get(key);
  if (hit && Date.now() - hit.at < 20_000) return hit.p;
  const t0 = performance.now();
  const p = fetch(`/api/brief?${key}`, { signal: AbortSignal.timeout(8000) })
    .then(async (r) => (r.ok ? ((await r.json()) as Brief) : null))
    .then((b) => (log("brief", Math.round(performance.now() - t0), `${key} related=${b?.related ?? "-"}`), b))
    .catch(() => (briefs.delete(key), null));
  briefs.set(key, { at: Date.now(), p });
  return p;
}
let active: { text: string; at: number; items: { id: string; title: string; until: string | null }[] } = { text: "- (not loaded yet)", at: 0, items: [] };
async function refreshActive() {
  try {
    const r = await fetch("/api/brief/active", { signal: AbortSignal.timeout(6000) });
    if (r.ok) {
      const j = (await r.json()) as { text: string; items: { id: string; title: string; until: string | null }[] };
      active = { text: j.text, at: Date.now(), items: j.items ?? [] };
    }
  } catch {
    /* keep the last list */
  }
  return active.text;
}

/** "Interrupted service: Olten - Aarau" → ["Olten", "Aarau"] */
function ends(title: string): [string, string] | null {
  const m = /(?:service:?|:)\s*(?:between\s+)?(.+?)\s+[–-]\s+(.+)$/i.exec(title);
  return m ? [m[1].trim(), m[2].trim()] : null;
}
/** The suggestion chips: from the passenger's station brief, else from the biggest disruptions now. */
function chipsFor(b: Brief | null): string[] {
  const out: string[] = [];
  if (b?.station) {
    const main = b.disruptions.find((d) => d.active && (d.effect === "interrupted" || d.effect === "limited"));
    const e = main && ends(main.title);
    if (main && e) {
      const other = e[0] === b.station.name ? e[1] : e[1] === b.station.name ? e[0] : e[1];
      out.push(`How do I get to ${other} now?`);
      out.push(`When does ${e[0]} – ${e[1]} reopen?`);
    }
    out.push(`Next trains from ${b.station.name}`);
    out.push("Track my train");
    return out.slice(0, 4);
  }
  for (const it of active.items.slice(0, 2)) {
    const e = ends(it.title);
    if (e) out.push(out.length === 0 ? `What's happening between ${e[0]} and ${e[1]}?` : `How do I get from ${e[0]} to ${e[1]} now?`);
  }
  out.push("Is my train running?");
  out.push("Track my train");
  return out.slice(0, 4);
}

/** One line for the agent: what the map now shows. */
function describeVisuals(vs: VisualCommand[]) {
  const bits: string[] = [];
  for (const v of vs) {
    if (v.type === "route" && v.style !== "alternative") bits.push(`the recommended route${v.label ? ` (${v.label})` : ""}`);
    if (v.type === "section") bits.push(`the section "${v.label ?? "affected"}" in ${v.colour ?? "red"}`);
    if (v.type === "train") bits.push(`the train${v.label ? ` "${v.label}"` : ""}`);
    if (v.type === "timer") bits.push(`a countdown "${v.label}"`);
  }
  const alt = vs.filter((v) => v.type === "route" && v.style === "alternative").length;
  if (alt) bits.push(`${alt} alternative route${alt > 1 ? "s" : ""}`);
  return bits.length ? `The passenger's map now shows ${bits.join(", ")}.` : "Nothing new on the map.";
}

const nextFrame = () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
const SUGGESTIONS = ["My IC from Zürich HB to Bern was cancelled", "Lausanne to Geneva Airport, fastest way", "Is Olten – Bern running normally?"];

export function App() {
  const mapEl = useRef<HTMLDivElement>(null);
  const sheetRef = useRef<HTMLElement>(null);
  const topRef = useRef<HTMLElement>(null);
  const ctl = useRef<MapController | null>(null);
  const voice = useRef<Voice | null>(null);

  const [items, setItems] = useState<Item[]>([]);
  const [cards, setCards] = useState<Cards | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const [timer, setTimer] = useState<DepartureTimer | null>(null);
  const cardsRef = useRef(cards);
  cardsRef.current = cards;
  const [located, setLocated] = useState<Located | null>(null);
  const [locating, setLocating] = useState(false);
  const [fleet, setFleet] = useState<{ count: number; source: "live" | "poll" } | null>(null);
  const [voiceStatus, setVoiceStatus] = useState<VoiceStatus>("idle");
  const [voiceMode, setVoiceMode] = useState<"listening" | "speaking">("listening");
  const [typing, setTyping] = useState(false);
  // the sheet: "peek" (only the dock), "half" (the map keeps the top half), "full" (reading the details)
  const [sheet, setSheet] = useState<"peek" | "half" | "full">("half");
  const timerOn = useRef(false);
  const [toast, setToast] = useState<string | null>(null);
  const [drawn, setDrawn] = useState<DrawnItem[]>([]);
  const [chips, setChips] = useState<string[]>(SUGGESTIONS);
  const [pick, setPick] = useState<PickInfo | null>(null);
  const [tracking, setTracking] = useState<Tracking | null>(null);
  const trackRef = useRef<Tracking | null>(null);
  trackRef.current = tracking;
  /** What the passenger tapped last: context for the next question ("and from here?") */
  const picked = useRef<{ stationId?: string; stationName?: string; sectionId?: string; sectionTitle?: string; routeId?: string } | null>(null);
  /** The place the current picture is about, re-checked live (see the watch below). */
  const watching = useRef<{ params: Record<string, string>; signature: string; offered: Set<string> } | null>(null);

  const itemsRef = useRef(items);
  itemsRef.current = items;
  const locatedRef = useRef(located);
  locatedRef.current = located;
  const lastIds = useRef<{ fromStopId: string | null; toStopId: string | null; trainId: string | null }>({ fromStopId: null, toStopId: null, trainId: null });
  const cardSeq = useRef(0);

  const say = useCallback((role: "passenger" | "assistant", text: string, extra?: string | null) => {
    setItems((list) => {
      const last = [...list].reverse().find((i) => i.kind === "msg");
      if (last?.kind === "msg" && last.role === role && last.text === text) return list; // the same line twice
      return [...list, { kind: "msg", key: `m${Date.now()}-${list.length}`, role, text, extra: extra ?? null }];
    });
  }, []);
  const showToast = useCallback((t: string) => {
    setToast(t);
    window.setTimeout(() => setToast((x) => (x === t ? null : x)), 3500);
  }, []);

  // ---------- map ----------
  useEffect(() => {
    if (!mapEl.current || ctl.current) return;
    const c = new MapController(mapEl.current);
    ctl.current = c;
    c.onTimer = (t) => {
      timerOn.current = Boolean(t);
      const matches = t && cardsRef.current?.options.filter((o) => o.departure === t.until);
      setTimer(t && matches?.length === 1 ? { ...t, option: matches[0] } : t);
    };
    c.onChange = setDrawn;
    c.onTrack = (t) => startTrackRef.current(t.trainId, t.stopId, t.label ?? undefined);
    c.onPick = (p) => void onPickRef.current(p);
    c.onFleet = (count, source) => setFleet((f) => (f && f.count === count && f.source === source ? f : { count, source }));
    c.padding = () => {
      const view = mapEl.current!.getBoundingClientRect();
      const top = topRef.current?.getBoundingClientRect();
      const sheet = sheetRef.current?.getBoundingClientRect();
      const desktop = view.width >= 900;
      // the countdown chip sits above the sheet; a highlighted train's label hangs below its ring
      const chip = (timerOn.current ? 56 : 0) + (ctl.current?.hasHighlight() ? 30 : 0);
      return desktop
        ? { top: 84, bottom: 48 + chip, left: (sheet?.right ?? 440) + 64, right: 96 }
        : { top: (top?.bottom ?? 70) + 48, bottom: Math.max(40, view.bottom - (sheet?.top ?? view.bottom - 300)) + 24 + chip, left: 44, right: 44 };
    };
    prefetchVoice();
    void refreshActive().then(() => setChips((c) => (locatedRef.current ? c : chipsFor(null))));
    // location first (one line of reason is on the welcome card); the browser asks the passenger
    window.setTimeout(() => locateRef.current(true), 600);
    const keep = window.setInterval(prefetchVoice, 60_000);
    // the list of active disruptions the agent starts with; a live conversation hears about changes
    const act = window.setInterval(async () => {
      const before = active.text;
      const now = await refreshActive();
      if (now !== before && voice.current?.live()) voice.current.context(`[App] The list of active disruptions changed. It is now:\n${now}`);
    }, 20_000);
    return () => {
      window.clearInterval(keep);
      window.clearInterval(act);
    };
  }, []);

  // the sheet's height: the map's attribution stays above it
  useEffect(() => {
    const s = sheetRef.current;
    if (!s) return;
    const ro = new ResizeObserver(() => document.documentElement.style.setProperty("--sheet-h", `${Math.round(s.getBoundingClientRect().height)}px`));
    ro.observe(s);
    return () => ro.disconnect();
  }, []);

  // keyboard on iOS: lift the sheet above it
  useEffect(() => {
    const vv = window.visualViewport;
    if (!vv) return;
    const f = () => document.documentElement.style.setProperty("--kb", `${Math.max(0, window.innerHeight - vv.height - vv.offsetTop)}px`);
    vv.addEventListener("resize", f);
    vv.addEventListener("scroll", f);
    return () => {
      vv.removeEventListener("resize", f);
      vv.removeEventListener("scroll", f);
    };
  }, []);

  // ---------- asking: the text box and the voice agent's tool share this path ----------
  const ask = useCallback(async (question: string, extra: Partial<AskContext> = {}): Promise<AskResponse> => {
    setChecking(true);
    setSheet((s) => (s === "peek" ? "half" : s));
    const here = locatedRef.current;
    const context: AskContext = {
      fromStopId: extra.fromStopId || picked.current?.stationId || here?.id || null,
      toStopId: extra.toStopId || null,
      trainId: extra.trainId || null,
      lat: here?.userLat ?? null,
      lon: here?.userLon ?? null,
      onScreen: screenView(),
    };
    const history = itemsRef.current.flatMap((i) => (i.kind === "msg" ? [{ role: i.role, text: i.text }] : [])).slice(-8);
    try {
      const r = await fetch("/api/v1/assistant/ask", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ question, context, history, sessionId }),
        signal: AbortSignal.timeout(60_000),
      });
      const res = (await r.json().catch(() => ({}))) as AskResponse & { error?: string };
      if (!r.ok || !res.answer) throw new Error(res.error ?? `HTTP ${r.status}`);
      await show(res, context);
      // keep this trip under live watch
      const ids = lastIds.current;
      const params: Record<string, string | null> = ids.fromStopId && ids.toStopId ? { from: ids.fromStopId, to: ids.toStopId } : { station: ids.fromStopId };
      const answerId = cardSeq.current;
      void fetchBrief(params).then((b) => b && cardSeq.current === answerId && watchRef.current(params, b));
      return res;
    } finally {
      setChecking(false);
    }
  }, []);

  /** Cards and map for an answer (from the back office or from a preloaded brief). */
  const show = useCallback(async (res: Pick<AskResponse, "visuals" | "options" | "situation" | "summary">, context: Partial<AskContext>) => {
    {
      const visuals: VisualCommand[] = res.visuals ?? [];
      const fresh = visuals.some((v) => v.type === "clear");
      if (fresh || res.options?.length || res.situation?.length) {
        const id = ++cardSeq.current;
        if (fresh || res.options?.length) watching.current = null;
        setCards((prev) => {
          const options = fresh || res.options?.length ? res.options ?? [] : prev?.options ?? [];
          return {
            id, options, summary: res.summary ?? null,
            situation: fresh || res.situation?.length ? res.situation ?? [] : prev?.situation ?? [],
            walking: refreshWalkingPlan(prev?.walking ?? null, options),
          };
        });
        setItems((list) => [...list.filter((i) => i.kind !== "cards"), { kind: "cards", key: `c${id}` }]);
        const rec = res.options?.find((o) => o.recommended) ?? res.options?.[0];
        if (rec || fresh) setSelected(rec?.journeyId ?? rec?.id ?? null);
      }
      // the map once the sheet has its new height, so the route is framed in the visible part
      await nextFrame();
      // every answer replaces the picture (the brief and the back office never stack), unless it says append
      ctl.current?.apply(visuals, Boolean((res as AskResponse).append));
      const st = (role: string) => visuals.find((v): v is Extract<VisualCommand, { type: "station" }> => v.type === "station" && v.role === role);
      lastIds.current = {
        fromStopId: context.fromStopId ?? st("origin")?.stopId ?? null,
        toStopId: context.toStopId ?? st("destination")?.stopId ?? null,
        trainId: visuals.find((v): v is Extract<VisualCommand, { type: "train" }> => v.type === "train")?.trainId ?? context.trainId ?? null,
      };
    }
  }, []);

  /** What the voice agent gets back: the answer to speak plus a compact view of the screen. */
  const toolResult = (res: AskResponse) =>
    JSON.stringify({
      answer: res.answer,
      followUp: res.followUp ?? null,
      options: (res.options ?? []).map((o) => ({ id: o.id, label: o.label, departs: hhmm(o.departure), arrives: hhmm(o.arrival), transfers: o.transfers, recommended: Boolean(o.recommended), why: o.why ?? null })),
      situation: (res.situation ?? []).map((s) => `${s.title}${s.where ? ` (${s.where})` : ""}: ${s.severity}`),
      unavailable: (res as AskResponse & { unavailable?: string[] }).unavailable ?? [],
      ids: lastIds.current,
      shown: describeVisuals(res.visuals ?? []),
    });

  const askBackend = useCallback(
    async (p: AskBackendParams) => {
      const question = String(p.question ?? "").trim();
      if (!question) return JSON.stringify({ error: "Empty question: ask the passenger what they need." });
      // out of scope (platforms, accessibility, fares, ...): no call, the honest answer at once
      if (onlyUnavailable(question)) {
        const u = unavailableIn(question);
        log("ask_backend", 0, "out of scope");
        return JSON.stringify({ answer: unavailableAnswer(u.words), unavailable: u.topics, shown: "Nothing new on the map." });
      }
      const t0 = performance.now();
      if (voice.current) voice.current.busy = true;
      hold.start(1000);
      try {
        const res = await ask(question, { fromStopId: p.fromStopId, toStopId: p.toStopId, trainId: p.trainId });
        log("ask_backend", Math.round(performance.now() - t0), `backend ${res.elapsedMs ?? "?"} ms`);
        return toolResult(res);
      } catch {
        showToast("The live timetable did not answer");
        return JSON.stringify({ error: "The live timetable did not answer. Apologise briefly and offer to try again." });
      } finally {
        hold.stop();
        if (voice.current) voice.current.busy = false;
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [ask, showToast],
  );

  /** What the voice agent gets from a brief: compact, plain facts, no geometry. */
  const briefResult = (b: Brief) =>
    JSON.stringify({
      place: b.place,
      relatedDisruption: b.related,
      disruptions: b.disruptions.filter((d) => !(b.from && b.to) || d.onRoute).slice(0, 3).map((d) => ({
        title: d.title,
        source: d.kind === "operator" ? "SBB control centre" : d.kind === "sbb" ? "SBB notice" : "detected from train movements",
        active: d.active,
        effect: d.effect,
        cause: d.cause,
        expectedEnd: d.until,
        bulletin: d.text.slice(0, 450),
        latest: d.measures.slice(-2).map((m) => m.slice(0, 320)),
        replacementBus: d.replacementBus,
        onTheRoute: d.onRoute ?? null,
      })),
      connections: !b.related ? [] : b.journeys.map((j) => ({ departs: j.departs, arrives: j.arrives, minutes: j.durationMin, changes: j.transfers, lateMin: j.lateMin, legs: j.legs, uncertainTime: j.uncertain })),
      summary: b.related ? b.say : null,
      next: b.related ? "Answer from this brief; no other call." : "No related disruption here. If the passenger wants a connection or anything live, say one short sentence that you are looking into it and call ask_backend.",
      shown: b.shown,
      ids: { stationId: b.station?.id ?? null, fromStopId: b.from?.id ?? null, toStopId: b.to?.id ?? null },
    });

  const getBrief = useCallback(
    async (p: GetBriefParams) => {
      const t0 = performance.now();
      const here = locatedRef.current;
      // a destination alone: from where the passenger is (their location or a station they tapped)
      const origin = picked.current?.stationId ?? here?.id;
      const params = p.from && p.to ? { from: p.from, to: p.to, line: p.line } : p.to && !p.station && origin ? { from: origin, to: p.to, line: p.line } : { station: p.station || p.from || p.to || (p.line ? here?.id : undefined), line: p.line };
      const b = await fetchBrief(params);
      if (!b) return JSON.stringify({ found: false, note: "No station, route or line recognised. Ask the passenger which station they mean, or use ask_backend." });
      setSheet((s) => (s === "peek" ? "half" : s));
      if (b.related) {
        await show(b, { fromStopId: b.from?.id ?? b.station?.id ?? null, toStopId: b.to?.id ?? null });
        watch(params, b);
      }
      log("get_brief", Math.round(performance.now() - t0), `${b.key} related=${b.related} (server ${b.elapsedMs} ms)`);
      return briefResult(b.related ? b : { ...b, shown: "Nothing new on the map yet." });
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [show],
  );

  // ---------- live: the place on the map is re-checked every 20 s; a change is drawn and told at once ----------
  const signature = (b: Brief) => b.disruptions.filter((d) => d.active).map((d) => `${d.id}:${d.effect}:${d.until}:${d.replacementBus}`).sort().join("|");
  const watch = (params: Record<string, string | null | undefined>, b: Brief) => {
    const clean = Object.fromEntries(Object.entries(params).filter((e): e is [string, string] => Boolean(e[1])));
    watching.current = { params: clean, signature: signature(b), offered: new Set(b.options.flatMap((o) => o.journeyId ? [o.journeyId] : [])) };
  };
  const watchRef = useRef(watch);
  watchRef.current = watch;
  useEffect(() => {
    const t = window.setInterval(async () => {
      const w = watching.current;
      if (!w) return;
      briefs.delete(new URLSearchParams(w.params).toString());
      const b = await fetchBrief(w.params);
      if (!b || watching.current !== w) return;
      // live times on the option cards (same journeys, new expected times)
      const fresh = new Map(b.options.filter((o) => o.journeyId).map((o) => [o.journeyId!, o]));
      // A completed route search includes visuals even when it finds no journeys. A failed lookup has none.
      const withdrawn = new Set<string>();
      const newlyOffered: Option[] = [];
      if (b.from && b.to && b.visuals.length) {
        for (const id of w.offered) if (!fresh.has(id)) withdrawn.add(id);
        for (const [id, option] of fresh) if (!w.offered.has(id)) newlyOffered.push(option);
        w.offered = new Set(fresh.keys());
      }
      const timedId = cardsRef.current?.walking?.option.journeyId;
      if (timedId && withdrawn.has(timedId)) showToast("Your timed departure is no longer listed.");
      setCards((c) => {
        if (!c || (!fresh.size && !withdrawn.size)) return c;
        const options = c.options.filter((o) => !o.journeyId || !withdrawn.has(o.journeyId)).map((o) => {
          const n = o.journeyId && fresh.get(o.journeyId);
          if (!n || (o.departure === n.departure && o.arrival === n.arrival)) return o;
          return { ...o, departure: n.departure, arrival: n.arrival };
        });
        for (const option of newlyOffered) if (!findJourneyOption(option, options)) options.push(option);
        return { ...c, options, walking: refreshWalkingPlan(c.walking, options) };
      });
      const sig = signature(b);
      if (sig === w.signature) return;
      const before = w.signature;
      w.signature = sig;
      // redraw the sections with their new state (append: the route the passenger chose stays)
      ctl.current?.apply(b.visuals.filter((v) => v.type === "section" || v.type === "remove"), true);
      const gone = before.split("|").filter((x) => x && !sig.includes(x.split(":")[0]));
      const main = b.disruptions.find((d) => d.active);
      const news = gone.length && !main ? `Good news: the disruption on ${b.place} is over.` : main ? `Update for ${b.place}: ${main.title}${main.until ? `, ${main.until}` : ""}.` : null;
      if (!news) return;
      showToast(news);
      setCards((c) => (c ? { ...c, situation: b.situation.length ? b.situation : c.situation } : c));
      // only for the trip the passenger is on, once per change, never over the passenger (Voice.notify gates it)
      if (b.related || gone.length) voice.current?.notify(`trip:${b.key}:${sig}`, `Live update for the passenger's trip (${b.place}): ${news} Tell the passenger in one short sentence; if the best connection changed, give the new one.`);
    }, 15_000);
    return () => window.clearInterval(t);
  }, [showToast]);

  // ---------- taps on the map: a card at once, and context for the assistant ----------
  const onPick = async (p: MapPick) => {
    if (p.kind === "section") {
      picked.current = { ...picked.current, sectionId: p.id, sectionTitle: p.label };
      setPick({ kind: "section", title: p.label || "Disruption", lines: ["Loading…"] });
      const d = (await fetch(`/api/brief/item?id=${encodeURIComponent(p.id)}`).then((r) => (r.ok ? r.json() : null)).catch(() => null)) as (Brief["disruptions"][number] & { lines: string[]; moreLines: number }) | null;
      if (!d) return setPick({ kind: "section", title: p.label || "Disruption", lines: ["No details for this section."] });
      setPick({ kind: "section", title: d.title, lines: [d.text, d.until ? `Expected end: ${d.until}` : "", d.lines.length ? `Lines: ${d.lines.join(", ")}${d.moreLines ? ` and ${d.moreLines} more` : ""}` : "", ...d.measures.slice(-2)].filter(Boolean), ask: `Tell me about the disruption ${d.title}` });
      voice.current?.context(`[App] The passenger tapped the section "${d.title}" on the map (${d.until ?? "end unknown"}). They may ask about it.`);
    } else if (p.kind === "station") {
      picked.current = { ...picked.current, stationId: p.id, stationName: p.label };
      setPick({ kind: "station", title: p.label, lines: ["Loading…"], stationId: p.id, lon: p.lon, lat: p.lat });
      const sit = (await fetch(`/api/v1/situation?station=${encodeURIComponent(p.id)}`).then((r) => (r.ok ? r.json() : null)).catch(() => null)) as { incidents?: { title: string; status?: string; lifecycle?: string }[]; departures?: { line: string; headsign: string; planned: number; expected?: number; delayMin?: number; cancelled?: boolean }[] } | null;
      const deps = (sit?.departures ?? []).slice(0, 5).map((d) => `${hhmm(d.expected ?? d.planned)}  ${d.line} → ${d.headsign}${d.cancelled ? " · cancelled" : d.delayMin && d.delayMin >= 2 ? ` · +${d.delayMin}′` : ""}`);
      const inc = (sit?.incidents ?? []).filter((i) => i.lifecycle !== "ENDED" && i.status !== "ended").slice(0, 2).map((i) => `⚠ ${i.title}`);
      const arr = ((await fetch(`/api/v1/stations/${encodeURIComponent(p.id)}/arrivals`).then((r) => (r.ok ? r.json() : [])).catch(() => [])) as Arrival[]).slice(0, 4);
      setPick({
        kind: "station",
        title: p.label,
        lines: [...inc, ...(deps.length ? ["Departures", ...deps] : ["No departures in the next minutes."])],
        rows: arr.map((a) => ({ label: a.headsign === p.label ? `${a.line} (ends here)` : `${a.line} → ${a.headsign}`, detail: a.status === "at_station" ? "at the station" : `in ${Math.max(1, Math.round(a.etaSec / 60))} min${a.delaySec >= 120 ? ` · +${Math.round(a.delaySec / 60)}′` : ""}`, late: a.delaySec >= 120, onTap: () => startTrack(a.trainId, p.id, p.label) })),
        stationId: p.id,
        lon: p.lon,
        lat: p.lat,
        ask: `What is happening at ${p.label}?`,
      });
      voice.current?.context(`[App] The passenger tapped ${p.label} station (stop id ${p.id}) on the map.`);
    } else if (p.kind === "route") {
      picked.current = { ...picked.current, routeId: p.id };
      const o = cards?.options.find((x) => x.journeyId === p.id);
      setPick({ kind: "route", title: p.label || "Route", lines: [o ? `${hhmm(o.departure)} → ${hhmm(o.arrival)} · ${o.transfers} change${o.transfers === 1 ? "" : "s"}` : "", p.legs?.length ? `Lines: ${p.legs.join(" → ")}` : "", o?.why ?? ""].filter(Boolean), ask: `Tell me more about the route ${p.label}` });
      if (o) void choose(o);
      voice.current?.context(`[App] The passenger tapped the route "${p.label}" on the map.`);
    } else if (p.kind === "train") {
      // ktor_app's TrainDetail ({train: TrainSummary, stops, incidents}), fed by the MQTT trains/+/info + fleet
      const detail = (await fetch(`/api/v1/trains/${encodeURIComponent(p.id)}`).then((r) => (r.ok ? r.json() : null)).catch(() => null)) as { train?: { headsign?: string | null; destination?: { name?: string } | null; nextStop?: { stopId?: string; name?: string } | null; status?: string } } | null;
      const info = detail?.train;
      const next = info?.nextStop ? { id: info.nextStop.stopId, name: info.nextStop.name } : undefined;
      const line = p.label.replace(/^([A-Z]+)(\d)/, "$1 $2");
      const to = info?.headsign ?? info?.destination?.name ?? "";
      setPick({
        kind: "train",
        title: `${line}${to ? ` → ${to}` : ""}`,
        lines: [p.delay && p.delay >= 120 ? `About ${Math.round(p.delay / 60)} min late` : "On time", next?.name ? `Next stop: ${next.name}` : ""].filter(Boolean),
        rows: next?.id ? [{ label: `Track it to ${next.name}`, detail: "live", late: false, onTap: () => startTrack(p.id, next.id!, next.name) }] : [],
        ask: `Tell me about the ${line}${to ? ` to ${to}` : ""}`,
      });
      picked.current = { ...picked.current };
      voice.current?.context(`[App] The passenger tapped the train ${line}${to ? ` to ${to}` : ""} (train id ${p.id}) on the map.`);
    } else {
      const r = await fetch(`/api/nearest?lat=${p.lat}&lon=${p.lon}`).then((x) => (x.ok ? x.json() : null)).catch(() => null);
      if (!r) return;
      setPick({ kind: "point", title: r.name, lines: [`Nearest station · ${r.distanceKm} km`], stationId: r.id, lon: r.lon, lat: r.lat });
    }
  };
  // ---------- live tracking of the train the passenger waits for ----------
  const startTrack = (trainId: string, stopId: string, stationName?: string) => {
    setPick(null);
    setTracking({ trainId, stopId, stationName: stationName ?? "", follow: false, arrival: null, at: Date.now() });
    ctl.current?.highlight(trainId, "Your train");
    voice.current?.context(`[App] The passenger is now tracking train ${trainId} arriving at stop ${stopId}${stationName ? ` (${stationName})` : ""}.`);
  };
  const startTrackRef = useRef(startTrack);
  startTrackRef.current = startTrack;
  const stopTrack = () => {
    const t = trackRef.current;
    if (t) ctl.current?.unhighlight(t.trainId);
    setTracking(null);
  };
  useEffect(() => {
    if (!tracking) return;
    let stop = false;
    let told = false;
    let framed = false;
    const poll = async () => {
      const t = trackRef.current;
      if (!t || stop) return;
      const list = (await fetch(`/api/v1/stations/${encodeURIComponent(t.stopId)}/arrivals`, { signal: AbortSignal.timeout(4000) }).then((r) => (r.ok ? r.json() : null)).catch(() => null)) as Arrival[] | null;
      if (stop || !list) return;
      const a = list.find((x) => x.trainId === t.trainId) ?? null;
      setTracking((cur) => (cur && cur.trainId === t.trainId ? { ...cur, arrival: a, at: Date.now(), gone: !a && Boolean(cur.arrival) } : cur));
      if (a?.status === "at_station" && !told) {
        told = true;
        voice.current?.notify(`arrived:${a.trainId}`, `The tracked train ${a.line} to ${a.headsign} is now at the station. Tell the passenger in one short sentence.`);
      }
      if (a && (t.follow || !framed)) ctl.current?.flyTo(a.lon, a.lat, t.follow ? 12.5 : 11);
      if (a) framed = true;
    };
    void poll();
    const iv = window.setInterval(poll, 2500);
    return () => {
      stop = true;
      window.clearInterval(iv);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tracking?.trainId, tracking?.stopId]);

  const onPickRef = useRef(onPick);
  onPickRef.current = onPick;
  const pickAction = (action: "ask" | "from" | "to") => {
    const p = pick;
    if (!p) return;
    setPick(null);
    if (action === "ask" && p.ask) return void submitText(p.ask);
    if (!p.stationId) return;
    if (action === "from") {
      picked.current = { ...picked.current, stationId: p.stationId, stationName: p.title };
      ctl.current?.apply([{ type: "station", stopId: p.stationId, name: p.title, lon: p.lon ?? 0, lat: p.lat ?? 0, role: "origin", label: p.title }], true);
      voice.current?.context(`[App] The passenger chose to start at ${p.title} (stop id ${p.stationId}).`);
      showToast(`Starting at ${p.title}`);
    } else {
      void submitText(`How do I get to ${p.title} from ${picked.current?.stationName ?? locatedRef.current?.name ?? "here"}?`);
    }
  };

  // ---------- voice ----------
  const askRef = useRef(askBackend);
  askRef.current = askBackend;
  const briefRef = useRef(getBrief);
  briefRef.current = getBrief;
  if (!voice.current)
    voice.current = new Voice({
      status: (s) => {
        setVoiceStatus(s);
        if (s === "live") {
          const here = locatedRef.current;
          if (here) voice.current?.context(`[App] The passenger is at or near ${here.name} station (stop id ${here.id}).`);
          const v = screenView();
          if (v) voice.current?.context(`[App] Behind the assistant, the passenger has ${describeScreen(v)} open. "This train", "this line" or "here" may refer to it.`);
        }
      },
      mode: (m) => {
        setVoiceMode(m);
        hold.setSpeaking(m === "speaking");
      },
      transcript: (role, text) => {
        say(role, text);
        // preload at once what the passenger's words point at (the agent's get_brief then hits the cache)
        if (role === "passenger") void fetchBrief({ q: text });
      },
      error: (m) => showToast(m),
      bargeIn: () => hold.setSpeaking(true),
      userSpeech: () => hold.userSpeech(),
      askBackend: (p) => askRef.current(p),
      getBrief: (p) => briefRef.current(p),
      variables: () => {
        const here = locatedRef.current;
        return { active_disruptions: active.text, passenger_location: here ? `at or near ${here.name} station (stop id ${here.id})` : "unknown" };
      },
    });
  // the passenger opened another screen behind the assistant: the voice agent hears about it (the text path reads
  // screenView() on every question)
  useEffect(() => onScreenView((v) => {
    if (v) voice.current?.context(`[App] Behind the assistant, the passenger now has ${describeScreen(v)} open. "This train", "this line" or "here" may refer to it.`);
  }), []);
  const levels = useCallback(() => voice.current?.levels() ?? { input: 0, output: 0 }, []);
  // Tikt speaks for the assistant: while the voice agent talks, his mouth follows the audio level.
  const speechLevel = useCallback(() => levels().output, [levels]);
  const speaking = voiceStatus === "live" && voiceMode === "speaking";
  const talk = () => {
    hold.unlock();
    const v = voice.current!;
    if (v.status === "idle") void v.start();
    else v.stop();
  };

  const submitText = async (raw: string) => {
    const text = raw.trim();
    if (!text || checking) return;
    say("passenger", text);
    const v = voice.current!;
    if (v.live()) {
      v.sendText(text);
      return;
    }
    // the same triage as the voice agent: out of scope at once, a related disruption from the preloaded brief,
    // everything else from the back office
    if (onlyUnavailable(text)) return say("assistant", unavailableAnswer(unavailableIn(text).words));
    const t0 = performance.now();
    const origin = picked.current?.stationId ?? locatedRef.current?.id;
    const b = await fetchBrief({ q: text, from: origin && /\b(to|towards|nach|à|a|para)\s+\p{Lu}/u.test(text) && !/\bfrom\b/i.test(text) ? origin : null });
    if (b?.related && !/\b(how does|how do .* work|what is|what does|explain|why|where is|which trains?|track)\b/i.test(text)) {
      await show(b, { fromStopId: b.from?.id ?? b.station?.id ?? null, toStopId: b.to?.id ?? null });
      if (b.from && b.to) watch({ from: b.from.id, to: b.to.id }, b);
      log("text answer from brief", Math.round(performance.now() - t0));
      return say("assistant", b.say, "Would you like me to look for another way?");
    }
    try {
      const res = await ask(text);
      log("text answer from back office", Math.round(performance.now() - t0));
      say("assistant", res.answer, res.followUp);
    } catch {
      say("assistant", "I'm sorry, I couldn't reach the live timetable just now. Please try again in a moment.");
    }
  };

  // ---------- options ----------
  const choose = async (o: Option) => {
    const id = o.journeyId ?? o.id;
    setSelected(id);
    setTimer(timerForOption(o));
    voice.current?.context(`[App] The passenger tapped option ${o.id} on the screen: ${o.label}, leaving ${hhmm(o.departure)}, arriving ${hhmm(o.arrival)}.`);
    const c = ctl.current;
    if (c && o.journeyId) {
      if (!c.selectRoute(o.journeyId)) await c.showJourney(o.journeyId, o.label).catch(() => false);
      c.fitRoute(o.journeyId);
    }
  };

  const setWalk = (plan: WalkingPlan | null) => {
    setCards((c) => c && { ...c, walking: refreshWalkingPlan(plan, c.options) });
    if (!plan) {
      const option = cards?.options.find((o) => (o.journeyId ?? o.id) === selected);
      if (option) setTimer(timerForOption(option));
    }
  };
  const walking = cards?.walking;
  const walkingDeparture = walking?.option.departure;
  useEffect(() => {
    if (!walkingDeparture) return;
    const expire = () => {
      if (Date.now() >= walkingDeparture) setCards((c) =>
        c?.walking?.option.departure === walkingDeparture ? { ...c, walking: null } : c);
    };
    const timeout = window.setTimeout(expire, Math.max(0, walkingDeparture - Date.now()));
    document.addEventListener("visibilitychange", expire);
    return () => {
      window.clearTimeout(timeout);
      document.removeEventListener("visibilitychange", expire);
    };
  }, [walkingDeparture]);

  // ---------- location ----------
  const locate = (quiet = false) => {
    if (!navigator.geolocation) return quiet ? undefined : showToast("Location is not available on this device");
    setLocating(true);
    navigator.geolocation.getCurrentPosition(
      async (pos) => {
        try {
          const { latitude: lat, longitude: lon } = pos.coords;
          const r = await fetch(`/api/nearest?lat=${lat}&lon=${lon}`);
          const st = (await r.json()) as { id: string; name: string; lon: number; lat: number; distanceKm: number };
          if (!r.ok) throw new Error();
          setLocated({ id: st.id, name: st.name, lon: st.lon, lat: st.lat, userLat: lat, userLon: lon });
          ctl.current?.showMe(lon, lat, st);
          voice.current?.context(`[App] The passenger is at or near ${st.name} station (stop id ${st.id}).`);
          void fetchBrief({ station: st.id }).then((b) => b && setChips(chipsFor(b)));
          showToast(`You are near ${st.name}`);
        } catch {
          showToast("Could not find the nearest station");
        } finally {
          setLocating(false);
        }
      },
      (err) => {
        setLocating(false);
        if (!quiet) showToast(err.code === 1 ? "Location permission was denied" : "Could not find your location");
      },
      { enableHighAccuracy: true, timeout: 12000, maximumAge: 60000 },
    );
  };

  const locateRef = useRef(locate);
  locateRef.current = locate;

  const restart = () => {
    voice.current?.stop();
    watching.current = null;
    cardSeq.current++;
    setItems([]);
    setCards(null);
    setSelected(null);
    setTimer(null);
    timerOn.current = false;
    setSheet("half");
    ctl.current?.clear();
    ctl.current?.home();
  };

  // ---------- sheet ----------
  const bodyRef = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const b = bodyRef.current;
    if (b && items.length) b.scrollTo({ top: b.scrollHeight, behavior: items.length > 1 ? "smooth" : "auto" });
  }, [items, checking]);
  const drag = useRef<{ y: number; moved: boolean } | null>(null);

  const empty = items.length === 0;
  const timedOption = timer?.option && findJourneyOption(timer.option, cards?.options ?? []);
  const departureTimer = timer?.option ? timedOption?.departure && { label: timer.label, until: timedOption.departure } : timer;
  timerOn.current = !walking && Boolean(departureTimer);
  return (
    <main className="app" data-sheet={sheet}>
      <div ref={mapEl} className="map" aria-label="Map of the Swiss railway with live trains" />

      <header ref={topRef} className="topbar">
        <div className="brand">
          <Tikt className="mark mark--tikt" mood={checking ? "think" : undefined} voiceLevel={speaking ? speechLevel : undefined} />
          <div>
            <h1>Tikt · Travel assistant</h1>
            <p className="status">
              <i className={`dot ${fleet ? "dot--live" : ""}`} />
              {fleet ? <>Live · <b>{fleet.count.toLocaleString("en")}</b> trains</> : "Connecting to the live network"}
              {located && <> · Near <b>{located.name}</b></>}
            </p>
          </div>
        </div>
        {!empty && (
          <button type="button" className="btn btn-ghost btn-circle icon-btn" onClick={restart} title="New conversation" aria-label="New conversation">
            <RotateCcw size={18} />
          </button>
        )}
      </header>

      <MapLegend items={drawn} onRemove={(k) => ctl.current?.remove(k)} onToggle={(k) => ctl.current?.toggle(k)} onClear={() => ctl.current?.clear()} onUndo={() => ctl.current?.undo()} />
      {pick && <PickCard info={pick} onClose={() => setPick(null)} onAction={pickAction} />}
      {tracking && !pick && <TrackChip t={tracking} onClose={stopTrack} onFollow={() => setTracking((t) => (t ? { ...t, follow: !t.follow } : t))} />}
      <div className="overlays">{checking ? <CheckingPill /> : !walking && departureTimer ? <TimerChip timer={departureTimer} /> : null}</div>

      <section ref={sheetRef} className="sheet" data-state={sheet} data-empty={empty}>
        <button
          type="button"
          className="handle"
          aria-label={sheet === "peek" ? "Show the conversation" : "Hide the conversation"}
          onClick={(event) => {
            if (event.detail === 0) setSheet((s) => s === "peek" ? "half" : "peek");
          }}
          onPointerDown={(e) => {
            drag.current = { y: e.clientY, moved: false };
            e.currentTarget.setPointerCapture(e.pointerId);
          }}
          onPointerMove={(e) => {
            const d = drag.current;
            if (!d || d.moved) return;
            if (e.clientY - d.y > 28) (d.moved = true), setSheet((s) => (s === "full" ? "half" : "peek"));
            if (d.y - e.clientY > 28) (d.moved = true), setSheet((s) => (s === "peek" ? "half" : "full"));
          }}
          onPointerUp={() => {
            if (drag.current && !drag.current.moved) setSheet((s) => (s === "peek" ? "half" : "peek"));
            drag.current = null;
          }}
        >
          <span />
        </button>
        {walking && <TimerChip walking={walking} />}
        <div ref={bodyRef} className="sheet-body">
          {empty ? (
            <Welcome suggestions={chips} onPick={(s) => void submitText(s)} place={located?.name ?? null} onLocate={() => locate()} locating={locating} />
          ) : (
            <Transcript
              items={items}
              checking={checking}
              speechLevel={speaking ? speechLevel : undefined}
              renderCards={() =>
                cards && (
                  <>
                    {cards.situation.length > 0 && <SituationCard items={cards.situation} />}
                    {cards.options.length > 0 && <OptionsCard options={cards.options} selected={selected} walking={cards.walking} onChoose={(o) => void choose(o)} onWalk={setWalk} />}
                  </>
                )
              }
            />
          )}
        </div>
        <Dock
          typing={typing}
          setTyping={setTyping}
          onSubmit={submitText}
          busy={checking}
          voiceStatus={voiceStatus}
          voiceMode={voiceMode}
          checking={checking}
          onTalk={talk}
          levels={levels}
          onLocate={() => locate()}
          locating={locating}
          located={Boolean(located)}
        />
      </section>

      {toast && (
        <div className="toast" role="status">
          {toast}
        </div>
      )}
    </main>
  );
}
