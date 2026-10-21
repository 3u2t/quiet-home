package de.four04.quiethome;

import android.content.ComponentName;
import android.media.session.*;
import android.os.Handler;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.*;

/** Optional notification access: only records metadata from allowlisted streaming apps. */
public final class MediaAccessService extends NotificationListenerService {
    private MediaSessionManager manager;
    private final Handler handler = new Handler();
    private final Map<MediaController,MediaController.Callback> registered = new HashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener listener = this::attach;
    private final Runnable sample = new Runnable() { @Override public void run() {
        for (MediaController c : new ArrayList<>(registered.keySet())) {
            PlaybackState state = c.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) safeCapture(c);
        }
        handler.postDelayed(this, 30000);
    }};
    @Override public void onListenerConnected() {
        manager = (MediaSessionManager)getSystemService(MEDIA_SESSION_SERVICE);
        try {
            ComponentName component = new ComponentName(this, MediaAccessService.class);
            manager.addOnActiveSessionsChangedListener(listener, component);
            attach(manager.getActiveSessions(component));
            handler.removeCallbacks(sample); handler.postDelayed(sample,30000);
        } catch (SecurityException ignored) { }
    }
    private void safeCapture(MediaController c) {
        try { ResumeStore.capture(this,c); } catch (RuntimeException ignored) { }
    }
    private void attach(List<MediaController> controllers) {
        for (Map.Entry<MediaController,MediaController.Callback> entry : registered.entrySet()) entry.getKey().unregisterCallback(entry.getValue());
        registered.clear();
        if (controllers == null) return;
        for (MediaController c : controllers) {
            if (!ResumeStore.STREAMING.contains(c.getPackageName())) continue;
            MediaController.Callback callback = new MediaController.Callback() {
                @Override public void onMetadataChanged(android.media.MediaMetadata m) { safeCapture(c); }
                @Override public void onPlaybackStateChanged(PlaybackState state) { safeCapture(c); }
            };
            c.registerCallback(callback,handler); registered.put(c,callback); safeCapture(c);
        }
    }
    @Override public void onNotificationPosted(StatusBarNotification notification) {
        if (manager == null || !ResumeStore.STREAMING.contains(notification.getPackageName())) return;
        try { attach(manager.getActiveSessions(new ComponentName(this,MediaAccessService.class))); } catch (SecurityException ignored) { }
    }
    @Override public void onListenerDisconnected() { detach(); }
    private void detach() {
        handler.removeCallbacksAndMessages(null);
        if (manager != null) manager.removeOnActiveSessionsChangedListener(listener);
        for (Map.Entry<MediaController,MediaController.Callback> e : registered.entrySet()) e.getKey().unregisterCallback(e.getValue());
        registered.clear();
    }
    @Override public void onDestroy() { detach(); super.onDestroy(); }
}
