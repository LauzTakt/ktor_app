// Loki, the mascot of the app (docs/STYLE.md "9. Mascot"). Styles: /loki.css. The same drawing lives in
// client/src/Loki.tsx for the travel assistant: change both together.

import { esc } from "./common.js";

export const LOKI_SVG = `
<svg class="loki-svg" viewBox="0 0 100 92" aria-hidden="true">
  <g class="loki-panto"><path d="M38 15 50 5 62 15" /><path d="M41 3h18" /></g>
  <g class="loki-body">
    <rect x="14" y="14" width="72" height="62" rx="18" class="loki-red" />
    <rect x="22" y="22" width="56" height="28" rx="10" class="loki-glass" />
    <path class="loki-brow l" d="M32 28q6-4 12 0" />
    <path class="loki-brow r" d="M56 28q6-4 12 0" />
    <g class="loki-eyes">
      <g class="loki-eye"><circle cx="39" cy="38" r="6.5" class="loki-ink" /><circle class="loki-glint" cx="41" cy="36" r="2" /></g>
      <g class="loki-eye"><circle cx="61" cy="38" r="6.5" class="loki-ink" /><circle class="loki-glint" cx="63" cy="36" r="2" /></g>
    </g>
    <circle cx="26" cy="62" r="4.5" class="loki-lamp" />
    <circle cx="74" cy="62" r="4.5" class="loki-lamp" />
    <path class="loki-smile" d="M41 58q9 8 18 0" />
    <ellipse class="loki-mouth" cx="50" cy="61" rx="5.5" ry="4" />
    <rect x="20" y="73" width="60" height="7" rx="3" class="loki-skirt" />
    <circle cx="32" cy="83" r="6" class="loki-ink" /><circle cx="32" cy="83" r="2" class="loki-hub" />
    <circle cx="68" cy="83" r="6" class="loki-ink" /><circle cx="68" cy="83" r="2" class="loki-hub" />
  </g>
</svg>`;


/** Loki rolling on a moving track: the loading scene. `mini` for cards and lists; `caption` is read out (role=status). */
export function lokiLoading({ caption = "Loading…", sub = "", mini = false, mood = "" } = {}) {
  return `<div class="loki-loading${mini ? " mini" : ""}" role="status">
    <span class="loki"${mood ? ` data-mood="${esc(mood)}"` : ""}>${LOKI_SVG}</span>
    <svg class="loki-track" viewBox="0 0 260 10" preserveAspectRatio="none" aria-hidden="true">
      <line class="sleepers" x1="0" y1="6" x2="260" y2="6" /><line class="rail" x1="0" y1="3" x2="260" y2="3" />
    </svg>
    ${mini ? `<p class="cap">${esc(caption)}</p>` : `<p class="cap">${esc(caption)}</p>${sub ? `<p class="sub">${esc(sub)}</p>` : ""}`}
  </div>`;
}
