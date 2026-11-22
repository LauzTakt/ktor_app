// The assistant contract (ASSISTANT_CONTRACT.md, section 2): POST /api/v1/assistant/ask and the visual commands.
import type { Geometry } from "geojson";

/** What the passenger has open in the passenger view behind the assistant (screenView.ts). */
export type ScreenView = {
  /** `train | line | station | destinations | connections` */
  screen: string;
  /** human text the page shows, e.g. "IC 1 to Genève-Aéroport", "Bern", "Bern to Thun" */
  label: string | null;
  trainId: string | null;
  line: string | null;
  stopId: string | null;
  fromStopId: string | null;
  toStopId: string | null;
};

export type AskContext = { fromStopId: string | null; toStopId: string | null; trainId: string | null; lat: number | null; lon: number | null; onScreen?: ScreenView | null };

export type Severity = "info" | "minor" | "major" | "critical";
export type SituationItem = { id: string; title: string; where?: string | null; severity: Severity; source?: "staff" | "auto" | "sbb" | string | null };
export type Option = {
  id: string;
  label: string;
  departure: number;
  arrival: number;
  transfers: number;
  recommended?: boolean;
  why?: string | null;
  journeyId?: string | null;
};

/** GeoJSON as the backend sends it: a bare geometry, a Feature or a FeatureCollection. */
export type AnyGeoJSON = Geometry | { type: "Feature"; geometry: Geometry | null } | { type: "FeatureCollection"; features: { geometry: Geometry | null }[] };

export type RouteLeg = { line?: string | null; trainId?: string | null; fromName?: string | null; toName?: string | null; geometry: AnyGeoJSON | null };
export type VisualCommand =
  | { type: "clear" }
  | { type: "focus"; bbox: [number, number, number, number] }
  | { type: "route"; id: string; style?: "recommended" | "alternative"; label?: string | null; legs: RouteLeg[] }
  | { type: "section"; id: string; colour?: "red" | "orange" | "green" | "blue"; label?: string | null; geometry: AnyGeoJSON | null }
  | { type: "train"; trainId: string; label?: string | null; follow?: boolean }
  | { type: "station"; stopId?: string | null; name: string; lon: number; lat: number; role?: "origin" | "destination" | "transfer" | "incident"; label?: string | null }
  | { type: "timer"; label: string; until: number }
  /** highlight every running train of a line */
  | { type: "line"; line: string; label?: string | null }
  /** remove one drawn item by its id (route/section id, trainId, stopId, line) */
  | { type: "remove"; id: string }
  /** live tracking of the train the passenger waits for at a station (arrivals poll + highlight) */
  | { type: "track_train"; trainId: string; stopId: string; label?: string | null }
  /** a callout on the map */
  | { type: "label"; id: string; lon: number; lat: number; text: string }
  /** fit these live trains, drawn stations and sections into the visible part of the map */
  | { type: "fit"; trainIds?: string[] | null; stopIds?: string[] | null; sectionIds?: string[] | null };

export type AskResponse = {
  answer: string;
  summary?: string | null;
  situation?: SituationItem[] | null;
  options?: Option[] | null;
  visuals?: VisualCommand[] | null;
  followUp?: string | null;
  elapsedMs?: number | null;
  /** add to the current picture instead of replacing it (default: every answer replaces the picture) */
  append?: boolean | null;
  unavailable?: string[] | null;
};

/** The place brief (server/brief.ts, GET /api/brief): what the app preloads before any slow model call. */
export type BriefDisruption = {
  id: string;
  /** operator = registered by the control centre (scenario), sbb = a real SBB alert, detected = found from train movements */
  kind: "operator" | "sbb" | "detected";
  title: string;
  where: string | null;
  effect: "interrupted" | "limited" | "resumed" | "train" | null;
  cause: string | null;
  /** "at least until 02:55", "expected until 04:00", "the end is not known yet" */
  until: string | null;
  /** the bulletin in plain words */
  text: string;
  /** the latest bulletin messages ("Replacement bus: …") */
  measures: string[];
  replacementBus: boolean;
  severity: string;
  active: boolean;
  /** for a route: on the way of the planned journeys (not just touching the origin or destination station) */
  onRoute?: boolean;
};
export type BriefJourney = { journeyId: string; durationMin: number; /** implausible duration (e.g. a very slow bus): never present as the best */ uncertain: boolean; departs: string; arrives: string; transfers: number; lateMin: number; legs: string[]; warnings: string[] };
export type Brief = {
  key: string;
  place: string;
  station: { id: string; name: string } | null;
  from: { id: string; name: string } | null;
  to: { id: string; name: string } | null;
  line: string | null;
  /** an active disruption concerns this place: answer from the brief */
  related: boolean;
  disruptions: BriefDisruption[];
  journeys: BriefJourney[];
  say: string;
  shown: string;
  situation: SituationItem[];
  options: Option[];
  visuals: VisualCommand[];
  twin: string | null;
  /** the next departures at the station (twin brief) */
  departures?: { line: string; headsign: string; time: string; delayMin: number; cancelled: boolean }[];
  /** with ?lat=&lon=: the station nearest to the passenger */
  nearest?: { id: string; name: string; distanceM: number } | null;
  source: "twin-brief" | "composed";
  elapsedMs: number;
};

/** GET /api/v1/stations/{id}/arrivals (ktor_app, from the MQTT feed): the trains on their way to a station. */
export type Arrival = { trainId: string; line: string; category: string; headsign: string; plannedArr: number; expectedArr: number; etaSec: number; delaySec: number; stopsAway: number; status: "approaching" | "at_station"; lat: number; lon: number; speedKmh: number };
