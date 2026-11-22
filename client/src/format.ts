// Times as HH:mm in Europe/Zurich (payloads are epoch ms UTC).
const clock = new Intl.DateTimeFormat("en-GB", { timeZone: "Europe/Zurich", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
export const hhmm = (ms: number | null | undefined) => (ms ? clock.format(ms) : "");
export const minutes = (ms: number) => Math.max(0, Math.round(ms / 60000));

/** "1 h 05 min" / "47 min" */
export function duration(ms: number) {
  const m = minutes(ms);
  return m >= 60 ? `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, "0")} min` : `${m} min`;
}

/** Countdown "4:07", or "1:02:10" above an hour. */
export function countdown(ms: number) {
  const s = Math.max(0, Math.round(ms / 1000));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = String(s % 60).padStart(2, "0");
  return h ? `${h}:${String(m).padStart(2, "0")}:${ss}` : `${m}:${ss}`;
}

export function greeting(now = Date.now()) {
  const h = Number(new Intl.DateTimeFormat("en-GB", { timeZone: "Europe/Zurich", hour: "2-digit", hourCycle: "h23" }).format(now));
  return h < 5 ? "Good evening" : h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
}

/** SBB writes lines with a space: "S12" -> "S 12", "IC8" -> "IC 8" (inside any label). */
export const sbbText = (s: string | null | undefined) => (s ?? "").replace(/\b(IC|IR|ICE|EC|EN|NJ|TGV|RJX?|RE|PE|IRE|SN|S|R)(\d{1,3})\b/g, "$1 $2");
