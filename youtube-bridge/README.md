# Quiet Home YouTube Bridge

This optional, self-hosted service reads the signed-in user's YouTube history through `youtubei.js` and exposes only unfinished videos to Quiet Home. It is intended for a Raspberry Pi or another always-on device inside a trusted home network.

The Android app never receives the YouTube cookie. The bridge returns only a title, channel label, thumbnail URL, playback URL and progress percentage. Requests to the bridge require a separate random access key.

## Requirements

- A Raspberry Pi or Linux host with Docker Engine and Docker Compose.
- SSH access from the Windows PC used for setup.
- A Fire TV or Android TV device with Quiet Home 2.0 installed.
- A current YouTube browser session for the account/profile whose history should be shown.

## Recommended setup

From the repository root, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\SetupYouTubeBridge.ps1 -PiHost raspberrypi.local -PiUser pi -AdbTarget FIRE_TV_IP:5555
```

The script checks SSH before asking for the cookie, prompts for the cookie as a hidden value, generates a random bridge key, transfers both directly over SSH, starts the container and configures Quiet Home through ADB. No cookie or key is written into the repository or APK.

To reuse an existing private configuration after updating the code:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\SetupYouTubeBridge.ps1 -PiHost raspberrypi.local -PiUser pi -AdbTarget FIRE_TV_IP:5555 -ResumeExisting
```

## Capturing the browser values

Use a new private/incognito window, select the YouTube profile you actually use, and verify that `https://www.youtube.com/feed/history` contains your watched videos. In Developer Tools, reload the page and select the POST request to `/youtubei/v1/browse`.

Copy the complete `Cookie` request-header value. Also copy `X-Goog-AuthUser` and, if present, `X-Goog-PageId`. Treat the cookie as an account credential: do not paste it into issues, logs, chat, source files or shell commands.

## Private runtime files

The Pi stores these files under `~/quiet-home-youtube/data/` with restrictive permissions:

- `youtube.cookie` — the YouTube browser session.
- `youtube.identity.json` — the selected account slot and optional channel page ID.
- `access.key` — authentication for the LAN endpoint.

The entire `data/` directory and `.env` are ignored by Git. Never publish or back them up to a public location.

## Revoke access

Stop the container and remove the private files on the Pi:

```sh
cd ~/quiet-home-youtube
docker compose down
rm -f data/youtube.cookie data/youtube.identity.json data/access.key
```

Then remove the connection in Quiet Home's YouTube settings. If the cookie may have been exposed, revoke the corresponding Google session as well.

## Notes

This integration uses an unofficial YouTube client library and can require maintenance when YouTube changes its internal API. The bridge listens on port 8787; keep it on a trusted LAN and do not forward that port to the Internet.
