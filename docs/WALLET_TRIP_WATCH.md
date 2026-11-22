# Wallet disruption alerts

Trip Watch is an informational pass for one saved journey. The passenger adds it from the web app and picks a plan, such as taking a replacement service or waiting for a train that serves their stop. The pass shows their next action, boarding place, departure when known, and a link to the trip. The project can issue its own pass. Updating a ticket issued by SBB would require SBB's issuer integration. [Apple pass issuance](https://developer.apple.com/wallet/get-started/) · [Google generic passes](https://developers.google.com/wallet/generic)

## What Apple and Google can deliver

| | Nearby reminder | Update when advice changes |
| --- | --- | --- |
| Apple Wallet | Set `locations` to the next relevant boarding station and add a trip time window. Wallet decides when to show the pass on the lock screen. | Wake Wallet over APNs so it fetches a signed pass. A changed field with `changeMessage` can alert the passenger. |
| Google Wallet | Set `merchantLocations` on the individual pass object. Google chooses proximity, dwell time and reminder copy. The passenger needs Wallet notifications and precise, always-on location access for Wallet. | Patch the pass to show the latest instruction. Add a `TEXT_AND_NOTIFY` message when the passenger needs to act. Google documents a limit of three notification-triggering messages per 24 hours and controls the lock-screen presentation. |

Wallet determines proximity on the device. Its documented issuer APIs expose pass management and updates. For an alert that must send custom wording only when the rider is inside a station zone and their trip is affected, a native client needs to monitor that zone with permission and check fresh trip advice. The web MVP can use Wallet relevance and send action changes independently. Configure relevance only for the next boarding location and expire the pass after the trip. [Apple lock-screen relevance](https://developer.apple.com/documentation/walletpasses/showing-a-pass-on-the-lock-screen) · [Apple change messages](https://developer.apple.com/documentation/walletpasses/passfieldcontent) · [Google notifications](https://developers.google.com/wallet/generic/use-cases/trigger-push-notifications) · [Google callbacks](https://developers.google.com/wallet/generic/use-cases/use-callbacks-for-saves-and-deletions) · [iOS region monitoring](https://developer.apple.com/documentation/corelocation/monitoring-the-user-s-proximity-to-geographic-regions) · [Android geofencing](https://developer.android.com/develop/sensors-and-location/location/geofencing)

## Where it fits in this repo

The **Watch this trip** entry point fits in `connectionsScreen` after the rider chooses an origin, destination and service. Those choices already identify the route. The `sbb-client` voice UI can pass its selected journey to the same server-side watch service. A pass link should open the watch with its current advice and allow the passenger to choose a different plan or stop watching. [Ktor passenger page](../src/main/resources/static/passenger.js) · [Voice client](https://github.com/lauzhack2026/sbb-client)

| Existing code | Use in Trip Watch |
| --- | --- |
| [`JourneyQueries.connections`](../src/main/kotlin/ch/lauzhack/twin/api/v1/Journey.kt) and [`TwinQueries`](../src/main/kotlin/ch/lauzhack/twin/api/v1/TwinQueries.kt) | Work with station IDs and ordered stop calls. Verify that the selected in-service train serves the boarding stop and destination. Check cancellation, upcoming calls and departure freshness before offering boarding. Connections list direct trains already in service. Source verified platform assignments separately. |
| [`MqttIngestor`](../src/main/kotlin/ch/lauzhack/twin/mqtt/MqttIngestor.kt) and [`LiveState`](../src/main/kotlin/ch/lauzhack/twin/state/LiveState.kt) | Recheck relevant watches when a train's stop calls or status change. The ingestor subscribes to the twin's `incidents/+`, train info, fleet and status. Real public GTFS service alerts use the twin's separate `/api/alerts` or `alerts/+` feed. |
| [`Database.addIncidentListener`](../src/main/kotlin/ch/lauzhack/twin/db/Database.kt) | Wake a watch worker on an incident create, update or withdrawal. The listener runs on the SQLite writer thread under the database lock. Enqueue work there and fetch alternatives or contact Wallet outside the callback. Use a bounded periodic reconciliation of active watches for missed events, watch deadlines and restart. |
| [`Application.configureHttp`](../src/main/kotlin/ch/lauzhack/twin/Application.kt) | Inject the watch service alongside `TwinQueries` when its routes are ready. Start the worker in `Application.module` and stop it before `Database.close()`. Update the OpenAPI file and `docs/API.md` when adding routes. |

Use the twin's `/api/plan` for future rail journeys and supported replacement buses. Use a verified multimodal timetable such as Swiss OJP for tram and bus options outside the twin's coverage. The tram 7 example can use the reviewed replay fixture. The assistant branch has a `SbbTwinClient.plan` adapter suitable for reuse once integrated into main. A missing train in the live Ktor view can simply mean that the service has yet to start. A section reopening estimate is a reason to recheck the itinerary. A boarding instruction needs a named, usable departure or a verified scheduled alternative that serves the passenger's stop. [Twin planner](https://github.com/lauzhack2026/sbb-twin/blob/main/src/planner.ts) · [Twin alerts](https://github.com/lauzhack2026/sbb-twin/blob/main/src/alerts.ts) · [Ktor assistant PR](https://github.com/lauzhack2026/ktor_app/pull/4) · [Swiss Open Journey Planner](https://opentransportdata.swiss/en/cookbook/open-journey-planner-ojp-landing-page/) · [Reviewed boarding logic](https://github.com/lauzhack2026/sbb-disruption-research/blob/f620499b18d176058d675ed084f62fdd186b7431/docs/integration.md)

## Watch state and delivery

1. Save the origin, destination, service date, last actionable boarding station, planned departure, selected trip or leg IDs, passenger choice and expiry. Resolve IDs on the server. Use the repo's UTC epoch milliseconds for event times and `Europe/Zurich` when showing times to passengers. Read station coordinates from the catalog or verified timetable source. The issuer only needs coordinates for the station included on the pass.
2. Resolve an instruction for each active watch using the public bulletin, timetable, stop calls and the choice the rider made. Scope the bulletin to the remaining itinerary, including direction. Keep provider issue time, receipt time and source revision distinct. A retained removal or stale data prompts a recheck. Only use an alternative to replace a boarding instruction once its stop calls and availability are verified. If a correction is confirmed and no alternate service can be verified, tell the rider to check the trip.
3. Compare the resulting advice with the advice recorded for the pass. [`TripWatchUpdate.kt`](../src/main/kotlin/ch/lauzhack/twin/wallet/TripWatchUpdate.kt) classifies the initial advice and copy-only changes as a silent sync. A change to the next action, published departure or boarding point requests an alert. Exact repeats are skipped. Use a stable service identifier scoped to the service date. Put small forecast changes and explanation text in `detail` so they can sync silently. Handle changes to relevance or validity as part of the watch lifecycle.
4. Store the advice and an outbox entry together in SQLite. Key jobs by pass and revision so repeated feed events do not produce duplicate notifications. Keep pass revision and delivery progress separate so a provider failure can retry the outstanding work. Serialize updates per pass, combine bulletin edits that change the same instruction within a short interval, and recheck the watch's expiry before delivery. If a cleared incident returns and changes the advice again, the resulting revision can notify once.

Proposed persistence consists of a trip watch and its owner, a provider pass record with current advice and delivery revision, and an outbox. Apple also needs device registrations and a monotonic revision cursor for listing changed passes. Derive or securely store the Apple pass authentication token so it stays the same across signed versions. Keep credential material, push tokens and private trip links out of application logs and source control. On expiry or opt-out, cancel pending action alerts and queue a final silent sync to clear relevance or expire the pass.

### Proposed watch API

| Method | Input and result |
| --- | --- |
| `POST /api/v1/trip-watches` | Create a watch for a server-validated itinerary and explicit choice. Return watch ID, advice, expiry and the supported Add to Wallet links. |
| `GET /api/v1/trip-watches/{id}` | Return the latest advice for the owner. Use a scoped read link if the pass needs to open the trip without a browser session. |
| `PATCH /api/v1/trip-watches/{id}` | Set the selected plan and re-resolve its advice. A tap that changes the rider's own selection can sync silently because the trip UI has shown the change. |
| `DELETE /api/v1/trip-watches/{id}` | Stop watching and expire the provider passes. |

The existing auth service protects staff tools. Use a passenger session for watch ownership, or a bounded, opt-in demo session with an opaque management token. Keep Apple device callbacks under their own auth. Both issuer adapters should receive the same resolved advice and a delivery decision. The branch adds the decision code. Watch routes, persistence and provider delivery are implementation steps below.

## Apple Wallet adapter

* Build a generic `.pkpass` with a `passTypeIdentifier`, team identifier, random serial number, pass assets, `webServiceURL` and a stable `authenticationToken`. Set `expirationDate`, `locations` and a relevant date or supported `relevantDates` interval. Pass JSON should contain the public action, a separate detail field and a trip link. Sign the manifest with the project's Pass Type ID certificate and Apple intermediate. The pass downloads as `application/vnd.apple.pkpass`. A JVM signing library such as jPassKit is a candidate. Check its support for the selected relevance fields before adding it to Gradle. [Apple pass format](https://developer.apple.com/documentation/walletpasses/pass) · [Signing a pass](https://developer.apple.com/documentation/walletpasses/building-a-pass) · [jPassKit](https://github.com/drallgood/jpasskit)
* Mount the protocol paths under the pass `webServiceURL`, for example `https://<pass-host>/wallet/apple`. They are relative to that URL and separate from the passenger API. Requests for individual passes use `Authorization: ApplePass <token>`. [Apple update web service](https://developer.apple.com/documentation/walletpasses/adding-a-web-service-to-update-passes)

| Request | Handling |
| --- | --- |
| `POST /v1/devices/{deviceId}/registrations/{passTypeId}/{serial}` | Authorize the pass token and upsert registration and push token. Return 201 for a new registration and 200 for an existing one. |
| `DELETE /v1/devices/{deviceId}/registrations/{passTypeId}/{serial}` | Authorize the pass token and remove the registration. |
| `GET /v1/devices/{deviceId}/registrations/{passTypeId}?passesUpdatedSince={cursor}` | Use the device identifier to select only the registrations for that device. Send `serialNumbers` and `lastUpdated`, or 204 when there are no changes. |
| `GET /v1/passes/{passTypeId}/{serial}` | Authorize the pass token and return the signed version, with `Last-Modified` and conditional `304` support. |
| `POST /v1/log` | Accept bounded Wallet diagnostics and redact payloads before recording them. |

The backend publishes the new signed-pass revision before waking the phone via production APNs. Use the Pass Type ID certificate with the registered push token and an empty JSON payload. The pass is fetched after the wake-up. Wallet compares field values to decide whether to show a message. Keep the field with `changeMessage` stable when only forecast or explanation copy changes. Test that a correction still appears if the device fetches only the latest of several revisions. [Apple APNs flow](https://developer.apple.com/documentation/walletpasses/adding-a-web-service-to-update-passes) · [Apple field comparison and caching](https://developer.apple.com/library/archive/documentation/UserExperience/Conceptual/PassKit_PG/Updating.html)

## Google Wallet adapter

* Enable the Wallet REST API and grant the server service account Developer access on the Wallet issuer. Demo mode supports issuer roles and configured test accounts. Use one Generic class for branding and one Generic object per issued watch. Sign the Add to Google Wallet JWT on the server. The browser receives the save URL. [Issuer onboarding](https://developers.google.com/wallet/generic/getting-started/issuer-onboarding) · [REST API credentials](https://developers.google.com/wallet/generic/getting-started/auth/rest) · [Web save flow](https://developers.google.com/wallet/generic/web)
* On that object, use `merchantLocations` for the relevant station, `validTimeInterval` for the watch's validity, and `linksModuleData` to open the trip. Use the validity interval to expire the pass. Measure the timing of nearby reminders on device because Google sets their trigger. [Generic object fields](https://developers.google.com/wallet/reference/rest/v1/genericobject) · [Nearby reminders](https://developers.google.com/wallet/generic/use-cases/trigger-push-notifications#nearby_notifications)
* Obtain an OAuth token scoped to `https://www.googleapis.com/auth/wallet_object.issuer`. Google's Java client can be called from Kotlin and handles credential refresh. Patch `/walletobjects/v1/genericObject/{id}` with the instruction and details. For an alert, call the object's `addMessage` using `TEXT_AND_NOTIFY` after the patch. Use a stable message ID for the advice revision and look it up after an ambiguous failure before retrying the notification. Track quota-related failures separately from pass sync so the advice can still appear on the pass. [Google client authentication](https://developers.google.com/wallet/generic/use-cases/auth) · [Patch](https://developers.google.com/wallet/reference/rest/v1/genericobject/patch) · [Add message](https://developers.google.com/wallet/reference/rest/v1/genericobject/addmessage)

## Implementation order and acceptance

1. Add an opted-in watch to the Ktor passenger screen and a related read view. Store the selected plan and drive both views from a fixed replay clock and reviewed public facts. A fake delivery adapter can record a pass sync or alert request without involving provider accounts.
2. Add each issuer adapter behind its own configuration, starting with the first issuer account the team can use. Set a public HTTPS base URL, Apple Pass Type ID and signing credentials where available, and Google issuer, class ID and server credential where available. Use an HTTPS preview with test devices and test passes. Validate iPhone and Android behavior separately, including a pass add, quiet update, meaningful alert, disable and expiry. Apple documents that the simulator cannot show a nearby pass on the lock screen. The Android check needs Google Wallet's notification and precise background location settings enabled. [Apple device testing](https://developer.apple.com/documentation/walletpasses/showing-a-pass-on-the-lock-screen) · [Google nearby setup](https://developers.google.com/wallet/generic/use-cases/trigger-push-notifications#nearby_notifications)
3. Wire the worker to the twin and add the multimodal fallback source. Start with selected watches and record the public source revision, instruction, delivery decision and provider outcome. Test startup reconciliation, skipped stops, stale inputs, withdrawals, plan changes, retries and expiry before widening the rollout. Provider acceptance tells us that an update was sent or queued. Device checks establish what surfaced near a station.

Use the Zürich HB to Stettbach replay for the first acceptance check. The times below are in Zürich time. Proximity can be simulated for this archive replay. For a real lock-screen test, issue a test pass with a current time window and a station the tester can approach. Keep the public advice and passenger choices in the replay as the evidence for the decision. [Replay timeline](https://github.com/lauzhack2026/sbb-disruption-research/blob/f620499b18d176058d675ed084f62fdd186b7431/docs/findings.md#boarding-alerts)

| Replay time | Expected pass behavior |
| --- | --- |
| 19:35 | Offer tram 7 from Bahnhofstrasse/HB. The rider can take it or watch for a confirmed train. Adding the pass syncs it silently. |
| 19:36 | The 20:00 reopening estimate can update detail copy. The waiting rider still waits. |
| 19:37 | Tell the waiting rider to board the confirmed 19:58 S9 to Stettbach. The rider who chose the tram keeps their directions. |
| 19:56 | An edit about a different line leaves both actions in place. |

Run the local classifier tests with JDK 21. They cover what advice merits a delivery request. The device and provider checks above cover the later integration.

```sh
./gradlew test --tests 'ch.lauzhack.twin.wallet.TripWatchUpdateTest' --no-daemon --max-workers=2
```
