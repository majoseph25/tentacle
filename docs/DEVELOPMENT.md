# Development guide

Building, testing, debugging and releasing Tentacle. For how the code fits together, see
[ARCHITECTURE.md](ARCHITECTURE.md). For exact tool and library versions, see [DEPENDENCIES.md](DEPENDENCIES.md).

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Android Studio | Any recent version | Bundles the SDK manager. Its own JDK may be too new (see next row). |
| JDK | **17 or 21** | Set **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**. JDK 25 (bundled with recent Android Studio) is too new for Gradle 8.14. |
| Android SDK | Platform 36, Build-Tools 35.0.0 | Installed automatically on first build if missing. |
| Gradle | 8.14.5 (via the wrapper) | Don't install Gradle separately. The wrapper downloads and verifies it. |

## Building

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # unsigned, R8-shrunk: app/build/outputs/apk/release/
./gradlew bundleRelease        # App Bundle for Google Play: app/build/outputs/bundle/release/
```

On Windows use `gradlew.bat`. From Android Studio: **File → Open** the project folder, let it sync, then
**Run**.

**Release builds:**
- R8 code shrinking and obfuscation plus resource shrinking (`isMinifyEnabled`, `isShrinkResources`),
- `isDebuggable = false`,
- `proguard-rules.pro` strips `Log.d`/`Log.v` and forces `Log.isLoggable` to false as a second safeguard,
- diagnostic logging is also gated on `BuildConfig.DEBUG`.

## Checks

Run all of these before every change is merged:

```bash
./gradlew testDebugUnitTest testReleaseUnitTest   # unit tests (debug and release variants)
./gradlew lintDebug lintRelease                   # Android lint; any error fails the build
```

- **Unit tests** live in `app/src/test`. They're plain JVM tests (no device needed) and cover:
  - `SecurityChecksTest`: ID validation (traversal), LAN detection, the default scheme, stream URLs never
    carrying credentials, and the token only going to the signed-in server,
  - `PagingTest`: page size limits and overflow,
  - `ui/UiLogicTest`: artwork decode bounds, sign-in preflight and the HTTP warning, time formatting.
- **Lint** is configured with `abortOnError = true` and `checkReleaseBuilds = true`. A few rules are
  suppressed where the finding is intended, each with an explanation next to it:
  - `ExportedService` and `ExportedContentProvider`: Android Auto requires these components, and both
    check callers in code,
  - `MissingIntentFilterForMediaSearch`: see [ARCHITECTURE.md → Known limits](ARCHITECTURE.md#known-limits),
  - `InsecureBaseConfiguration`: cleartext is allowed for LAN servers, with a warning in the UI.

### Continuous integration (GitHub Actions)

`.github/workflows/android.yml` runs on every push to `main` and every pull request:
- **Checks:** Gradle wrapper validation, the unit tests (debug and release), lint (debug and release) and
  a release build.
- **Reports:** saved as an artifact of the run.
- **Permissions:** read-only (`contents: read`), and no secrets.

Dependabot (`.github/dependabot.yml`) opens weekly update pull requests for Gradle and GitHub Actions
dependencies. It skips Compose BOMs that need compileSdk 37, and AGP major versions.

## Running on a phone

1. Enable **Developer options** (tap **Build number** 7 times in About phone) and **USB debugging**.
2. Plug in the phone, accept the prompt, then:

```bash
./gradlew installDebug
```

The debug build shows "· debug" next to the version in Settings.

## Testing Android Auto

**Real car:**
1. Install the app.
2. In Android Auto settings, tap **Version** about 10 times, then turn on ⋮ → **Developer settings** →
   **Unknown sources**.
3. Connect to the car.

**Desktop Head Unit (DHU),** which shows the car screen in a window on your computer:
1. Install **Android Auto Desktop Head Unit Emulator** from Android Studio's SDK Manager (SDK Tools tab).
2. On the phone: Android Auto → ⋮ → **Start head unit server**.
3. On the computer:

```bash
adb forward tcp:5277 tcp:5277
```

4. Run `desktop-head-unit` from `<sdk>/extras/google/auto/`.

The Android Auto quality checklist that Play review applies is at
<https://developer.android.com/docs/quality-guidelines/car-app-quality>. Media apps are held to these rules:
- **MC-1:** artwork for every item,
- **MA-1:** no autoplay,
- **DR-3:** content loads within 10 s,
- **VC-1:** voice commands work.

## Debugging

- **Crashes:**

```bash
adb logcat -b crash
```

- **App log:** the tag is `Tentacle`. Release builds log only error types; debug builds include details.
  To follow it:

```bash
adb logcat -s Tentacle
```

- **Diagnostic logging** (debug builds only) is off by default. To enable it:

```bash
adb shell setprop log.tag.Tentacle DEBUG
```

- **Which apps connected to the player,** and whether they were refused: on the phone, open
  **Settings → Android Auto connections**. Nothing else is needed.
- **Media session state:**

```bash
adb shell dumpsys media_session
```

## Project conventions

- **Licence headers.** Every source file starts with:

```kotlin
// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0
```

  New files need the same header. Apache-2.0 requires forks to keep these notices and the `NOTICE` file.

- **Security rules have tests.** Anything that validates IDs, builds URLs or decides where the token goes
  needs a unit test in `SecurityChecksTest`.
- **IDs:** every ID that goes into a URL path goes through `requireSafeId`.
- **Stream URLs:** no credentials in URLs. Auth goes in headers, added by `JellyfinApi.AuthInterceptor`
  only for the signed-in server.
- **Media3 paging:** never return more items than the requested `pageSize` from a library callback. Media3
  treats it as fatal. Use `pageOf`/`pageStart` from `Paging.kt`.
- **Logging:** no personal data in logs. Use `JellyfinApi.warn` for errors (type only in release) and
  `JellyfinApi.debugLogging()` to gate diagnostics.
- **Test data:** no real server addresses, usernames, device names or IDs in tests, docs or comments.
- **Upgrading libraries:** check the library's `aar-metadata.properties` for `minCompileSdk` and
  `minAndroidGradlePluginVersion` first. That's why Compose is held at BOM 2026.06.01. After upgrading,
  update [DEPENDENCIES.md](DEPENDENCIES.md) and re-run the vulnerability check.

## Security checks before a release

1. Run the full checks above (tests and lint, both variants).
2. Check dependencies against [OSV](https://osv.dev) (see [DEPENDENCIES.md](DEPENDENCIES.md#regenerating-this-page)).
3. Inspect the release APK's merged manifest, and confirm it's not debuggable and only the expected
   components are exported:

```bash
aapt2 dump xmltree --file AndroidManifest.xml app-release.apk
```

4. Scan the repository for secrets and personal data.
5. Update [SECURITY_ASSESSMENT.md](../SECURITY_ASSESSMENT.md).

## Releasing to Google Play

1. **Bump the version:** `versionCode` (must increase) and `versionName` in `app/build.gradle.kts`, and
   add an entry to [CHANGELOG.md](../CHANGELOG.md).
2. **Signing:**
   - Create an upload key once, and keep it (and its passwords) outside the repository. `.gitignore`
     already excludes `*.jks`, `*.keystore` and `keystore.properties`.
   - Enrol in Play App Signing.
3. **Build:** `./gradlew bundleRelease`, then sign the bundle with the upload key.
4. **Play Console:**
   - Host [PRIVACY.md](../PRIVACY.md) at a public URL.
   - Complete the Data safety form: data goes only to the user's own server, with no collection by the
     developer.
   - Opt in to Android Auto if you want it listed there. That adds the car-app-quality review.
5. **Listing:** state that Tentacle is unofficial and not affiliated with the Jellyfin project, and don't
   use Jellyfin's logo (Jellyfin's [branding guidelines](https://jellyfin.org/docs/general/contributing/branding/)).
6. **Target API:** Play requires `targetSdk` 36 for new apps and updates. Check each year's new deadline.

## Open items before the first public release

- **Licence notices:** add an in-app "Open-source licences" screen or a `THIRD_PARTY_LICENSES` file.
  [DEPENDENCIES.md](DEPENDENCIES.md) lists every library and licence.
- **Translations:** move UI strings into `strings.xml` so the app can be translated (lint's `SetTextI18n`
  notes).
- **On-device checks:** complete the list in [SECURITY_ASSESSMENT.md](../SECURITY_ASSESSMENT.md)
  (Android Auto, Bluetooth, lock screen).
