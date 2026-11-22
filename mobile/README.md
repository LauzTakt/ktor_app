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
