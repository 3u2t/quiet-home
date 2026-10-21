package de.four04.quiethome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/** Optional, narrowly filtered helper. It cannot read window contents. */
public final class HomeButtonService extends AccessibilityService {
    private static HomeButtonService connected;
    private long lastLaunch;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable bootLaunch = () -> startOncePerBoot();
    @Override protected void onServiceConnected() {
        super.onServiceConnected(); connected = this;
        Log.i("QuietHome", "Home helper connected; checking boot autostart");
        main.postDelayed(bootLaunch, 1500);
    }
    static void onBootSignal() {
        if (connected != null) {
            connected.main.removeCallbacks(connected.bootLaunch);
            connected.main.postDelayed(connected.bootLaunch, 1500);
        }
    }
    private void startOncePerBoot() {
        UserManager users = (UserManager)getSystemService(USER_SERVICE);
        if (users != null && !users.isUserUnlocked()) return;
        SharedPreferences prefs = getSharedPreferences("quiet", MODE_PRIVATE);
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        boolean pending = prefs.getBoolean("bootPending", false);
        if (boot >= 0 && prefs.getInt("autostartBoot", -2) == boot) {
            prefs.edit().putBoolean("bootPending", false).apply(); return;
        }
        if (boot < 0 && !pending) return;
        try {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            prefs.edit().putInt("autostartBoot", boot).putBoolean("bootPending", false).apply();
            Log.i("QuietHome", "Boot autostart requested; boot=" + boot);
        } catch (RuntimeException ex) { Log.e("QuietHome", "Boot autostart rejected", ex); }
    }
    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (connected == this) connected = null;
        super.onDestroy();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!getSharedPreferences("quiet", MODE_PRIVATE).getBoolean("homeHelper", true)) return;
        if (getSharedPreferences("quiet", MODE_PRIVATE).getLong("helperPausedUntil", 0) > System.currentTimeMillis()) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!"com.amazon.tv.launcher".contentEquals(event.getPackageName() == null ? "" : event.getPackageName())) return;
        String name = String.valueOf(event.getClassName());
        if (!name.contains("HomeActivity")) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastLaunch < 1500) return;
        lastLaunch = now;
        try { startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)); }
        catch (RuntimeException ignored) { }
    }
    @Override public void onInterrupt() { }
}
