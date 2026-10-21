package de.four04.quiethome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** One-shot boot signal. Activity launch is delegated to the enabled Home helper. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        context.getSharedPreferences("quiet", Context.MODE_PRIVATE).edit().putBoolean("bootPending", true).apply();
        Log.i("QuietHome", "Boot completed received");
        HomeButtonService.onBootSignal();
    }
}
