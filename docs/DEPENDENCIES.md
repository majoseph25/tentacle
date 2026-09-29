# Dependencies

Everything Tentacle is built with and everything that ships inside it, with exact versions and licences.

- **Generated:** 2026-09-28, for **Tentacle 0.4.0 (build 4)**. The dependencies are unchanged in
  0.5.0 (branding only).
- **Source of every version:** the resolved Gradle dependency graph, not the requested versions.
- **Source of every licence:** the artifact's published POM.
- **Regenerating:** see [the end of this page](#regenerating-this-page).

**At a glance**

| | Count | Licences |
|---|---|---|
| Libraries in the release APK | **130** artifacts (12 declared directly, the rest transitive) | 129 Apache-2.0, 1 MIT |
| Test-only libraries | 2 | EPL-1.0, BSD-3-Clause |
| Gradle plugins | 3 (+ their build-time dependencies) | Apache-2.0 |
| Known vulnerabilities | **0** ([OSV](https://osv.dev), checked 2026-09-28) | |

All licences are permissive and compatible with any licence you choose for Tentacle itself. Apache-2.0
asks you to keep the notices; see [Licence obligations](#licence-obligations).

---

## 1. Toolchain

| Component | Version | Where it's set | Notes |
|---|---|---|---|
| Gradle | **8.14.5** | `gradle/wrapper/gradle-wrapper.properties` | The wrapper verifies the distribution's SHA-256 (`distributionSha256Sum`) before running it. |
| Android Gradle Plugin (AGP) | **8.13.2** | `build.gradle.kts` | Last 8.x release. AGP 9 changes the build DSL and needs its own migration. |
| Kotlin (compiler and Gradle plugin) | **2.2.21** | `build.gradle.kts` | Kotlin Android plugin `org.jetbrains.kotlin.android`. |
| Compose compiler | **2.2.21** | `build.gradle.kts` | Plugin `org.jetbrains.kotlin.plugin.compose`, versioned with Kotlin. |
| JDK used to build | **JetBrains Runtime 21.0.11** | Android Studio → Gradle JDK, or `JAVA_HOME` | Any JDK 17 or 21 works. Android Studio's bundled JDK 25 is too new for Gradle 8.14. |
| Java/Kotlin bytecode target | **17** | `app/build.gradle.kts` (`compileOptions`, `jvmTarget`) | |
| Gradle's embedded Kotlin (build scripts only) | 2.0.21 | Bundled with Gradle | Not used for app code. |

## 2. Android SDK

| Component | Version | Notes |
|---|---|---|
| `compileSdk` | **36** (Android 16) | Media3 1.10+ requires at least 36. |
| `targetSdk` | **36** (Android 16) | Google Play has required 36 for new apps and updates since 2026-08-31. |
| `minSdk` | **26** (Android 8.0) | |
| SDK Build-Tools | **35.0.0** | AGP 8.13.2's default. |
| SDK Platform | android-36 | android-34 and android-37 are also installed but unused. |
| Platform-Tools (adb) | 37.0.1 | For installing and debugging only. |
| Android Auto Desktop Head Unit | 2.0 | For testing Android Auto without a car. Optional. |

## 3. Gradle plugins

| Plugin ID | Version | Purpose |
|---|---|---|
| `com.android.application` | 8.13.2 | Builds the Android app (manifest merging, resources, R8, signing, lint). |
| `org.jetbrains.kotlin.android` | 2.2.21 | Compiles Kotlin for Android. |
| `org.jetbrains.kotlin.plugin.compose` | 2.2.21 | The Compose compiler (turns `@Composable` functions into UI code). |

These run only at build time. Their full dependency tree (about 430 lines, including AGP's internals such
as R8, lint, bundletool and protobuf) is in
[`dependency-tree/build-plugins.txt`](dependency-tree/build-plugins.txt). Nothing from it is packaged into
the app.

## 4. Declared dependencies (`app/build.gradle.kts`)

These are the 12 libraries Tentacle asks for directly. Everything else in section 5 is pulled in by them.

| Library | Version | Purpose in Tentacle |
|---|---|---|
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.2.21 | Kotlin standard library (added by the Kotlin plugin). |
| `androidx.core:core-ktx` | 1.17.0 | Android compatibility helpers: `SharedPreferences.edit {}`, `NotificationManagerCompat`, `ContextCompat`, window insets. |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | HTTP client for all server calls and audio streaming. Redirects are disabled; an interceptor adds the token only for the signed-in server. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.10.2 | Background work (network, disk) off the main thread. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-guava` | 1.10.2 | Bridges coroutines and Guava `ListenableFuture`, which Media3's session API uses. |
| `androidx.media3:media3-exoplayer` | 1.11.1 | The audio player (ExoPlayer): streaming, buffering, gapless playback, audio focus. |
| `androidx.media3:media3-datasource-okhttp` | 1.11.1 | Lets ExoPlayer stream through Tentacle's OkHttp client (and its auth rules). |
| `androidx.media3:media3-session` | 1.11.1 | `MediaLibraryService`/`MediaSession`: Android Auto, lock screen, notification, Bluetooth, the phone app's controller. |
| `androidx.compose:compose-bom` | 2026.06.01 | Bill of materials that pins all Compose library versions. Held at 2026.06.01 because later BOMs need compileSdk 37 and AGP 9.1. |
| `androidx.compose.ui:ui` | 1.11.4 (from the BOM) | Compose UI toolkit. |
| `androidx.compose.material3:material3` | 1.4.0 (from the BOM) | Material 3 components and theming (the green light/dark theme). |
| `androidx.activity:activity-compose` | 1.12.4 | Hosts Compose in the activity; edge-to-edge and back handling. |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.10.0 | Lifecycle-aware state collection in Compose (`collectAsStateWithLifecycle`). |

Test-only (not in the app):

| Library | Version | Licence | Purpose |
|---|---|---|---|
| `junit:junit` | 4.13.2 | Eclipse Public License 1.0 | Unit test framework. |
| `org.hamcrest:hamcrest-core` | 1.3 | New BSD License | Matchers used by JUnit. |

## 5. Everything in the release APK (resolved)

All **130** artifacts on the release runtime classpath, at the version Gradle actually resolved. When two
libraries ask for different versions, the higher one wins. The full tree, showing who requires what, is in
[`dependency-tree/release-runtime.txt`](dependency-tree/release-runtime.txt).

Some artifacts are split per platform, for example `annotation` and `annotation-jvm`, or `ui` and
`ui-android`. Both halves are listed. `listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` is an
intentionally empty placeholder that stops a duplicate of a class Guava already provides.

### 5.1 Media3 (playback and media session) — 9 artifacts

| Group | Artifact | Version | Licence |
|---|---|---|---|
| `androidx.media3` | `media3-common` | 1.11.1 | Apache-2.0 |
| `androidx.media3` | `media3-container` | 1.11.1 | Apache-2.0 |
| `androidx.media3` | `media3-database` | 1.11.1 | Apache-2.0 |
| `androidx.media3` | `media3-datasource` | 1.11.1 | Apache-2.0 |
| `androidx.media3` | `media3-datasource-okhttp` | 1.11.1 | Apache-2.0 |
| `androidx.media3` | `media3-decoder` | 1.11.1 | Apache-2.0 |
| `androidx.media3` | `media3-exoplayer` | 1.11.1 | Apache-2.0 |
| `androidx.media3` | `media3-extractor` | 1.11.1 | Apache-2.0 |
| `androidx.media3` | `media3-session` | 1.11.1 | Apache-2.0 |

### 5.2 Jetpack Compose (phone UI) — 33 artifacts

| Group | Artifact | Version | Licence |
|---|---|---|---|
| `androidx.compose` | `compose-bom` | 2026.06.01 | Apache-2.0 |
| `androidx.compose.animation` | `animation` | 1.11.4 | Apache-2.0 |
| `androidx.compose.animation` | `animation-android` | 1.11.4 | Apache-2.0 |
| `androidx.compose.animation` | `animation-core` | 1.11.4 | Apache-2.0 |
| `androidx.compose.animation` | `animation-core-android` | 1.11.4 | Apache-2.0 |
| `androidx.compose.foundation` | `foundation` | 1.11.4 | Apache-2.0 |
| `androidx.compose.foundation` | `foundation-android` | 1.11.4 | Apache-2.0 |
| `androidx.compose.foundation` | `foundation-layout` | 1.11.4 | Apache-2.0 |
| `androidx.compose.foundation` | `foundation-layout-android` | 1.11.4 | Apache-2.0 |
| `androidx.compose.material` | `material-ripple` | 1.11.4 | Apache-2.0 |
| `androidx.compose.material` | `material-ripple-android` | 1.11.4 | Apache-2.0 |
| `androidx.compose.material3` | `material3` | 1.4.0 | Apache-2.0 |
| `androidx.compose.material3` | `material3-android` | 1.4.0 | Apache-2.0 |
| `androidx.compose.runtime` | `runtime` | 1.11.4 | Apache-2.0 |
| `androidx.compose.runtime` | `runtime-android` | 1.11.4 | Apache-2.0 |
| `androidx.compose.runtime` | `runtime-annotation` | 1.11.4 | Apache-2.0 |
| `androidx.compose.runtime` | `runtime-annotation-android` | 1.11.4 | Apache-2.0 |
| `androidx.compose.runtime` | `runtime-retain` | 1.11.4 | Apache-2.0 |
| `androidx.compose.runtime` | `runtime-retain-android` | 1.11.4 | Apache-2.0 |
| `androidx.compose.runtime` | `runtime-saveable` | 1.11.4 | Apache-2.0 |
| `androidx.compose.runtime` | `runtime-saveable-android` | 1.11.4 | Apache-2.0 |
| `androidx.compose.ui` | `ui` | 1.11.4 | Apache-2.0 |
| `androidx.compose.ui` | `ui-android` | 1.11.4 | Apache-2.0 |
| `androidx.compose.ui` | `ui-geometry` | 1.11.4 | Apache-2.0 |
| `androidx.compose.ui` | `ui-geometry-android` | 1.11.4 | Apache-2.0 |
| `androidx.compose.ui` | `ui-graphics` | 1.11.4 | Apache-2.0 |
| `androidx.compose.ui` | `ui-graphics-android` | 1.11.4 | Apache-2.0 |
| `androidx.compose.ui` | `ui-text` | 1.11.4 | Apache-2.0 |
| `androidx.compose.ui` | `ui-text-android` | 1.11.4 | Apache-2.0 |
| `androidx.compose.ui` | `ui-unit` | 1.11.4 | Apache-2.0 |
| `androidx.compose.ui` | `ui-unit-android` | 1.11.4 | Apache-2.0 |
| `androidx.compose.ui` | `ui-util` | 1.11.4 | Apache-2.0 |
| `androidx.compose.ui` | `ui-util-android` | 1.11.4 | Apache-2.0 |

### 5.3 Other AndroidX — 64 artifacts

| Group | Artifact | Version | Licence |
|---|---|---|---|
| `androidx.activity` | `activity` | 1.12.4 | Apache-2.0 |
| `androidx.activity` | `activity-compose` | 1.12.4 | Apache-2.0 |
| `androidx.activity` | `activity-ktx` | 1.12.4 | Apache-2.0 |
| `androidx.annotation` | `annotation` | 1.9.1 | Apache-2.0 |
| `androidx.annotation` | `annotation-experimental` | 1.4.1 | Apache-2.0 |
| `androidx.annotation` | `annotation-jvm` | 1.9.1 | Apache-2.0 |
| `androidx.arch.core` | `core-common` | 2.2.0 | Apache-2.0 |
| `androidx.arch.core` | `core-runtime` | 2.2.0 | Apache-2.0 |
| `androidx.autofill` | `autofill` | 1.0.0 | Apache-2.0 |
| `androidx.collection` | `collection` | 1.5.0 | Apache-2.0 |
| `androidx.collection` | `collection-jvm` | 1.5.0 | Apache-2.0 |
| `androidx.collection` | `collection-ktx` | 1.5.0 | Apache-2.0 |
| `androidx.concurrent` | `concurrent-futures` | 1.2.0 | Apache-2.0 |
| `androidx.core` | `core` | 1.17.0 | Apache-2.0 |
| `androidx.core` | `core-ktx` | 1.17.0 | Apache-2.0 |
| `androidx.core` | `core-viewtree` | 1.0.0 | Apache-2.0 |
| `androidx.customview` | `customview-poolingcontainer` | 1.0.0 | Apache-2.0 |
| `androidx.documentfile` | `documentfile` | 1.0.0 | Apache-2.0 |
| `androidx.dynamicanimation` | `dynamicanimation` | 1.0.0 | Apache-2.0 |
| `androidx.emoji2` | `emoji2` | 1.4.0 | Apache-2.0 |
| `androidx.exifinterface` | `exifinterface` | 1.3.6 | Apache-2.0 |
| `androidx.graphics` | `graphics-path` | 1.0.1 | Apache-2.0 |
| `androidx.interpolator` | `interpolator` | 1.0.0 | Apache-2.0 |
| `androidx.legacy` | `legacy-support-core-utils` | 1.0.0 | Apache-2.0 |
| `androidx.lifecycle` | `lifecycle-common` | 2.10.0 | Apache-2.0 |
| `androidx.lifecycle` | `lifecycle-common-java8` | 2.10.0 | Apache-2.0 |
| `androidx.lifecycle` | `lifecycle-common-jvm` | 2.10.0 | Apache-2.0 |
| `androidx.lifecycle` | `lifecycle-livedata` | 2.10.0 | Apache-2.0 |
| `androidx.lifecycle` | `lifecycle-livedata-core` | 2.10.0 | Apache-2.0 |
| `androidx.lifecycle` | `lifecycle-livedata-core-ktx` | 2.10.0 | Apache-2.0 |
| `androidx.lifecycle` | `lifecycle-process` | 2.10.0 | Apache-2.0 |
| `androidx.lifecycle` | `lifecycle-runtime` | 2.10.0 | Apache-2.0 |
| `androidx.lifecycle` | `lifecycle-runtime-android` | 2.10.0 | Apache-2.0 |
| `androidx.lifecycle` | `lifecycle-runtime-compose` | 2.10.0 | Apache-2.0 |
| `androidx.lifecycle` | `lifecycle-runtime-compose-android` | 2.10.0 | Apache-2.0 |
| `androidx.lifecycle` | `lifecycle-runtime-ktx` | 2.10.0 | Apache-2.0 |
| `androidx.lifecycle` | `lifecycle-runtime-ktx-android` | 2.10.0 | Apache-2.0 |
| `androidx.lifecycle` | `lifecycle-service` | 2.10.0 | Apache-2.0 |
| `androidx.lifecycle` | `lifecycle-viewmodel` | 2.10.0 | Apache-2.0 |
| `androidx.lifecycle` | `lifecycle-viewmodel-android` | 2.10.0 | Apache-2.0 |
| `androidx.lifecycle` | `lifecycle-viewmodel-ktx` | 2.10.0 | Apache-2.0 |
| `androidx.lifecycle` | `lifecycle-viewmodel-savedstate` | 2.10.0 | Apache-2.0 |
| `androidx.lifecycle` | `lifecycle-viewmodel-savedstate-android` | 2.10.0 | Apache-2.0 |
| `androidx.loader` | `loader` | 1.0.0 | Apache-2.0 |
| `androidx.localbroadcastmanager` | `localbroadcastmanager` | 1.0.0 | Apache-2.0 |
| `androidx.media` | `media` | 1.7.0 | Apache-2.0 |
| `androidx.navigationevent` | `navigationevent` | 1.0.2 | Apache-2.0 |
| `androidx.navigationevent` | `navigationevent-android` | 1.0.2 | Apache-2.0 |
| `androidx.navigationevent` | `navigationevent-compose` | 1.0.2 | Apache-2.0 |
| `androidx.navigationevent` | `navigationevent-compose-android` | 1.0.2 | Apache-2.0 |
| `androidx.print` | `print` | 1.0.0 | Apache-2.0 |
| `androidx.profileinstaller` | `profileinstaller` | 1.4.0 | Apache-2.0 |
| `androidx.savedstate` | `savedstate` | 1.4.0 | Apache-2.0 |
| `androidx.savedstate` | `savedstate-android` | 1.4.0 | Apache-2.0 |
| `androidx.savedstate` | `savedstate-compose` | 1.4.0 | Apache-2.0 |
| `androidx.savedstate` | `savedstate-compose-android` | 1.4.0 | Apache-2.0 |
| `androidx.savedstate` | `savedstate-ktx` | 1.4.0 | Apache-2.0 |
| `androidx.startup` | `startup-runtime` | 1.1.1 | Apache-2.0 |
| `androidx.tracing` | `tracing` | 1.2.0 | Apache-2.0 |
| `androidx.transition` | `transition` | 1.6.0 | Apache-2.0 |
| `androidx.versionedparcelable` | `versionedparcelable` | 1.1.1 | Apache-2.0 |
| `androidx.window` | `window` | 1.5.0 | Apache-2.0 |
| `androidx.window` | `window-core` | 1.5.0 | Apache-2.0 |
| `androidx.window` | `window-core-android` | 1.5.0 | Apache-2.0 |

### 5.4 Kotlin and JetBrains — 13 artifacts

| Group | Artifact | Version | Licence |
|---|---|---|---|
| `org.jetbrains` | `annotations` | 23.0.0 | Apache-2.0 |
| `org.jetbrains.kotlin` | `kotlin-stdlib` | 2.2.21 | Apache-2.0 |
| `org.jetbrains.kotlin` | `kotlin-stdlib-common` | 2.2.21 | Apache-2.0 |
| `org.jetbrains.kotlin` | `kotlin-stdlib-jdk7` | 1.9.10 | Apache-2.0 |
| `org.jetbrains.kotlin` | `kotlin-stdlib-jdk8` | 1.9.10 | Apache-2.0 |
| `org.jetbrains.kotlinx` | `kotlinx-coroutines-android` | 1.10.2 | Apache-2.0 |
| `org.jetbrains.kotlinx` | `kotlinx-coroutines-bom` | 1.10.2 | Apache-2.0 |
| `org.jetbrains.kotlinx` | `kotlinx-coroutines-core` | 1.10.2 | Apache-2.0 |
| `org.jetbrains.kotlinx` | `kotlinx-coroutines-core-jvm` | 1.10.2 | Apache-2.0 |
| `org.jetbrains.kotlinx` | `kotlinx-coroutines-guava` | 1.10.2 | Apache-2.0 |
| `org.jetbrains.kotlinx` | `kotlinx-serialization-bom` | 1.7.3 | Apache-2.0 |
| `org.jetbrains.kotlinx` | `kotlinx-serialization-core` | 1.7.3 | Apache-2.0 |
| `org.jetbrains.kotlinx` | `kotlinx-serialization-core-jvm` | 1.7.3 | Apache-2.0 |

### 5.5 Square (HTTP) — 3 artifacts

| Group | Artifact | Version | Licence |
|---|---|---|---|
| `com.squareup.okhttp3` | `okhttp` | 4.12.0 | Apache-2.0 |
| `com.squareup.okio` | `okio` | 3.6.0 | Apache-2.0 |
| `com.squareup.okio` | `okio-jvm` | 3.6.0 | Apache-2.0 |

### 5.6 Google (Guava and annotations) — 6 artifacts

| Group | Artifact | Version | Licence |
|---|---|---|---|
| `com.google.code.findbugs` | `jsr305` | 3.0.2 | Apache-2.0 |
| `com.google.errorprone` | `error_prone_annotations` | 2.28.0 | Apache-2.0 |
| `com.google.guava` | `failureaccess` | 1.0.2 | Apache-2.0 |
| `com.google.guava` | `guava` | 33.3.1-android | Apache-2.0 |
| `com.google.guava` | `listenablefuture` | 9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0 |
| `com.google.j2objc` | `j2objc-annotations` | 3.0.0 | Apache-2.0 |

### 5.7 Other — 2 artifacts

| Group | Artifact | Version | Licence |
|---|---|---|---|
| `org.checkerframework` | `checker-qual` | 3.43.0 | MIT |
| `org.jspecify` | `jspecify` | 1.0.0 | Apache-2.0 |

## 6. Components the libraries add to the app manifest

Libraries can add their own components to the merged manifest. These end up in the release APK:

| Component | Added by | Exported | Protection |
|---|---|---|---|
| `androidx.media3.session.BluetoothValidationActivity` | media3-session | yes | Requires `android.permission.BLUETOOTH_PRIVILEGED`, so only the system Bluetooth stack can start it. |
| `androidx.startup.InitializationProvider` | androidx.startup | no | Runs library initializers at app start (emoji, lifecycle, profile installer). |
| `androidx.profileinstaller.ProfileInstallReceiver` | androidx.profileinstaller | yes | Requires `android.permission.DUMP` (system and adb only). Installs baseline performance profiles. |
| Permission `ACCESS_NETWORK_STATE` | Media3 | | Lets the player react to connectivity changes. |
| Permission `app.tentacle.music.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | androidx.core | | Internal, signature-level. Keeps runtime-registered receivers private to the app. |

Tentacle's own components (the launcher activity, `PlaybackService`, `ArtworkProvider`) are described in
[ARCHITECTURE.md](ARCHITECTURE.md#components).

## 7. Other building blocks

- **Jellyfin server API.** Tentacle speaks the Jellyfin REST API directly (no Jellyfin SDK library). It
  was tested against Jellyfin Server 10.11.11. The endpoints used are listed in
  [ARCHITECTURE.md](ARCHITECTURE.md#jellyfin-api-endpoints).
- **Icons.**
  - The Tentacle logo was created for the project owner with ChatGPT. The source files are in
    [`branding/`](../branding/).
  - The launcher icons (adaptive, round and themed), the in-app logos and the 512 px Play Store icon are
    all generated from the logo.
  - All other icons are [Material Design icons](https://fonts.google.com/icons) (Apache-2.0), stored as
    vector drawables in `app/src/main/res/drawable/`.
  - The shuffle and repeat buttons in Android Auto and the notification use Media3's built-in icons.
- **No other services.** No analytics, crash reporting, ads, Firebase or Google Play Services libraries.

## Licence obligations

- **Apache-2.0 (129 artifacts).** Redistribution is allowed, including in closed or differently-licensed
  apps. Keep the licence and any NOTICE files, and state changes if you modify the library. For an Android
  app, the usual practice is an "Open-source licences" screen or a `NOTICE`/`THIRD_PARTY_LICENSES` file in
  the repository that lists these libraries. This page can serve as the list.
- **MIT (`org.checkerframework:checker-qual`).** Keep the copyright notice. It's an annotations-only
  artifact pulled in by Guava.
- **Test-only EPL-1.0 and BSD libraries.** Not distributed with the app, so there are no obligations for
  users.
- **Tentacle's own licence.** Apache-2.0 (`LICENSE`), with attribution in `NOTICE`. Every licence above
  is compatible with it.

## Regenerating this page

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
./gradlew :app:dependencies --configuration releaseUnitTestRuntimeClasspath
./gradlew buildEnvironment
./gradlew --version
```

Save the output into `docs/dependency-tree/`. Licences come from each artifact's POM
(`<licenses>`, following `<parent>` when a POM has none). Check for known vulnerabilities by sending the
resolved list to the OSV batch API (`https://api.osv.dev/v1/querybatch`, ecosystem `Maven`).

Before upgrading, check which compileSdk and AGP version a library needs:
- It's recorded in `META-INF/com/android/build/gradle/aar-metadata.properties` inside the AAR.
- This is why Compose is held at BOM 2026.06.01: later BOMs need compileSdk 37 and AGP 9.1.
