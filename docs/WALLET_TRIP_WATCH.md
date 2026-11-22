# Wallet disruption alerts

Trip Watch follows an itinerary the passenger selects in the app. It stores the ordered legs and shows the next instruction, including where to board or get off. The passenger connection view and the voice assistant both open the same watch screen. The Android and iOS apps load that screen from the hosted app. [Passenger connections](../src/main/resources/static/passenger.js) · [Assistant options](../client/src/ui.tsx) · [Mobile app](../mobile/README.md)

## Run it

Set `WALLET_ENABLED=true` in the Ktor service environment. With live data configured, choose a connection as usual, open **Watch this trip**, and confirm the selection. The watch works in the browser before either issuer is configured. **Watched trips** lists watches created by that browser. A pass links back to a read view that also shows management controls in the owning browser. [Environment example](../.env.example) · [API](API.md#endpoints)

When using a Wallet issuer, set `WALLET_PUBLIC_BASE_URL` to the public HTTPS origin of the app. Set `WALLET_DB_PATH` if the service should store watch state elsewhere. By default Ktor puts `.wallet.sqlite` beside the main database. Use a persistent volume for both. The issuer settings take file paths to mounted credentials.

| Provider | Configuration |
| --- | --- |
| Apple Wallet | Set `WALLET_APPLE_PASS_TYPE_ID`, `WALLET_APPLE_TEAM_ID`, `WALLET_APPLE_P12` and its password if applicable. Set `WALLET_APPLE_WWDR_CERT` to the matching WWDR certificate if the PKCS#12 lacks it. The pass update service runs at `/wallet/apple/v1`. |
| Google Wallet | Create a Generic class on the Wallet issuer, configure its ID as `WALLET_GOOGLE_CLASS_ID` and set `GOOGLE_APPLICATION_CREDENTIALS` to the issuer's authorized service account JSON. Issuers in demo mode can save through their test accounts. |

Apple Pass Type ID and Google issuer credentials belong to the project that issues the informational pass. Updating an actual SBB ticket would require SBB's issuer integration. [Apple signing](https://developer.apple.com/documentation/walletpasses/building-a-pass) · [Google issuer setup](https://developers.google.com/wallet/generic/getting-started/issuer-onboarding) · [Google REST credentials](https://developers.google.com/wallet/generic/getting-started/auth/rest)

## What the pass shows

Both passes look like a rail ticket in Apple Wallet: a white train boarding pass with black type. The header shows the planned departure of the leg the passenger boards next (`Departure:` `dd.MM.yyyy HH:mm`, Europe/Zurich). The origin and destination of the trip sit on each side of the train glyph. Below them are the line of that leg, `Direct` or the number of changes, and the date, then `Next step` with the current instruction. A large QR code opens the Trip Watch page. The Google Generic object shows the same face: `Departure …` as subheader, `Origin → Destination` as header, the QR code, and text modules for the next step, the line and changes, and the trip update. The watch page renders an HTML replica of the Apple pass above its controls. The three read one shared face. [Pass face](../src/main/kotlin/ch/lauzhack/twin/wallet/TripWatchPass.kt) · [Page preview](../src/main/resources/static/trip-watch.js)

The pass copies the ticket layout but carries no railway branding and no ticket data. Its logo is a plain red bar next to the name "Train twin". The QR code encodes only the watch page URL, captioned "Travel information · not a ticket", and a back field repeats that the pass is not valid for travel. Google crops logos into a circle, so Ktor serves a white square with the bar inside the circle at `/wallet/logo.png`. Lock-screen alerts still come from the back `alert` field. It changes only for actionable advice, so the passenger's own boarding and alighting confirmations update the pass silently.

## How trip data becomes advice

* The passenger connection screen supplies a run ID and its two selected stop IDs. Ktor checks the ordered calls. The assistant supplies a journey ID drawn from a server-cached planner result. Ktor saves its underlying run IDs, board and alight stops, and times so the watch survives planner cache expiry.
* Ktor rechecks the remaining legs using live stop calls. Cancellations, skipped calls and a transfer that has become impossible can change the advice. When a selected connection can no longer work and the passenger is still at the boarding stage, the service looks for a usable trip in the twin planner. It shows a check-your-connection instruction when it has insufficient information for boarding. A missing live run can also mean its departure is still in the future.
* The passenger can confirm boarding and alighting to move to the next leg. Those confirmations and forecast-only detail changes sync silently. A verified change to what the passenger should board or where they should get off is eligible for a notification. The pass uses station coordinates from that leg for nearby relevance.
* Incident changes wake the watch worker promptly and periodic checks also pick up stop-call updates and expire watches. The watch database stores revision and delivery progress through restarts. A trip ends when the passenger stops watching or the watch expires. On expiry the pass clears nearby relevance.

Station and run IDs, not a route name or an area around one station, determine which trip is affected. Direct stop calls are limited to trains or supported replacement services available to the current twin. A separate verified multimodal planner would be needed for alternatives outside its coverage. [Journey queries](../src/main/kotlin/ch/lauzhack/twin/api/v1/Journey.kt) · [Twin planner](https://github.com/lauzhack2026/sbb-twin/blob/main/src/planner.ts) · [Watch service](../src/main/kotlin/ch/lauzhack/twin/wallet/TripWatchService.kt)

## Nearby reminders and update alerts

| | Nearby reminder | Change alert |
| --- | --- | --- |
| Apple Wallet | The signed pass contains the next boarding location and a relevant departure date. Wallet decides lock-screen presentation. | An empty APNs update wakes Wallet to fetch the latest signed revision. A change to the pass's alert field can display a notification. |
| Google Wallet | The Generic object includes its boarding location. Google sets proximity, dwell and wording. The rider needs Wallet notifications and precise background location enabled for nearby reminders. | Ktor patches the Generic object and sends a `TEXT_AND_NOTIFY` message for an actionable revision. A stable message ID is checked before sending a retry. Google limits notification-triggering messages. |

Wallet proximity and issuer alerts are independent. The server stores the selected station coordinates. A product rule that sends custom copy only after crossing an exact geofence requires location authorization and monitoring in a native app. [Apple lock-screen relevance](https://developer.apple.com/documentation/walletpasses/showing-a-pass-on-the-lock-screen) · [Apple pass update protocol](https://developer.apple.com/documentation/walletpasses/adding-a-web-service-to-update-passes) · [Google nearby and update behavior](https://developers.google.com/wallet/generic/use-cases/trigger-push-notifications)

## Verify it

Run the local provider and itinerary tests with JDK 21. The Apple test validates the ZIP hashes and detached signature with a test key. The Google test verifies the save JWT and the retry contract against a local HTTP fixture.

~~~sh
./gradlew test --tests 'ch.lauzhack.twin.wallet.*' --no-daemon --max-workers=2
~~~

For issuer acceptance, use one physical iPhone and one Android phone with test accounts. Choose a direct and a connecting trip. Add the pass, verify the current information on the phone, then change the confirmed boarding advice for one selected trip. A rider whose destination is still served should keep their instructions. Verify boarding and alighting move nearby relevance to the correct interchange. Test nearby reminders with a current trip and an actual approach to the relevant station. Finish by stopping the watch and checking the pass expiry. [Apple testing guidance](https://developer.apple.com/documentation/walletpasses/showing-a-pass-on-the-lock-screen) · [Google demo mode](https://developers.google.com/wallet/generic/getting-started/issuer-onboarding)
