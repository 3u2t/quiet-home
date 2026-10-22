# Quiet Home

Quiet Home is a fast, minimal launcher and local screensaver for Android TV and Fire TV. It uses Android framework APIs only: no ads, analytics, cloud account, subscription, Google Play Services, WebView, or third-party runtime SDKs.

## Features

- Rounded streaming-app dock with remote-friendly focus states.
- Home, Continue Watching, and Apps views; no games, Live TV, or guide sections.
- User-supplied local photo and video backgrounds.
- The same local media can be used by the idle screensaver.
- Wi-Fi status, HDMI/input shortcuts, and a matching settings interface.
- Optional Home-button accessibility helper and boot start for Fire OS devices that do not honor third-party launchers normally.
- Optional local media-session history. It does not synchronize streaming accounts.
- Optional self-hosted YouTube Continue Watching bridge for unfinished videos.
- Photo and video backgrounds are fitted to the screen without cropping their content.

The default dock contains package identifiers for Netflix, Disney+, Paramount+, YouTube, Prime Video, and Jellyfin. Their icons are loaded at runtime from installed applications; this repository contains no streaming-service logos or application assets.

## Privacy-first public edition

This repository contains no bundled photographs, videos, location-specific media, screenshots, device backups, IP addresses, signing keys, or TV diagnostics. Until a user supplies local media on their own TV, Quiet Home uses its neutral built-in color background. Personal screensaver media is never included in the source tree or APK.

Quiet Home 2.0 requests Internet access only for the optional, explicitly configured YouTube bridge and its HTTPS thumbnail URLs. The YouTube cookie remains on the user's private server and is never stored in the APK or Android app. Launcher preferences, optional media history, and user-supplied background choices remain on the TV. See [`PRIVACY.md`](PRIVACY.md).

## Optional YouTube Continue Watching

The self-hosted bridge under [`youtube-bridge/`](youtube-bridge/) can show unfinished YouTube videos in Continue Watching. It runs on a Raspberry Pi or another trusted LAN host, keeps the YouTube session cookie there, and exposes only sanitized metadata through a key-protected endpoint.

After installing Quiet Home 2.0, follow [`youtube-bridge/README.md`](youtube-bridge/README.md). The provided PowerShell setup script transfers secrets directly over SSH and never writes them into this repository or the APK.

## Build on Windows

Requirements: PowerShell 5.1 or newer and an internet connection for the one-time toolchain download.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\PrepareBuild.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\Build.ps1
```

The resulting APK is `dist\QuietHome.apk`. The first local build creates a private signing key under `.tools\android-toolchain`. Keep that directory private and backed up: future APK updates must use the same key. Signing keys and passwords are ignored by Git.

GitHub Actions also builds a test APK, but its temporary signing key changes between runs. Use a locally signed APK and preserve `.tools` for an installation that can receive future updates.

## License

Quiet Home is source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE).

You may use, study, modify, contribute to, and redistribute Quiet Home for noncommercial purposes. Commercial use, selling copies, paid redistribution, or using it with an anticipated commercial application requires separate written permission from the copyright holder. Redistributions must include the license and every `Required Notice:` line.

Because commercial use is restricted, this project is not OSI-approved open-source software. See [`CONTRIBUTING.md`](CONTRIBUTING.md) before submitting changes.

## Original implementation and acknowledgements

This codebase is an independent implementation. Its visual direction was inspired by [Sva Launcher](https://github.com/clay-36/Sva-launcher), and its network/input presentation was inspired by [cleanyourtv](https://github.com/Gathin23/cleanyourtv). No source code, fonts, artwork, photographs, or other assets from either project are included here. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Amazon, Fire TV, Android TV, Netflix, Disney+, Paramount+, YouTube, Prime Video, and Jellyfin are names or trademarks of their respective owners. This project is independent and is not endorsed by them.
