package com.snapmaps.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Uses the existing history schema, so installed users keep their saved places and favorites. */
final class HistoryStore {
    private HistoryStore() { }

    static synchronized JSONArray read(Context context) {
        try { return new JSONArray(AppSettings.prefs(context).getString("history", "[]")); }
        catch (JSONException ignored) { return new JSONArray(); }
    }

    static synchronized void save(Context context, String place) {
        String normalized = place.trim();
        if (normalized.isEmpty()) return;
        JSONArray old = read(context);
        JSONArray rest = new JSONArray();
        boolean favorite = false;
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i);
            if (item == null) continue;
            if (item.optString("place").equalsIgnoreCase(normalized)) {
                favorite |= item.optBoolean("favorite");
            } else rest.put(item);
        }
        JSONObject current = new JSONObject();
        try {
            current.put("place", normalized);
            current.put("at", System.currentTimeMillis());
            current.put("favorite", favorite);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        JSONArray saved = new JSONArray();
        saved.put(current);
        for (int i = 0; i < Math.min(rest.length(), 49); i++) saved.put(rest.optJSONObject(i));
        write(context, saved);
    }

    static synchronized void favorite(Context context, String place, boolean value) {
        JSONArray items = read(context);
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && item.optString("place").equals(place)) {
                try { item.put("favorite", value); }
                catch (JSONException impossible) { throw new IllegalStateException(impossible); }
            }
        }
        write(context, items);
    }

    static synchronized void remove(Context context, String place) {
        JSONArray items = read(context);
        JSONArray next = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && !item.optString("place").equals(place)) next.put(item);
        }
        write(context, next);
    }

    static synchronized void clear(Context context) { write(context, new JSONArray()); }

    private static void write(Context context, JSONArray history) {
        AppSettings.prefs(context).edit().putString("history", history.toString()).apply();
    }
}
