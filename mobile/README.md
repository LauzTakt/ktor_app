# Mobile app (Capacitor)

Native Android shell around the web UI: a WebView that loads the deployed server
(`server.url` in `capacitor.config.json`). There is no web code here, so any change in
`src/main/resources/static/` reaches the app on its next launch without a new APK.
`www/index.html` is only the offline/error page.

## Release

Push a tag `vX.Y.Z` on a commit on `main`:

```sh
git tag v1.0.0 && git push origin v1.0.0
```

`.github/workflows/android-release.yml` builds `train-twin-X.Y.Z.apk` and attaches it to a GitHub Release.
`versionName` comes from the tag and `versionCode` from the run number.

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

## Local build

Needs Node, JDK 21 and the Android SDK (`ANDROID_HOME`):

```sh
cd mobile && npm ci && npx cap sync android
cd android && ./gradlew assembleDebug    # app/build/outputs/apk/debug/
```

Or `npx cap open android` to open it in Android Studio.
