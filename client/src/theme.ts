// Light / dark theme, shared with the passenger view and the staff pages (localStorage key "twin-theme").
// Embedded as the passenger view's assistant panel (same-origin iframe), we follow the parent page's
// <html data-theme>, live. Opened on its own: the saved choice, else the system preference.
// index.html sets data-theme before the first paint with the same rules.

export type Theme = "light" | "dark";

const KEY = "twin-theme";
const listeners = new Set<(t: Theme) => void>();

function parentRoot(): HTMLElement | null {
  try {
    return window.parent !== window ? window.parent.document.documentElement : null;
  } catch {
    return null; // cross-origin parent
  }
}

function resolve(): Theme {
  const fromParent = parentRoot()?.dataset.theme;
  if (fromParent === "dark" || fromParent === "light") return fromParent;
  let saved: string | null = null;
  try {
    saved = localStorage.getItem(KEY);
  } catch {
    /* storage blocked */
  }
  if (saved === "dark" || saved === "light") return saved;
  return matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

export const currentTheme = (): Theme => (document.documentElement.dataset.theme === "dark" ? "dark" : "light");

function apply() {
  const t = resolve();
  if (t === currentTheme() && document.documentElement.dataset.theme) return;
  document.documentElement.dataset.theme = t;
  document.querySelector('meta[name="theme-color"]')?.setAttribute("content", t === "dark" ? "#17191c" : "#ffffff");
  listeners.forEach((fn) => fn(t));
}

/** Calls fn on every theme change; returns the unsubscribe function. */
export function onTheme(fn: (t: Theme) => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

const root = parentRoot();
if (root) new MutationObserver(apply).observe(root, { attributes: true, attributeFilter: ["data-theme"] });
window.addEventListener("storage", (e) => (e.key === KEY || e.key === null) && apply());
matchMedia("(prefers-color-scheme: dark)").addEventListener("change", apply);
apply();
