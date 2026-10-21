package de.four04.quiethome;

import android.app.PendingIntent;
import android.content.*;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.*;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.util.*;

/** Uses public TV-provider rows and optional media-session metadata only. No account scraping. */
final class ResumeStore {
    static final Set<String> STREAMING = new HashSet<>(Arrays.asList(
        "com.netflix.ninja", "com.disney.disneyplus", "com.cbs.ca", "com.amazon.firetv.youtube",
        "com.amazon.firebat", "org.jellyfin.androidtv", "com.crunchyroll.crunchyroid",
        "com.apple.atve.amazon.appletv", "com.hbo.hbonow", "de.prosiebensat1digital.seventv",
        "de.cbc.tvnow.firetv", "com.zdf.android.mediathek", "de.swr.ard.avp.mobile.android.amazon"));
    static final class Item {
        String title = "", pkg = "", uri = "", art = "", source = "";
        long position, duration, seen;
        PendingIntent activity;
        boolean direct;
        String key() { return pkg + ":" + title; }
    }
    static final class Result {
        final List<Item> items = new ArrayList<>();
        String providerStatus = "";
    }
    private static final Map<String, PendingIntent> pending = new HashMap<>();
    private static String read(Cursor c, String key) {
        int i = c.getColumnIndex(key);
        return i < 0 || c.isNull(i) ? "" : c.getString(i);
    }
    private static long number(Cursor c, String key) {
        try { return Long.parseLong(read(c, key)); } catch (RuntimeException ex) { return 0; }
    }
    static Result load(Context context) {
        Result result = new Result();
        LinkedHashMap<String, Item> items = new LinkedHashMap<>();
        try (Cursor c = context.getContentResolver().query(Uri.parse("content://android.media.tv/watch_next_program"), null, null, null, null)) {
            if (c != null) {
                while (c.moveToNext() && items.size() < 40) {
                    Item item = new Item();
                    item.pkg = read(c, "package_name");
                    if (!STREAMING.contains(item.pkg)) continue;
                    item.title = read(c, "title");
                    if (item.title.trim().isEmpty()) continue;
                    if (c.getColumnIndex("browsable") >= 0 && number(c, "browsable") == 0) continue;
                    if (number(c, "watch_next_type") == 3) continue; // watchlist is not playback history
                    item.position = number(c, "last_playback_position_millis");
                    item.duration = number(c, "duration_millis");
                    if (item.duration > 0 && item.position >= item.duration - 10000) continue;
                    item.uri = read(c, "intent_uri");
                    item.art = read(c, "poster_art_uri");
                    item.seen = number(c, "last_engagement_time_utc_millis");
                    item.source = "Watch Next";
                    item.direct = !item.uri.isEmpty();
                    items.put(item.key(), item);
                }
                result.providerStatus = items.isEmpty() ? "Noch keine freigegebenen Watch-Next-Einträge." : "Watch Next verfügbar.";
            } else result.providerStatus = "Keine Watch-Next-Daten verfügbar.";
        } catch (SecurityException ex) {
            result.providerStatus = "Fire OS gibt den Watch-Next-Verlauf hier nicht frei.";
        } catch (RuntimeException ex) {
            result.providerStatus = "Dieser Fire-TV-Anbieter stellt keine lesbaren Watch-Next-Daten bereit.";
        }
        synchronized (ResumeStore.class) {
            try {
                JSONArray history = new JSONArray(context.getSharedPreferences("quiet", Context.MODE_PRIVATE).getString("history", "[]"));
                for (int i = 0; i < history.length(); i++) {
                    JSONObject j = history.getJSONObject(i);
                    Item item = new Item();
                    item.pkg = j.optString("pkg"); item.title = j.optString("title");
                    item.position = j.optLong("position"); item.duration = j.optLong("duration"); item.seen = j.optLong("seen");
                    item.art = j.optString("art"); item.source = "Medienverlauf";
                    item.activity = pending.get(item.key());
                    if (!STREAMING.contains(item.pkg) || item.title.isEmpty() || items.containsKey(item.key())) continue;
                    if (item.duration > 0 && item.position >= item.duration - 10000) continue;
                    items.put(item.key(), item);
                }
            } catch (JSONException ignored) { }
        }
        result.items.addAll(items.values());
        Collections.sort(result.items, (a, b) -> Long.compare(b.seen, a.seen));
        return result;
    }
    static synchronized void capture(Context context, MediaController controller) {
        String pkg = controller.getPackageName();
        if (!STREAMING.contains(pkg)) return;
        MediaMetadata metadata = controller.getMetadata();
        PlaybackState state = controller.getPlaybackState();
        if (metadata == null || state == null) return;
        String title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title == null || title.trim().isEmpty()) title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null || title.trim().isEmpty()) return;
        long position = state.getPosition(), duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (state.getState() == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0) {
            position += (long)((android.os.SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        }
        if (duration > 0) position = Math.min(position, duration);
        if (position < 1000 && state.getState() != PlaybackState.STATE_PLAYING) return;
        String key = pkg + ":" + title;
        PendingIntent activity = controller.getSessionActivity();
        if (activity != null) pending.put(key, activity);
        String artPath = "";
        File artwork = new File(context.getFilesDir(), "media-" + Integer.toHexString(key.hashCode()) + ".jpg");
        if (!artwork.exists()) {
            Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art != null) {
                float scale = Math.min(1f, 640f / Math.max(art.getWidth(), art.getHeight()));
                Bitmap small = Bitmap.createScaledBitmap(art, Math.max(1,(int)(art.getWidth()*scale)), Math.max(1,(int)(art.getHeight()*scale)), true);
                try (OutputStream out = new FileOutputStream(artwork)) { small.compress(Bitmap.CompressFormat.JPEG, 82, out); }
                catch (IOException ignored) { }
                if (small != art) small.recycle();
            }
        }
        if (artwork.exists()) artPath = Uri.fromFile(artwork).toString();
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("quiet", Context.MODE_PRIVATE);
            JSONArray previous = new JSONArray(prefs.getString("history", "[]"));
            JSONArray next = new JSONArray();
            next.put(new JSONObject().put("pkg", pkg).put("title", title).put("position", Math.max(0,position))
                .put("duration",duration).put("seen",System.currentTimeMillis()).put("art",artPath));
            for (int i = 0; i < previous.length() && next.length() < 24; i++) {
                JSONObject item = previous.getJSONObject(i);
                if (!(pkg.equals(item.optString("pkg")) && title.equals(item.optString("title")))) next.put(item);
            }
            prefs.edit().putString("history", next.toString()).apply();
            Set<String> keep = new HashSet<>();
            for (int i = 0; i < next.length(); i++) keep.add(next.getJSONObject(i).optString("art"));
            File[] cached = context.getFilesDir().listFiles();
            if (cached != null) for (File f : cached) if (f.getName().startsWith("media-") && f.getName().endsWith(".jpg") && !keep.contains(Uri.fromFile(f).toString())) f.delete();
            pending.keySet().removeIf(k -> !k.equals(key) && pending.size() > 32);
        } catch (JSONException ignored) { }
    }
}
