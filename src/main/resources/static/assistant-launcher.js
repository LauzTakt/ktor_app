// Floating travel-assistant button: opens /assistant/ (voice + chat) in a panel over the passenger view. The iframe is
// created on first open (no cost until then) and kept, so closing and reopening keeps the conversation.
(() => {
  const fab = document.getElementById("assistant-fab");
  const panel = document.getElementById("assistant-panel");
  if (!fab || !panel) return;
  let opener;
  const setOpen = (open) => {
    if (open) opener = document.activeElement;
    if (open && !panel.firstChild) {
      // Loki waits behind the frame while the assistant loads (map + voice), then the frame covers him.
      const wait = document.createElement("div");
      wait.className = "assistant-wait";
      panel.appendChild(wait);
      import("/loki.js").then(({ lokiLoading }) => {
        wait.innerHTML = lokiLoading({ caption: "Loki is on his way…", sub: "Your travel assistant, by voice or chat.", mood: "hello" });
      }).catch(() => {});
      const frame = document.createElement("iframe");
      frame.src = "/assistant/";
      frame.title = "Travel assistant";
      frame.allow = "microphone; geolocation; autoplay";
      frame.addEventListener("load", () => { post(); setTimeout(() => wait.remove(), 400); }, { once: true });
      panel.appendChild(frame);
    }
    panel.hidden = !open;
    fab.setAttribute("aria-expanded", String(open));
    fab.setAttribute("aria-label", open ? "Close the travel assistant" : "Open the travel assistant");
    fab.title = open ? "Close" : "Travel assistant";
    if (open) panel.focus({ preventScroll: true });
    else (opener?.isConnected && opener !== document.body ? opener : fab).focus({ preventScroll: true });
  };
  // What the passenger has open (passenger/ui.js setView): the assistant sends it with every question.
  const post = () => panel.querySelector("iframe")?.contentWindow?.postMessage({ type: "twin-view", view: window.twinView ?? null }, location.origin);
  window.addEventListener("twin-view", post);
  window.addEventListener("message", (e) => {
    if (e.origin === location.origin && e.data?.type === "twin-view-request" && e.source === panel.querySelector("iframe")?.contentWindow) post();
  });
  fab.addEventListener("click", () => setOpen(panel.hidden));
  document.addEventListener("keydown", (e) => { if (e.key === "Escape" && !panel.hidden) setOpen(false); });
})();
