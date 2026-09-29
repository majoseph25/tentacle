# Changelog

All notable changes to Tentacle. Versions follow `versionName` / `versionCode` in `app/build.gradle.kts`.

## Repository (2026-09-29)

- **Published to a private GitHub repository** with Android CI, Dependabot, issue and pull-request
  templates and a contributing guide.
- **CI actions pinned to commit SHAs.** Dependabot alerts and security updates are on.
- **`scripts/github-hardening.ps1`** turns on the remaining protections once the repository is public:
  private vulnerability reporting, secret scanning, push protection, and a ruleset protecting `main`.

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
