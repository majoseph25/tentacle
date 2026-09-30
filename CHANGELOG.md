# Changelog

All notable changes to Tentacle. Versions follow `versionName` / `versionCode` in `app/build.gradle.kts`.

## 0.6.2 (build 9) — 2026-09-30

### Dependencies
- **Kotlin 2.3.21 and kotlinx-coroutines 1.11.0.**
  - **Why not Kotlin 2.4.20:** Dependabot proposed Kotlin 2.4.20 with the coroutines update. The build
    passed, but R8 (the release build's shrinker, bundled with AGP 8.13) can't read Kotlin 2.4's
    metadata; Kotlin 2.4 needs R8 9.1.29, which only comes with AGP 9. Kotlin 2.3.21 is the newest
    release that supports AGP 8.13, and the release build is clean with it.
  - **Dependabot** now skips Kotlin 2.4 until the move to AGP 9.
  - **No known vulnerabilities:** all 130 shipped libraries were re-checked against OSV.

### Security
- **Code scanning alerts #1–#5, second attempt.** 0.6.1's fix was right, but CodeQL couldn't see it:
  the ID check was called through Kotlin's `let`, which CodeQL treats as passing the original text
  straight through. The check is now called directly.

## 0.6.1 (build 8) — 2026-09-29

### Changed
- **Tailscale connects only when it's needed, and turns off again.** In 0.6.0, **Always** connected
  Tailscale every time the app opened, even at home, and nothing turned it off.
  - **The setting** is now a single switch: **Connect when your server can't be reached**. A saved
    "Always" becomes this.
  - **Turning it off:** Tentacle turns Tailscale off when you're done (you close Tentacle, or the music
    stops and the car disconnects), and when you switch the setting off, but only if Tentacle turned it
    on. A Tailscale connection you started yourself is left alone.
  - **Disconnect button:** Settings shows **Disconnect** while a VPN is on.
- **Albums and Artists list every album and artist A–Z** on the phone, loading more as you scroll,
  instead of an A–Z letter picker. The Albums tab opens straight onto the album grid, with **A–Z**,
  **By artist**, **Newest releases** and **Recently added** as buttons along the top. The car keeps the
  letter picker for large libraries, since Android Auto can't load long lists a page at a time.

### Security
- **GitHub code scanning.** CodeQL reported 5 alerts (1 server-side request forgery, 4 path injection),
  all from one input: the item ID in an artwork request from another app. None was exploitable, because
  the ID was already restricted to letters, digits and dashes and only trusted apps can make the request.
  The ID is now accepted only as a Jellyfin GUID and rebuilt from its numeric value, so none of the
  requesting app's text reaches the cache file name or the server URL.

### Fixed
- **Stopping Tailscale mid-attempt.** Stopping a Tailscale attempt while one was under way (for example
  switching the setting off) could have failed a library request that was waiting for it.

## 0.6.0 (build 7) — 2026-09-29

### Added
- **Tailscale support (optional).** For servers you reach through Tailscale away from home.
  - **Settings → Remote access with Tailscale:** choose **Off** (the default), **When the server can't be
    reached** (recommended) or **Always**.
  - **When it connects:** Tentacle asks the Tailscale app to connect when you open Tentacle, when your car
    connects, or when a list or song can't load. Once Tailscale is up, lists reload and the song
    resumes.
  - **Buttons:** **Connect now** and **Open Tailscale**, plus a VPN status line.
  - **Sign-in:** offers **Connect Tailscale and try again** when the server can't be reached.
  - **Scope:** Tentacle only asks Tailscale to connect. It never reads your Tailscale account or traffic,
    and never turns Tailscale off. New permission: `ACCESS_NETWORK_STATE`, to see whether a VPN is up.
  - **Tested** on a Pixel 8a with Android 17 and Tailscale 1.102.4. Tentacle turned Tailscale on, and
    the library loaded through it.

### Fixed
- **Garbled characters in two docs.** The architecture doc's diagrams and a line in `SECURITY.md` had
  garbled characters from a documentation edit. Both are repaired.

## 0.5.1 (build 6) — 2026-09-29

### Fixed
- **"All songs" in Android Auto.** On the car's Home screen, **All songs** was drawn as a large tile with
  its title hidden below the fold. It's now a normal list row.

### Tested
- **Android Auto,** on the Desktop Head Unit:
  - Tentacle is listed, with its icon, and opens without autoplaying.
  - Browsing, album artwork and playback work, and shuffle and repeat toggle.
  - Android Auto passes the access check.
- **In a car:** playback and steering-wheel controls work. Tentacle was missing from the car's app list
  because Android Auto hides apps not installed from Google Play until **Unknown sources** is turned on.
  The user guide's troubleshooting table now explains this.

## Repository (2026-09-29)

- **Published on GitHub,** public since 2026-09-29 and tagged `v0.5.0`. Includes Android CI, Dependabot,
  issue and pull-request templates and a contributing guide.
- **Repository hardening,** applied by `scripts/github-hardening.ps1`:
  - CI actions pinned to commit SHAs,
  - Dependabot alerts and security updates,
  - private vulnerability reporting,
  - secret scanning and push protection,
  - CodeQL code scanning,
  - a ruleset protecting `main` (pull requests plus passing CI).
- **History checked before going public:** the full git history was scanned for secrets and personal
  data before the repository went public.
- **Documentation reorganized.**
  - The security assessment moved to `docs/` and is now organized by topic: threat model, controls,
    findings and open items.
  - The README is a project front page, with a map of all the documents.
  - `docs/README.md` points each kind of reader to the right guide.
  - The credits note that Tentacle was built with Claude Code.

## 0.5.0 (build 5) — 2026-09-28

### Changed
- **New logo.** Tentacle has its own logo. The emblem is the launcher icon (adaptive, round, and themed
  on Android 13+), and the full logo is on the sign-in screen.
- **Green interface.** A green light and dark theme taken from the logo replaces the wallpaper-based
  colours. The system status and navigation bars are now transparent, so the green runs behind them.

### Added
- **Licence.** Apache License 2.0 (`LICENSE`), plus a `NOTICE` file crediting Mark Joseph as the
  original author. Every source file carries a copyright/SPDX header. The Tentacle name and logo are
  reserved (not licensed). The credit also appears in Settings.

### Fixed
- **Crash when leaving the app.** The app crashed every time you left it. The reconnect logic added in
  0.4.0 released the player connection a second time, after Media3 had already released it. Found on
  the phone. Checked by leaving and returning several times without a crash.

## 0.4.0 (build 4) — 2026-09-28

### Added
- **Search** at the top of the Library: songs, artists and albums as you type. A song plays with the rest
  of its album. Android Auto's search uses the same code.

### Security (fourth review)
- **Untrusted apps are refused.** Apps that aren't trusted can no longer connect to the player. Before
  this, any installed app could read the current song and queue and control playback.
- **HTTP(S) only.** The player can only open HTTP(S) streams, not local files or content URIs.
- **Queue requests capped** at 500 items.
- **No failing reports after sign-out.** Playback reports are no longer sent once you've signed out.

### Fixed
- **Songs tab crash.** Opening the Songs tab crashed the app, because a page returned more items than
  requested.
- **Page overflow.** A huge page number could overflow into a negative server index.
- **Reconnecting.** The phone app now reconnects if the system stops the playback service.

## 0.3.0 (build 3) — 2026-09-28

### Changed
- **Renamed to Tentacle.** The app ID is now `app.tentacle.music`, and the app has a new icon.
- **Standalone player.** Tentacle now streams and plays music itself (Media3/ExoPlayer) instead of
  remote-controlling the official Jellyfin app. It works on the phone and natively in Android Auto, with
  lock-screen, notification, Bluetooth and steering-wheel controls.

### Added
- **Songs tab:** every song A–Z, loaded as you scroll. In the car, it's under Home → All songs with an
  A–Z picker.
- **Streaming quality setting:** Original, 320, 192 or 128 kbps.
- **Automatic MP3 fallback** for files the phone can't decode.
- **Playback reporting** to Jellyfin: Recently played, play counts, the dashboard.
- **Resume where you left off** after a restart.
- **Mini player** while browsing.

### Removed
- Remote control of the official Jellyfin app. The last version with it (0.2.0) is backed up locally in
  `backups/`.

## 0.2.0 (build 2) — 2026-09-28

### Added
- **Full phone app** (Jetpack Compose): Now playing, Library and Settings, mirroring the car.
- **Shuffle and repeat, Up next,** and four library tabs with sorting, grids and section headers.
- **Android Auto connection log** in Settings.
- **Version number** in the app.

### Security (second and third reviews)
- **Play and Android Auto requirements:** targets Android 16 (API 36), with edge-to-edge insets and
  icons for every item.
- **Stricter caller checks** (UAMP rules), plus decompression-bomb protection for artwork.
- **Hardened release build:** R8, not debuggable, no diagnostic logging, and a Gradle wrapper pinned by
  checksum.

## 0.1.0 (build 1) — 2026-09-27

- **First version, "Jellyfin Auto Companion":** an Android Auto companion that mirrored and controlled
  the Jellyfin app's playback through the Jellyfin Sessions API.
- **First security review:** 11 security findings and 2 bugs fixed.
