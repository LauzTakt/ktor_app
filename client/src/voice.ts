// The ElevenLabs conversation (Claude Haiku behind it). Credentials come from our server (/api/voice/session), so
// the API key never reaches the browser. WebRTC first (best echo cancellation on phones), WebSocket as fallback.
import { Conversation } from "@elevenlabs/client";

export type VoiceStatus = "idle" | "connecting" | "live";
export type AskBackendParams = { question?: string; fromStopId?: string; toStopId?: string; trainId?: string };
export type GetBriefParams = { station?: string; from?: string; to?: string; line?: string };
export type VoiceEvents = {
  status: (s: VoiceStatus) => void;
  mode: (m: "listening" | "speaking") => void;
  transcript: (role: "passenger" | "assistant", text: string) => void;
  error: (message: string) => void;
  /** The passenger started speaking over the agent: her audio is already muted. */
  bargeIn: () => void;
  /** The passenger is talking (the hold music steps back). */
  userSpeech: () => void;
  askBackend: (p: AskBackendParams) => Promise<string>;
  getBrief: (p: GetBriefParams) => Promise<string>;
  /** Variables the agent's prompt reads at the start ({{active_disruptions}}, {{passenger_location}}). */
  variables: () => Record<string, string>;
};
type Credentials = { at: number; conversationToken: string | null; signedUrl: string | null };

let cached: Credentials | undefined;
let inflight: Promise<Credentials> | undefined;
async function fetchCredentials(): Promise<Credentials> {
  inflight ??= fetch("/api/voice/session")
    .then(async (r) => {
      const j = (await r.json()) as { conversationToken?: string | null; signedUrl?: string | null; error?: string };
      if (!r.ok) throw new Error(j.error ?? `HTTP ${r.status}`);
      cached = { at: Date.now(), conversationToken: j.conversationToken ?? null, signedUrl: j.signedUrl ?? null };
      return cached;
    })
    .finally(() => (inflight = undefined));
  return inflight;
}
/** Fetch credentials ahead of the tap, so the session starts inside the user gesture (iOS audio rules). */
export function prefetchVoice() {
  if (!cached || Date.now() - cached.at > 4 * 60_000) void fetchCredentials().catch(() => {});
}

/**
 * Triggers the microphone permission prompt (and checks a microphone exists). The stream is released at once: the
 * ElevenLabs session opens its own, and with the permission granted it gets it without a second prompt.
 */
function askMicrophone(): Promise<void> {
  if (!navigator.mediaDevices?.getUserMedia) return Promise.reject(Object.assign(new Error("no mediaDevices"), { name: "NoMicrophone" }));
  return navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } }).then((stream) => {
    for (const t of stream.getTracks()) t.stop();
  });
}

/** iOS lets a page play sound only after audio was started inside a tap: do it before any await. */
function unlockAudio() {
  try {
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctx) return;
    const ctx = new Ctx();
    const src = ctx.createBufferSource();
    src.buffer = ctx.createBuffer(1, 1, 22050);
    src.connect(ctx.destination);
    src.start(0);
    void ctx.resume().finally(() => setTimeout(() => void ctx.close(), 500));
  } catch {
    /* nothing to unlock */
  }
}

export class Voice {
  private conv?: Conversation;
  status: VoiceStatus = "idle";
  private mode: "listening" | "speaking" = "listening";
  private muted = 0; // when the agent's audio was muted locally (0 = not muted)
  private loud = 0; // consecutive loud microphone samples
  private watch?: number;
  private serverInterrupted = false;
  private onset = 0; // when the microphone first rose above the noise floor
  private lastVoice = 0; // the last time the passenger's microphone was above the noise floor
  private lastAgent = 0; // the last time the agent was speaking
  private notes: { key: string; text: string }[] = [];
  private told = new Set<string>();
  /** a tool call is running (the agent is about to speak) */
  busy = false;

  /**
   * Something the agent should say on its own (a live change to the trip, the tracked train arriving). Delivered only
   * when the passenger has been quiet for 3 s and the agent is listening, once per key.
   */
  notify(key: string, text: string) {
    if (this.told.has(key) || this.notes.some((n) => n.key === key)) return;
    this.notes.push({ key, text });
  }
  private deliver() {
    const now = performance.now();
    if (!this.notes.length || !this.conv || this.mode !== "listening" || this.busy) return;
    if (now - this.lastVoice < 3000 || now - this.lastAgent < 1500) return;
    const n = this.notes.shift()!;
    this.told.add(n.key);
    console.info(`[timing] proactive: ${n.key}`);
    this.conv.sendUserMessage(`[App] ${n.text}`);
  }

  /**
   * Barge-in without waiting for the server: while the agent speaks, the (echo-cancelled) microphone is sampled every
   * 40 ms; three loud samples in a row (or a high VAD score) mute her audio at once. The server's interruption
   * confirms it and drops the rest of the reply; if it does not follow within a second (a cough, a door), the
   * volume comes back.
   */
  private duck(why: string) {
    if (this.muted || this.mode !== "speaking" || !this.conv) return;
    this.muted = performance.now();
    this.serverInterrupted = false;
    try {
      this.conv.setVolume({ volume: 0 });
    } catch {
      /* not connected */
    }
    console.info(`[timing] barge-in: agent muted locally ${this.onset ? Math.round(performance.now() - this.onset) : "?"} ms after the passenger's voice started (${why})`);
    this.ev.bargeIn();
    window.setTimeout(() => {
      if (this.muted && !this.serverInterrupted && this.mode === "speaking") {
        console.info("[timing] barge-in: no interruption from the server, volume restored");
        this.unduck();
      }
    }, 1100);
  }
  private unduck() {
    this.muted = 0;
    try {
      this.conv?.setVolume({ volume: 1 });
    } catch {
      /* not connected */
    }
  }
  private startWatch() {
    window.clearInterval(this.watch);
    this.watch = window.setInterval(() => {
      if (!this.conv) return;
      let v = 0;
      try {
        v = this.conv.getInputVolume();
      } catch {
        return;
      }
      if (v > 0.05) this.lastVoice = performance.now();
      if (this.mode === "speaking") this.lastAgent = performance.now();
      this.deliver();
      if (v > 0.05 && !this.onset) this.onset = performance.now();
      if (v <= 0.05) this.onset = 0;
      this.loud = v > 0.16 ? this.loud + 1 : 0;
      if (this.loud < 3) return;
      if (this.mode === "speaking" && !this.muted) this.duck(`mic level ${v.toFixed(2)}`);
      else if (this.mode === "listening") this.ev.userSpeech();
    }, 40);
  }
  constructor(private ev: VoiceEvents) {}

  private set(s: VoiceStatus) {
    this.status = s;
    this.ev.status(s);
  }

  async start() {
    if (this.status !== "idle") return;
    unlockAudio();
    // Ask for the microphone inside the tap, before any await: browsers (iOS Safari, Android WebView) only show the
    // permission prompt from a user gesture. Done in parallel with the credentials so a server problem can't hide it.
    const mic = askMicrophone();
    this.set("connecting");
    try {
      const credentials = cached && Date.now() - cached.at < 5 * 60_000 ? Promise.resolve(cached) : fetchCredentials();
      credentials.catch(() => {}); // reported below, after the microphone
      await mic;
      const fresh = await credentials;
      cached = undefined; // credentials are single use
      // synchronous on purpose (the app keeps them fresh): no await between the tap and the microphone (iOS)
      const dynamicVariables = this.ev.variables();
      const options = {
        dynamicVariables: { active_disruptions: "- (not loaded)", passenger_location: "unknown", ...dynamicVariables },
        clientTools: { ask_backend: (p: AskBackendParams) => this.ev.askBackend(p), get_brief: (p: GetBriefParams) => this.ev.getBrief(p) },
        onMessage: (m: { message: string; source: "user" | "ai" }) => {
          const t = m.message?.trim();
          // silence comes back as "..." from speech recognition: not something the passenger said
          if (t && /[\p{L}\p{N}]/u.test(t)) this.ev.transcript(m.source === "user" ? "passenger" : "assistant", t);
        },
        onModeChange: ({ mode }: { mode: "speaking" | "listening" }) => {
          const was = this.mode;
          this.mode = mode;
          if (mode === "listening" && this.muted) console.info(`[timing] barge-in: agent stopped ${Math.round(performance.now() - this.muted)} ms after the local mute`);
          // a new reply after an interruption plays at full volume again
          if (mode === "speaking" && was === "listening" && this.muted) this.unduck();
          this.ev.mode(mode);
        },
        onVadScore: ({ vadScore }: { vadScore: number }) => {
          if (vadScore > 0.8) this.duck(`vad ${vadScore.toFixed(2)}`);
        },
        onInterruption: () => {
          this.serverInterrupted = true;
          console.info(`[timing] barge-in: server interruption${this.muted ? ` ${Math.round(performance.now() - this.muted)} ms after the local mute` : " (no local mute before it)"}`);
          if (!this.muted) {
            this.muted = performance.now();
            try {
              this.conv?.setVolume({ volume: 0 });
            } catch {
              /* not connected */
            }
            this.ev.bargeIn();
          }
        },
        onDisconnect: () => {
          window.clearInterval(this.watch);
          this.muted = 0;
          this.conv = undefined;
          this.set("idle");
          prefetchVoice();
        },
        onError: (message: string) => this.ev.error(message),
      };
      try {
        this.conv = fresh.conversationToken
          ? await Conversation.startSession({ ...options, conversationToken: fresh.conversationToken, connectionType: "webrtc" })
          : await Conversation.startSession({ ...options, signedUrl: fresh.signedUrl!, connectionType: "websocket" });
      } catch (e) {
        if (!fresh.conversationToken || !fresh.signedUrl) throw e;
        // WebRTC blocked (a strict network, for example): the WebSocket path
        this.conv = await Conversation.startSession({ ...options, signedUrl: fresh.signedUrl, connectionType: "websocket" });
      }
      this.set("live");
      this.startWatch();
    } catch (e) {
      this.conv = undefined;
      this.set("idle");
      const err = e as Error & { name?: string };
      const msg = `${err?.name ?? ""} ${err?.message ?? String(e)}`;
      this.ev.error(
        err?.name === "NoMicrophone" ? "This browser can't use a microphone here (it needs HTTPS). You can type your question instead."
        : /permission|denied|notallowed|security/i.test(msg) ? "Please allow the microphone to talk to the assistant (browser or app settings)."
        : /notfound|notreadable|overconstrained/i.test(msg) ? "No working microphone was found. You can type your question instead."
        : /not configured/i.test(msg) ? "Voice is not set up on this server yet. You can type your question instead."
        : "The voice assistant could not start. You can type your question instead.",
      );
      console.warn("[voice] start failed:", msg);
      prefetchVoice();
    }
  }

  stop() {
    void this.conv?.endSession();
  }
  live() {
    return this.status === "live" && Boolean(this.conv);
  }
  sendText(text: string) {
    this.conv?.sendUserMessage(text);
  }
  /** Context the agent should know without speaking about it (location, a tapped option). */
  context(text: string) {
    if (this.live()) this.conv?.sendContextualUpdate(text);
  }
  levels() {
    try {
      return { input: this.conv?.getInputVolume() ?? 0, output: this.conv?.getOutputVolume() ?? 0 };
    } catch {
      return { input: 0, output: 0 };
    }
  }
}
