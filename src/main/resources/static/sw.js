// Service worker (scope `/`): shows Web Push notifications and opens the right page when one is tapped.
// Payload (server: push/Senders.kt PushMessage): { title, body, url, tag }.
// No offline caching: the app needs live data anyway.

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (e) => e.waitUntil(self.clients.claim()));

self.addEventListener("push", (e) => {
  let m = {};
  try { m = e.data ? e.data.json() : {}; } catch { m = { title: "Train twin", body: e.data?.text() || "" }; }
  e.waitUntil(self.registration.showNotification(m.title || "Train twin", {
    body: m.body || "",
    tag: m.tag || undefined,
    renotify: Boolean(m.tag),
    icon: "/icons/icon-192.png",
    badge: "/icons/badge-96.png",
    data: { url: m.url || "/" },
  }));
});

self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  const url = new URL(e.notification.data?.url || "/", self.location.origin).href;
  e.waitUntil((async () => {
    const wins = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    const same = wins.find((w) => w.url === url) || wins.find((w) => new URL(w.url).origin === self.location.origin);
    if (same) {
      if (same.url !== url && "navigate" in same) await same.navigate(url).catch(() => {});
      return same.focus();
    }
    return self.clients.openWindow(url);
  })());
});

// The browser may rotate the subscription: re-register the new one under the same device (same endpoint key).
self.addEventListener("pushsubscriptionchange", (e) => {
  e.waitUntil((async () => {
    const cfg = await fetch("/api/v1/push/config").then((r) => r.json());
    const sub = e.newSubscription || await self.registration.pushManager.subscribe({
      userVisibleOnly: true, applicationServerKey: cfg.webPushPublicKey,
    });
    const j = sub.toJSON();
    await fetch("/api/v1/push/devices", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ kind: "web", endpoint: j.endpoint, keys: j.keys, platform: "web" }),
    });
  })());
});
