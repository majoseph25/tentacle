# Privacy policy

_Draft. Review it, add the publisher name and contact details, and host it at a public URL before
linking it from Google Play._

**Effective date:** [DATE]  ·  **Publisher:** [NAME / CONTACT EMAIL]

Tentacle ("the app") is an unofficial music player that streams from your own Jellyfin server, on your
phone and in Android Auto. It is not affiliated with the Jellyfin project.

## What the app handles, and where it goes

| Data | Why | Where it goes | Stored |
|---|---|---|---|
| Your Jellyfin server address and username | To connect to your server | Only your own Jellyfin server | On your phone, in app-private storage |
| Your Jellyfin password | To sign in once | Only your own Jellyfin server | **Never stored** |
| Access token issued by your server | To stay signed in and to stream | Only your own Jellyfin server | On your phone, in app-private storage, until you sign out |
| Your library and music (titles, artists, album art, audio) | To browse and play it | Streamed from your server to your phone and car | Album art is cached on your phone and cleared on sign-out or with "Clear cache". Audio isn't saved. |
| What you play (song, position, paused or playing) | So your server's Recently played, play counts and dashboard stay accurate | Only your own Jellyfin server | Your current queue and position, on your phone, so playback can resume. Cleared on sign-out. |
| Apps that asked to browse your library (package names and times) | So you can see why Android Auto connected or not | Nowhere; shown in Settings only | On your phone, last 8 entries |
| Whether a VPN is connected (optional Tailscale feature) | To decide whether to ask the Tailscale app to connect or disconnect | Nowhere | Only whether Tentacle turned Tailscale on (so it can turn it off again), on your phone |

The app talks **only to the Jellyfin server you enter**. It has no analytics, advertising,
crash-reporting or tracking of any kind, and sends nothing to the developer or any third party.

If you turn on the optional Tailscale feature, the app also sends the Tailscale app on your phone
requests to connect and disconnect. The requests contain no data, and the app never reads your
Tailscale account, settings or traffic.

## Sharing

None. The developer never receives your data.

## Security

The password is never stored. The access token is kept in app-private storage, excluded from cloud
backup and device-to-device transfer, and revoked on your server when you sign out. Use an `https://`
server address when your server is reachable from outside your home network; the app warns you before
signing in to an unencrypted public address.

## Your choices

- **Sign out** stops playback, removes the stored token, cached artwork and saved queue, and revokes the
  token on your server.
- **Streaming quality** lets you choose how much mobile data streaming uses.
- **Clear cache** deletes cached album art.
- Uninstalling the app deletes everything it stored.

## Children

The app is not directed at children and collects no personal data from anyone.

## Changes

Changes to this policy are published at this URL with a new effective date.

## Contact

[CONTACT EMAIL]
