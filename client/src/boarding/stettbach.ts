import type { StyleSpecification } from "maplibre-gl";
import type { VisualCommand } from "../contract";
import type { BoardingPoint, BoardingService } from "./BoardingCard";
import routes from "./stettbach-routes.json";
import overview from "./zurich-overview.webp?inline";
import station from "./zurich-hb.webp?inline";

type Point = [number, number];
type Bounds = [Point, Point];
const imageCorners = ([[west, south], [east, north]]: Bounds): [Point, Point, Point, Point] => [
  [west, north], [east, north], [east, south], [west, south],
];
const overviewExtent: Bounds = [[8.512837023, 47.358739275], [8.615162977, 47.413246635]];
const stationExtent: Bounds = [[8.532225204, 47.375071360], [8.545674796, 47.382028410]];
export const routeBounds: [number, number, number, number] = [8.538, 47.365, 8.598, 47.402];

// Two swisstopo map extracts ship with the replay. The station view can open offline.
export const replayMapStyle: StyleSpecification = {
  version: 8,
  sources: {
    overview: { type: "image", url: overview, coordinates: imageCorners(overviewExtent) },
    station: { type: "image", url: station, coordinates: imageCorners(stationExtent) },
  },
  layers: [
    { id: "ground", type: "background", paint: { "background-color": "#eeeee9" } },
    { id: "overview-map", type: "raster", source: "overview", paint: { "raster-saturation": -0.75, "raster-opacity": 0.85, "raster-fade-duration": 0 } },
    { id: "station-map", type: "raster", source: "station", minzoom: 15, paint: { "raster-saturation": -0.68, "raster-fade-duration": 0 } },
  ],
};

export const timeline = [
  { time: "19:35", notice: "The S 9 skips Stettbach. SBB advises taking tram 7 from Bahnhofstrasse/HB." },
  { time: "19:36", notice: "SBB expects trains to start stopping at Stettbach from 20:00." },
  { time: "19:37", notice: "SBB names the first train that stops at Stettbach." },
] as const;

// GTFS 20260923: 25 September trip .ojp-91-9-D.1.TA.482.j26, quay ch:1:sloid:3000:503:43.
const publishedPoint: BoardingPoint = {
  label: "Tracks 43–44",
  coordinates: [8.53884610, 47.37896698],
  landmark: "ZVV-Contact · Halle Landesmuseum",
  directions: "In Halle Landesmuseum, use the access beside ZVV-Contact. Follow the signs for tracks 43–44.",
};

// The platform is real. Its use by this service is a simulation for the reconnect check.
export const simulatedPoint: BoardingPoint = {
  label: "Tracks 41–42",
  coordinates: [8.53881017, 47.37890615],
  landmark: "Halle Landesmuseum · Access to tracks 41–42",
  directions: "In Halle Landesmuseum, follow the signs for tracks 41–42.",
};

export const train: BoardingService = {
  line: "S 9",
  number: "18975",
  direction: "Uster",
  departure: Date.parse("2026-09-25T19:58:00+02:00"),
  from: "Zürich HB",
  destination: "Stettbach",
  point: publishedPoint,
};

export const tram: BoardingService = {
  line: "Tram 7",
  direction: "Bahnhof Stettbach",
  departure: Date.parse("2026-09-25T19:43:00+02:00"),
  from: "Zürich, Bahnhofstrasse/HB",
  destination: "Bahnhof Stettbach",
  point: {
    label: "Bahnhofstrasse/HB",
    coordinates: [8.53950187, 47.37611402],
    landmark: "Tram stop on Bahnhofstrasse",
    directions: "Follow the Bahnhofstrasse exit at Zürich HB. At the tram stop, look for tram 7 toward Bahnhof Stettbach.",
  },
};

const hb: Point = [8.54021154, 47.37817620];
const stettbach: Point = [8.59614065, 47.39721255];
export const restrictionId = "sa-stettbach-replay";

export function replayVisuals(plan: "rail" | "tram" | "disrupted"): VisualCommand[] {
  const rail = plan === "rail";
  const visuals: VisualCommand[] = [
    {
      type: "route", id: "s9-replay", label: "S 9", style: rail ? "recommended" : "alternative",
      legs: [{ line: "S 9", geometry: { type: "LineString", coordinates: routes.rail } }],
    },
  ];
  if (!rail) visuals.push(
    {
      type: "route", id: "tram7-replay", label: "Tram 7", style: plan === "tram" ? "recommended" : "alternative",
      legs: [{ line: "Tram 7", geometry: { type: "LineString", coordinates: routes.tram } }],
    },
    { type: "section", id: restrictionId, colour: "red", label: "S 9 skips Stettbach", geometry: { type: "LineString", coordinates: routes.affected } },
  );
  visuals.push(
    { type: "station", name: "Zürich HB", lon: hb[0], lat: hb[1], role: "origin" },
    { type: "station", name: "Stettbach", lon: stettbach[0], lat: stettbach[1], role: "destination" },
    { type: "focus", bbox: routeBounds },
  );
  return visuals;
}
