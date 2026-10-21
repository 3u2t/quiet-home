# Quiet Home

Quiet Home is a lightweight launcher and local screensaver for Android TV and Fire TV. It is built with Android framework APIs only: no ads, analytics, cloud account, subscription, Google Play Services, WebView or third-party runtime SDKs.

## Features

- Rounded streaming-app dock with remote-friendly focus states.
- Home, Continue Watching and Apps views; no games, Live TV or guide sections.
- Local rotating wallpapers and optional user-supplied photo/video backgrounds.
- The same local media can be used by the idle screensaver.
- Wi-Fi status, HDMI/input shortcuts and a matching settings interface.
- Optional Home-button accessibility helper and boot start for Fire OS devices that do not honor third-party launchers normally.
- Optional local media-session history. It does not synchronize streaming accounts.

The default dock contains package identifiers for Netflix, Disney+, Paramount+, YouTube, Prime Video and Jellyfin. Their icons are loaded at runtime from the installed applications; this repository contains no streaming-service logos or application assets.

## Build on Windows

Requirements: PowerShell 5.1 or newer and an internet connection for the one-time toolchain download.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\PrepareBuild.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\Build.ps1
```

The scripts download Android SDK Build Tools 35.0.0, Android platform 30 and Microsoft OpenJDK 17 from their official distribution servers. Downloads are checked against Google's published package checksum where available. The resulting APK is `dist\QuietHome.apk`.

The first local build creates a private signing key under `.tools\android-toolchain`. Keep that directory private and backed up: later APK updates must use the same key. Signing keys and passwords are ignored by Git.

GitHub Actions also builds a test APK, but its temporary signing key changes between runs. For an installation that can receive future updates, build locally and preserve your `.tools` directory.

Install with Android platform-tools already configured:

```powershell
adb connect TV-IP-ADDRESS:5555
adb install -r .\dist\QuietHome.apk
```

Do not enable the optional accessibility or notification-listener services until you have reviewed their source and understand their scope.

## Wallpaper licensing

The repository deliberately contains no personal videos and no unverified wallpaper downloads. The four bundled wallpapers are documented adaptations of Wikimedia Commons photographs:

- Three photographs by Kersti Nebelsiek are distributed under CC BY-SA 4.0.
- One photograph by Asio otus is distributed under CC BY-SA 3.0.

The exact title, author, source page, date, changes and SHA-256 hash for every file are in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md), [`app/assets/PHOTO-CREDITS.txt`](app/assets/PHOTO-CREDITS.txt) and [`app/assets/LOCAL-PHOTOS.json`](app/assets/LOCAL-PHOTOS.json). The cropped/recompressed adaptations remain under their respective CC BY-SA licenses; the GPL software license does not replace the photo licenses.

## Privacy

Quiet Home has no Internet permission. Wallpaper and history data remain on the TV. See [`PRIVACY.md`](PRIVACY.md) for the optional services and local-storage behavior.

## Original implementation and acknowledgements

This codebase is an independent implementation. Its visual direction was inspired by [Sva Launcher](https://github.com/clay-36/Sva-launcher), and its network/input presentation was inspired by [cleanyourtv](https://github.com/Gathin23/cleanyourtv). No source code, fonts, artwork or other assets from either project are included here.

Quiet Home source code is licensed under GPL-3.0-or-later. Sva Launcher is also GPLv3; cleanyourtv is MIT-licensed. See [`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Amazon, Fire TV, Android TV, Netflix, Disney+, Paramount+, YouTube, Prime Video and Jellyfin are names or trademarks of their respective owners. This project is independent and is not endorsed by them.

