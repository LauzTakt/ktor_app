# Mobile app (Capacitor)

Native Android and iOS shell around the web UI: a WebView that loads the deployed server
(`server.url` in `capacitor.config.json`). There is no web code here, so any change in
`src/main/resources/static/` reaches the app on its next launch without a new APK/IPA.
`www/index.html` is only the offline/error page.

## Release

Push a tag `vX.Y.Z` on a commit on `main`:

```sh
git tag v1.0.0 && git push origin v1.0.0
```

`.github/workflows/android-release.yml` builds `train-twin-X.Y.Z.apk` and `.github/workflows/ios-release.yml`
builds `train-twin-X.Y.Z[-unsigned].ipa`; both are attached to the same GitHub Release. The version name comes from
the tag and the build number from the run number. Both can also be run by hand (Actions -> Run workflow, artifact
only); the iOS one also builds on every pull request that touches `mobile/`.

Repo settings (optional):

- Variable `MOBILE_SERVER_URL`: overrides the URL the app loads.
- Secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`:
  a stable signing key. Without them every build is signed with a fresh debug key, so installing a new
  version requires uninstalling the old one first.

Create a key once (keep it outside the repo):

```sh
keytool -genkeypair -v -keystore release.jks -alias twin -keyalg RSA -keysize 2048 -validity 10000
base64 -i release.jks | pbcopy   # paste as ANDROID_KEYSTORE_BASE64
```

## iOS signing

Without secrets the iOS build is an **unsigned** `-unsigned.ipa`. iPhones only install signed apps, so install it
with a sideloading tool that re-signs it with your (free) Apple ID: [Sideloadly](https://sideloadly.io) or
AltStore. With a free Apple ID the app expires after 7 days and must be re-sideloaded.

With a paid Apple Developer account, add these secrets and CI produces a signed `.ipa`:

- `IOS_CERTIFICATE_P12_BASE64`, `IOS_CERTIFICATE_PASSWORD`: distribution (or development) certificate exported
  from Keychain as .p12 (`base64 -i cert.p12 | pbcopy`).
- `IOS_PROVISIONING_PROFILE_BASE64`: the matching `.mobileprovision` (`base64 -i profile.mobileprovision | pbcopy`).
  Team, bundle id and export method (ad hoc / development / App Store / enterprise) are read from it; override the
  method with the variable `IOS_EXPORT_METHOD`. An ad hoc profile installs on the devices registered in it.

## Deep links (QR codes and links open the app)

- **Android App Links**: links and QR codes to `https://<server host>/` (`/`, `/index.html`, `/trip-watch.html`, with
  any query such as `?stop=` / `?train=`) open the app directly once Android has verified the domain. The server
  publishes `/.well-known/assetlinks.json` from the env var `ANDROID_CERT_SHA256` (comma-separated SHA-256
  fingerprints of the signing certificate). This needs the **stable release key** (secrets above): with the
  throwaway debug key every build has a new fingerprint, verification fails and Android opens the browser
  (the user can still enable it in Settings -> Apps -> Train Twin -> Open by default). Fingerprint:

  ```sh
  keytool -list -v -keystore release.jks -alias twin | grep SHA256
  ```

  Set it in Coolify (`ANDROID_CERT_SHA256=AA:BB:…`), reinstall the app, then check with
  `adb shell pm get-app-links ch.lauzhack.twin` (`verified`). The host comes from `server.url` (CI passes
  `-PdeepLinkHost`).
- **Custom scheme** `traintwin://open/?stop=…` (Android and iOS, no verification): used by the "Open in the app"
  bar that the website shows on phone browsers (`static/app-links.js`). A web page cannot open an app without a tap.
- **iOS Universal Links** need the Associated Domains entitlement (paid Apple Developer account): then set
  `IOS_APP_ID=<TEAMID>.ch.lauzhack.twin` on the server (`/.well-known/apple-app-site-association`) and add
  `applinks:<host>` to the app's entitlements. Until then iPhones open QR codes in Safari, and the bar there
  offers the app.
- In the app, `@capacitor/app` (`appUrlOpen`, `getLaunchUrl`) loads the linked page in the WebView.

## Push notifications (Android)

The app uses `@capacitor/push-notifications` (FCM); the web UI asks for the token when the user taps "Notify me"
(`src/main/resources/static/push.js`). One-time setup, in a free [Firebase](https://console.firebase.google.com) project:

1. Add an **Android app** with package `ch.lauzhack.twin`, download `google-services.json` and either commit it as
   `mobile/android/app/google-services.json` or store it as the secret `GOOGLE_SERVICES_JSON_BASE64`
   (`base64 -i google-services.json | pbcopy`). Without it the APK builds but cannot receive push.
2. Project settings -> Service accounts -> **Generate new private key**: put that JSON (raw or base64) in the server's
   `FCM_SERVICE_ACCOUNT_JSON` env var (Coolify). Without it the server refuses app tokens (`503 fcm_disabled`).

iPhone app: push needs APNs, i.e. a paid Apple Developer account (and Firebase's APNs key). Until then the app tells
iPhone users to add the site to the Home Screen, where Web Push works (iOS 16.4+).

## Local build

Needs Node, JDK 21 and the Android SDK (`ANDROID_HOME`):

```sh
cd mobile && npm ci && npx cap sync android
cd android && ./gradlew assembleDebug    # app/build/outputs/apk/debug/
```

Or `npx cap open android` to open it in Android Studio.

iOS needs macOS with Xcode:

```sh
cd mobile && npm ci && npx cap sync ios && npx cap open ios   # run from Xcode
```
