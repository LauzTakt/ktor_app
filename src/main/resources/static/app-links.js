// Deep links between the website and the mobile app (mobile/, Capacitor WebView loading this site).
//
// In the app: a scanned QR code / tapped link (Android App Link `https://<host>/?stop=…`, verified through
// /.well-known/assetlinks.json) or the custom scheme `traintwin://open/?stop=…` launches the app; we then load the same
// page and query in the WebView (`appUrlOpen` while running, `getLaunchUrl` on a cold start).
//
// In a phone browser: a dismissible bar "Open in the app". Android uses an intent:// URL (opens the app when
// installed, else stays on this page); iOS uses traintwin:// (Universal Links need a paid Apple account). Browsers
// do not let a page open an app without a tap, so this cannot happen on its own.
(() => {
  const SCHEME = "traintwin";
  const PACKAGE = "ch.lauzhack.twin";
  const HIDE_KEY = "twin-app-banner-hidden";   // localStorage: closed by the user
  const TRIED_KEY = "twin-app-banner-tried";   // sessionStorage: already tried this session
  const LAUNCH_KEY = "twin-app-launch-url";    // sessionStorage: cold-start link already followed
  const cap = window.Capacitor;

  /** Same page and query on this origin, or null when [url] is not one of our links. */
  const target = (url) => {
    let u;
    try { u = new URL(url); } catch { return null; }
    if (u.protocol === `${SCHEME}:`) return `${location.origin}${u.pathname && u.pathname !== "" ? u.pathname : "/"}${u.search}${u.hash}`;
    if (u.protocol === "https:" && u.host === location.host) return u.href;
    return null;
  };

  if (cap?.isNativePlatform?.()) {
    const App = cap.Plugins?.App;
    if (!App) return;
    const go = (url) => {
      const t = target(url);
      if (t && t !== location.href) location.href = t;
    };
    App.addListener("appUrlOpen", (e) => go(e.url));
    App.getLaunchUrl?.().then((r) => {
      if (!r?.url || sessionStorage.getItem(LAUNCH_KEY) === r.url) return;
      sessionStorage.setItem(LAUNCH_KEY, r.url);
      go(r.url);
    }).catch(() => {});
    return;
  }

  const ua = navigator.userAgent;
  const android = /Android/i.test(ua);
  const ios = /iPhone|iPod/.test(ua);
  const standalone = matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;
  if (!(android || ios) || standalone) return;
  if (localStorage.getItem(HIDE_KEY) || sessionStorage.getItem(TRIED_KEY)) return;

  const appUrl = () => {
    const rest = `${location.pathname}${location.search}`;
    return android
      ? `intent://open${rest}#Intent;scheme=${SCHEME};package=${PACKAGE};S.browser_fallback_url=${encodeURIComponent(location.href)};end`
      : `${SCHEME}://open${rest}`;
  };

  const show = () => {
    const bar = document.createElement("div");
    bar.className = "app-banner";
    bar.setAttribute("role", "region");
    bar.setAttribute("aria-label", "Mobile app");
    bar.innerHTML = `
      <span class="t">Have the Train twin app?</span>
      <a class="btn" id="app-open" href="#">Open in the app</a>
      <button class="btn btn-ghost btn-circle icon-btn" type="button" id="app-close" title="Close" aria-label="Close">
        <svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg></button>`;
    const header = document.querySelector(".topbar");
    if (header) header.after(bar); else document.body.prepend(bar);
    bar.querySelector("#app-open").addEventListener("click", (e) => {
      e.preventDefault();
      sessionStorage.setItem(TRIED_KEY, "1");
      bar.remove();
      location.href = appUrl(); // the current URL: the passenger view keeps it in sync (?train= / ?stop= …)
    });
    bar.querySelector("#app-close").addEventListener("click", () => { localStorage.setItem(HIDE_KEY, "1"); bar.remove(); });
  };
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", show); else show();
})();
