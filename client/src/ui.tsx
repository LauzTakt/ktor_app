import { ArrowRight, ArrowUp, Clock3, Keyboard, LocateFixed, Mic, Square } from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";
import type { Option, SituationItem } from "./contract";
import { countdown, duration, greeting, hhmm, sbbText } from "./format";
import type { Timer } from "./map/MapController";
import type { VoiceStatus } from "./voice";

export type Item = { kind: "msg"; key: string; role: "passenger" | "assistant"; text: string; extra: string | null } | { kind: "cards"; key: string };

export function Welcome({ suggestions, onPick, place, onLocate, locating }: { suggestions: string[]; onPick: (s: string) => void; voiceReady: boolean; place: string | null; onLocate: () => void; locating: boolean }) {
  return (
    <div className="welcome">
      <h2>{greeting()}</h2>
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
    </div>
  );
}

export function Transcript({ items, checking, renderCards }: { items: Item[]; checking: boolean; renderCards: () => ReactNode }) {
  return (
    <div className="transcript">
      {items.map((i) =>
        i.kind === "cards" ? (
          <div key={i.key} className="cards">
            {renderCards()}
          </div>
        ) : (
          <div key={i.key} className={`bubble bubble--${i.role}`}>
            <p>{i.text}</p>
            {i.extra && <p className="bubble-extra">{i.extra}</p>}
          </div>
        ),
      )}
      {checking && (
        <div className="bubble bubble--assistant bubble--checking" role="status" aria-label="Checking the live timetable">
          <span className="dots" aria-hidden="true">
            <i />
            <i />
            <i />
          </span>
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

export function OptionsCard({ options, selected, onChoose }: { options: Option[]; selected: string | null; onChoose: (o: Option) => void }) {
  return (
    <article className="card">
      <h3>Your options</h3>
      <ul className="options">
        {options.slice(0, 4).map((o) => {
          const id = o.journeyId ?? o.id;
          const on = selected === id;
          return (
            <li key={o.id}>
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
            </li>
          );
        })}
      </ul>
    </article>
  );
}

export function TimerChip({ timer }: { timer: Timer }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 500);
    return () => window.clearInterval(id);
  }, []);
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
        <span>{sbbText(timer.label.replace(/ leaves in$| in$/, ""))} is leaving now</span>
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
