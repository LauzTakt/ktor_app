// Floating travel-assistant button: opens /assistant/ (voice + chat) in a panel over the passenger view. The iframe is
// created on first open (no cost until then) and kept, so closing and reopening keeps the conversation.
(() => {
  const fab = document.getElementById("assistant-fab");
  const panel = document.getElementById("assistant-panel");
  if (!fab || !panel) return;
  const setOpen = (open) => {
    if (open && !panel.firstChild) {
      const frame = document.createElement("iframe");
      frame.src = "/assistant/";
      frame.title = "Travel assistant";
      frame.allow = "microphone; geolocation; autoplay";
      panel.appendChild(frame);
    }
    panel.hidden = !open;
    fab.setAttribute("aria-expanded", String(open));
    fab.setAttribute("aria-label", open ? "Close the travel assistant" : "Open the travel assistant");
    fab.title = open ? "Close" : "Travel assistant";
  };
  fab.addEventListener("click", () => setOpen(panel.hidden));
  document.addEventListener("keydown", (e) => { if (e.key === "Escape" && !panel.hidden) setOpen(false); });
})();
