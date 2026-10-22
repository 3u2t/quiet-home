# Privacy

Quiet Home does not contain advertising, analytics, telemetry, crash-reporting or tracking code. Version 2.0 has Android Internet permission for the optional, user-configured YouTube bridge and the HTTPS thumbnail URLs returned by that bridge.

## Local data

Launcher preferences, the selected dock, optional media-history entries, wallpaper choices, and an optional bridge URL and random bridge access key are stored locally in the app's private storage. User-supplied screensaver media remains in the app-owned Android media directory.

## Optional privileged services

- **Home-button helper:** an Android accessibility service restricted to window-state events from `com.amazon.tv.launcher`. It does not request view contents and does not observe streaming apps.
- **Media history:** an Android notification-listener service. When explicitly enabled, it processes media-session metadata only for the allowlisted streaming packages and stores matching history locally. Notification access is broad at the operating-system level, so users should enable it only after reviewing `MediaAccessService.java` and `ResumeStore.java`.
- **Watch Next:** reads TV-provider Watch Next rows when the device makes them available.
- **YouTube bridge:** when explicitly configured, Quiet Home requests sanitized unfinished-video metadata from a user-operated service on the private LAN. The YouTube cookie, account slot and channel identity remain on that server. The app receives title, channel label, thumbnail URL, playback URL and progress percentage. Thumbnail images may be downloaded over HTTPS from the URL provided by YouTube.

Quiet Home does not upload local launcher, wallpaper or media-session data. The optional bridge itself contacts YouTube using the cookie supplied by its operator; see [`youtube-bridge/README.md`](youtube-bridge/README.md).
