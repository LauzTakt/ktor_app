# Knowledge pack: how Swiss railways normally work

General background for explaining things to passengers. It is not live data: today's trains, times, incidents,
buses and end times come only from the tools. Use this pack to answer "how does it work" questions without tools,
and to put live results into plain words.

## Scope: what the assistant can and cannot answer

CAN give: timetables and live delays; cancelled, short-turned and held trains; disruptions with cause, section, expected end ("until" / "at least until") and the official bulletin text; replacement buses: route, stops and departure times; alternatives: next trains and routes with changes.

CANNOT give: platforms or tracks, accessibility (step-free access, lifts), fares, tickets, passes, refunds, train occupancy, luggage or bike rules, the exact bus stop location (only the station), other local transport (trams, city buses). When asked about one of these, say so at once in one sentence, without investigating, and point to the station staff or the SBB app; list the topics in respond's unavailable.

Never mention a limitation the passenger did not ask about, and never state anything about the out-of-scope items.

## Train categories (what they mean for a passenger)

- EC / EuroCity, ICE, TGV Lyria, RJX (Railjet), NJ (Nightjet): international long-distance trains; few stops in
  Switzerland.
- IC (InterCity): the fastest national trains between the big cities (for example IC 1 Geneva - Bern - Zurich -
  St. Gallen, IC 8 Brig - Bern - Zurich). Stop only at main stations. Usually every 30 or 60 minutes.
- IR (InterRegio): fast regional-to-city trains, stop at more towns than an IC.
- RE (RegioExpress): fast regional trains that skip small stations.
- R (Regio): regional trains stopping almost everywhere.
- S (S-Bahn, e.g. S3, S12; SN = night S-Bahn): suburban trains around a city, all stops, usually every 15 to 30
  minutes. The slowest, but the most frequent and the best to reach small stations.
- PE (Panorama Express) and tourist trains (Glacier Express, GoldenPass).
- EV (Ersatzverkehr): a replacement bus or coach that runs instead of trains on a closed section.
- Line numbers (IC 8, S 3, IR 15) identify a route; several trains of the same line run in both directions.
  Trains on the same line in the same direction are interchangeable for a passenger.

Swiss trains run on a fixed, regular-interval timetable ("Taktfahrplan"): the same minutes past the hour every
hour, with connections planned at the main hubs (Zurich, Bern, Basel, Lausanne, Olten, Lucerne).

## Changing trains

- The twin's journey planner allows transfers of at least 4 minutes and at most 2 transfers per journey. Large
  stations (Zurich HB, Bern) can need more time; tell the passenger to allow time and follow the signs.
- Connections are not guaranteed during disruptions: a late train can miss its planned connection, and the next
  train of the same line usually comes 30 or 60 minutes later (S-Bahn often 15 to 30).

## How disruptions are announced (SBB / VIA bulletins)

Every disruption gets one bulletin (VIA, the SBB passenger-information system). It is published at the start and
updated as things change; each update is a new version (version 1, 2, 3, ...). The latest version is what counts.

- Lifecycle: INFO while the disruption is active; ENDED once service has resumed. An ended bulletin stays visible
  for about 30 minutes ("Service resumed: A - B").
- Title: "Interrupted service: A - B", "Limited service: A - B", or "Service resumed: A - B". A single station
  ("A") means the problem is at that station.
- Description, one fact per line: what (interrupted / limited service between A and B), why ("This is due to ..."),
  how long ("The restriction lasts until 18:30" / "at least until 18:30" / "The duration is unknown"), which lines
  are affected, advice ("Allow for delays and cancellations."), and what to do ("Passengers travelling between A
  and B should use the replacement bus service (EV).").
- Messages added over time, each with a title:
  - Interruption: service is now interrupted (no trains between A and B).
  - Impact on train services: what trains do, for example "Trains end their journey at A and B; their remaining
    stops are cancelled" (short turns) or "Trains wait at A" (holds).
  - Cancellations: some trains or lines do not run.
  - Replacement bus: where EV buses run, which stations they call at, first departures and how often.
  - Recovery prognosis: a new expected end time.
  - Partial takeover: part of the service runs again (for example one track open, slow running).
  - Traffic can resume: the line is about to reopen.
  - End of disruption: the disruption is over; "Allow for further delays and occasional cancellations."
- Typical advice phrases: "Allow for delays and cancellations.", "Please allow for longer travel times.",
  "Allow for further delays and occasional cancellations." (after the end).

## Kinds of disruption and what trains do

- Interrupted service: no trains run between A and B. Trains end their journey at the last open station before
  the section (short turn) and their remaining stops are cancelled, or, when the reopening is near (about 20
  minutes or less), trains wait (hold) at the last station before the section and continue late.
- Limited service: trains still run but fewer or slower, for example only one track open: trains pass slowly
  (about 40 km/h) and about half of them are cancelled. Expect a few minutes of delay and some cancellations.
- Partial resumption: an interrupted section becomes limited (trains run again, slowly).
- Short turn: a train ends its run early, at the edge of the closed section, and usually goes back the other way.
  Passengers for stations beyond must change to the replacement bus or another route.
- Hold: the train waits at a station until the section reopens; it then continues with a delay.
- Train breakdown: one train stopped on the open line. Passengers stay aboard (never leave the train on the open
  line); staff give instructions. Trains behind it are held.
- Real figures (SBB 2024-2026): during an interruption practically no train runs through (median 0% of normal);
  about two thirds are short-turned at the edges. During limited service about two thirds of trains run, a few
  minutes late (median +2 to +4 min).

## Replacement buses (EV)

- Used on about three quarters of interruptions (less often for limited service or very short ones). They link the
  two ends of the closed section and usually call at the stations in between.
- They take longer than the train: plan for clearly longer travel times. Often every 15 to 30 minutes, first
  departures some time after the start of the disruption.
- Mention buses only when the tools say they run for this disruption.

## How long disruptions last (prognosis)

- "until 18:30" (durationType UNTIL): the expected end; say "expected until 18:30" or "until about 18:30".
- "at least until 18:30" (UNTIL_AT_LEAST): the earliest possible end; it may be extended. Say "at least until 18:30".
- unknown (UNKNOWN): no end time yet; say the duration is not known yet. Prognoses are updated in "Recovery
  prognosis" messages.
- After "Service resumed", expect some delays for a while: the section is usually back to normal within about 30
  minutes, almost always within an hour or two.

Typical durations by cause, from 520 real SBB incidents (2024-2026). Median = half of the incidents are shorter;
p75 = three quarters are shorter. Use only to say what is typical when no prognosis is given; the bulletin's own
time always wins.

| Cause | Typical (median) | Long cases (p75) |
|---|---|---|
| All incidents | 2 h 45 | 4 h 25 |
| Engine / train fault (EG_014) | 1 h 30 | 2 h |
| Barrier failure (EG_001) | 1 h 30 | 2 h |
| Fire at a station (EG_110) | 1 h 45 | 2 h 25 |
| Signal box failure (EG_010) | 2 h | 3 h 35 |
| Persons near the tracks (EG_046) | 1 h 45 | 2 h 30 |
| Accident involving a person (EG_047) | 2 h 55 | 3 h 40 |
| Obstacle on the tracks (EG_100) | 2 h 50 | 5 h 20 |
| Event outside SBB's control (EG_070) | 2 h 50 | 3 h 45 |
| Collision with a vehicle (EG_193) | 3 h 10 | 3 h 45 |
| Damage to the track (EG_006) | 3 h 40 | 10 h |
| Overhead line problem (EG_008) | 5 h | 8 h 40 |
| Unscheduled construction work (EG_158) | 5 h 15 | 8 h |
| Fallen tree (EG_025) | 6 h 20 | 7 h 40 |
| Storm, storm damage (EG_101, EG_026) | 9 h | 12 h |
| Derailment (EG_028) | 10 h 30 | 13 h |
| Vandalism, flooding (EG_052, EG_019) | 1 to 1.5 days | 1.5 days |

## What passengers should do

- Allow extra time; check the departure screens and the SBB app.
- On a stopped train: stay aboard and follow staff instructions.
- If the train ends early (short turn): change to the replacement bus if one runs, or take the route the planner
  suggests around the closed section.
- If the end is near and the train waits (hold): staying on is often the simplest option.
- For a long or unknown interruption, a detour by another line is often faster than waiting.
