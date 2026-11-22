// Loki, the mascot of the app: the travel assistant speaks as Loki. Same drawing as
// src/main/resources/static/loki.js (passenger view): change both together. Styles: /loki.css (served by ktor_app).
import { useEffect, useRef } from "react";

export type LokiMood = "hello" | "point" | "cheer" | "think";

type Props = {
  className?: string;
  mood?: LokiMood;
  /** Mouth moves on its own (e.g. while a reply appears). */
  talking?: boolean;
  /** Mouth follows the voice: 0–1 audio level of the assistant's speech, read every frame. */
  voiceLevel?: () => number;
};

export function Loki({ className = "", mood, talking = false, voiceLevel }: Props) {
  const el = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    if (!voiceLevel) return;
    let raf = 0;
    let level = 0;
    const tick = () => {
      level += (Math.min(1, voiceLevel() * 2.4) - level) * 0.35;
      el.current?.style.setProperty("--talk", level.toFixed(3));
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [voiceLevel]);
  const cls = ["loki", className, talking && !voiceLevel ? "talking" : "", voiceLevel ? "voice" : ""].filter(Boolean).join(" ");
  return (
    <span ref={el} className={cls} data-mood={mood} aria-hidden="true">
      <svg className="loki-svg" viewBox="0 0 100 92">
        <g className="loki-panto">
          <path d="M38 15 50 5 62 15" />
          <path d="M41 3h18" />
        </g>
        <g className="loki-body">
          <rect x="14" y="14" width="72" height="62" rx="18" className="loki-red" />
          <rect x="22" y="22" width="56" height="28" rx="10" className="loki-glass" />
          <path className="loki-brow l" d="M32 28q6-4 12 0" />
          <path className="loki-brow r" d="M56 28q6-4 12 0" />
          <g className="loki-eyes">
            <g className="loki-eye">
              <circle cx="39" cy="38" r="6.5" className="loki-ink" />
              <circle className="loki-glint" cx="41" cy="36" r="2" />
            </g>
            <g className="loki-eye">
              <circle cx="61" cy="38" r="6.5" className="loki-ink" />
              <circle className="loki-glint" cx="63" cy="36" r="2" />
            </g>
          </g>
          <circle cx="26" cy="62" r="4.5" className="loki-lamp" />
          <circle cx="74" cy="62" r="4.5" className="loki-lamp" />
          <path className="loki-smile" d="M41 58q9 8 18 0" />
          <ellipse className="loki-mouth" cx="50" cy="61" rx="5.5" ry="4" />
          <rect x="20" y="73" width="60" height="7" rx="3" className="loki-skirt" />
          <circle cx="32" cy="83" r="6" className="loki-ink" />
          <circle cx="32" cy="83" r="2" className="loki-hub" />
          <circle cx="68" cy="83" r="6" className="loki-ink" />
          <circle cx="68" cy="83" r="2" className="loki-hub" />
        </g>
      </svg>
    </span>
  );
}
