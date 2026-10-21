# Privacy

Quiet Home does not contain advertising, analytics, telemetry, crash-reporting, tracking or cloud-account code. The public build has no Android Internet permission.

## Local data

Launcher preferences, the selected dock, optional media-history entries and wallpaper choices are stored locally in the app's private storage. User-supplied screensaver media remains in the app-owned Android media directory.

## Optional privileged services

- **Home-button helper:** an Android accessibility service restricted to window-state events from `com.amazon.tv.launcher`. It does not request view contents and does not observe streaming apps.
- **Media history:** an Android notification-listener service. When explicitly enabled, it processes media-session metadata only for the allowlisted streaming packages and stores matching history locally. Notification access is broad at the operating-system level, so users should enable it only after reviewing `MediaAccessService.java` and `ResumeStore.java`.
- **Watch Next:** reads TV-provider Watch Next rows when the device makes them available.

Quiet Home does not upload any of this information.
