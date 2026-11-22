// UI languages: English (source text), French, Spanish. Same rules as the host pages (src/main/resources/static/i18n.js):
// `?lang=xx` in the URL wins and is saved, else the saved choice (localStorage `twin-lang`), else the browser's
// language, else English. The passenger page reloads on a change, which reloads this iframe.
//
// Keys are the English text itself, so English needs no dictionary and a missing translation falls back to English.
//   t("Track my train")                    -> "Suivre mon train"
//   t("{n} min walk", { n: 4 })            -> "4 min à pied"   (placeholders {name}, same in every language)
import { es } from "./locales/es";
import { fr } from "./locales/fr";

export type Lang = "en" | "fr" | "es";
const KEY = "twin-lang";
const LANGS: readonly Lang[] = ["en", "fr", "es"];

const pick = (l: string | null | undefined): Lang | null => {
  const c = (l ?? "").slice(0, 2).toLowerCase();
  return (LANGS as readonly string[]).includes(c) ? (c as Lang) : null;
};

function resolve(): Lang {
  let fromUrl: Lang | null = null;
  try {
    fromUrl = pick(new URLSearchParams(location.search).get("lang"));
  } catch {
    /* no URL */
  }
  let saved: Lang | null = null;
  try {
    if (fromUrl) localStorage.setItem(KEY, fromUrl);
    saved = pick(localStorage.getItem(KEY));
  } catch {
    /* storage blocked */
  }
  const browser = typeof navigator !== "undefined" ? pick((navigator.languages?.[0] ?? navigator.language) || "") : null;
  return fromUrl || saved || browser || "en";
}

/** The UI language. */
export const lang: Lang = resolve();
/** BCP 47 locale for Intl formatters (Swiss French, Spain Spanish). */
export const locale = ({ en: "en-GB", fr: "fr-CH", es: "es-ES" } as const)[lang];

const dicts: Record<Lang, Record<string, string>> = { en: {}, fr, es };

/** Translated text of the English [key], `{name}` placeholders filled from [vars]. */
export function t(key: string, vars?: Record<string, string | number>): string {
  const s = dicts[lang][key] || key;
  return vars ? s.replace(/\{(\w+)\}/g, (m, k: string) => (k in vars ? String(vars[k]) : m)) : s;
}

if (typeof document !== "undefined") {
  document.documentElement.lang = lang;
  document.title = t("Travel assistant");
}
