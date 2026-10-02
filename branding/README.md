# Tentacle branding

| File | What it is | Use it for |
|---|---|---|
| `tentacle-logo.png` | Full logo, emblem and "TENTACLE" wordmark (1254 × 1254, transparent) | Website, README, store graphics |
| `tentacle-emblem.png` | Emblem only (822 × 930, transparent) | Anywhere the name appears next to it |
| `play-store-icon-512.png` | 512 × 512 opaque PNG, emblem on black | Google Play's "App icon" (hi-res icon) |

The logo was created for the project owner with ChatGPT.

**Rights.** The Tentacle name and logo (everything in this folder, and the app icons generated from it)
are © 2026 Mark Joseph, all rights reserved. The Apache License that covers the code doesn't cover them
(see [`NOTICE`](../NOTICE)). Forks and derivative works must use their own name and icon.

## Colours

| Name | Hex | Where |
|---|---|---|
| Deep green | `#02341B` | Logo shading |
| Mid green | `#088C32` | Logo |
| Bright green | `#5EDA2F` | Logo |
| Lime | `#ACF753` | Logo highlights, suckers |
| Theme primary (light) | `#0A7A2E` | Buttons and accents in light mode (white text on it passes WCAG AA) |
| Theme primary (dark) | `#7EE24D` | Buttons and accents in dark mode |

The full light and dark palettes are in `app/src/main/java/app/tentacle/music/ui/Theme.kt`.

## App icons

These are generated from `tentacle-logo.png` and live under `app/src/main/res/`:
- **Launcher icon:** `mipmap-anydpi/ic_launcher.xml`, an adaptive icon.
  - Background: black (`#000000`), so the icon looks the same in the launcher, Android Auto and
    settings, and matches the app's dark screens.
  - Foreground: the emblem, sized to stay inside every launcher mask shape.
  - Monochrome layer: for Android 13+ themed icons.
  - Bitmaps at 5 densities in `mipmap-*dpi/`.
- **In-app logos:** `drawable-nodpi/logo_full.png` (sign-in screen) and `logo_emblem.png` (empty states).

Please don't use the Jellyfin logo alongside or instead of this one. Tentacle is not affiliated with the
Jellyfin project.
