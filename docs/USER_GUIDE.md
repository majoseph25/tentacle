# Tentacle user guide

Tentacle plays the music from your [Jellyfin](https://jellyfin.org) server on your phone and in Android
Auto. It's an unofficial app and isn't affiliated with the Jellyfin project.

## What you need

- **Server:** a Jellyfin server (tested with 10.11) with a music library, and an account on it.
- **Phone:** Android 8.0 or newer.
- **For the car:** Android Auto (built into Android 10 and later; an app on older versions) and a car or
  head unit that supports it.

## Getting started

1. Open **Tentacle** and enter:
   - **Jellyfin server:** your server's address, for example `192.168.1.10:8096` at home or
     `https://music.example.com`. Without `http://` or `https://`, Tentacle uses `http://` for home-network
     addresses and `https://` for everything else.
   - **Username** and **Password.** The password is only used to sign in and is never saved.
2. Tap **Sign in**. You'll land on the **Library**.

**Using an `http://` address that isn't on your home network?** Tentacle warns you first. Plain HTTP
isn't encrypted, so your login and music could be read on the way. Use your server's `https://` address
if it has one. Tailscale addresses count as private.

## The phone app

Three tabs along the bottom: **Now playing**, **Library** and **Settings**.

### Library

- **Search** (at the top): type at least two letters to find songs, artists and albums. Results update as
  you type.
  - Tapping a song plays it, followed by the rest of its album.
  - Tapping an artist or album opens it.
  - Back clears the search.
- **Home:**
  - Shuffle my library
  - All songs
  - Recently played
  - Recently added albums
- **Songs:** every song A–Z with letter headings. It loads more as you scroll. **Shuffle all songs** is at
  the top.
- **Albums:** choose a sort order (A–Z, By artist, Newest releases, Recently added) to see covers in a grid.
- **Artists:** A–Z. An artist shows **Play all**, **Shuffle** and their albums, newest first.
- **Playlists:** your playlists with song counts.

Inside an album, tracks are numbered, with durations and a heading per disc. **Play all** and **Shuffle**
sit at the top of albums, artists and playlists. Tap any song to play from there.

While you browse, the **mini player** above the tabs shows what's playing, with play/pause and next. Tap
it to open Now playing.

### Now playing

- Cover art, song, artist and album.
- A **seek bar**: drag to jump.
- **Previous / play-pause / next.** "Previous" restarts the song if you're a few seconds in.
- **Shuffle:** highlighted when on.
- **Repeat:** tap to cycle through off → repeat all → repeat this song.
- **Up next:** the songs that follow. Tap one to jump to it.

Playback carries on with the screen off and when you leave the app. Controls also appear:
- on the **lock screen** and in the **notification**,
- on **Bluetooth** headphones and speakers, and **headset buttons**,
- on **steering-wheel buttons** in Android Auto.

Music pauses if your headphones or Bluetooth disconnect.

### Settings

- **Account:** who you're signed in as, and **Sign out**. Signing out stops the music, forgets your login
  and saved queue, and ends the login on your server too.
- **Streaming quality:**
  - **Original:** the file as stored on the server.
  - **High / Medium / Low:** 320, 192 or 128 kbps MP3, to use less mobile data.
  - The setting applies to songs you start after changing it.
- **Clear cache:** deletes saved album art. It's downloaded again when needed.
- **Android Auto connections:** the apps that recently asked to connect to Tentacle, and whether they
  were allowed. See [Troubleshooting](#troubleshooting).
- **Version:** the version you're running.

## Android Auto

Connect your phone to the car and open **Tentacle** from Android Auto's app list.

- **Tabs:** Home, Albums, Artists, Playlists. All your songs are under **Home → All songs**, with an A–Z
  picker for large libraries.
- **Now playing:** cover, controls, **shuffle** and **repeat**, and the queue icon for **Up next**.
- **Search:** tap the search icon.
- **Voice:** say "Hey Google, play *Abbey Road* on Tentacle" (or an artist, song or playlist).
- **Steering-wheel buttons:** skip and play/pause.

Tentacle never starts playing by itself. Press play, or pick something, and it picks up where you left
off.

**Installed Tentacle yourself (not from Google Play)?** Android Auto hides such apps until you allow them:
1. Open Android Auto settings on your phone and tap **Version** about 10 times to unlock developer mode.
2. Open ⋮ → **Developer settings** and turn on **Unknown sources**.

## Troubleshooting

| Problem | Try |
|---|---|
| "Wrong username or password" | Check them in the Jellyfin web app. Usernames are case-sensitive on some servers. |
| "Can't reach the server" | Check the address and that your phone can reach the server (Wi-Fi vs mobile data). A home address like `192.168.x.x` only works at home unless you use a VPN such as Tailscale. |
| "Server redirected to …" | Enter the address it names. Tentacle doesn't follow redirects, for security. |
| A song won't play | Tentacle converts files your phone can't decode automatically. If it still fails, try a lower streaming quality, and check the file plays in the Jellyfin web app. |
| Tentacle isn't in Android Auto, or the car says it can't run | If you installed it yourself, enable **Unknown sources** (above), then reconnect to the car. Music can still play through the car's media controls while it's hidden, which is why it may look half-working. Still missing? Open **Settings → Android Auto connections** on the phone. An empty list means Android Auto never asked Tentacle for its library (it's still hidden). A "Rejected" entry shows which app was refused: please report it. |
| Nothing under Recently played | Jellyfin updates it as you listen. Play a song for more than a few seconds. |
| Album art missing | Tap **Clear cache** in Settings. Covers are downloaded again. |

## Privacy

Tentacle talks only to your Jellyfin server. It has no ads, analytics or tracking, and sends nothing to
the developer. See [PRIVACY.md](../PRIVACY.md).
