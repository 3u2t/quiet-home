package de.four04.quiethome;

import android.content.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads sanitized continue-watching metadata from the owner's private LAN bridge. */
final class YouTubeBridgeStore {
    static final String URL_KEY = "youtubeBridgeUrl";
    static final String KEY_KEY = "youtubeBridgeKey";
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    static final class Result {
        final List<ResumeStore.Item> items = new ArrayList<>();
        String status = "";
    }

    static boolean isConfigured(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("quiet", Context.MODE_PRIVATE);
        return allowedEndpoint(prefs.getString(URL_KEY, "")) && validKey(prefs.getString(KEY_KEY, ""));
    }

    static String endpoint(Context context) {
        return context.getSharedPreferences("quiet", Context.MODE_PRIVATE).getString(URL_KEY, "");
    }

    static boolean configure(Context context, String endpoint, String accessKey) {
        if (!allowedEndpoint(endpoint) || !validKey(accessKey)) return false;
        context.getSharedPreferences("quiet", Context.MODE_PRIVATE).edit()
            .putString(URL_KEY, endpoint.trim()).putString(KEY_KEY, accessKey.trim()).apply();
        return true;
    }

    static void clear(Context context) {
        context.getSharedPreferences("quiet", Context.MODE_PRIVATE).edit().remove(URL_KEY).remove(KEY_KEY).apply();
    }

    static boolean allowedEndpoint(String value) {
        if (value == null || value.length() > 2048) return false;
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme(), host = uri.getHost();
            if (scheme == null || host == null || uri.getUserInfo() != null || uri.getFragment() != null) return false;
            if (!"/api/continue-watching".equals(uri.getPath()) || uri.getQuery() != null) return false;
            if ("https".equalsIgnoreCase(scheme)) return true;
            return "http".equalsIgnoreCase(scheme) && privateLanHost(host);
        } catch (URISyntaxException ex) { return false; }
    }

    private static boolean privateLanHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.startsWith("[") && lower.endsWith("]")) lower = lower.substring(1, lower.length() - 1);
        if (lower.endsWith(".local") || "localhost".equals(lower)) return true;
        if (lower.indexOf(':') >= 0) return "::1".equals(lower) || lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80:");
        String[] parts = lower.split("\\.");
        if (parts.length != 4) return false;
        try {
            int a = Integer.parseInt(parts[0]), b = Integer.parseInt(parts[1]);
            for (String part : parts) if (Integer.parseInt(part) < 0 || Integer.parseInt(part) > 255) return false;
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b >= 16 && b <= 31) || (a == 169 && b == 254);
        } catch (NumberFormatException ex) { return false; }
    }

    private static boolean validKey(String key) {
        return key != null && key.matches("[A-Fa-f0-9]{32,128}");
    }

    private static String clean(String value, int maximum) {
        if (value == null) return "";
        value = value.trim();
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    static Result load(Context context) {
        Result result = new Result();
        SharedPreferences prefs = context.getSharedPreferences("quiet", Context.MODE_PRIVATE);
        String endpoint = prefs.getString(URL_KEY, ""), accessKey = prefs.getString(KEY_KEY, "");
        if (!allowedEndpoint(endpoint) || !validKey(accessKey)) {
            result.status = "YouTube-Verlauf noch nicht eingerichtet.";
            return result;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection)new URL(endpoint).openConnection();
            connection.setConnectTimeout(3000); connection.setReadTimeout(6500);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-Quiet-Home-Key", accessKey);
            connection.setRequestProperty("User-Agent", "QuietHome/2.0");
            int code = connection.getResponseCode();
            if (code == 401 || code == 403) {
                result.status = "YouTube-Verlauf: Zugriffsschlüssel stimmt nicht.";
                return result;
            }
            if (code != 200) {
                result.status = code == 503 ? "YouTube-Verlauf: Anmeldung auf dem Pi fehlt." : "YouTube-Verlauf auf dem Raspberry Pi nicht erreichbar.";
                return result;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192]; int count, total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count; if (total > MAX_RESPONSE_BYTES) throw new IOException("response too large");
                    out.write(buffer, 0, count);
                }
            }
            JSONObject root = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            if (root.optInt("version", 0) != 1) throw new JSONException("unsupported response version");
            JSONArray entries = root.optJSONArray("items");
            if (entries == null) throw new JSONException("items missing");
            long now = System.currentTimeMillis();
            for (int i = 0; i < entries.length() && result.items.size() < 24; i++) {
                JSONObject json = entries.optJSONObject(i); if (json == null) continue;
                String id = clean(json.optString("videoId"), 20);
                String title = clean(json.optString("title"), 200);
                int progress = json.optInt("progressPercent", -1);
                if (!id.matches("[A-Za-z0-9_-]{11}") || title.isEmpty() || progress < 2 || progress >= 95) continue;
                ResumeStore.Item item = new ResumeStore.Item();
                item.pkg = "com.amazon.firetv.youtube"; item.title = title;
                item.uri = clean(json.optString("url"), 2048);
                if (!item.uri.startsWith("https://www.youtube.com/watch?") && !item.uri.startsWith("https://youtu.be/")) item.uri = "https://www.youtube.com/watch?v=" + id;
                String art = clean(json.optString("thumbnail"), 2048);
                item.art = art.startsWith("https://") ? art : "";
                item.position = progress; item.duration = 100; item.seen = now - i;
                String channel = clean(json.optString("channel"), 100);
                item.source = channel.isEmpty() ? "YouTube" : "YouTube - " + channel;
                item.direct = true;
                result.items.add(item);
            }
            result.status = result.items.isEmpty() ? "YouTube: keine angefangenen Videos gefunden." : "YouTube: " + result.items.size() + " angefangene Videos.";
        } catch (SocketTimeoutException ex) {
            result.status = "YouTube-Verlauf: Raspberry Pi antwortet zu langsam.";
        } catch (IOException ex) {
            result.status = "YouTube-Verlauf auf dem Raspberry Pi nicht erreichbar.";
        } catch (JSONException ex) {
            result.status = "YouTube-Verlauf: ungültige Antwort vom Raspberry Pi.";
        } finally { if (connection != null) connection.disconnect(); }
        return result;
    }
}
