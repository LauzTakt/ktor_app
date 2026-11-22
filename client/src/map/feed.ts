// Live trains: ktor_app's fleet WebSocket (/ws/fleet, one frame per second), else the same snapshot polled
// through our server (/api/fleet, same rows). Rows: [trainId, lat, lon, bearing, speedKmh, motion, delaySec,
// line, category, atStopId, continuity].
export type FleetRow = [string, number, number, number, number, string, number, string, string, string | null, number];
export type FleetListener = (rows: FleetRow[], intervalMs: number, source: "live" | "poll") => void;

const POLL_MS = 2500;
const WS_RETRY_MS = 60000;

export class FleetFeed {
  private ws?: WebSocket;
  private poll?: number;
  private retry?: number;
  private lastWs = 0;
  private stopped = false;

  constructor(private onRows: FleetListener) {
    document.addEventListener("visibilitychange", () => (document.hidden ? this.pause() : this.start()));
  }

  start() {
    this.stopped = false;
    this.connect();
  }

  private connect() {
    if (this.stopped || this.ws) return;
    let opened = false;
    const ws = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws/fleet`);
    this.ws = ws;
    const giveUp = window.setTimeout(() => !opened && ws.close(), 4000);
    ws.onopen = () => {
      opened = true;
      window.clearTimeout(giveUp);
      this.stopPolling();
    };
    ws.onmessage = (e) => {
      const now = performance.now();
      if (now - this.lastWs < 700) return; // at most one update per ~second
      this.lastWs = now;
      try {
        const m = JSON.parse(String(e.data)) as { type?: string; trains?: FleetRow[] };
        if (m.trains) this.onRows(m.trains, 1000, "live");
      } catch {
        /* a malformed frame: wait for the next one */
      }
    };
    ws.onclose = () => {
      window.clearTimeout(giveUp);
      this.ws = undefined;
      if (this.stopped) return;
      this.startPolling();
      this.retry = window.setTimeout(() => this.connect(), opened ? 3000 : WS_RETRY_MS);
    };
  }

  private startPolling() {
    if (this.poll || this.stopped) return;
    const tick = async () => {
      try {
        const r = await fetch("/api/fleet");
        if (r.ok) {
          const m = (await r.json()) as { trains?: FleetRow[] };
          if (m.trains && !this.ws) this.onRows(m.trains, POLL_MS, "poll");
        }
      } catch {
        /* keep the last picture; try again on the next tick */
      }
    };
    void tick();
    this.poll = window.setInterval(tick, POLL_MS);
  }
  private stopPolling() {
    window.clearInterval(this.poll);
    this.poll = undefined;
  }

  pause() {
    this.stopped = true;
    window.clearTimeout(this.retry);
    this.stopPolling();
    this.ws?.close();
    this.ws = undefined;
  }
}
