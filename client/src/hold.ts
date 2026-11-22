// Soft hold music while the back office thinks: a calm ambient pad made with Web Audio (no recorded audio).
// It fades in after about a second of waiting, fades out when the answer arrives, and is silent whenever the
// agent itself is speaking.
type Ctx = AudioContext;

const CHORDS = [
  [220.0, 277.18, 329.63, 415.3], // A maj7
  [185.0, 233.08, 277.18, 349.23], // F#m7 (soft)
  [146.83, 220.0, 277.18, 329.63], // D maj9 shape
  [164.81, 246.94, 311.13, 369.99], // E6/9-ish
];

export class HoldMusic {
  private ctx?: Ctx;
  private master?: GainNode;
  private voices: { osc: OscillatorNode; gain: GainNode }[] = [];
  private chordTimer?: number;
  private startTimer?: number;
  private waiting = false;
  private speaking = false;
  private chord = 0;
  level = 0.055;

  /** Create (or resume) the audio context: call inside a tap so iOS lets it play. */
  unlock() {
    try {
      if (!this.ctx) {
        const C = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
        if (!C) return;
        this.ctx = new C();
      }
      void this.ctx.resume();
    } catch {
      /* no audio */
    }
  }

  private build() {
    const ctx = this.ctx!;
    if (this.master) return;
    const master = ctx.createGain();
    master.gain.value = 0;
    const lp = ctx.createBiquadFilter();
    lp.type = "lowpass";
    lp.frequency.value = 900;
    lp.Q.value = 0.4;
    // a slow breathing of the filter
    const lfo = ctx.createOscillator();
    const lfoGain = ctx.createGain();
    lfo.frequency.value = 0.07;
    lfoGain.gain.value = 260;
    lfo.connect(lfoGain).connect(lp.frequency);
    lfo.start();
    lp.connect(master).connect(ctx.destination);
    for (let i = 0; i < 4; i++) {
      const osc = ctx.createOscillator();
      osc.type = i % 2 ? "sine" : "triangle";
      osc.detune.value = (i - 1.5) * 4;
      const gain = ctx.createGain();
      gain.gain.value = 0.22;
      osc.connect(gain).connect(lp);
      osc.frequency.value = CHORDS[0][i];
      osc.start();
      this.voices.push({ osc, gain });
    }
    this.master = master;
  }

  private nextChord() {
    const ctx = this.ctx!;
    this.chord = (this.chord + 1) % CHORDS.length;
    const t = ctx.currentTime;
    this.voices.forEach((v, i) => v.osc.frequency.setTargetAtTime(CHORDS[this.chord][i], t, 1.2));
  }

  private target() {
    if (!this.ctx || !this.master) return;
    const on = this.waiting && !this.speaking && !this.user;
    const t = this.ctx.currentTime;
    this.master.gain.cancelScheduledValues(t);
    this.master.gain.setTargetAtTime(on ? this.level : 0, t, on ? 0.9 : 0.25);
  }

  /** The passenger is waiting for the back office. */
  start(delayMs = 1000) {
    if (this.waiting) return;
    this.waiting = true;
    window.clearTimeout(this.startTimer);
    this.startTimer = window.setTimeout(() => {
      if (!this.waiting || !this.ctx) return;
      this.build();
      void this.ctx.resume();
      this.target();
      console.info(`[timing] hold music fades in${this.speaking ? " (held back: the agent is speaking)" : ""}`);
      window.clearInterval(this.chordTimer);
      this.chordTimer = window.setInterval(() => this.nextChord(), 4200);
    }, delayMs);
  }

  stop() {
    if (this.waiting && this.master) console.info("[timing] hold music fades out");
    this.waiting = false;
    window.clearTimeout(this.startTimer);
    window.clearInterval(this.chordTimer);
    this.target();
  }

  private userTimer?: number;
  private user = false;
  /** The passenger is talking: silent for a moment. */
  userSpeech() {
    this.user = true;
    this.target();
    window.clearTimeout(this.userTimer);
    this.userTimer = window.setTimeout(() => ((this.user = false), this.target()), 1500);
  }

  /** Never over the agent's voice. */
  setSpeaking(s: boolean) {
    this.speaking = s;
    this.target();
  }

  playing() {
    return this.waiting && !this.speaking && Boolean(this.master);
  }
}
