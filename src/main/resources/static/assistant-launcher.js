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
      // Tikt waits behind the frame while the assistant loads (map + voice), then the frame covers him.
      const wait = document.createElement("div");
      wait.className = "assistant-wait";
      panel.appendChild(wait);
      import("/tikt.js").then(({ tiktLoading }) => {
        wait.innerHTML = tiktLoading({ caption: "Tikt is on his way…", sub: "Your travel assistant, by voice or chat.", mood: "hello" });
      }).catch(() => {});
      const frame = document.createElement("iframe");
      frame.src = "/assistant/";
      frame.title = "Travel assistant";
      frame.allow = "microphone; geolocation; autoplay";
      frame.addEventListener("load", () => { loaded = true; post(); flush(); setTimeout(() => wait.remove(), 400); }, { once: true });
      panel.appendChild(frame);
    }
    panel.hidden = !open;
    fab.setAttribute("aria-expanded", String(open));
    fab.setAttribute("aria-label", open ? "Close the travel assistant" : "Open the travel assistant");
    fab.title = open ? "Close" : "Travel assistant";
    if (open) panel.focus({ preventScroll: true });
    else (opener?.isConnected && opener !== document.body ? opener : fab).focus({ preventScroll: true });
  };
  // Questions asked from the passenger view (window.askAssistant): kept until the frame has loaded.
  let loaded = false;
  const pending = [];
  const frameWin = () => panel.querySelector("iframe")?.contentWindow;
  const flush = () => { while (loaded && pending.length) frameWin()?.postMessage({ type: "twin-ask", text: pending.shift() }, location.origin); };
  window.askAssistant = (text) => {
    if (!text) return;
    pending.push(String(text).slice(0, 600));
    if (panel.hidden) setOpen(true);
    flush();
  };
  // What the passenger has open (passenger/ui.js setView): the assistant sends it with every question.
  const post = () => panel.querySelector("iframe")?.contentWindow?.postMessage({ type: "twin-view", view: window.twinView ?? null }, location.origin);
  window.addEventListener("twin-view", post);
  window.addEventListener("message", (e) => {
    if (e.origin !== location.origin || e.source !== frameWin()) return;
    if (e.data?.type === "twin-view-request") post();
    // "Open train view" on the assistant's map: close the panel, show the train in the passenger view.
    if (e.data?.type === "twin-open-train" && typeof e.data.trainId === "string") {
      setOpen(false);
      window.dispatchEvent(new CustomEvent("twin-open-train", { detail: { trainId: e.data.trainId } }));
    }
  });
  fab.addEventListener("click", () => setOpen(panel.hidden));
  document.addEventListener("keydown", (e) => { if (e.key === "Escape" && !panel.hidden) setOpen(false); });
})();
