import { ArrowRight, RotateCcw, X } from "lucide-react";
import { Loki } from "../Loki";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { MapController } from "../map/MapController";
import { BoardingCard } from "./BoardingCard";
import { replayMapStyle, replayVisuals, restrictionId, routeBounds, simulatedPoint, timeline, train, tram } from "./stettbach";
import "./boarding.css";

type Choice = "train" | "tram";
type TrackUpdate = "published" | "queued" | "changed";
const fitExtent: [[number, number], [number, number]] = [[routeBounds[0], routeBounds[1]], [routeBounds[2], routeBounds[3]]];

export function StettbachReplay() {
  const [choice, setChoice] = useState<Choice | null>(null);
  const [step, setStep] = useState(0);
  const [focused, setFocused] = useState(false);
  const [track, setTrack] = useState<TrackUpdate>("published");
  const [online, setOnline] = useState(navigator.onLine);
  const [mapAvailable, setMapAvailable] = useState(true);
  const mapNode = useRef<HTMLDivElement>(null);
  const top = useRef<HTMLElement>(null);
  const sheet = useRef<HTMLElement>(null);
  const contentHeading = useRef<HTMLHeadingElement>(null);
  const advanceFocus = useRef(false);
  const map = useRef<MapController | null>(null);
  const update = timeline[step];
  const confirmed = choice === "train" && step === timeline.length - 1;
  const service = choice === "tram" ? tram : confirmed ? { ...train, point: track === "changed" ? simulatedPoint : train.point } : null;
  const point = service?.point;
  const mapPlan = choice === "tram" ? "tram" : confirmed ? "rail" : "disrupted";

  useLayoutEffect(() => {
    const node = mapNode.current;
    if (!node) return;
    let controller: MapController;
    try {
      controller = new MapController(node, { style: replayMapStyle, bounds: fitExtent });
    } catch {
      setMapAvailable(false);
      return;
    }
    map.current = controller;
    controller.padding = () => {
      const view = node.getBoundingClientRect();
      const panel = sheet.current?.getBoundingClientRect();
      const header = top.current?.getBoundingClientRect();
      return view.width >= 900
        ? { top: 72, left: (panel?.right ?? 436) + 52, bottom: 60, right: 72 }
        : { top: (header?.bottom ?? 72) + 22, left: 26, bottom: view.bottom - (panel?.top ?? view.bottom) + 24, right: 26 };
    };
    const observer = new ResizeObserver(() => {
      node.style.setProperty("--sheet-h", `${sheet.current?.offsetHeight ?? 0}px`);
    });
    if (sheet.current) observer.observe(sheet.current);
    return () => {
      observer.disconnect();
      controller.dispose();
      map.current = null;
    };
  }, []);

  useLayoutEffect(() => {
    map.current?.apply(replayVisuals(mapPlan));
  }, [mapPlan]);

  useLayoutEffect(() => {
    if (choice === "tram" && step === timeline.length - 1) map.current?.apply([{ type: "remove", id: restrictionId }], true);
  }, [choice, step]);

  useLayoutEffect(() => {
    if (focused && point) map.current?.focusBoarding(point);
    else map.current?.clearBoarding();
  }, [focused, point]);

  useLayoutEffect(() => {
    if (!advanceFocus.current) return;
    advanceFocus.current = false;
    contentHeading.current?.focus();
  }, [choice, step]);

  useEffect(() => {
    const connected = () => {
      setOnline(true);
      setTrack((state) => state === "queued" ? "changed" : state);
    };
    const disconnected = () => setOnline(false);
    window.addEventListener("online", connected);
    window.addEventListener("offline", disconnected);
    return () => {
      window.removeEventListener("online", connected);
      window.removeEventListener("offline", disconnected);
    };
  }, []);

  const choose = (next: Choice) => {
    advanceFocus.current = true;
    setChoice(next);
  };
  const nextUpdate = () => {
    if (choice === "train" && step === timeline.length - 2) advanceFocus.current = true;
    setStep((i) => Math.min(i + 1, timeline.length - 1));
  };
  const restart = () => {
    advanceFocus.current = true;
    setChoice(null);
    setStep(0);
    setFocused(false);
    setTrack("published");
    map.current?.fit(routeBounds);
  };
  const toggleBoarding = () => {
    if (focused) map.current?.fit(routeBounds);
    setFocused(!focused);
  };
  const changeTrack = () => setTrack(navigator.onLine ? "changed" : "queued");
  const checkedAt = track === "changed" ? "19:40" : choice === "tram" ? "19:35" : "19:37";
  const status = !online
    ? `Offline · Last checked at ${checkedAt}`
    : track === "changed"
      ? "Simulated change · 19:40"
      : choice === "tram"
        ? step === timeline.length - 1 ? "Tram plan kept after the 19:37 update" : "Scheduled departure · 19:35 replay"
        : "Stop confirmed at 19:37 · Published tracks";

  return (
    <main className="app app--boarding">
      <a className="boarding-skip" href="#boarding-guidance">Skip to journey guidance</a>
      <div ref={mapNode} className="map" role="region" aria-label="Rail map from Zürich HB to Stettbach" />
      <header ref={top} className="topbar">
        <div className="brand">
          <Loki className="mark mark--loki" />
          <div>
            <h1>Loki · Travel assistant</h1>
            <p className="status">Disruption replay · 25 Sep 2026</p>
          </div>
        </div>
        <a className="btn btn-ghost btn-circle icon-btn boarding-exit" href={import.meta.env.BASE_URL} aria-label="Exit replay" title="Exit replay">
          <X size={19} aria-hidden="true" />
        </a>
      </header>

      {!mapAvailable && <p className="boarding-map-fallback" role="status">Map unavailable. Boarding directions are below.</p>}
      <section ref={sheet} id="boarding-guidance" className="sheet boarding-sheet" aria-label="Journey guidance" tabIndex={-1}>
        <div className="sheet-body boarding-body">
          {service ? (
            <BoardingCard
              service={service}
              focused={focused}
              status={status}
              action={choice === "tram" ? "Show tram stop" : undefined}
              onToggle={toggleBoarding}
              headingRef={contentHeading}
            />
          ) : (
            <article className="card boarding-card replay-notice" aria-labelledby="replay-trip">
              <h2 id="replay-trip" ref={contentHeading} tabIndex={-1}>Zürich HB to Stettbach</h2>
              <p className="replay-bulletin">{step === 0 ? "Trip alert" : "SBB bulletin"} · {update.time}</p>
              <p className="replay-notice-copy" role="status">{update.notice}</p>
              {choice === "train" ? (
                <button type="button" className="btn btn-primary boarding-action" onClick={nextUpdate}>
                  Next SBB update <ArrowRight size={18} aria-hidden="true" />
                </button>
              ) : (
                <div className="boarding-choices">
                  <button type="button" className="btn btn-primary boarding-action" onClick={() => choose("tram")}>Take tram 7 <ArrowRight size={18} aria-hidden="true" /></button>
                  <button type="button" className="btn btn-outline boarding-action" onClick={() => choose("train")}>Wait for the S 9</button>
                </div>
              )}
            </article>
          )}
          {(choice || step > 0) && (
            <div className="boarding-replay-controls">
              <button className="btn btn-ghost btn-sm" type="button" onClick={restart}><RotateCcw size={15} aria-hidden="true" /> Restart replay</button>
              {choice === "tram" && step < timeline.length - 1 && <button className="btn btn-ghost btn-sm" type="button" onClick={nextUpdate}>Next SBB update</button>}
              {confirmed && focused && track === "published" && <button className="btn btn-ghost btn-sm" type="button" onClick={changeTrack}>Simulate track change</button>}
              {track === "queued" && <p role="status">Simulated update queued for reconnect</p>}
            </div>
          )}
        </div>
      </section>
    </main>
  );
}
