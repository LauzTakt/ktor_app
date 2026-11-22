import { ArrowRight, ArrowUp, ChevronDown, Clock3, Footprints, Keyboard, LocateFixed, Mic, Square } from "lucide-react";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import type { Option, SituationItem } from "./contract";
import { Loki } from "./Loki";
import { countdown, duration, greeting, hhmm, sbbText } from "./format";
import type { Timer } from "./map/MapController";
import type { VoiceStatus } from "./voice";
import { BOARDING_MINUTES, leaveBy, walkingPhase, waitRemaining, type WalkingPlan } from "./walking";

export type Item = { kind: "msg"; key: string; role: "passenger" | "assistant"; text: string; extra: string | null } | { kind: "cards"; key: string };

export function Welcome({ suggestions, onPick, place, onLocate, locating }: { suggestions: string[]; onPick: (s: string) => void; place: string | null; onLocate: () => void; locating: boolean }) {
  return (
    <div className="welcome">
      <div className="welcome-hi">
        <Loki className="welcome-loki" mood="hello" />
        <h2>
          {greeting()}, I'm Loki.
          <span>Your travel assistant</span>
        </h2>
      </div>
      {place ? (
        <p>
          Near <b>{place}</b>. Ask me anything about your trip.
        </p>
      ) : (
        <p className="welcome-loc">
          <span>Share your location and I'll show what's happening at your station.</span>
          <button type="button" onClick={onLocate} disabled={locating}>
            <LocateFixed size={15} /> {locating ? "Locating…" : "Use my location"}
          </button>
        </p>
      )}
      <ul className="suggestions" aria-label="Examples">
        {suggestions.map((s) => (
          <li key={s}>
            <button type="button" onClick={() => onPick(s)}>
              <span className="sugg-text">{s}</span>
              <ArrowRight size={16} aria-hidden="true" />
            </button>
          </li>
        ))}
      </ul>
      <a className="replay-link" href={`${import.meta.env.BASE_URL}?replay=stettbach`}>
        Replay the Stettbach disruption <ArrowRight size={15} aria-hidden="true" />
      </a>
    </div>
  );
}

/** The conversation. Loki speaks for the assistant: his face sits next to each run of assistant messages (on the
 *  last one, like a messenger avatar); the newest one talks while the voice agent speaks. */
export function Transcript({ items, checking, renderCards, speechLevel }: { items: Item[]; checking: boolean; renderCards: () => ReactNode; speechLevel?: () => number }) {
  const isLoki = (i: Item | undefined) => i?.kind === "msg" && i.role === "assistant";
  const lastLoki = checking ? -1 : items.findLastIndex(isLoki);
  return (
    <div className="transcript">
      {items.map((i, k) =>
        i.kind === "cards" ? (
          <div key={i.key} className="cards">
            {renderCards()}
          </div>
        ) : i.role === "assistant" ? (
          <div key={i.key} className="say">
            {isLoki(items[k + 1]) || (checking && k === items.length - 1) ? <span className="avatar-gap" /> : <Loki className={k === lastLoki ? "avatar said" : "avatar"} voiceLevel={k === lastLoki ? speechLevel : undefined} />}
            <div className="bubble bubble--assistant">
              <p>{i.text}</p>
              {i.extra && <p className="bubble-extra">{i.extra}</p>}
            </div>
          </div>
        ) : (
          <div key={i.key} className={`bubble bubble--${i.role}`}>
            <p>{i.text}</p>
            {i.extra && <p className="bubble-extra">{i.extra}</p>}
          </div>
        ),
      )}
      {checking && (
        <div className="say">
          <Loki className="avatar" mood="think" />
          <div className="bubble bubble--assistant bubble--checking" role="status" aria-label="Loki is checking the live timetable">
            <span className="dots" aria-hidden="true">
              <i />
              <i />
              <i />
            </span>
          </div>
        </div>
      )}
    </div>
  );
}

const SOURCE: Record<string, string> = { sbb: "SBB", staff: "SBB staff", auto: "Live data" };
export function SituationCard({ items }: { items: SituationItem[] }) {
  const shown = items.slice(0, 3);
  return (
    <article className="card card--situation" aria-label="What's happening">
      <ul className="situation">
        {shown.map((s) => (
          <li key={s.id}>
            <i className={`sev sev--${s.severity}`} aria-hidden="true" />
            <div>
              <p className="sit-title">{sbbText(s.title)}</p>
              <p className="sit-meta">{sbbText([s.where, s.source ? SOURCE[s.source] ?? s.source : null].filter(Boolean).join(" · "))}</p>
            </div>
          </li>
        ))}
      </ul>
      {items.length > shown.length && <p className="more">{items.length - shown.length} more on the way</p>}
    </article>
  );
}

type OptionsProps = {
  options: Option[];
  selected: string | null;
  walking: WalkingPlan | null;
  onChoose: (o: Option) => void;
  onWalk: (plan: WalkingPlan | null) => void;
};

export function OptionsCard({ options, selected, walking, onChoose, onWalk }: OptionsProps) {
  const selectedJourney = options.find((option) => (option.journeyId ?? option.id) === selected)?.journeyId;
  const [canWatch, setCanWatch] = useState(false);
  useEffect(() => {
    const abort = new AbortController();
    fetch("/api/v1/trip-watches/availability", { signal: abort.signal })
      .then((response) => response.ok ? response.json() : null)
      .then((config) => setCanWatch(config?.enabled === true))
      .catch(() => {});
    return () => abort.abort();
  }, []);
  return (
    <article className="card">
      <h3>Your options</h3>
      <ul className="options">
        {options.slice(0, 4).map((o) => {
          const id = o.journeyId ?? o.id;
          const on = selected === id;
          const plan = walking?.option === o ? walking : null;
          return (
            <li key={id}>
              <button type="button" className="option" data-on={on} data-rec={Boolean(o.recommended)} onClick={() => onChoose(o)} aria-pressed={on}>
                <span className="opt-top">
                  <span className="opt-times">
                    {hhmm(o.departure)} <span className="arrow">→</span> {hhmm(o.arrival)}
                  </span>
                  {o.recommended && <span className="pill pill--rec">Recommended</span>}
                </span>
                <span className="opt-label">{sbbText(o.label)}</span>
                <span className="opt-meta">
                  {sbbText([duration(o.arrival - o.departure), /\bdirect\b/i.test(o.label) ? null : o.transfers === 0 ? "Direct" : `${o.transfers} change${o.transfers > 1 ? "s" : ""}`, o.why ? o.why.charAt(0).toUpperCase() + o.why.slice(1) : null].filter(Boolean).join(" · "))}
                </span>
              </button>
              {(on || plan) && o.departure > Date.now() && (
                <WalkingTimeControl option={o} plan={plan} replacing={Boolean(walking && !plan)} onWalk={onWalk} />
              )}
            </li>
          );
        })}
      </ul>
      {canWatch && selectedJourney && (
        <a className="option-watch" href={`/trip-watch.html?${new URLSearchParams({ journeyId: selectedJourney })}`} target="_top">
          Watch this trip
        </a>
      )}
    </article>
  );
}

function WalkingTimeControl({ option, plan, replacing, onWalk }: {
  option: Option; plan: WalkingPlan | null; replacing: boolean; onWalk: OptionsProps["onWalk"];
}) {
  const id = useId();
  const details = useRef<HTMLDetailsElement>(null);
  const summary = useRef<HTMLElement>(null);
  const field = useRef<HTMLInputElement>(null);
  const [expired, setExpired] = useState(false);
  const close = () => {
    if (details.current) details.current.open = false;
    summary.current?.focus();
  };
  return (
    <details className="walk-settings" ref={details} onToggle={(event) => {
      if (event.currentTarget.open && field.current) {
        setExpired(false);
        field.current.value = plan ? String(plan.minutes) : "";
        field.current.focus();
      }
    }}>
      <summary ref={summary}>
        <Footprints size={16} aria-hidden="true" />
        <span>{plan ? <>{plan.minutes === 0 ? "Be ready by" : "Leave by"} <b>{hhmm(leaveBy(plan))}</b> · {plan.minutes} min walk</> : "Set leave-by timer"}</span>
        <ChevronDown className="walk-chevron" size={16} aria-hidden="true" />
      </summary>
      <form className="walk-form" onSubmit={(event) => {
        event.preventDefault();
        const minutes = field.current?.valueAsNumber;
        if (option.departure <= Date.now()) { setExpired(true); return; }
        if (minutes === undefined || !Number.isInteger(minutes) || minutes < 0 || minutes > 1440) return;
        onWalk({ option, minutes });
        close();
      }} onKeyDown={(event) => {
        if (event.key === "Escape") { event.preventDefault(); close(); }
      }}>
        <label htmlFor={id}>Your walk to the boarding point</label>
        <div className="walk-entry">
          <span className="walk-number"><input ref={field} id={id} type="number" inputMode="numeric" min="0" max="1440" step="1" required aria-describedby={id + "-hint"} /><span>min</span></span>
          <button className="walk-save" type="submit">{plan ? "Save" : replacing ? "Replace timer" : "Set timer"}</button>
        </div>
        <p id={id + "-hint"}>{BOARDING_MINUTES} extra minute to board.</p>
        {expired && <p role="alert">The departure time has passed. Choose another service.</p>}
        <div className="walk-actions">
          {plan && <button type="button" onClick={() => {
            const optionButton = details.current?.closest("li")?.querySelector<HTMLButtonElement>(".option");
            onWalk(null);
            close();
            optionButton?.focus();
          }}>Remove timer</button>}
          <button type="button" onClick={close}>Cancel</button>
        </div>
      </form>
    </details>
  );
}

export function TimerChip(props: { timer: Timer } | { walking: WalkingPlan }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const tick = () => setNow(Date.now());
    const id = window.setInterval(tick, 500);
    document.addEventListener("visibilitychange", tick);
    return () => {
      window.clearInterval(id);
      document.removeEventListener("visibilitychange", tick);
    };
  }, []);
  if ("walking" in props) {
    const plan = props.walking;
    const phase = walkingPhase(plan, now);
    const atStop = plan.minutes === 0;
    const messages = {
      waiting: atStop ? "Be ready to board in" : "Start walking in",
      go: atStop ? "Be ready to board now" : "Start walking now",
      tight: "You may miss this departure",
      departed: "Departure time reached",
    };
    return (
      <div className="walk-timer" role="group" aria-label="Leave-by timer" data-phase={phase}>
        {atStop ? <Clock3 size={22} aria-hidden="true" /> : <Footprints size={22} aria-hidden="true" />}
        <div className="walk-timer-text">
          <div className="walk-head"><strong role="status">{messages[phase]}</strong>
            {phase === "waiting" && <b role="timer" aria-live="off">{waitRemaining(leaveBy(plan) - now)}</b>}
          </div>
          <p>{hhmm(plan.option.departure)} · {sbbText(plan.option.label)}</p>
        </div>
      </div>
    );
  }
  const { timer } = props;
  const left = timer.until - now;
  if (left < -120_000) return null;
  return (
    <div className="chip chip--timer" data-soon={left < 120_000} role="timer" aria-live="off">
      <Clock3 size={16} aria-hidden="true" />
      {left > 0 ? (
        <>
          <span>{sbbText(timer.label)}</span>
          <b>{countdown(left)}</b>
        </>
      ) : (
        <span>{timer.label === "Departure in" ? "Departure time reached" : `${sbbText(timer.label.replace(/ leaves in$| in$/, ""))} is leaving now`}</span>
      )}
    </div>
  );
}

export function CheckingPill() {
  return (
    <div className="chip chip--checking" aria-live="polite">
      <span className="scan" aria-hidden="true" />
      Checking the live timetable…
    </div>
  );
}

type DockProps = {
  typing: boolean;
  setTyping: (t: boolean) => void;
  onSubmit: (t: string) => void | Promise<void>;
  busy: boolean;
  voiceStatus: VoiceStatus;
  voiceMode: "listening" | "speaking";
  checking: boolean;
  onTalk: () => void;
  levels: () => { input: number; output: number };
  onLocate: () => void;
  locating: boolean;
  located: boolean;
};
export function Dock(p: DockProps) {
  const [text, setText] = useState("");
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (p.typing) input.current?.focus();
  }, [p.typing]);
  const live = p.voiceStatus === "live";
  const label = p.voiceStatus === "connecting" ? "Connecting…" : live ? (p.checking ? "Checking…" : p.voiceMode === "speaking" ? "Speaking · tap to end" : "Listening · tap to end") : "Tap to talk";

  if (p.typing)
    return (
      <form
        className="dock dock--typing"
        onSubmit={(e) => {
          e.preventDefault();
          const t = text;
          setText("");
          void p.onSubmit(t);
        }}
      >
        <button type="button" className="round" onClick={() => p.setTyping(false)} title="Talk instead" aria-label="Talk instead">
          <Mic size={20} />
        </button>
        <div className="field">
          <input ref={input} value={text} onChange={(e) => setText(e.target.value)} placeholder="Type your question" aria-label="Your question" enterKeyHint="send" autoComplete="off" />
          <button type="submit" className="send" disabled={!text.trim() || p.busy} title="Send" aria-label="Send">
            <ArrowUp size={19} strokeWidth={2.4} />
          </button>
        </div>
      </form>
    );

  return (
    <div className="dock">
      <button type="button" className="round" onClick={() => p.setTyping(true)} title="Type instead" aria-label="Type instead">
        <Keyboard size={20} />
      </button>
      <div className="talk-wrap">
        <TalkButton status={p.voiceStatus} mode={p.voiceMode} onTalk={p.onTalk} levels={p.levels} />
        <span className="talk-label" aria-live="polite">
          {label}
        </span>
      </div>
      <button type="button" className="round" data-on={p.located} onClick={p.onLocate} disabled={p.locating} title="Use my location" aria-label="Use my location">
        <LocateFixed size={20} className={p.locating ? "pulsing" : undefined} />
      </button>
    </div>
  );
}

function TalkButton({ status, mode, onTalk, levels }: { status: VoiceStatus; mode: "listening" | "speaking"; onTalk: () => void; levels: () => { input: number; output: number } }) {
  const ring = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    if (status !== "live") return;
    let raf = 0;
    let level = 0;
    const tick = () => {
      const l = levels();
      const target = Math.min(1, (mode === "speaking" ? l.output : l.input) * 2.2);
      level += (target - level) * 0.25;
      ring.current?.style.setProperty("--level", level.toFixed(3));
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [status, mode, levels]);
  const live = status === "live";
  return (
    <button type="button" className="talk" data-status={status} data-mode={mode} onClick={onTalk} title={live ? "End the conversation" : "Talk to the assistant"} aria-label={live ? "End the conversation" : "Talk to the assistant"}>
      <span ref={ring} className="talk-ring" aria-hidden="true" />
      {live ? <Square size={20} fill="currentColor" strokeWidth={0} /> : <Mic size={28} strokeWidth={2.2} />}
    </button>
  );
}
