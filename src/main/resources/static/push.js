// Push notifications on every platform (server contract: docs/API.md, "Push notifications").
//
//  - Browser (desktop, Android Chrome, iPhone home-screen web app on iOS 16.4+): Web Push through /sw.js.
//  - Android app (Capacitor WebView loading this site): FCM through the native PushNotifications plugin
//    (Android WebViews have no Web Push).
//  - iPhone app: not yet (needs a paid Apple Developer account for APNs); users are pointed to the home-screen web app.
//
// The server returns a random deviceId on registration; it is kept in localStorage and is the only handle to the
// device's subscriptions.

import { publicApi } from "./common.js";

const DEVICE_KEY = "twin-push-device";
const cap = window.Capacitor;
const native = Boolean(cap?.isNativePlatform?.());
const platform = native ? cap.getPlatform() : "web";
const NativePush = native ? cap.Plugins?.PushNotifications : null;
const isIos = /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
const standalone = matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;

/** `/api/v1/push` call (errors carry `status` and `code`, see common.js). */
const call = (method, path, body) => publicApi(`/push${path}`, { method, json: body });

let configP = null;
/** Seconds before arrival of the "arriving soon" alert (from the server config). */
export let leadSec = 120;
const config = () => (configP ??= call("GET", "/config").then((c) => { leadSec = c.arrivalLeadSec ?? leadSec; return c; })
  .catch((e) => { configP = null; throw e; }));

/** Whether this device can get notifications, and if not, what to tell the user. */
export function support() {
  if (native && platform === "android") {
    return NativePush ? { ok: true } : { ok: false, reason: "This version of the app cannot show notifications. Update the app." };
  }
  if (native) {
    return { ok: false, reason: "Alerts are not available in the iPhone app yet. Open this site in Safari, tap Share, then Add to Home Screen, and turn alerts on from there." };
  }
  if (!window.isSecureContext) return { ok: false, reason: "Alerts need a secure (https) connection." };
  if (isIos && !standalone) {
    return { ok: false, reason: "On iPhone, alerts work from the Home Screen app: tap Share, then Add to Home Screen, open it from there and turn alerts on." };
  }
  if (!("serviceWorker" in navigator) || !("PushManager" in window) || !("Notification" in window)) {
    return { ok: false, reason: "This browser cannot show notifications. Try Chrome, Edge, Firefox or Safari." };
  }
  return { ok: true };
}

export const deviceId = () => localStorage.getItem(DEVICE_KEY);
export const enabled = () => Boolean(deviceId());

const b64ToBytes = (s) => {
  const b = atob(s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4));
  return Uint8Array.from(b, (c) => c.charCodeAt(0));
};

async function registerWeb() {
  const cfg = await config();
  const reg = await navigator.serviceWorker.register("/sw.js", { scope: "/" });
  await navigator.serviceWorker.ready;
  if (Notification.permission !== "granted") {
    const p = await Notification.requestPermission();
    if (p !== "granted") throw new Error("Notifications are blocked. Allow them in your browser settings for this site.");
  }
  let sub = await reg.pushManager.getSubscription();
  // A subscription made with another server key cannot be reused.
  const key = b64ToBytes(cfg.webPushPublicKey);
  const sameKey = (s) => {
    const k = s?.options?.applicationServerKey;
    if (!k) return true;
    const a = new Uint8Array(k);
    return a.length === key.length && a.every((v, i) => v === key[i]);
  };
  if (sub && !sameKey(sub)) { await sub.unsubscribe(); sub = null; }
  sub ??= await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
  const j = sub.toJSON();
  return call("POST", "/devices", { kind: "web", endpoint: j.endpoint, keys: j.keys, platform: isIos ? "ios-web" : "web" });
}

async function registerNative() {
  // 1. Permission first, so the user always sees the Android prompt (Android 13+; granted by default before).
  let perm = await NativePush.checkPermissions();
  if (perm.receive !== "granted") perm = await NativePush.requestPermissions();
  if (perm.receive !== "granted") throw new Error("Notifications are blocked. Allow them in Settings > Apps > Train Twin > Notifications.");
  // 2. The APK must contain the Firebase config: without it register() crashes the app (TwinAppPlugin.java).
  const app = await cap.Plugins?.TwinApp?.info?.().catch(() => null);
  if (!app?.firebase) {
    throw new Error("This version of the app was built without notification support. Install the latest version of the app.");
  }
  // 3. The server must accept app tokens (FCM_SERVICE_ACCOUNT_JSON).
  const cfg = await config();
  if (!cfg.fcm) throw new Error("Notifications are not set up on the server yet (Firebase key missing). Ask the team.");
  const handles = [];
  try {
    const token = await new Promise((resolve, reject) => {
      setTimeout(() => reject(new Error("The phone did not answer. Try again.")), 15_000);
      handles.push(NativePush.addListener("registration", (t) => resolve(t.value)));
      handles.push(NativePush.addListener("registrationError", (e) => reject(new Error(e?.error || "Registration failed."))));
      NativePush.register().catch(reject);
    });
    return await call("POST", "/devices", { kind: "fcm", token, platform });
  } finally {
    handles.forEach((h) => Promise.resolve(h).then((x) => x?.remove?.()).catch(() => {}));
  }
}

/** Asks for permission and registers this device. Resolves with the deviceId; throws an Error with a user message. */
export async function enable() {
  const s = support();
  if (!s.ok) throw new Error(s.reason);
  const d = native ? await registerNative() : await registerWeb();
  localStorage.setItem(DEVICE_KEY, d.deviceId);
  return d.deviceId;
}

/** Runs [fn] with the deviceId, registering first if needed (and again if the server forgot the device). */
async function withDevice(fn) {
  let id = deviceId() || await enable();
  try {
    return await fn(id);
  } catch (e) {
    if (e.status !== 404 || e.code !== "device_not_found") throw e;
    localStorage.removeItem(DEVICE_KEY);
    id = await enable();
    return fn(id);
  }
}

/** Follow a train ({ trainId, stopId? }) or a line ({ line }). */
export const follow = (what) => withDevice((id) => call("POST", `/devices/${encodeURIComponent(id)}/subscriptions`, what));

export async function list() {
  const id = deviceId();
  if (!id) return [];
  try {
    return await call("GET", `/devices/${encodeURIComponent(id)}/subscriptions`);
  } catch (e) {
    if (e.status === 404) { localStorage.removeItem(DEVICE_KEY); return []; }
    throw e;
  }
}

export const unfollow = (subscriptionId) =>
  call("DELETE", `/devices/${encodeURIComponent(deviceId())}/subscriptions/${encodeURIComponent(subscriptionId)}`);

export const test = () => withDevice((id) => call("POST", `/devices/${encodeURIComponent(id)}/test`));

/**
 * Call once at start-up. Web: keeps the service worker registered. Android app: notifications that arrive while
 * the app is open go to [onForeground] (the system does not show them), and a tapped notification opens its page.
 */
export function init({ onForeground } = {}) {
  config().catch(() => {});
  if (native && NativePush) {
    NativePush.addListener("pushNotificationReceived", (n) => onForeground?.({ title: n.title, body: n.body, url: n.data?.url }));
    NativePush.addListener("pushNotificationActionPerformed", (a) => {
      const url = a.notification?.data?.url;
      if (url && url !== location.pathname + location.search) location.href = url;
    });
  } else if (!native && "serviceWorker" in navigator && deviceId()) {
    navigator.serviceWorker.register("/sw.js", { scope: "/" }).catch(() => {});
  }
}
