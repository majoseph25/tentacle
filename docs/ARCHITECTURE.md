# Architecture

How Tentacle is put together, how data moves through it, and why it's built the way it is.

## Overview

Tentacle is a single-module Android app (`app/`, package `app.tentacle.music`) with two faces on one engine:

```
                ┌────────────────── Tentacle process ──────────────────┐
 Phone UI ──────┤ MainActivity (Compose)                               │
 (Now playing,  │   └─ PlayerConnection ── Media3 MediaBrowser ─┐      │
  Library,      │                                               ▼      │
  Settings)     │                          ┌─────────────── PlaybackService ─────────────┐
                │                          │ MediaLibrarySession  ◄── access control      │
 Android Auto ──┼── (binds to service) ──► │   callback            (ClientAccess)        │
 Assistant      │                          │ ExoPlayer ── OkHttp ──► stream  ─────────────┼──► Jellyfin
 Lock screen,   │                          │ Library (browse tree) ──► REST API ──────────┼──► server
 notification,  │                          │ PlaybackReporter ──► playback reports ───────┼──►
 Bluetooth ─────┼── (system media session) │                                              │
                │                          └──────────────────────────────────────────────┘
                │  ArtworkProvider (content://) ── cached album art ◄── REST API
                └──────────────────────────────────────────────────────┘
```

- **One player.** `PlaybackService` owns the only player. The phone UI, Android Auto, the lock screen,
  the notification and Bluetooth are all controllers of the same session, so they always agree on what's
  playing.
- **One browse tree.** `Library` builds the tree that both the phone app and Android Auto show. The phone
  adds a Songs tab and paging.
- **Direct streaming.** Audio comes straight from the user's Jellyfin server. No other service is involved.

## Components

| Component | Kind | Exported | Role |
|---|---|---|---|
| `ui.MainActivity` | Activity (launcher) | yes (launcher) | Hosts the Compose UI. Protected against tapjacking (`filterTouchesWhenObscured`). |
| `PlaybackService` | `MediaLibraryService` (foreground, `mediaPlayback`) | yes (required by Android Auto) | The player and media session. Serves the browse tree, search and playback to trusted callers. |
| `ArtworkProvider` | `ContentProvider` | yes (required by Android Auto) | Serves album art as `content://app.tentacle.music.artwork/<itemId>`, downloaded once and cached. Read-only; checks every caller. |

## Source layout

```
app/src/main/java/app/tentacle/music/
├── PlaybackService.kt   Media3 MediaLibraryService: player, session callback, access control, queue resolution
├── PlaybackReporter.kt  Reports start/progress/stop to the server; saves the queue for resuming
├── Library.kt           Builds the browse tree and resolves media ids into tracks (queue building)
├── MediaItems.kt        Converts browse rows and Jellyfin items into Media3 MediaItems (stream URL, artwork, hints)
├── JellyfinApi.kt       REST client (auth, library, images, reports), stream URLs, auth interceptor, URL rules
├── ClientAccess.kt      Decides which apps are trusted (browse / choose what plays / read artwork)
├── ArtworkProvider.kt   content:// artwork for Android Auto, the notification and the phone UI
├── Account.kt           Sign-in (preflight checks, HTTP warning), sign-out and token revocation
├── Prefs.kt             App-private settings: server, token, quality, resume state, connection log
├── Models.kt            BrowseEntry, StreamQuality, ContentStyle, IconKind, id validation
├── Paging.kt            Overflow-safe paging helpers (Media3 rejects oversized pages)
├── AppScope.kt          Process-lifetime coroutine scope for fire-and-forget server calls
└── ui/
    ├── MainActivity.kt      Scaffold, bottom navigation, mini player, sign-in gate
    ├── PlayerConnection.kt  Media3 MediaBrowser wrapper exposing player state as a StateFlow
    ├── NowPlayingScreen.kt  Artwork, details, seek bar, controls, shuffle/repeat, Up next
    ├── LibraryScreen.kt     Search, tabs, Songs (paged), grids, section headers, drill-down
    ├── SettingsScreen.kt    Account, streaming quality, cache, Android Auto connections, version
    ├── SignInScreen.kt      Server/username/password form
    ├── Artwork.kt           Bounded, cached artwork loading for Compose
    └── Theme.kt             Material 3 green theme from the logo (light and dark, no wallpaper colours)
app/src/main/res/
├── drawable/            Launcher icon, notification icon, Material icons (white vectors)
├── xml/automotive_app_desc.xml     Declares the app as an Android Auto media app
├── xml/network_security_config.xml System CAs only; cleartext allowed for LAN servers
├── xml/data_extraction_rules.xml   Excludes all data from cloud backup and device transfer
├── values/, values-night/          App name, launch theme (light/dark)
app/src/test/java/app/tentacle/music/  JVM unit tests (security rules, streaming, paging, UI logic)
```

## Key flows

### Signing in

1. `SignInScreen` → `Account.signIn(server, user, password)`.
2. `Account.preflight` runs checks that need no network:
   - the fields are filled in and the address parses,
   - an address without a scheme gets `http://` for LAN hosts and `https://` otherwise,
   - plain `http://` to a public host returns `InsecureWarning`, and the user must confirm.
3. `JellyfinApi.authenticate` calls `POST /Users/AuthenticateByName`. The response's token must match
   `[A-Za-z0-9._~-]{16,512}`, and the user ID must be a safe ID.
4. `Prefs` stores the server, user ID, user name and token. The password is never stored. If the server
   changed, the artwork cache is cleared. Any previous token is revoked on the server.

### Browsing (phone and car)

1. A controller asks `PlaybackService` for children of a media ID.
2. `onGetChildren` checks the caller (see [Security model](#security-model)).
3. `Library.children(parentId, page, pageSize, withSongsTab)` calls the Jellyfin API and returns
   `BrowseEntry` rows. Rows carry content-style hints (grid or list), section headers and a fallback icon.
4. `MediaItems.browse` turns rows into Media3 `MediaItem`s. Artwork points at `ArtworkProvider`, or at a
   built-in `android.resource://` icon.
5. The result is trimmed to the requested page. Media3 treats an oversized page as a fatal error.

The phone app's Songs tab is paged on the server (`StartIndex`/`Limit`, 100 per page). Android Auto
doesn't page: it gets whole lists capped at 200, or A–Z pickers for large libraries.

### Media ID scheme

| ID | Meaning |
|---|---|
| `root` | The tabs: `home`, `albums`, `artists`, `playlists`, plus `songs` for the phone app. |
| `songs` | All songs A–Z. Paged for the phone; a list or A–Z picker in the car. |
| `sort:albums:<name\|artist\|year\|added>` | Albums in one sort order, with section headers. |
| `az:<albums\|artists\|songs>:<letter>` | One letter of an A–Z picker (`#` means non-letters). |
| `album:ID`, `artist:ID`, `playlist:ID` | Containers. |
| `track:ID\|<ctx>` | A track, plus the queue it belongs to (`album:ID`, `playlist:ID`, `artist:ID`, `songs:<index>`, `songsaz:<letter>`, `recent:all`). |
| `ctx:<ctx>`, `shuffle:<ctx>` | "Play all" / "Shuffle" rows. `ctx` may also be `library:all` (150 random songs). |
| `<raw Jellyfin item id>` | A queue item (used for resumption and by the player itself). |

Every ID segment that reaches a URL path goes through `requireSafeId` (`[A-Za-z0-9-]{1,64}`), which
rules out path traversal.

### Playing

1. A controller calls `setMediaItem(MediaItem(mediaId))`. The phone app does this in
   `PlayerConnection.play`, and Android Auto does it via "play from media ID" or voice search.
2. `onSetMediaItems` resolves it:
   - **a browse ID:** `Library.resolvePlayback` builds a queue of up to 150 tracks around the chosen one,
     with 10 before it so "previous" works,
   - **a voice query:** `Library.searchPlay`,
   - **a list of raw item IDs:** `JellyfinApi.itemsByIds`, capped at 500.
3. `MediaItems.track` builds each queue item: metadata, artwork and a **stream URL with no credentials**.
   - Original quality: `/Audio/{id}/stream?static=true`.
   - Transcoded: `/Audio/{id}/stream.mp3?audioCodec=mp3&audioBitRate=…&static=false`.
4. ExoPlayer streams through `OkHttpDataSource`, which uses `JellyfinApi.streamingClient`. Its
   `AuthInterceptor` adds the `Authorization` header only when the scheme, host, port and base path match
   the signed-in server. HTTP(S) is the only data source.
5. If the phone can't decode a file (a decoder or container error), `PlaybackService` swaps that item for
   a transcoded MP3 stream once and carries on from the same position.

Media3 does not pass a `MediaItem`'s URI between processes, and every queue item is rebuilt from its ID.
So no controller can make the player fetch an arbitrary URL.

### Reporting playback

`PlaybackReporter` listens to the player:
- **Start:** `POST /Sessions/Playing` when a track starts. The first time, it also sends
  `POST /Sessions/Capabilities/Full`, which lists the app as an audio player in the server's dashboard.
- **Progress:** `POST /Sessions/Playing/Progress` every 10 s while playing, and on pause, resume and seek.
- **Stopped:** `POST /Sessions/Playing/Stopped` on a track change, the end of the queue, or service
  shutdown. A track that played to the end is reported at its full length, so the server marks it played.
- **Resume state:** the queue's item IDs, the index and the position are saved to `Prefs` on every
  change. That's what `onPlaybackResumption` restores when you press play in the car or on a headset
  after a restart.

Reports are best-effort and run in `AppScope`, so they never block or interrupt playback.

### Search

The phone's search bar and Android Auto's search both go through `onSearch` and `onGetSearchResult`.
They call `Library.search`: a `SearchTerm` query for songs, artists and albums, capped at 60 results and
a 100-character query. Songs play in the context of their album. The phone debounces typing by 350 ms.

## Security model

| Caller | How it's identified | Allowed to |
|---|---|---|
| Tentacle itself (UI, notification) | Own UID | Everything |
| Trusted apps (see `ClientAccess`) | System-verified UID | Browse, search, choose what plays, read artwork, control playback |
| The system's combined legacy controller (Android 8 lock screen, Bluetooth and headset buttons) | Media3 legacy package name | Play/pause/skip/seek on what's already playing |
| Anything else | | Refused (logged under Settings → Android Auto connections) |

`ClientAccess` trusts these, following Google's reference app, UAMP:
- this app and the system,
- platform-signed apps (System UI),
- holders of `MEDIA_CONTENT_CONTROL` (for example Bluetooth),
- apps signed like the preinstalled Google Play services (Google's own apps: Android Auto, Assistant),
- apps the user has granted notification access,
- Android Auto and Assistant by name, only if preinstalled or installed from Play.

Being preinstalled isn't enough on its own, because many phones ship third-party apps on the system image.

**Other protections:**
- **Network:**
  - no redirects are followed (they could re-send the password or token elsewhere, or downgrade HTTPS),
  - HTTPS trusts system CAs only,
  - requests have a 9 s time limit, streams have none.
- **Response sizes:** capped at 16 MB (images 5 MB). The phone decodes art at most 1024 px, and refuses
  images larger than 20,000 px.
- **Storage:**
  - the token is kept in app-private preferences and excluded from backup and device transfer,
  - sign-out revokes it on the server and clears the art cache and resume state.
- **Logging:** diagnostic logging exists only in debug builds; release builds log only error types.

The full security review history is in [SECURITY_ASSESSMENT.md](../SECURITY_ASSESSMENT.md).

## Jellyfin API endpoints

| Method | Endpoint | Used for |
|---|---|---|
| POST | `/Users/AuthenticateByName` | Sign-in |
| POST | `/Sessions/Logout` | Revoking a token on sign-out or re-sign-in |
| GET | `/Items` | Albums, songs, recently played or added, search, items by ID |
| GET | `/Artists/AlbumArtists` | Artists tab |
| GET | `/Playlists/{id}/Items` | Playlist tracks |
| GET | `/Items/{id}/Images/Primary` | Album art (≤ 512 px, JPEG) |
| GET | `/Audio/{id}/stream` (`static=true`) | Original-quality streaming |
| GET | `/Audio/{id}/stream.mp3` | Transcoded streaming |
| POST | `/Sessions/Capabilities/Full` | Registering as an audio player |
| POST | `/Sessions/Playing`, `/Playing/Progress`, `/Playing/Stopped` | Playback reporting |

Every request carries
`Authorization: MediaBrowser Client="Tentacle", Device="Android", DeviceId="<random per install>", Version="<app version>", Token="<token>"`.
The token is left out when signing in.

## Threading

- **Main thread:** Media3 callbacks, player access, the Compose UI.
- **`Dispatchers.IO`:** all network and disk work, via `scope.future(Dispatchers.IO)` in the session
  callback. Coroutines are bridged to Guava futures with `kotlinx-coroutines-guava`.
- **`AppScope`:** fire-and-forget calls that must outlive a screen or the service (token revocation,
  final playback reports).
- **Binder threads:** `ArtworkProvider.openFile` runs there and downloads synchronously, within the 9 s
  request limit.

## Android Auto specifics

- **Declaration:** `automotive_app_desc.xml` declares the app as a media app. Android Auto binds via the
  `android.media.browse.MediaBrowserService` intent filter, which Media3 bridges to `MediaLibraryService`.
- **Root tabs:**
  - the limit follows the `KEY_ROOT_CHILDREN_LIMIT` hint, defaulting to 4,
  - only browsable items appear as tabs,
  - each tab has a white icon.
- **Content styles:** grid or list and section headers are set in `MediaMetadata` extras with the
  `android.media.browse.CONTENT_STYLE_*` keys.
- **Artwork:** every item has artwork, as required by car-app-quality rule MC-1. Items without a cover
  fall back to a built-in `android.resource://` vector icon.
- **Shuffle and repeat:** custom session commands shown as Media3 `CommandButton`s (media button
  preferences) in the car and the notification.
- **Playback:** nothing autoplays (rule MA-1). Content loads within the 10 s limit (rule DR-3) thanks to
  the 9 s request cap.

## Known limits

- **Car lists:** Android Auto can't page, so car lists are capped at 200, and large libraries get A–Z
  pickers.
- **Queue length:** queues started from the library hold at most 150 songs.
- **Transcoding:** only MP3 (fallback and reduced-quality streams).
- **Offline:** no downloads or offline playback.
- **Voice from the phone:** there's no phone-side "Play X on Tentacle" intent, deliberately: an exported
  search activity would let any app start playback without the access check. Voice works in the car.
