// What the assistant can and cannot tell (David's fixed scope). A question that is only about an out-of-scope topic
// gets an immediate honest answer, with no backend call and no "let me check".
import { t } from "./i18n";

export type Unavailable = "platforms" | "accessibility" | "fares" | "tickets" | "refunds" | "occupancy" | "luggage" | "bikes" | "bus_stop_location" | "local_transport";

const TOPICS: [Unavailable, RegExp, string][] = [
  ["platforms", /\b(platforms?|tracks?|gleis|voie|quai|binario|and[eé]n)\b/i, t("platform information")],
  ["accessibility", /\b(step[- ]?free|wheelchair|accessib\w*|lifts?|elevators?|barrier[- ]?free|rollstuhl|stufenlos|fauteuil|ramp)\b/i, t("accessibility information")],
  ["fares", /\b(fares?|prices?|how much does|what does it cost|preis|prix|tarif\w*)\b/i, t("fare information")],
  ["tickets", /\b(tickets?|billets?|travel pass(?:es)?|halbtax|ga travelcard|supersaver|abo)\b/i, t("ticket information")],
  ["refunds", /\b(refunds?|compensation|money back|reimburs\w*|erstattung|rembours\w*)\b/i, t("refund information")],
  ["occupancy", /\b(occupancy|crowded|how full|seats? (?:free|available)|auslastung)\b/i, t("occupancy information")],
  ["luggage", /\b(luggage|baggage|suitcases?|gep[aä]ck|bagages?)\b/i, t("luggage rules")],
  ["bikes", /\b(bikes?|bicycles?|velo|vélo|fahrrad)\b/i, t("bike rules")],
  ["bus_stop_location", /\bwhere (?:exactly )?(?:is|are|do i (?:find|catch|get)) the (?:replacement )?bus(?:es)?(?: stop)?\b|\bbus stop\b/i, t("the exact location of the bus stop")],
  ["local_transport", /\b(trams?|city bus(?:es)?|local bus(?:es)?|metro|tpg|vbz|tl lausanne|bernmobil)\b/i, t("local transport")],
];

/** Topics in the question the assistant has no data for. */
export function unavailableIn(question: string): { topics: Unavailable[]; words: string[] } {
  const hits = TOPICS.filter(([, re]) => re.test(question));
  return { topics: hits.map((h) => h[0]), words: hits.map((h) => h[2]) };
}

/** True when the question asks nothing else that the live data could answer. */
export function onlyUnavailable(question: string) {
  const { topics } = unavailableIn(question);
  if (!topics.length) return false;
  const inScope = /\b(delay\w*|late|cancel\w*|next train|when does|when is|how do i get|how can i get|route|disrupt\w*|interrupt\w*|running|connection|alternative|until|why)\b/i;
  return !inScope.test(question);
}

export function unavailableAnswer(words: string[]) {
  const what = words.length > 1 ? t("{list} or {last}", { list: words.slice(0, -1).join(", "), last: words.at(-1)! }) : words[0];
  return t("I'm sorry, I don't have {what}. The station staff or the SBB app can tell you.", { what });
}
