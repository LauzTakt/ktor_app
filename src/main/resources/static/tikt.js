// Tikt, the mascot of the app (docs/STYLE.md "9. Mascot"). Styles: /tikt.css. The same drawing lives in
// client/src/Tikt.tsx for the travel assistant: change both together.

import { esc, t } from "./common.js";

export const TIKT_SVG = `
<svg class="tikt-svg" viewBox="0 0 100 92" aria-hidden="true">
  <g class="tikt-panto"><path d="M38 15 50 5 62 15" /><path d="M41 3h18" /></g>
  <g class="tikt-body">
    <rect x="14" y="14" width="72" height="62" rx="18" class="tikt-red" />
    <rect x="22" y="22" width="56" height="28" rx="10" class="tikt-glass" />
    <path class="tikt-brow l" d="M32 28q6-4 12 0" />
    <path class="tikt-brow r" d="M56 28q6-4 12 0" />
    <g class="tikt-eyes">
      <g class="tikt-eye"><circle cx="39" cy="38" r="6.5" class="tikt-ink" /><circle class="tikt-glint" cx="41" cy="36" r="2" /></g>
      <g class="tikt-eye"><circle cx="61" cy="38" r="6.5" class="tikt-ink" /><circle class="tikt-glint" cx="63" cy="36" r="2" /></g>
    </g>
    <circle cx="26" cy="62" r="4.5" class="tikt-lamp" />
    <circle cx="74" cy="62" r="4.5" class="tikt-lamp" />
    <path class="tikt-smile" d="M41 58q9 8 18 0" />
    <ellipse class="tikt-mouth" cx="50" cy="61" rx="5.5" ry="4" />
    <rect x="20" y="73" width="60" height="7" rx="3" class="tikt-skirt" />
    <circle cx="32" cy="83" r="6" class="tikt-ink" /><circle cx="32" cy="83" r="2" class="tikt-hub" />
    <circle cx="68" cy="83" r="6" class="tikt-ink" /><circle cx="68" cy="83" r="2" class="tikt-hub" />
  </g>
</svg>`;


/** Tikt rolling on a moving track: the loading scene. `mini` for cards and lists; `caption` is read out (role=status). */
export function tiktLoading({ caption = t("Loading…"), sub = "", mini = false, mood = "" } = {}) {
  return `<div class="tikt-loading${mini ? " mini" : ""}" role="status">
    <span class="tikt"${mood ? ` data-mood="${esc(mood)}"` : ""}>${TIKT_SVG}</span>
    <svg class="tikt-track" viewBox="0 0 260 10" preserveAspectRatio="none" aria-hidden="true">
      <line class="sleepers" x1="0" y1="6" x2="260" y2="6" /><line class="rail" x1="0" y1="3" x2="260" y2="3" />
    </svg>
    ${mini ? `<p class="cap">${esc(caption)}</p>` : `<p class="cap">${esc(caption)}</p>${sub ? `<p class="sub">${esc(sub)}</p>` : ""}`}
  </div>`;
}
