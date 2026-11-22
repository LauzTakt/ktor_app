// UI languages: English (source text), French, Spanish. Classic, blocking script in every page's <head>, before any
// other script, followed by /i18n-strings.js (the fr / es dictionaries).
//
// Keys are the English text itself, so English needs no dictionary and a missing translation falls back to English.
//   t("Change trip")                       -> "Changer de trajet"
//   t("{n} min ago", { n: 3 })             -> "il y a 3 min"   (placeholders {name}, same in every language)
// Static HTML: data-i18n (text), data-i18n-title, data-i18n-aria-label, data-i18n-placeholder, data-i18n-alt; the
// attribute value is the key, or empty to use the element's current English text / attribute. Applied on
// DOMContentLoaded and by `TwinI18n.apply(root)`.
// Language: saved choice (localStorage `twin-lang`), else the browser's, else English. Changing it reloads the page.
// ES modules use the re-exports in common.js (`t`, `lang`, `locale`). The React client (/assistant/) reads the same key.
(() => {
  const LANGS = { en: "English", fr: "Français", es: "Español" };
  const KEY = "twin-lang";
  const pick = (l) => (l && LANGS[l.slice(0, 2).toLowerCase()] ? l.slice(0, 2).toLowerCase() : null);
  const fromUrl = pick(new URLSearchParams(location.search).get("lang") || "");
  if (fromUrl) localStorage.setItem(KEY, fromUrl);
  const lang = fromUrl || pick(localStorage.getItem(KEY)) || pick((navigator.languages || [navigator.language])[0] || "") || "en";
  document.documentElement.lang = lang;
  const dicts = { en: {} };

  const fill = (s, vars) => (vars ? s.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? String(vars[k]) : m)) : s);
  /** Translated text of the English [key], placeholders filled from [vars]. */
  const t = (key, vars) => fill((dicts[lang] && dicts[lang][key]) || key, vars);

  const ATTRS = ["title", "aria-label", "placeholder", "alt"];
  function apply(root = document) {
    root.querySelectorAll("[data-i18n]").forEach((el) => {
      const key = el.dataset.i18n || (el.dataset.i18n = el.textContent.trim());
      el.textContent = t(key);
    });
    for (const a of ATTRS) {
      root.querySelectorAll(`[data-i18n-${a}]`).forEach((el) => {
        const d = `i18n-${a}`;
        const key = el.getAttribute(`data-${d}`) || el.getAttribute(a) || "";
        el.setAttribute(`data-${d}`, key);
        el.setAttribute(a, t(key));
      });
    }
  }

  function setLang(l) {
    if (!LANGS[l] || l === lang) return;
    localStorage.setItem(KEY, l);
    const u = new URL(location.href);
    u.searchParams.delete("lang");
    location.replace(u.href);
  }

  /** A <select> with the three languages; changing it reloads the page in that language. */
  function picker(className = "select select-ghost select-sm lang-select") {
    const s = document.createElement("select");
    s.className = className;
    s.setAttribute("aria-label", t("Language"));
    s.title = t("Language");
    for (const [code, name] of Object.entries(LANGS)) {
      const o = document.createElement("option");
      o.value = code;
      o.textContent = code.toUpperCase();
      o.title = name;
      o.selected = code === lang;
      s.appendChild(o);
    }
    s.addEventListener("change", () => setLang(s.value));
    return s;
  }

  /** BCP 47 locale for Intl formatters (Swiss variants). */
  const locale = { en: "en-GB", fr: "fr-CH", es: "es-ES" }[lang];

  window.TwinI18n = {
    lang, locale, languages: LANGS, t, apply, setLang, picker,
    /** Adds translations: `add({ fr: {...}, es: {...} })`. */
    add(d) { for (const [l, m] of Object.entries(d)) dicts[l] = Object.assign(dicts[l] || {}, m); },
  };

  document.addEventListener("DOMContentLoaded", () => {
    apply();
    // every element with data-lang-picker becomes the language menu
    document.querySelectorAll("[data-lang-picker]").forEach((el) => el.replaceWith(picker(el.className || undefined)));
  });
})();
