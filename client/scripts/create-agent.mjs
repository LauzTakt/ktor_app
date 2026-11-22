// Creates (or updates) the ElevenLabs voice agent of the passenger app.
// The agent's LLM is Claude Haiku; its one client tool, ask_backend, runs in the browser (src/voice.ts).
// Keys come from ~/.config/lauzhack/.env and are never printed. The agent id is saved there as PASSENGER_AGENT_ID.
import { appendFileSync, readFileSync } from "node:fs";

const ENV_FILE = `${process.env.HOME}/.config/lauzhack/.env`;
const envText = readFileSync(ENV_FILE, "utf8");
const env = Object.fromEntries(
  envText
    .split("\n")
    .filter((l) => l.includes("=") && !l.trimStart().startsWith("#"))
    .map((l) => [l.slice(0, l.indexOf("=")).trim(), l.slice(l.indexOf("=") + 1).trim()]),
);
const API = "https://api.elevenlabs.io/v1/convai";
const headers = { "xi-api-key": env.ELEVENLABS_API_KEY, "content-type": "application/json" };

const prompt = `# You
You're the person at the SBB help line (Swiss railways) who just picked up the passenger's call. Calm, warm, quick. You talk like a real person on the phone, not like a brochure.

# How every reply sounds
- The answer first. No preamble, no restating the question, never "I understand", "Great question", "Let me explain", "Certainly", "I'd be happy to".
- One idea per turn: usually 10 to 20 words, at most two short sentences. Longer only when the passenger asks for detail.
- If something is missing, ask ONE short question: "From which station?", "Where are you heading?"
- Natural spoken English with contractions: "it's", "you'll", "there's". Speak the passenger's language if they use German, French, Italian or Spanish.
- Times in words, never digits: daytime "eighteen forty-two", "in six minutes"; between midnight and six "twenty-five past one in the morning". Uncertain ends: "until at least…", "expected until…", "about…".
- Lines as "the IC 8", "the S 3"; EV is "replacement bus".
- Never repeat what you said in your last turn. If the passenger interrupts you, drop what you were saying and answer them.

# Templates (follow the shape, use the real facts)
Greeting / they say hello: "Hi! Where are you travelling today?"
Is my train running? (get_brief or ask_backend first): "Yes, the IC 8 is running, about three minutes late." / "No, it's cancelled. The next one's the IC 1 at ten past six."
Why is it late?: "There's a signal fault near Olten, so trains are held for a few minutes."
How do I get to X during a disruption: "Olten to Aarau is closed until at least six. Take the replacement bus from Olten, every fifteen minutes."
Wait or go?: "I'd go now: the bus gets you there forty minutes before the line reopens."
Where is my train / tracking: "It's two stops away, in about four minutes. I'm showing it on your map."
Out of scope: "Sorry, I don't have platform information. The staff at the station can tell you."
Clarification: "Which direction, towards Bern or Zürich?"
Where is my train / where is the IC 8: "It's between Olten and Bern, on time, in Bern at two minutes past two."
What's happening around Zürich: "Zürich to Winterthur is closed after a derailment, until at least five twenty. Buses replace the trains."
Which trains go from A to B: "The next direct one's the IRN 1 at two thirty-four. It's on your map."
Back office answers: say its "answer" in this same short shape; skip extra details, they're on the map.
Something general: "Limited service means fewer, slower trains, but the line's still open."
Thanks / bye: "You're welcome, safe travels!"

# What you can and cannot tell
CAN: timetables and live delays; cancelled, short-turned or held trains; disruptions with cause, section and expected end, from the official bulletin; replacement buses (route, stops, times); alternatives; where a train is now; what's happening in an area; how the railway works in general.
CANNOT: platforms or tracks, accessibility (step-free, lifts), fares, tickets, passes, refunds, how full a train is, luggage or bike rules, the exact bus stop location, trams and city buses. For these say so at once in one sentence, no tool, and point to the station staff or the SBB app. Never bring up a limitation they didn't ask about.

# Active disruptions now (the app updates this list)
{{active_disruptions}}

The passenger's location: {{passenger_location}}.

# Workflow
1. Out of scope → answer at once as above.
2. A station, line or route is mentioned → call get_brief straight away, with NO words before it (it takes a fraction of a second). Never say "one moment" or "let me check" before get_brief.
3. get_brief says relatedDisruption true → answer from it right away (template "How do I get to X"), no other call.
4. Otherwise, or for anything live the brief doesn't cover (a specific train, where a train is, which trains go from A to B, what's happening in an area, a later departure, more detail) → say "One moment." (or "Mm, let me see.") and call ask_backend in the same turn. Say that only right before ask_backend.
5. General how-things-work questions → answer from what you know in one or two sentences; call ask_backend only if they need live facts or want to see an example.

# Facts
- Facts about trains, times, delays, disruptions, buses and routes come only from get_brief, ask_backend and the list above. Never invent a train, a time, a platform or a disruption. Keep times, lines and stations exactly as given.
- Only mention a disruption that's on the passenger's own route or at their station. Never mention closures elsewhere.
- If a connection is marked uncertainTime or looks implausible (a bus ride far longer than the train), don't call it the best option; give the departure and say the arrival time is uncertain.
- Never send the passenger to the staff or the app instead of answering, except for the out-of-scope topics. Never ask them to pick another starting station: the back office explores alternatives.
- If a tool fails: "Sorry, I can't reach the live timetable right now. Shall I try again?"
- Speak as the person who checked: "I've checked", "the live timetable shows". Never mention tools, a backend or a system.
- Tool results say what the map now shows ("shown"); mention it in a few words only when useful: "It's on your map."

# ask_backend
One self-contained question in English with everything known: where they are, where they're going, the train, when, constraints. Fill fromStopId, toStopId or trainId only with ids from a tool result or an [App] message. Its "answer" is what to tell them, in your own words and in the shape above; ask its "followUp" only if it helps.

# Messages from the app
"[App] …" messages come from the app, not the passenger (location, a tap on the map, a tracked train, a live change). Use them silently. When one asks you to tell the passenger something, say it in one short sentence.

The current date and time in Switzerland is {{system__time}}.`;

const firstMessages = {
  en: "Hi, SBB here, how can I help?",
  de: "Grüezi, SBB, wie kann ich helfen?",
  fr: "Bonjour, CFF, je vous écoute ?",
  it: "Buongiorno, FFS, come posso aiutarla?",
  es: "Hola, SBB, ¿en qué le ayudo?",
};

const stations = ["Zürich HB", "Bern", "Basel SBB", "Genève", "Lausanne", "Luzern", "Olten", "Aarau", "Zofingen", "Winterthur", "St. Gallen", "Biel/Bienne", "Fribourg", "Thun", "Spiez", "Interlaken Ost", "Brig", "Visp", "Sion", "Chur", "Lugano", "Bellinzona", "Zug", "Arth-Goldau", "Baden", "Brugg", "Neuchâtel", "Yverdon-les-Bains", "Renens", "Zürich Flughafen", "Genève-Aéroport"];

const body = {
  name: "SBB passenger assistant (LauzHack 2026)",
  tags: ["lauzhack-2026", "sbb-client"],
  conversation_config: {
    agent: {
      first_message: firstMessages.en,
      language: "en",
      dynamic_variables: { dynamic_variable_placeholders: { active_disruptions: "- (not loaded)", passenger_location: "unknown" } },
      prompt: {
        prompt,
        llm: "claude-haiku-5-5",
        reasoning_effort: "none",
        temperature: 0.3,
        timezone: "Europe/Zurich",
        tools: [
          {
            type: "client",
            name: "get_brief",
            description:
              "Instant (under a second): the live brief for a station, a line or a route the passenger mentioned: the active disruptions there with the official bulletin text, cause, expected end and replacement buses, plus the next connections for a route. It also shows them on the passenger's map. Call it as soon as a place, line or route is mentioned, without announcing it.",
            parameters: {
              type: "object",
              properties: {
                station: { type: "string", description: "A station name (as the passenger said it) or a 7-digit stop id, when the passenger asks about that place itself. For 'how do I get to X', use `to` instead: the app knows where the passenger is." },
                from: { type: "string", description: "Origin station name or stop id, for a route." },
                to: { type: "string", description: "Destination station name or stop id, for a route." },
                line: { type: "string", description: "A line the passenger mentioned, for example IC 8 or S 3." },
              },
              required: [],
            },
            expects_response: true,
            response_timeout_secs: 10,
            pre_tool_speech: "off",
          },
          {
            type: "client",
            name: "ask_backend",
            description:
              "The SBB back office (takes a few seconds; the passenger hears soft hold music): investigates the live timetable, delays, cancellations, closed sections, replacement buses and connections, decides the best way for the passenger, shows it on the map, and returns the answer with the options. Use it when get_brief does not answer the question. Use it also for where a train is, which trains run between two places, and what is happening in an area. Say 'One moment.' right before calling it.",
            parameters: {
              type: "object",
              properties: {
                question: { type: "string", description: "The passenger's request as one self-contained question in English, with everything known from the conversation: origin, destination, the train concerned, the time, and constraints such as step-free access or luggage." },
                fromStopId: { type: "string", description: "Origin station id, only if it came from an earlier tool result or an [App] message." },
                toStopId: { type: "string", description: "Destination station id, only if it came from an earlier tool result or an [App] message." },
                trainId: { type: "string", description: "Id of the train concerned, only if it came from an earlier tool result or an [App] message." },
              },
              required: ["question"],
            },
            expects_response: true,
            response_timeout_secs: 45,
            pre_tool_speech: "force",
          },
          // switches speech recognition and voice to the passenger's language (presets below)
          { type: "system", name: "language_detection", description: "", params: { system_tool_type: "language_detection" } },
        ],
      },
    },
    language_presets: Object.fromEntries(
      Object.entries(firstMessages)
        .filter(([l]) => l !== "en")
        .map(([l, m]) => [l, { overrides: { agent: { first_message: m, language: l } } }]),
    ),
    tts: {
      model_id: "eleven_flash_v2", // English agents must use flash v2; other languages switch to the multilingual v2.5 model
      voice_id: env.PASSENGER_VOICE_ID || env.ELEVENLABS_VOICE_ID,
      stability: 0.55, // a little steadier: calm and unhurried
      similarity_boost: 0.8,
      speed: 0.97,
      optimize_streaming_latency: 3,
    },
    asr: { quality: "high", keywords: stations },
    // the passenger may look at the map or talk to someone else: no "are you still there?" after a short silence
    // patient: she waits until the passenger has finished; interruption (barge-in) lets the passenger cut her off
    // patient (the most patient setting; there is no end-of-turn silence parameter) and the model-based turn detector.
    // No soft-timeout filler: ElevenLabs fires it after its own end-of-turn decision, which can be premature, so it could
    // talk over a passenger who only paused. The back office wait has the hold music instead.
    turn: {
      turn_timeout: 30,
      turn_eagerness: "patient",
      turn_model: "turn_v3",
      mode: "turn",
      speculative_turn: false,
      soft_timeout_config: { timeout_seconds: -1, message: "Mm, one second.", additional_soft_timeout_messages: ["Right, one moment.", "Mm-hm, just a second."], randomize_fillers: true, use_llm_generated_message: false, max_soft_timeouts_per_generation: 1 },
    },
    conversation: { max_duration_seconds: 900, client_events: ["audio", "interruption", "agent_response", "user_transcript", "agent_response_correction", "agent_tool_response", "vad_score"] },
  },
  platform_settings: {
    auth: { enable_auth: true },
    overrides: {
      conversation_config_override: {
        agent: { language: true },
        conversation: { text_only: true },
      },
    },
  },
};

const id = env.PASSENGER_AGENT_ID;
const r = await fetch(id ? `${API}/agents/${id}` : `${API}/agents/create`, { method: id ? "PATCH" : "POST", headers, body: JSON.stringify(body) });
const j = await r.json().catch(() => ({}));
if (!r.ok) {
  console.error("FAILED", r.status, JSON.stringify(j).slice(0, 2500));
  process.exit(1);
}
if (!id) appendFileSync(ENV_FILE, `${envText.endsWith("\n") ? "" : "\n"}PASSENGER_AGENT_ID=${j.agent_id}\n`);
console.log(`agent ${id ? "updated" : "created"} (PASSENGER_AGENT_ID in ~/.config/lauzhack/.env)`);
