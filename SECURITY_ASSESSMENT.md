# Security and release-readiness assessment

**Project:** Tentacle **0.5.0** (formerly Jellyfin Auto Companion) · **Latest full review:** 2026-09-28 (fourth, of 0.4.0; later changes assessed in [the section after it](#changes-after-review-4-050-and-the-github-setup)) ·
**Earlier reviews:** 2026-09-27 (first) and 2026-09-28 (second and third)

## Summary

| | Review 1 | Review 2 | Review 3 | Review 4 (latest) |
|---|---|---|---|---|
| Version | 0.1.0 | 0.1.0 | 0.2.0 | **0.4.0 (Tentacle)** |
| Scope | Initial app | Car features, Play readiness | Compose phone app | **Standalone player rewrite (Media3), Songs, search; full re-review** |
| Security findings | 11 | 9 | 5 | 4 |
| Functional bugs | 2 | 9 (4 release blockers) | 3 | 3 (1 crash, found on device) |
| Open findings after fixes | 0 | 0 | 0 | 0 |
| Known vulnerabilities in shipped libraries | not checked | 0 of 33 | 0 of 112 | **0 of 130** (OSV) |
| Automated tests | 9 | 16 | 22 | **22**, all passing in debug and release |

**What "done" does and doesn't mean.** No review can prove software has no bugs or vulnerabilities, and
none of these claim that. Each review means every line was reviewed, every issue found was fixed, the fixes
are built, tested and checked in the shipped release APK, and every known gap is listed. The remaining gap is
on-device testing of the Android Auto side and a few phone features (see [Review 4](#review-4-2026-09-28-tentacle-040)).

## Review 4 (2026-09-28): Tentacle 0.4.0

**What changed since review 3.** The remote control of the official Jellyfin app was replaced by a
standalone player:
- **Playback:** Media3 `MediaLibraryService` with ExoPlayer, streaming from the server through OkHttp.
- **Server reporting:** playback reports sent to the server.
- **Resume:** the queue and position are saved so playback can resume.
- **Library:** a paged Songs tab and search (songs, artists, albums).
- **Settings:** a streaming quality setting.
- **Rename:** the app is now Tentacle, package `app.tentacle.music`.

All remote-control code was removed, so the review 2–3 findings about Jellyfin sessions (S-2, S-3, S3-2)
no longer apply.

**Scope.** Every file was re-read, with the focus on the new code:
- `PlaybackService`, `PlaybackReporter`, `MediaItems`, `Paging`,
- the streaming parts of `JellyfinApi`,
- the songs and search parts of `Library`,
- the new `PlayerConnection` and the Library, Now playing and Settings screens.

The threat model was updated for three new risks:
- **Arbitrary apps connecting to the player.** The service must be exported for Android Auto.
- **Streams as a token-exfiltration channel.** A stream request carries the login token, so it must never
  go anywhere but the signed-in server.
- **Listening activity as private data.** What you're playing and your queue shouldn't be readable by
  other apps.

### Findings

| # | Severity | Finding | Fix | Verified by |
|---|---|---|---|---|
| S4-1 | **Medium** | **Any installed app could see and control your music.** The playback service has to be exported for Android Auto. Media3 accepted a connection from any app, and untrusted apps got transport controls, so any app with no permission could read the current song and your whole queue, and pause or skip. (The old design refused unknown apps outright; the rewrite had loosened this.) | Untrusted apps are now refused at connection. The only exception is Android's combined legacy controller, whose callers can't be told apart: the Android 8 lock screen, Bluetooth and headset buttons. It gets transport controls only. Refusals are logged under Settings → Android Auto connections. | Code review. On-device check pending. |
| S4-2 | Low | **Player could read local files.** The player used `DefaultDataSource`, which also opens `file://`, `content://` and asset URIs. No caller can supply a URI (see below), but nothing stopped one either. | The player's only data source is the HTTP(S) streaming client. | Code review |
| S4-3 | Low | **Unbounded queue requests.** A trusted caller could send any number of IDs in one request, each triggering server lookups. | Capped at 500 items per request. Lookups are batched 100 IDs per call. | Code review |
| S4-4 | Low | **Reports after sign-out.** The reporter kept sending progress/stopped reports after sign-out, which failed with 401. | Skipped when signed out. Revoking the token already ends the server-side session. | Code review |
| B4-1 | **Bug (crash)** | **Songs tab crashed the app.** The first Songs page returned 101 items for a 100-item page (the extra "Shuffle all songs" row). Media3 treats that as fatal, so the app crashed as soon as the Songs tab opened. Found on the phone. | Songs pages contain only songs. Every library result is trimmed to the requested page size. | Unit tests (`PagingTest`). On device: no crash after the fix, and the Songs tab loaded and played. |
| B4-2 | Bug | **Page-number overflow.** A huge page number overflowed into a negative server index. | Overflow-safe page arithmetic. | Unit test |
| B4-3 | Bug | **Dead connection after service shutdown.** If the system stopped the playback service, the phone app kept a dead connection until reopened. | It reconnects on its own. | Code review |

### Security properties of the player (reviewed and found sound)

- **Who can do what.**
  - **Trusted callers** (checked by UID through `ClientAccess`: this app, Android Auto, Assistant, the
    system, media controllers such as Bluetooth, and apps with notification access) can browse, search
    and choose what plays.
  - **The legacy system controller** can use play/pause/skip/seek only.
  - **Everyone else** is refused.
- **Callers can't supply URLs.** Media3 doesn't pass a media item's URI between processes, and every
  queue item is rebuilt from its media ID with IDs validated. The player only streams from the signed-in
  server.
- **The token stays with the server.**
  - Stream URLs contain no credentials (unit-tested for every quality).
  - The `AuthInterceptor` adds the token only when a request's scheme, host, port and base path match the
    signed-in server (unit-tested, including look-alike paths and ports).
  - Redirects are refused for API calls and streams alike.
- **Playback reports** go only to the signed-in server, never to third parties, and are best-effort (a
  failed report never stops playback).
- **Saved state.** The saved queue holds item IDs only, validated on load, and is cleared on sign-out.
  All app data is excluded from backup and device transfer.
- **Search.** The query is capped at 100 characters and sent URL-encoded. Results are limited to 60 and
  reuse the same browse items.
- **Library-added component.** Media3 adds `BluetoothValidationActivity`, which is exported but requires
  `BLUETOOTH_PRIVILEGED`, so only the system Bluetooth stack can launch it.
- **Permissions.** `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK` and
  `ACCESS_NETWORK_STATE` (the last one added by Media3). Nothing sensitive: no storage, location,
  contacts or microphone.

### Verification

| Check | Result |
|---|---|
| Unit tests | **22 / 22** in debug and release (security checks, streaming URL and token rules, paging, UI logic) |
| Lint (debug and release) | 0 errors. No warnings beyond library versions and translatable-string notes. |
| Dependencies (OSV, 130 artifacts incl. Media3 1.11.1, Guava 33.3.1, OkHttp 4.12.0) | **0 known vulnerabilities** as of 2026-09-28 |
| Release APK (2.4 MB) | Not debuggable. No backup. Explicit network security config. targetSdk 36. Exports: launcher, player service, artwork provider (all guarded), plus the Media3 and AndroidX components listed above (permission-protected). |
| Repository | 0 personal identifiers or secrets |
| On the phone | Installs and runs. Songs tab crash found and fixed. Streaming playback confirmed (audio output active, no playback errors). |

### Not yet verified on a device

1. **Android Auto:** Tentacle listed in the car; browsing, playing, the steering wheel, voice, search.
   Also check Settings → Android Auto connections.
2. **The S4-1 fix doesn't block legitimate controllers.** Check the lock screen, the notification,
   Bluetooth and headset buttons, and a smartwatch if you use one.
3. **Phone features:** search, shuffle/repeat, Up next, streaming quality, resume after a restart,
   the automatic MP3 fallback for formats the phone can't decode, and playback reports showing up in
   Jellyfin's Recently played.

## Changes after review 4 (0.5.0) and the GitHub setup

Each change since review 4 was assessed for security impact:

| Change | Security impact | Notes |
|---|---|---|
| New logo, adaptive/themed launcher icon, green theme, in-app logo images | None | The images are bundled PNGs from the repository; no untrusted input is parsed. |
| Transparent system bars, navigation-bar contrast off | None | UI only. |
| `PlayerConnection` fix (**B5-1**): the app crashed every time it was left, because the reconnect logic from B4-3 released the Media3 controller twice | Availability bug, fixed | References are now cleared before release, and reconnection only happens for disconnects the app didn't ask for. Verified on the phone by leaving and returning three times with no crash. |
| Apache-2.0 `LICENSE`, `NOTICE`, copyright/SPDX headers, reserved name and logo | None | Legal only. |
| GitHub repository (private) | Reviewed | See below. |

**GitHub setup, as reviewed:**
- **CI permissions:** the workflow (`.github/workflows/android.yml`) runs with read-only permissions
  (`contents: read`). It uses no secrets and doesn't run for events that could expose any.
- **Gradle wrapper:** checked by `gradle/actions/wrapper-validation`, on top of the SHA-256 pinned in
  `gradle-wrapper.properties`.
- **Dependabot:** proposes dependency updates weekly, but only as pull requests that must pass CI. It
  skips Compose BOMs that need compileSdk 37 and AGP 9 major versions.
- **`.gitignore`:** excludes `local.properties`, build output, IDE state, keystores, signing
  properties, Play service-account keys and the local `backups/` folder.
- **Pre-upload scan:** the files were scanned before the first push: no secrets, server addresses,
  usernames, device names or personal paths.
- **Commits** use the owner's GitHub no-reply address, not a personal email.

**Hardening before going public (2026-09-29):**

| Item | Status |
|---|---|
| Pin GitHub Actions to commit SHAs | **Done.** All five steps are pinned to full SHAs of the latest releases: checkout v7.0.1, setup-java v6.0.1, upload-artifact v7.0.1, gradle/actions v6.4.0. Version comments let Dependabot keep them current. |
| Dependabot alerts and security updates | **On.** |
| Private vulnerability reporting | **After going public.** GitHub offers it only on public repositories. |
| Secret scanning and push protection | **After going public.** Not available for private repositories on the Free plan. |
| Protect `main` (ruleset) | **After going public**, or with GitHub Pro. GitHub: "Upgrade to GitHub Pro or make this repository public". The ruleset is ready: no deleting or force-pushing `main`, changes by pull request, and Android CI's `build` check must pass. Repository admins can bypass it, so the owner is never locked out. |

The three pending items are one command away. After making the repository public, run:

```bash
powershell -ExecutionPolicy Bypass -File scripts/github-hardening.ps1
```

It's safe to re-run. It reports what was enabled and anything GitHub refused.

**Dependabot's first run.** It proposed `androidx.core` 1.19.1, and CI rejected it: 1.19+ needs compileSdk
37 and AGP 9.1. Dependabot now skips `androidx.core` ≥ 1.19 (1.18.x is still compatible), matching the
existing Compose rule.

## Earlier reviews (0.1.0–0.2.0)

The findings below come from before the player rewrite. They're kept for the record. Where the code
still exists (ClientAccess, ArtworkProvider, sign-in, storage, network rules), the fixes still apply.

## Review 3 (2026-09-28): version 0.2.0

**Scope.** Every file was re-read, with the focus on code added since review 2:
- the Compose phone app (`ui/`: MainActivity and the Now playing, Library, Settings and Sign-in screens,
  Artwork, Theme),
- `Account` (sign-in and sign-out), `PlayerConnection` (the phone app's link to the media session),
- the `ClientAccess` rule changes, and the Android Auto connection log in `Prefs`.

The threat model gained two entries:
- **Library content as input.** Cover art and titles come from media files that may not be trustworthy.
- **Service and UI in one process.** The phone UI now runs in the same process as the car service, so a
  crash in either takes down both.

### Findings

| # | Severity | Finding | Fix | Verified by |
|---|---|---|---|---|
| S3-1 | **Medium** | **Oversized cover art could crash the car integration.** The phone app decoded album art at full resolution. A cover with huge dimensions (a malicious image in a downloaded album, or a server that doesn't resize) could exhaust memory. The Android Auto service runs in the same process, so the car integration went down with it. | Bounds read before decoding, downsampled to ≤ 1024 px, images over 20,000 px refused, out-of-memory caught. Server requests now cap width as well as height (512 px). | Unit tests for the downsampling rules |
| S3-2 | Low | **Stale state after sign-out.** The car service kept the previous account's target session, device name and Up next list until the next successful poll. A car command in that window would have been aimed at the old session (it would fail with 401, but it shouldn't be attempted). | The target, device and queue are cleared as soon as the app is signed out. | Code review |
| S3-3 | Low | **Forced disk writes.** Any app on the phone could bind to the media service repeatedly, and every attempt wrote the connection log to disk. | Repeat entries for the same app and outcome within 60 s are skipped. The log keeps at most 8 entries of package name, reason and time. | Code review |
| S3-4 | Low | **Server's raw reply shown in errors.** A sign-in to something that isn't a Jellyfin server (e.g. a login portal) could echo part of that server's HTML in the error text. | Friendly messages per failure type. The raw reply is never shown. | Code review |
| S3-5 | Low | **Keyboard learning of sign-in fields.** The server address and username could be learned and suggested by the keyboard. | Autocorrect and suggestions off for both fields. The password field already used a password keyboard. | Code review |
| S3-6 | Hardening | **Unrestricted artwork URIs.** The phone app's artwork loader would open any `content://` URI it was handed. | Accepts only this app's own artwork provider. Built-in icons are resolved through a fixed table, never by name lookup. | Code review |
| B3-1 | Bug | **Library error persisted after reconnecting.** A library page that failed to load while the app was reconnecting kept showing the error. | Pages reload when the connection comes back. | Code review |
| B3-2 | Bug | **"Open Jellyfin" did nothing when Jellyfin wasn't installed.** | Now shows a message saying the app isn't installed. | Code review |
| B3-3 | Bug | **Connection flag never reset.** The "connecting" flag wasn't reset after a successful connection. | Reset on connect. | Code review |

### Reviewed and found sound

- **New attack surface.** The phone app talks to the car service through the service's own caller check,
  as its own app (UID). Exported components are unchanged: the launcher activity, the media service and the
  artwork provider. The added AndroidX components (window extensions, emoji and lifecycle startup) are not
  exported. The profile-installer receiver still requires `DUMP`.
- **Password handling.**
  - The field is masked (`PasswordVisualTransformation`), uses a password keyboard and has a password
    autofill tag.
  - The password is kept only in memory: not in saved instance state, and cleared after sign-in.
  - The server and device fields carry no autofill tag, so password managers can't fill them.
- **Tapjacking.** Protection covers the whole window.
- **Sign-out.** It forgets the token locally first, clears cached art, and revokes the token on the server
  in an app-lifetime scope that leaving the screen can't cancel.
- **ClientAccess rules** (Google signature via the preinstalled Play services certificate, and notification
  listeners). These match Google's reference implementation (UAMP `PackageValidator`). A preinstalled
  third-party app is still refused, and lookup failures are treated as untrusted.
- **Connection log.** It holds package names, reasons and times only. Package names can't contain the
  separator character, so entries can't be forged, and it's excluded from backup like everything else.
- **Session extras.** They expose only the name of the controlled device, and only to callers that already
  passed the caller check.

### Verification

| Check | Result |
|---|---|
| Unit tests | **22 / 22** in debug and release (6 new: artwork bounds, sign-in preflight including the HTTP warning, time formatting) |
| Lint (debug and release) | 0 errors. No warnings beyond library versions and translatable-string notes. |
| Dependencies (OSV, 112 artifacts) | **0 known vulnerabilities** as of 2026-09-28 |
| Release APK (1.5 MB) | Not debuggable. No backup. Explicit network security config. targetSdk 36. Diagnostics absent from compiled code. |
| Repository | 0 personal identifiers or secrets |

## Between reviews 2 and 3: first car test and the new phone app

**Car test result.** In a real car, Jellyfin Auto didn't appear in the Android Auto launcher, and tapping
its now-playing tile did nothing. The song display and steering-wheel buttons worked, because those use the
system's media controls rather than the app's browse service. That matches unverified item 1 below: the
S-1 caller check probably turned away the component that Android Auto used in the car. It hasn't been
confirmed from logs yet.

**Change.** `ClientAccess` now follows Google's reference implementation (UAMP `PackageValidator`) in full.
It adds two rules to S-1, and neither lets an arbitrary preinstalled app back in:
- apps signed with the **same certificate as the preinstalled Google Play services** (Google's own apps,
  which a third party can't forge),
- apps the user has granted **notification access** (they can already control every media session).

Lookup failures are treated as "not trusted" instead of risking a crash. Every allow or reject decision
(package name, reason and time only) is recorded and shown under **Settings → Android Auto connections**,
so the next car test identifies the caller even without a computer. Rejections are also logged, by package
name only.

**New phone UI (Jetpack Compose), reviewed:**
- **Connection:** it connects to the app's own media session through `ClientAccess` (own UID), so it has no
  new exported surface. The only exported components are still the launcher activity, the media service and
  the artwork provider.
- **Password field:** masked (`PasswordVisualTransformation`), password keyboard, autofill tagged as
  password. It's held only in memory, deliberately not in saved instance state, so it isn't persisted when
  Android saves the screen. It's cleared after sign-in.
- **Device box:** not tagged for autofill, which fixes the earlier bug where the username was filled into
  it. The server field isn't tagged either.
- **Tapjacking:** protection applies to the whole window (`filterTouchesWhenObscured` on the root view).
- **Artwork:** the resolver accepts only this app's own `android.resource://` drawables, through a fixed
  table; content images come from `ArtworkProvider`.
- **Token revocation:** sign-out revokes the token in an app-lifetime scope, so leaving the screen doesn't
  cancel it.
- **Dependencies:** Compose BOM 2026.06.01 (UI 1.11.4, Material3 1.4.0), activity-compose 1.12.4,
  lifecycle-runtime-compose 2.10.0. Newer Compose releases need compileSdk 37 and AGP 9.1.
- **Re-checked:** release runtime classpath is now **112 artifacts, 0 known vulnerabilities** (OSV). Lint
  0 errors, 16/16 tests pass in debug and release.

**Still to verify on a phone:** everything in [Not yet verified](#not-yet-verified), plus the new phone
app's screens and Settings → Android Auto connections after a car session.

## Method (all reviews)

- **Code:** every Kotlin source file, the manifest, all resources, Gradle build files and ProGuard rules,
  reviewed line by line.
- **Threat model:** the table below, applied to each exported component, network call, stored value and
  piece of data shown in the car.
- **Static analysis:** Android lint on debug and release builds, with lint errors failing the build.
- **Dependencies:** every library in the release runtime classpath (33 artifacts) queried against the
  [OSV](https://osv.dev) vulnerability database, which aggregates GitHub advisories and the NVD.
- **Build output:** the release APK's merged manifest (via `aapt2`) and compiled code inspected directly,
  to confirm the hardening is present in what ships, not just in the source.
- **Tests:** 16 JVM unit tests covering ID validation, LAN detection, session selection and merging, and
  repeat/shuffle state.
- **Store requirements:** Google Play's target-API policy and the Android Auto
  [car app quality](https://developer.android.com/docs/quality-guidelines/car-app-quality) checklist.

### Threat model

| Asset | Threats considered |
|---|---|
| Jellyfin password (entered once) | Interception on the network, shoulder-surfing, tapjacking, leaking to another host via redirect |
| Access token (stored) | Interception, other apps on the phone, backup/device transfer, logs, header injection |
| Library, listening activity, other users' sessions | Other apps reading them through the app's exported components, logs, over-collection |
| Playback on your devices | Other apps driving playback, or the car controlling the wrong device or another user |
| Source repository | Committed secrets or personal data, tampered build tooling |

**Out of scope:** a rooted or physically compromised phone, a malicious Jellyfin server (it's yours), and
bugs in Android, Android Auto, the Jellyfin server or the official Jellyfin app.

### Attack surface

| Entry point | Exposed to | Protection |
|---|---|---|
| `AutoMediaService` (MediaBrowserService, exported) | Apps that bind to it | `ClientAccess` check in `onGetRoot`, before any library data or the session token is handed out |
| `ArtworkProvider` (ContentProvider, exported) | Apps that open its URIs | `ClientAccess` check on every call, read-only, strict ID validation, generic errors |
| `SetupActivity` (launcher) | The user | Tapjacking filter, no secrets accepted through intents |
| `MediaSession` callbacks | Callers that hold the session token (from the above, or apps granted notification access) | Command targets and IDs validated. Everything it can do is limited to controlling your own playback. |
| Network | Your Jellyfin server | No redirects, response size cap, 9 s timeout, system CAs only, cleartext warning |
| Stored data | App sandbox | No password stored, excluded from cloud backup and device transfer, revoked on sign-out |

## Review 2 findings (2026-09-28)

All findings are fixed. Severity is the impact on a user of this app.

### Security

| # | Severity | Finding | Fix | Verified by |
|---|---|---|---|---|
| S-1 | **Medium** | **Any preinstalled app was trusted.** `ClientAccess` accepted every app with the system flag. Many phones ship third-party apps (carrier tools, social apps) on the system image, and all of them could browse your library, get the media session and drive playback, or read artwork. | Callers must be signed with the platform key (System UI and core OS), hold `MEDIA_CONTENT_CONTROL`, or be Android Auto / Google Assistant installed on the system image or from Play. | Code review. On-device check pending (see below). |
| S-2 | **Medium** | **Car could control another of your devices.** If the Jellyfin app on this phone wasn't running, the app fell back to any other Android client on your account, such as a tablet at home, and the car's pause/skip would act on it. | Without a device filter, only this phone (matched by device name) is used. Other devices need an explicit **Device to control** entry. The setup screen explains why nothing matched. | Unit test `neverFallsBackToAnotherAndroidDevice` |
| S-3 | Low | **Loose session merging.** The merge that pairs the Jellyfin app's two sessions used an unbounded prefix match on device IDs, so a short or empty ID could pair unrelated devices. Now-playing would then show another device's track, with commands going to the wrong session. | Requires the same client and device name, and a shared ID prefix of at least 8 characters. | Unit test `shortOrMismatchedDeviceIdsDoNotMerge` |
| S-4 | Low | **Diagnostics in release builds.** Session logging could be switched on with a system property, even in release builds. On admin accounts it would write other users' device names and listening activity to the system log. Warnings also logged full stack traces, which can include the server address. | Diagnostics compile only into debug builds, and R8 strips logging as a second layer. Release warnings log only the error type. | Release APK inspected: the diagnostic strings are absent from the compiled code. |
| S-5 | Low | **Server-supplied token not validated.** The access token from the server goes straight into every request's `Authorization` header, so a malformed value could corrupt headers. | Sign-in rejects tokens that aren't plain `[A-Za-z0-9._~-]{16,512}`, and user IDs must pass `requireSafeId`. | Code review |
| S-6 | Low | **Error details leaked to callers.** When artwork failed to load, the provider returned the underlying exception message (which can contain the server address) to the calling app. | Returns a generic "artwork unavailable". | Code review |
| S-7 | Low | **No tapjacking protection.** Another app's overlay could trick taps onto the sign-in form. | Setup screen ignores touches while obscured (`filterTouchesWhenObscured`). | Code review |
| S-8 | Hardening | **Implicit network policy.** Cleartext and certificate trust relied on defaults. | Explicit `network_security_config.xml`: HTTPS trusts only system CAs (user-installed CAs can't intercept), and cleartext is allowed only because home servers use it, with the reason documented. | Release manifest inspected |
| S-9 | Hardening | **Repository and supply chain.** No Gradle wrapper, no protection against committing signing keys, and personal identifiers from on-device debugging (a device name and real Jellyfin device IDs) in tests and a comment. | Gradle wrapper added with the distribution's SHA-256 pinned. `.gitignore` covers keystores, signing properties and Play service-account keys. Identifiers replaced with fictitious values. Repository scanned clean. | Scan of the repository: 0 matches |

### Bugs and release blockers

| # | Severity | Finding | Fix |
|---|---|---|---|
| B-1 | **Blocker** | **Play would reject the app.** It targeted API 34. Since 2026-08-31, new apps and updates must target API 36 ([policy](https://support.google.com/googleplay/android-developer/answer/11926878)). | compileSdk/targetSdk 36. Toolchain: AGP 8.13.2, Gradle 8.14.5, Kotlin 2.2.21. Dependencies updated. |
| B-2 | **Blocker** (on Android 15+) | **Setup screen under the system bars.** Apps targeting 35+ are drawn edge-to-edge, so the setup screen's top and bottom would sit under the status and navigation bars, and the keyboard could cover fields. | Window-inset padding for system bars, display cutout and keyboard. |
| B-3 | **Blocker** (Android Auto review) | **Missing icons.** Android Auto requires a thumbnail for every media item and white icons for root tabs. Tabs, sort options, "Play all"/"Shuffle" rows, messages, and items without cover art had none. | White vector icons for all of them, including fallbacks for albums, artists and songs without art, as `android.resource://` URIs per the Android for Cars docs. |
| B-4 | **Blocker** (Android Auto review) | **Could exceed the 10-second load limit** (DR-3). A slow server could hold a request for about 15 s (5 s to connect plus 10 s to read). | Hard 9-second timeout per request. |
| B-5 | Medium | **Tab limit ignored.** When the car sent no hint, the root tab limit defaulted to unlimited instead of the documented 4. | Defaults to 4. |
| B-6 | Low | **Up next stuck retrying.** One malformed ID in the phone's queue failed the whole lookup, and it retried on every poll (every 1–3 s). | Malformed IDs are skipped. |
| B-7 | Low | **Crash path in the artwork provider.** Creating the temp file sat outside the error handling and would throw for very short IDs. | Temp file uses a fixed prefix and sits inside the error handling. |
| B-8 | Low | **Two device IDs on first launch.** The car service and the setup screen could each generate a different install ID at once, leaving a stale device registered on the server. | ID creation is locked and committed synchronously. |
| B-9 | Low | **Release build not optimized, version hard-coded.** The release build wasn't shrunk, and the version sent to the server was hard-coded. | R8 code and resource shrinking (release APK is 297 KB). Version comes from the build. |

## Findings: first review (2026-09-27), re-checked

All 13 remain fixed. Finding 10 was strengthened by S-2 above.

| # | Severity | Finding | Status |
|---|---|---|---|
| 1 | High | Other users' sessions exposed and controllable | Fixed. The app now fetches all sessions (needed for Up next) and filters to your user locally. See residual risk R-5. |
| 2 | Medium | Password shown in plain text on the setup screen | Fixed |
| 3 | Medium | Credentials over unencrypted HTTP to public servers | Mitigated: https by default for public hosts, plus a warning |
| 4 | Medium | Artwork provider open to every app | Fixed, now with the tighter caller rules from S-1 |
| 5 | Medium | Caller allowlist trusted package names alone | Fixed, tightened further by S-1 |
| 6 | Medium | Path traversal into other API endpoints via crafted IDs | Fixed (`requireSafeId` on every path segment) |
| 7 | Medium | Token copied to new phones by device-to-device transfer | Fixed |
| 8 | Medium | Redirects could re-send the password or downgrade to HTTP | Fixed (redirects refused) |
| 9 | Low | Sign-out didn't revoke the token on the server | Fixed |
| 10 | Low | Car could control a TV or browser at home | Fixed, now strict (S-2) |
| 11 | Low | Unbounded responses, an HTTP client per artwork request | Fixed |
| 12 | Bug | Corrupt artwork from concurrent downloads | Fixed |
| 13 | Bug | Stale car-screen state, fake "playing" state, negative seek, restricted API | Fixed |

## Review 2 verification results

| Check | Result |
|---|---|
| Unit tests (debug and release variants) | **16 / 16 pass** in each |
| Lint, debug and release | **0 errors.** Remaining warnings: newer library versions available (none with known vulnerabilities), and UI text not yet in translatable resources (33, localization only). |
| Dependency vulnerabilities (OSV, 33 artifacts) | **0 known** as of 2026-09-28 |
| Release manifest | Not debuggable. `allowBackup=false`. Explicit network security config. targetSdk 36. Only the service, provider and launcher activity exported by this app. The one exported AndroidX receiver (profile installer) requires `android.permission.DUMP`, which only the system and adb shell hold. |
| Release code | Diagnostic logging absent from the compiled code |
| Repository | 0 personal identifiers or secrets. `local.properties` (SDK path only) is git-ignored. |

## Not yet verified

These need a phone and Android Auto, because unit tests can't show them. They must be checked before
submitting:

1. **Android Auto still connects after S-1.** On the test phone Android Auto is both a system app and
   installed from Play, so it should pass, but confirm. If Jellyfin Auto disappears from the car or artwork
   is missing, check `ClientAccess` first.
2. **Pause, next, previous, seek** from the car reach the Jellyfin app. The commands go to the
   controllable half of its split session, which hasn't been observed working end to end.
3. **Shuffle and repeat:** the Jellyfin app accepts `SetShuffleQueue` / `SetRepeatMode`.
4. **Up next** appears. That depends on the Jellyfin app reporting its queue to the server, and on
   jumping to a song in it working.
5. **Layout:** tabs, grids, section headers and icons render as intended.
6. **Setup screen on Android 15+:** content clears the status bar, navigation bar and keyboard.
7. **Voice** ("Hey Google, play … on Jellyfin Auto") works. It's a car-app-quality requirement (VC-1).
8. **Loading within 10 s** on a slow connection (DR-3), including large libraries.
9. **The phone app (0.2.0):** each screen works end to end.
   - Now playing: controls, seek, shuffle/repeat, Up next.
   - Library: tabs, grids, sections, starting playback.
   - Settings: device box, sessions list, clear cache, sign-out.
   - Light and dark mode, and the keyboard not covering the sign-in form.
10. **Settings → Android Auto connections** after a real car session. This confirms whether the revised
    `ClientAccess` admits the car's caller, the open question from the first car test.

## Residual risks (accepted, documented)

| # | Risk | Why accepted |
|---|---|---|
| R-1 | Plain HTTP is allowed app-wide | Most home servers are `http://` on the LAN, and Android can't allow cleartext by IP range. Public HTTP addresses get a warning and default to HTTPS. |
| R-2 | Access token stored unencrypted in the app sandbox | Other apps can't read the sandbox. `EncryptedSharedPreferences` is deprecated, and Keystore wrapping adds little on an unrooted phone. |
| R-3 | Apps you've granted notification access can control any media session, including this one | Android's design. They can only control your own playback. |
| R-4 | A Google app is recognized by comparing its certificate with the phone's **preinstalled** Google Play services. On a phone without preinstalled Play services, it falls back to requiring the app to be preinstalled or installed from Play. | Android Auto needs Google Play services anyway, so this affects essentially no real phones. |
| R-5 | For admin accounts, `GET /Sessions` returns every user's sessions; they're filtered to your own immediately and kept only in memory | Needed because the controllable-only query hides the half of the Jellyfin app's session that reports playback. Nothing is stored or logged in release builds. |
| R-6 | No phone-side "play X on Jellyfin Auto" outside Android Auto | An exported search activity would let any app start playback without the caller check. Voice inside Android Auto uses the session and works. |
| R-7 | OkHttp is on the final 4.x release (4.12.0) | No known vulnerabilities. Moving to 5.x is a major upgrade best done as its own change. |
| R-8 | Only JVM unit tests; no automated UI or instrumented tests | Behavior on devices is covered by the manual checklist above. Adding instrumented tests would help long term. |
| R-9 | The phone UI and the Android Auto service share one process, so a crash in the UI also drops the car connection | Normal for Android media apps. The known crash vector (oversized art, S3-1) is fixed. A separate process would add IPC overhead for little gain. |
| R-10 | Compose is held at the 2026.06.01 BOM (UI 1.11.4). Newer releases need compileSdk 37 and AGP 9.1. | No known vulnerabilities in the pinned versions. Upgrade together with the move to AGP 9. |

## Before publishing: non-code items

These aren't bugs, but each one can block an open-source release or a Play listing:

1. **Licence.** *(Done: Apache-2.0 with a NOTICE file crediting Mark Joseph; the name and logo are
   reserved.)* The vector icons are Material Design icons (Apache-2.0), which
   requires attribution in a NOTICE file or the README.
2. **Name and branding.** Jellyfin's [branding guidelines](https://jellyfin.org/docs/general/contributing/branding/)
   forbid using its logo and wrongly implying you're part of the project. Play's impersonation policy is
   similar. The app already uses its own icon. Consider a name like "Car Controls for Jellyfin", with
   "unofficial, not affiliated with the Jellyfin project" in the listing.
3. **Privacy policy and Data safety form.** Play requires both. [PRIVACY.md](PRIVACY.md) is a draft:
   fill in the publisher details and host it at a public URL. The app sends data only to the user's own
   server, with no analytics, ads or third parties.
4. **Signing and bundle.** Create an upload key, kept out of the repo (the `.gitignore` covers it), enrol
   in Play App Signing, and upload an App Bundle (`gradlew bundleRelease`).
5. **Android Auto review.** Opting into Android Auto distribution adds a car-app-quality review for every
   release. Finish the "Not yet verified" checklist first.
6. **Vulnerability reporting.** [SECURITY.md](SECURITY.md) explains how to report issues privately.
   Enable GitHub private vulnerability reporting when the repository is created.
