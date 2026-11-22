// Talks to the passenger agent in text (no microphone): checks the prompt, the ask_backend tool loop and the answers.
// Usage: node scripts/agent-chat.mjs "My IC to Bern was cancelled at Zürich HB, what now?" ["follow-up" ...]
// ask_backend calls the app's endpoint (APP_URL, default http://127.0.0.1:8080), like the browser does.
import { readFileSync } from "node:fs";
import { Conversation } from "@elevenlabs/client";

const env = Object.fromEntries(
  readFileSync(`${process.env.HOME}/.config/lauzhack/.env`, "utf8")
    .split("\n")
    .filter((l) => l.includes("="))
    .map((l) => [l.slice(0, l.indexOf("=")).trim(), l.slice(l.indexOf("=") + 1).trim()]),
);
const APP = process.env.APP_URL ?? "http://127.0.0.1:8080";
const turns = process.argv.slice(2);
if (!turns.length) turns.push("Hi, my train to Bern was cancelled. I'm at Zürich HB. What should I do?");

const r = await fetch(`https://api.elevenlabs.io/v1/convai/conversation/get-signed-url?agent_id=${env.PASSENGER_AGENT_ID}`, { headers: { "xi-api-key": env.ELEVENLABS_API_KEY } });
if (!r.ok) throw new Error(`signed url: HTTP ${r.status}`);
const { signed_url } = await r.json();

const t0 = Date.now();
const stamp = () => `${((Date.now() - t0) / 1000).toFixed(1).padStart(5)}s`;
let lastAgentAt = 0;
const active = await fetch(`${APP}/api/brief/active`).then((x) => x.json()).catch(() => ({ text: "- (not loaded)" }));
const conv = await Conversation.startSession({
  signedUrl: signed_url,
  textOnly: true,
  dynamicVariables: { active_disruptions: active.text, passenger_location: process.env.AT ?? "unknown" },
  clientTools: {
    get_brief: async (p) => {
      const t = Date.now();
      const qs = new URLSearchParams(Object.entries(p).filter(([, v]) => v));
      const b = await fetch(`${APP}/api/brief?${qs}`).then((x) => (x.ok ? x.json() : null)).catch(() => null);
      const out = b
        ? JSON.stringify({ place: b.place, relatedDisruption: b.related, disruptions: b.disruptions.filter((d) => !(b.from && b.to) || d.onRoute).slice(0, 3).map((d) => ({ title: d.title, active: d.active, effect: d.effect, cause: d.cause, expectedEnd: d.until, bulletin: d.text.slice(0, 450), latest: d.measures.slice(-2).map((m) => m.slice(0, 320)), replacementBus: d.replacementBus, onTheRoute: d.onRoute ?? null })), connections: !b.related ? [] : b.journeys.map((j) => ({ departs: j.departs, arrives: j.arrives, minutes: j.durationMin, changes: j.transfers, legs: j.legs, uncertainTime: j.uncertain })), summary: b.related ? b.say : null,
      next: b.related ? "Answer from this brief; no other call." : "No related disruption here. If the passenger wants a connection or anything live, say one short sentence that you are looking into it and call ask_backend.", shown: b.shown })
        : JSON.stringify({ found: false, note: "No station, route or line recognised." });
      console.log(`${stamp()} TOOL get_brief ${JSON.stringify(p)} -> ${Date.now() - t} ms related=${b?.related}`);
      return out;
    },
    ask_backend: async (p) => {
      console.log(`${stamp()} TOOL ask_backend ${JSON.stringify(p)}`);
      const res = await fetch(`${APP}/api/v1/assistant/ask`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ question: p.question, context: { fromStopId: p.fromStopId ?? null, toStopId: p.toStopId ?? null, trainId: p.trainId ?? null, lat: null, lon: null }, history: [], sessionId: "agent-chat" }),
      }).then((x) => x.json()).catch((e) => ({ error: String(e) }));
      console.log(`${stamp()} TOOL ask_backend took ${res.elapsedMs ?? "?"} ms in the back office`);
      const out = JSON.stringify({ answer: res.answer, unavailable: res.unavailable ?? [], followUp: res.followUp ?? null, options: (res.options ?? []).map((o) => ({ id: o.id, label: o.label, transfers: o.transfers, recommended: o.recommended, why: o.why })), situation: (res.situation ?? []).map((s) => `${s.title} (${s.severity})`) });
      console.log(`${stamp()} TOOL result ${out.slice(0, 400)}`);
      return out;
    },
  },
  onMessage: (m) => {
    if (m.source === "ai") lastAgentAt = Date.now();
    console.log(`${stamp()} ${m.source === "ai" ? "AGENT" : "USER "} ${m.message}`);
  },
  onError: (e) => console.log(`${stamp()} ERROR ${e}`),
  onDisconnect: (d) => console.log(`${stamp()} disconnected (${d.reason})`),
});

const wait = (ms) => new Promise((res) => setTimeout(res, ms));
const quiet = async (ms, max = 70000) => {
  // wait until the agent has been silent for `ms` after its last message
  const start = Date.now();
  while (Date.now() - start < max && (lastAgentAt === 0 || Date.now() - lastAgentAt < ms)) await wait(250);
};
await quiet(2500, 15000);
for (const t of turns) {
  lastAgentAt = 0;
  console.log(`${stamp()} USER  ${t}`);
  conv.sendUserMessage(t);
  await quiet(9000);
}
await conv.endSession();
process.exit(0);
