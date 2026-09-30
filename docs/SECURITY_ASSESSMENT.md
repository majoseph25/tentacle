# Security assessment

| | |
|---|---|
| **App** | Tentacle 0.6.1 (build 8), Android |
| **Reviews** | Four full reviews (2026-09-27 to 2026-09-28), plus assessments of every later change |
| **Last updated** | 2026-09-29 |
| **Performed by** | Claude (Anthropic's AI model), working in [Claude Code](https://claude.com/claude-code) for the project owner, Mark Joseph |
| **Current status** | 0 open findings · 33 / 33 tests pass · 0 known vulnerabilities in 130 shipped libraries |

> **Please read this first.** These reviews were carried out by an AI assistant: code review, static
> analysis, dependency scanning, build inspection and testing on one phone. They are **not** a professional
> penetration test or a certified audit, and no review can prove software is free of bugs or
> vulnerabilities. What they do show: every issue found was fixed, each fix was verified, and the known
> gaps are listed in [section 8](#8-open-items).

**Contents**
1. [Summary](#1-summary)
2. [Scope and method](#2-scope-and-method)
3. [Threat model](#3-threat-model)
4. [Attack surface](#4-attack-surface)
5. [Security controls](#5-security-controls)
6. [Findings](#6-findings)
7. [Verification](#7-verification)
8. [Open items](#8-open-items)

---

## 1. Summary

Tentacle streams music from the user's own Jellyfin server, on the phone and in Android Auto. The things
worth protecting are the Jellyfin login, the user's library and listening activity, and control of what
plays. The main protections:

- **The login token only goes to your server.** The password is never stored. The token never appears in
  a URL, and it's attached only to requests for the signed-in server. Redirects are refused.
- **Only trusted apps can reach your library.** Android Auto, Assistant, the system and Tentacle itself
  can browse and play. The system's media buttons can only play, pause and skip. All other apps are
  refused.
- **Nothing can make the player fetch other addresses.** Every request to play is rebuilt from a validated
  item ID, so the player streams only from your server.
- **Hardened release build.** Not debuggable, shrunk with R8, no diagnostic logging, excluded from backup,
  and trusting only system certificate authorities.
- **Guarded supply chain.** A checksum-pinned Gradle wrapper, GitHub Actions pinned to commit SHAs,
  Dependabot, and CI on every change.

| Review | Date | Version | Focus | Security findings | Bugs | Open now |
|---|---|---|---|---|---|---|
| 1 | 2026-09-27 | 0.1.0 | First version (remote control of the Jellyfin app) | 11 | 2 | 0 |
| 2 | 2026-09-28 | 0.1.0 | Car features, Google Play readiness | 9 | 9 (4 release blockers) | 0 |
| 3 | 2026-09-28 | 0.2.0 | Compose phone app | 5 | 3 | 0 |
| 4 | 2026-09-28 | 0.4.0 | Standalone player rewrite (Media3), Songs, search | 4 | 3 | 0 |
| — | 2026-09-29 | 0.5.0–0.5.1 | Changes after review 4, GitHub setup, Android Auto testing | 0 | 2 | 0 |
| — | 2026-09-29 | 0.6.0–0.6.1 | Optional Tailscale connect, then connect only when needed and turn off again | 0 (1 accepted risk) | 3 | 0 |

Version 0.3.0 replaced the original remote-control design, so several early findings are now **obsolete**:
the code they concerned no longer exists. They're marked as such in [section 6](#6-findings).

## 2. Scope and method

Every review covered the whole project, with extra attention on what had changed:

| Technique | What it covers |
|---|---|
| Line-by-line code review | Every Kotlin source file, the manifest, resources, Gradle build files and R8 rules |
| Threat modelling | [Section 3](#3-threat-model), applied to every exported component, network call, stored value and piece of displayed data |
| Static analysis | Android lint on debug and release builds. Any error fails the build. |
| Dependency scanning | Every library in the release APK, checked against the [OSV](https://osv.dev) database (GitHub advisories and NVD) |
| Build inspection | The release APK's merged manifest (`aapt2`) and compiled code, to confirm the hardening is in what actually ships |
| Unit tests | 22 JVM tests for the security rules (see [section 7](#7-verification)) |
| On-device testing | Install, playback and crash checks on a real phone (Pixel 8a, Android 17) against a real Jellyfin 10.11 server |
| Platform rules | Google Play's target-API policy and the Android Auto [car app quality](https://developer.android.com/docs/quality-guidelines/car-app-quality) checklist |

## 3. Threat model

| Asset | Threats considered |
|---|---|
| Jellyfin password (typed once) | Interception on the network; shoulder-surfing; overlay (tapjacking) attacks; being re-sent to another host through a redirect |
| Access token (stored) | Interception; other apps on the phone; backups and device-to-device transfer; logs; leaking through stream URLs or requests to other hosts |
| Library and listening activity | Other apps reading them through the app's exported components; logs; over-collection |
| Control of playback | Other apps loading content into the player or controlling it |
| The app itself | Crashes from malicious library content (e.g. oversized cover art); resource exhaustion |
| Source repository | Committed secrets or personal data; tampered build tooling or CI steps |

**Out of scope:**
- a rooted or physically compromised phone,
- a malicious Jellyfin server (it's the user's own),
- vulnerabilities in Android, Android Auto, Google Play services or the Jellyfin server.

## 4. Attack surface

| Entry point | Reachable by | Protection |
|---|---|---|
| `PlaybackService` (Media3 `MediaLibraryService`, exported; Android Auto requires this) | Any app that binds to it | Untrusted apps are refused at connection. The system's combined legacy controller gets play/pause/skip/seek only. Browsing, search and loading items need a trusted caller. |
| `ArtworkProvider` (ContentProvider, exported for Android Auto) | Any app that opens its URIs | Caller checked on every request; read-only; strict ID validation; generic errors only |
| `MainActivity` (launcher) | The user | Tapjacking filter on the whole window; reads no data from intents |
| `BluetoothValidationActivity` (added by Media3) | Holders of `BLUETOOTH_PRIVILEGED` | Only the system Bluetooth stack can start it |
| `ProfileInstallReceiver` (added by AndroidX) | Holders of `DUMP` | System and adb only |
| Network | The signed-in Jellyfin server | See [5.2](#52-network-and-token-handling) |
| Outgoing request to Tailscale (optional, 0.6.0) | Sent by Tentacle only | An explicit broadcast to Tailscale's own receiver, carrying no data. See [5.8](#58-optional-tailscale-connect). |
| Stored data | The app's own sandbox | See [5.3](#53-credentials-and-storage) |

## 5. Security controls

### 5.1 Access control

**`ClientAccess`** decides who's trusted. It follows the rules of Google's reference media app (UAMP) and
identifies callers by their system-verified UID, never by a package name they report. Trusted callers:
- this app and the system,
- platform-signed apps (System UI),
- holders of `MEDIA_CONTENT_CONTROL` (e.g. Bluetooth),
- apps signed like the preinstalled Google Play services (Google's own apps: Android Auto, Assistant),
- apps the user has granted notification access,
- Android Auto and Assistant by package name, but only if preinstalled or installed from Google Play.

Being preinstalled isn't enough on its own, because many phones ship third-party apps on the system image.

**What each caller may do:**

| Caller | Allowed |
|---|---|
| Trusted | Browse, search, choose what plays, read artwork, control playback |
| System legacy controller (Android 8 lock screen, Bluetooth and headset buttons) | Play, pause, skip and seek on what's already playing |
| Everyone else | Refused; recorded under Settings → Android Auto connections |

**Callers can't supply URLs.** Media3 doesn't pass a media item's URI between processes, and every queue
item is rebuilt from a validated item ID. A request for more than 500 items is capped.

### 5.2 Network and token handling

- **No credentials in URLs.** Stream URLs never contain the token (unit-tested for every quality).
- **The token goes only to your server.** The `AuthInterceptor` adds the token only when a request's
  scheme, host, port and base path match the signed-in server (unit-tested, including look-alike hosts,
  ports and paths).
- **HTTP(S) only.** The player's only data source is the HTTP(S) streaming client, so it can't open local
  files or content URIs.
- **Redirects are refused** for API calls and streams alike. A redirect could otherwise re-send the
  password or token, or downgrade HTTPS to HTTP.
- **HTTPS trusts only system certificate authorities** (explicit `network_security_config.xml`).
- **Plain HTTP:** allowed for home servers. Without a scheme, public addresses default to HTTPS, and
  signing in to a public `http://` address shows a warning first.
- **Limits:**
  - 9 s per API request (also Android Auto's 10 s load rule),
  - responses capped at 16 MB (images at 5 MB),
  - redirects answered with a clear error instead of being followed.

### 5.3 Credentials and storage

- **Password:** masked, used once, held only in memory (not in saved screen state), and never stored.
- **Token:** validated on sign-in (`[A-Za-z0-9._~-]{16,512}`) and stored in app-private preferences.
- **Backups:** all app data is excluded from cloud backup and device-to-device transfer.
- **Sign-out:** stops playback, deletes the token, saved queue and cached artwork, and revokes the token on
  the server. The revocation runs in an app-lifetime scope, so leaving the screen doesn't cancel it.
- **Sign-in fields:** the keyboard doesn't learn or suggest the server address or username. Only the
  username and password fields are tagged for autofill.

### 5.4 Input handling

- **Item IDs:** every ID that reaches a URL path or a file name must match `[A-Za-z0-9-]{1,64}`, which
  rules out path traversal.
- **IDs from other apps** (artwork requests, 0.6.1): only a Jellyfin GUID is accepted. It's rebuilt from
  its numeric value (`canonicalItemId`), so none of the requesting app's text reaches the cache file name
  or the server URL.
- **Cover art** (which can come from any file in a library): the dimensions are read before decoding, the
  image is downsampled to at most 1024 px, anything over 20,000 px is refused, and out-of-memory is caught.
- **The phone app's artwork loader** opens only this app's own provider and icons, through a fixed table.
- **Search:** queries are capped at 100 characters and sent URL-encoded. Results are capped at 60.
- **Library pages** never exceed the requested size (Media3 treats a larger page as fatal). Page
  arithmetic is overflow-safe.

### 5.5 Logging and privacy

- **Release builds** log only error types, never messages, stack traces or addresses. Diagnostics exist
  only in debug builds, and R8 strips them as a second layer.
- **No data to third parties.** The app talks only to the user's server: no analytics, ads or tracking
  (see [PRIVACY.md](../PRIVACY.md)).
- **The connection log** (Settings → Android Auto connections) holds package names, reasons and times
  only. It's throttled against spam and excluded from backup.

### 5.6 Build and release

- **Release build:** not debuggable, R8 code and resource shrinking, `targetSdk` 36.
- **Permissions:** `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK` and
  `ACCESS_NETWORK_STATE` (the last added by Media3). No storage, location, contacts, camera or microphone.
- **Lint** errors fail the build, in both variants.

### 5.7 Repository and supply chain

| Control | Status |
|---|---|
| Gradle wrapper verified by SHA-256 (`distributionSha256Sum`) and by CI's wrapper validation | On |
| GitHub Actions pinned to full commit SHAs of their latest releases (checkout v7.0.1, setup-java v6.0.1, upload-artifact v7.0.1, gradle/actions v6.4.0) | On |
| CI with read-only permissions, no secrets; tests, lint and a release build on every push and PR | On (first runs passed) |
| Dependabot version updates, skipping versions that need compileSdk 37 / AGP 9 | On |
| Dependabot alerts and security updates | On |
| `.gitignore` excludes local config, build output, keystores, signing properties, Play keys and backups | On |
| Commits use the owner's GitHub no-reply address | On |
| Private vulnerability reporting | On |
| Secret scanning and push protection | On |
| Ruleset protecting `main` (pull requests, passing CI, no force-push or deletion; admins can bypass) | On |
| Code scanning (CodeQL default setup) | On |
| Full git history scanned for secrets and personal data before the repository went public | Done (2026-09-29, clean) |

The repository went public on 2026-09-29. The GitHub settings above are applied by
`scripts/github-hardening.ps1`, which is safe to run again at any time to check or restore them:

```bash
powershell -ExecutionPolicy Bypass -File scripts/github-hardening.ps1
```

### 5.8 Optional Tailscale connect

Added in 0.6.0 for servers that are reachable only through Tailscale away from home. Off by default.

- **What Tentacle does:** asks the installed Tailscale app to connect, using the public
  `com.tailscale.ipn.CONNECT_VPN` broadcast that Tailscale provides for automation apps. It's sent to
  Tailscale's `IPNReceiver` by explicit component, so no other app can receive it, and it carries no data.
- **Turning it off again (0.6.1):** Tentacle sends `DISCONNECT_VPN` when it's done (the player service
  stops) or when the setting is switched off, but only if Tentacle turned Tailscale on. It records that
  in a stored flag, which is cleared as soon as it finds the VPN off. A connection the user started is
  never turned off automatically; the **Disconnect** button is the only exception.
- **What Tentacle doesn't do:** it never reads Tailscale's state, account, keys or traffic, and never
  embeds a VPN of its own.
- **New permission:** `ACCESS_NETWORK_STATE` (granted at install; network state only, no traffic or
  data). It's used to see whether a VPN is up, and to wait for Tailscale's to appear.
- **Package visibility:** `<queries>` now lists `com.tailscale.ipn` so Tentacle can detect the app.
- **Only when needed (0.6.1):** the 0.6.0 "Always" option connected Tailscale even at home. It's
  gone, and a saved "Always" now means "when needed".
- **Reachability check:** a TCP connection to the signed-in
  server's own host and port, closed without sending anything, with a 1.5 s timeout.
- **No loops:**
  - Concurrent triggers share one attempt.
  - After a failed attempt, automatic triggers wait 60 s before trying again.
  - A failed song is retried once, and only after Tailscale actually came up.
- **Android Auto time limit:** car requests wait at most 7 s for an attempt already under way, leaving
  room within the 10 s limit (DR-3). If Tailscale comes up later, the car's lists reload by themselves.

## 6. Findings

Severity is the impact on a user of the app. Every finding below is **resolved**. "Obsolete" means the
affected code was removed when the app became a standalone player in 0.3.0.

### 6.0a GitHub code scanning (CodeQL, 0.6.1)

CodeQL's first scan of the public repository reported five alerts. All five trace one input: the item ID
in an artwork request (`ArtworkProvider.openFile`, from the requesting app's `content://` URI) flowing
into the cache file name (`java/path-injection`) and the server request (`java/ssrf`).

| Alert | Rule (CodeQL severity) | Where | Assessment | Resolution |
|---|---|---|---|---|
| #1 | `java/ssrf` (critical) | `JellyfinApi.kt` request URL | **Not exploitable.** The host is always the signed-in server, and the ID can only fill one path segment, restricted to `[A-Za-z0-9-]`. Only trusted callers (Android Auto, Assistant, the system) get past `ClientAccess`. | The ID is rebuilt from its numeric value as a canonical GUID (`canonicalItemId`), so no caller text reaches the URL |
| #2–#5 | `java/path-injection` (high) | `ArtworkProvider.kt` cache file | **Not exploitable.** `requireSafeId` rejected `/`, `.` and `%` before the ID was used, so it couldn't leave the cache folder. CodeQL doesn't treat that check as a sanitizer. | Same fix: the file name is built only from the rebuilt GUID |

The underlying check was already in place, so these are hardening fixes rather than vulnerabilities
closed. Unit tests cover the new check (`canonicalItemIdAcceptsJellyfinGuids`,
`canonicalItemIdRejectsEverythingElse`). The alerts close automatically once CodeQL re-scans `main`
after the merge.

### 6.0 Tailscale feature (0.6.0 and 0.6.1)

The new code (`Tailscale.kt`, and its use in the player service, Settings and sign-in) was reviewed
before release against the threat model in [section 3](#3-threat-model).

| ID | Severity | Finding | Resolution |
|---|---|---|---|
| T6-1 | Low (bug) | The timestamp that holds off retries after a failure was written on one thread and read on others without a memory barrier, so a retry could start too early | Marked `@Volatile` |
| T6-2 | Info | On Android 16 and newer, Tailscale's automation broadcast sometimes only starts its service (Tailscale issue #18847) | The request is re-sent after 3 s if the VPN isn't up, as Tailscale advises. Tentacle waits up to 10 s in total, then reports "didn't connect" and offers **Open Tailscale**. |
| T6-3 | Info | Another app could be installed under Tailscale's package name (only if sideloaded) | Accepted as R-9: the requests carry no data, so an impostor learns only that Tentacle wanted to connect or disconnect |
| T6-4 | Bug (behaviour) | Found by the owner in 0.6.0: the "Always" option connected Tailscale every time the app opened, even at home, and nothing ever turned it off again | 0.6.1: "Always" removed (a saved value now means "when needed"). Tentacle turns Tailscale off again when it's done or the setting is switched off, but only if it turned it on itself. Verified on the phone: Disconnect, reopening at home (stays off), switching off, and leaving the app (off within 5 s). |
| T6-5 | Low (bug) | Stopping an attempt under way (for example switching the setting off) would have made anything waiting on it fail, including a car's library request | Waiters use `join()`, and `ensure()` reports "Stopped" instead of throwing |

### 6.1 After review 4 (0.5.0 and 0.5.1)

| ID | Severity | Finding | Resolution |
|---|---|---|---|
| B5-1 | Bug (crash) | The app crashed every time it was left: the reconnect logic from B4-3 released the Media3 controller twice | References cleared before release; reconnects only after disconnects the app didn't request. Verified on the phone. |
| B5-2 | Bug (display) | In Android Auto, Home → **All songs** was drawn as a large album-style tile with its title pushed below the fold, because Home asks for its browsable rows as a grid | The row now carries Android Auto's per-item style hint (`CONTENT_STYLE_SINGLE_ITEM_HINT` = list). Fixed in 0.5.1 and verified on the Desktop Head Unit. |

**Not a code defect:** in the first car test, Tentacle was missing from the car's app list. The connection
log was empty (Android Auto never asked for the library), and the app was sideloaded. Android Auto hides
sideloaded media apps until **Unknown sources** is turned on in its developer settings. With it on,
Tentacle was listed and worked. Apps installed from Google Play aren't affected.

Branding, the green theme, the licence files and the GitHub setup were assessed and have no security
impact, apart from the controls listed in [5.7](#57-repository-and-supply-chain).

### 6.2 Review 4 (0.4.0): standalone player

| ID | Severity | Finding | Resolution |
|---|---|---|---|
| S4-1 | **Medium** | Any installed app could connect to the player, read the current song and queue, and pause or skip | Untrusted apps are refused at connection; the system legacy controller gets transport controls only |
| S4-2 | Low | The player's data source also opened `file://`, `content://` and asset URIs | HTTP(S)-only data source |
| S4-3 | Low | A trusted caller could request unlimited items in one call | Capped at 500; lookups batched by 100 |
| S4-4 | Low | Playback reports kept being sent after sign-out (failing with 401) | Skipped when signed out |
| B4-1 | Bug (crash) | The Songs tab crashed the app: a 101-item page for a 100-item request | Pages never exceed the requested size (unit-tested; fixed on the phone) |
| B4-2 | Bug | A huge page number could overflow into a negative server index | Overflow-safe page arithmetic (unit-tested) |
| B4-3 | Bug | After the system stopped the service, the phone app kept a dead connection | Automatic reconnect (see B5-1) |

<details>
<summary><b>6.3 Review 3 (0.2.0): Compose phone app</b>: 5 security findings, 3 bugs</summary>

| ID | Severity | Finding | Resolution |
|---|---|---|---|
| S3-1 | **Medium** | Oversized cover art could exhaust memory and take down the process (and the car integration with it) | Bounded decoding: ≤ 1024 px, > 20,000 px refused, out-of-memory caught; server images capped at 512 px |
| S3-2 | Low | The car service kept the previous account's session after sign-out | Fixed then; **obsolete** since 0.3.0 |
| S3-3 | Low | Any app could force repeated disk writes to the connection log | Writes throttled to one per app and outcome per minute; 8 entries kept |
| S3-4 | Low | Sign-in errors could echo a non-Jellyfin server's raw reply | Friendly error messages only |
| S3-5 | Low | The keyboard could learn the server address and username | Autocorrect and suggestions off |
| S3-6 | Hardening | The artwork loader would open any `content://` URI | Only the app's own provider and icons |
| B3-1 | Bug | A library page that failed while reconnecting stayed broken | Reloads on reconnect |
| B3-2 | Bug | "Open Jellyfin" gave no feedback when the app wasn't installed | Fixed then; **obsolete** (button removed) |
| B3-3 | Bug | A connection flag was never reset | Reset on connect |

</details>

<details>
<summary><b>6.4 Review 2 (0.1.0): car features and Play readiness</b>: 9 security findings, 9 bugs</summary>

| ID | Severity | Finding | Resolution |
|---|---|---|---|
| S-1 | **Medium** | Any preinstalled app was trusted (many phones ship third-party apps on the system image) | Stricter `ClientAccess` rules ([5.1](#51-access-control)) |
| S-2 | **Medium** | The car could control another of the user's devices | Fixed then; **obsolete** since 0.3.0 |
| S-3 | Low | Loose matching of the Jellyfin app's split sessions | Fixed then; **obsolete** since 0.3.0 |
| S-4 | Low | Diagnostic logging could be enabled in release builds | Debug-only diagnostics, stripped by R8 |
| S-5 | Low | The server-supplied token wasn't validated before use in headers | Token format validated at sign-in |
| S-6 | Low | Artwork errors passed exception text (possibly the server address) to callers | Generic errors |
| S-7 | Low | No tapjacking protection | Touches ignored while obscured |
| S-8 | Hardening | Network policy relied on defaults | Explicit network security config |
| S-9 | Hardening | No verified Gradle wrapper; personal identifiers in tests | Checksum-pinned wrapper; identifiers scrubbed; `.gitignore` for secrets |
| B-1 | **Blocker** | Targeted API 34; Google Play requires 36 | compileSdk/targetSdk 36 and a toolchain update |
| B-2 | **Blocker** | Content under the system bars on Android 15+ (edge-to-edge) | Insets handled |
| B-3 | **Blocker** | Missing icons required by the Android Auto review | White icons for every item |
| B-4 | **Blocker** | Requests could exceed Android Auto's 10 s load limit | 9 s request cap |
| B-5 | Medium | The root tab limit defaulted to unlimited | Defaults to 4 |
| B-6 | Low | One malformed queue ID broke the whole lookup | Malformed IDs skipped |
| B-7 | Low | A crash path in the artwork provider | Moved inside error handling |
| B-8 | Low | Two install IDs could be created on first launch | Locked, synchronous creation |
| B-9 | Low | The release build wasn't shrunk; the version was hard-coded | R8 enabled; version from the build |

</details>

<details>
<summary><b>6.5 Review 1 (0.1.0): first version</b>: 11 security findings, 2 bugs</summary>

| ID | Severity | Finding | Resolution |
|---|---|---|---|
| 1 | **High** | Other users' Jellyfin sessions were exposed and controllable | Fixed then; **obsolete** since 0.3.0 |
| 2 | **Medium** | The password was shown in plain text while typing | Masked |
| 3 | **Medium** | Credentials could go over plain HTTP to public servers | HTTPS by default for public hosts, plus a warning |
| 4 | **Medium** | The artwork provider was open to every app | Caller checks ([5.1](#51-access-control)) |
| 5 | **Medium** | The caller allowlist trusted package names alone | UID-based checks, tightened by S-1 |
| 6 | **Medium** | Crafted IDs could reach other API endpoints (path traversal) | `requireSafeId` on every path segment |
| 7 | **Medium** | The token could be copied to a new phone by device transfer | Excluded from backup and transfer |
| 8 | **Medium** | Redirects could re-send the password or downgrade to HTTP | Redirects refused |
| 9 | Low | Sign-out didn't revoke the token on the server | Revoked |
| 10 | Low | The car could control a TV or browser at home | Fixed then; **obsolete** since 0.3.0 |
| 11 | Low | Unbounded responses; an HTTP client created per request | Size caps; one shared client |
| 12 | Bug | Concurrent artwork downloads could corrupt the cache | Unique temp files |
| 13 | Bug | Stale or fake playback state on the car screen | Fixed then; **obsolete** since 0.3.0 |

</details>

## 7. Verification

Latest results (0.6.1):

| Check | Result |
|---|---|
| Unit tests | **33 / 33** pass in debug and release: ID validation, LAN detection, stream URLs without credentials, token only to the signed-in server, paging limits and overflow, artwork decode bounds, sign-in checks, Tailscale address detection and connect decisions, GUID rebuilding for artwork IDs |
| Android lint | 0 errors in debug and release. Remaining warnings are only newer library versions and translatable-string notes. |
| Dependencies | **0 known vulnerabilities** in 130 shipped libraries (OSV, 2026-09-28). Details in [DEPENDENCIES.md](DEPENDENCIES.md). |
| Release APK | Not debuggable. No backup. Explicit network security config. targetSdk 36. Diagnostic code absent. Exported components as in [section 4](#4-attack-surface). |
| Repository | No secrets or personal data: scanned before the first push, and the full git history again before going public |
| CI on GitHub | Passing |
| Code scanning (CodeQL) | 5 alerts on the first scan, all one input, addressed in 0.6.1 ([6.0a](#60a-github-code-scanning-codeql-061)) |
| On the phone | Installs and runs. Streaming playback confirmed. The Songs crash (B4-1) and exit crash (B5-1) are fixed and re-tested. Phone screens checked by screenshot (dark mode). |
| Android Auto (Desktop Head Unit, 2026-09-29) | Listed in the app list with its icon. Opens on Now playing without autoplaying (MA-1). Home, Albums (sort folders, year-grouped grid with artwork and placeholder icons) and an album page browse correctly. A song plays from the car. Shuffle and repeat toggle. The connection log shows Android Auto and the Google app allowed. No crashes. |
| In a car (2026-09-29) | Playback and now-playing work, and steering-wheel buttons skip tracks. The app list needed **Unknown sources** (see [6.1](#61-after-review-4-050-and-051)). |

## 8. Open items

### 8.1 Not yet verified on a device

These need a phone, a car or the Desktop Head Unit. Unit tests can't cover them:
1. **Android Auto, remaining checks:** listing, browsing, playback, shuffle/repeat and the access rule
   are verified ([section 7](#7-verification)). Still to check in a real car: that Tentacle is listed now
   that Unknown sources is on, voice ("Hey Google, play … on Tentacle"), search, the Queue screen, and
   the Artists and Playlists tabs.
2. **The S4-1 access rule** doesn't block legitimate controllers: lock screen, notification, Bluetooth and
   headset buttons, and smartwatches.
3. **Phone features:** search, shuffle/repeat, Up next, streaming quality, resume after a restart, the
   MP3 fallback for formats the phone can't decode, and plays appearing in Jellyfin's Recently played.
4. **Light mode** and the keyboard not covering the sign-in form.
5. **Content loads within 10 s** on a slow connection with a large library (Android Auto rule DR-3).
6. **Tailscale away from home:** with "When the server can't be reached" on and Tailscale off, leave home
   Wi-Fi (or turn Wi-Fi off). Check that opening Tentacle, and connecting to the car, turn Tailscale on,
   and that lists and playback recover.

### 8.2 Accepted risks

| ID | Risk | Why it's accepted |
|---|---|---|
| R-1 | Plain HTTP is allowed app-wide | Most home servers use `http://` on the LAN, and Android can't allow cleartext by IP range. Public addresses default to HTTPS, with a warning otherwise. |
| R-2 | The token is stored unencrypted inside the app sandbox | Other apps can't read the sandbox. `EncryptedSharedPreferences` is deprecated, and Keystore wrapping adds little on an unrooted phone. |
| R-3 | Apps with notification access count as trusted | They can already control every media session on the phone (Android's design). This matches Google's reference app. |
| R-4 | Google apps are recognized via the preinstalled Play services certificate | Phones without Play services fall back to the preinstalled/Play-installed rule; Android Auto needs Play services anyway. |
| R-5 | No phone-side "Play X on Tentacle" voice command | An exported search activity would let any app start playback without the caller check. Voice works in Android Auto. |
| R-6 | Some libraries are held back: OkHttp 4.12.0, Compose BOM 2026.06.01, androidx.core < 1.19 | None has a known vulnerability. Newer versions need AGP 9 / compileSdk 37 or are major upgrades, each best done as its own change. Dependabot's open proposals (Kotlin, OkHttp 5, Gradle 9) are left for the owner to decide. |
| R-7 | Only JVM unit tests; no automated UI or device tests | Device behaviour is covered by the checklist in [8.1](#81-not-yet-verified-on-a-device). Instrumented tests would help in the long run. |
| R-8 | The phone UI and the playback service share one process | Normal for Android media apps; the known crash vectors are fixed. |
| R-9 | The Tailscale app isn't verified by its signing certificate before Tentacle sends it the connect request | The request carries no data and grants nothing. An impostor under Tailscale's package name would have to be sideloaded by the user, and would learn only that Tentacle wanted to connect. |

### 8.3 Before a public or store release

| Item | Status |
|---|---|
| Licence | **Done:** Apache-2.0, `NOTICE` credits Mark Joseph, name and logo reserved |
| Name and branding | **Done:** own name (Tentacle) and logo, and "unofficial, not affiliated with Jellyfin" stated. Keep the store listing the same, and don't use Jellyfin's logo. |
| Repository hardening | **Done:** public since 2026-09-29, with every control in [5.7](#57-repository-and-supply-chain) on |
| Open-source licence notices in the app | **To do:** an in-app licences screen, or a `THIRD_PARTY_LICENSES` file |
| Privacy policy and Data safety form | **To do:** fill in [PRIVACY.md](../PRIVACY.md), host it at a public URL, and complete Play's Data safety form |
| Signing | **To do:** an upload key kept out of the repository, Play App Signing, and an App Bundle |
| Android Auto review | **To do:** complete [8.1](#81-not-yet-verified-on-a-device) first; opting in adds a car-app-quality review to every release |
