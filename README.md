# Tentacle

**Your Jellyfin music, on your phone and natively in Android Auto.**

Tentacle is an unofficial music player for [Jellyfin](https://jellyfin.org) servers. It streams from your
own server and plays the music itself, so it works in the car without any other app running. It isn't
affiliated with the Jellyfin project.

## Features

- **Phone app:**
  - **Now playing:** artwork, seek bar, previous/play/next, shuffle, repeat, and Up next (tap a song to jump to it).
  - **Library:** tabs for Home, **Songs** (your whole library A–Z, loaded as you scroll), Albums, Artists and
    Playlists. Albums can be sorted A–Z, by artist, by newest release or by recently added. Album tracks are
    numbered with durations and a heading per disc.
  - **Search** at the top of the Library: songs, artists and albums as you type. A song plays followed by
    the rest of its album.
  - **Mini player** above the tabs while you browse.
- **Android Auto:** the same library as tabs (Home, Albums, Artists, Playlists; all songs are under Home),
  now playing with shuffle and repeat, Up next, search, and voice ("Hey Google, play … on Tentacle").
- **Everywhere else:** lock-screen and notification controls, Bluetooth and steering-wheel buttons,
  pause when headphones disconnect, and resuming where you left off.
- **Streaming quality:** Original (no transcoding), or 320, 192 or 128 kbps MP3 to save mobile data.
  Files the phone can't decode are transcoded automatically.
- **Plays nicely with your server:** reports playback, so Jellyfin's Recently played, play counts and
  dashboard stay accurate.

## Build and install

1. Install [Android Studio](https://developer.android.com/studio). Set the Gradle JDK to 17 or 21
   (**Settings > Build, Execution, Deployment > Build Tools > Gradle**).
2. **File > Open** this folder, let Gradle sync, plug in your phone (USB debugging on) and press **Run**.
3. Open **Tentacle**, sign in to your Jellyfin server, and play something.

From the command line (the wrapper checks Gradle's SHA-256 before running it):

```bash
./gradlew testDebugUnitTest lintRelease assembleRelease
```

- **Versions:** targets Android 16 (API 36), minimum Android 8.0 (API 26).
- **Release builds:** shrunk with R8, not debuggable, and contain no diagnostic logging.
- **Google Play:** build an App Bundle with `./gradlew bundleRelease` and sign it with your upload key.
  Never commit the key; `.gitignore` covers keystores.

## Android Auto with a self-built app

Android Auto only lists apps from Google Play unless you allow others:
1. In Android Auto settings, tap **Version** about 10 times to enable developer mode.
2. Open ⋮ > **Developer settings** and turn on **Unknown sources**.

To test without a car, use the [Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu).
If Tentacle doesn't show up in the car, open **Settings → Android Auto connections** in the phone app. It
lists which apps asked to connect and whether they were allowed.

## How it works

```
Phone app ─┐
Android Auto ─┼──> PlaybackService (Media3 session + ExoPlayer) ──stream──> your Jellyfin server
Bluetooth, lock screen ─┘                    │
                                             └──> playback reports (Recently played, play counts)
```

- **Browse tree:** `Library` builds the tree that both the phone app and Android Auto show.
- **Starting playback:** tapping something sends only its ID. The service turns that into stream URLs for
  the signed-in server, so no caller can make the player fetch an arbitrary address.
- **Streams:** stream URLs contain no credentials. An OkHttp interceptor adds the access token as a
  header, and only on requests to your server.

## Known limits

- **Android Auto lists:** they have no paging, so lists in the car are capped at 200 entries, and large
  libraries get an A–Z picker. The phone app pages through everything.
- **Queue length:** starting playback builds a queue of up to 150 songs around your choice.
- **Plain HTTP:** traffic is unencrypted if your server address is `http://`. Use HTTPS when the server is
  reachable from outside your network. The app warns before signing in to a public `http://` address.
  Tailscale addresses count as private.
- **Voice from the phone:** "Hey Google, play … on Tentacle" works in the car, but not from the phone
  outside Android Auto.

## License

Copyright 2026 Mark Joseph. Licensed under the [Apache License 2.0](LICENSE).

You can use, modify and redistribute Tentacle, including in forks, on these conditions:
- **Keep the notices.** Keep the copyright notices, the [`NOTICE`](NOTICE) file crediting the original
  author, and the licence.
- **Mark your changes.** State the changes you've made.

The **Tentacle name and logo aren't covered by the licence**; all rights are reserved. A fork must use its
own name and icon.

## Documentation

- [User guide](docs/USER_GUIDE.md): using the app on the phone and in the car
- [Architecture](docs/ARCHITECTURE.md): how it's built, data flows, security model
- [Development](docs/DEVELOPMENT.md): building, testing, debugging and releasing
- [Dependencies](docs/DEPENDENCIES.md): every tool, plugin and library, with versions and licences
- [Changelog](CHANGELOG.md): version history
- [Contributing](CONTRIBUTING.md): how to report bugs and send changes

## Security and privacy

- [SECURITY_ASSESSMENT.md](SECURITY_ASSESSMENT.md): security reviews
- [SECURITY.md](SECURITY.md): how to report a vulnerability
- [PRIVACY.md](PRIVACY.md): privacy policy (draft)

The project started as "Jellyfin Auto", a remote control for the official Jellyfin app. Version 0.3.0
replaced that with a standalone player.
