package com.oneshot.surround;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local favorites, history and user playlists, keyed by stable document URIs. */
public final class MusicCollections {
    private final SharedPreferences prefs;
    public MusicCollections(Context context) { prefs = context.getSharedPreferences("collections", 0); }
    public Set<String> favorites() { return new HashSet<>(prefs.getStringSet("favorites", new HashSet<>())); }
    public boolean toggleFavorite(String uri) {
        Set<String> values = favorites(); boolean selected = !values.remove(uri);
        if (selected) values.add(uri);
        prefs.edit().putStringSet("favorites", values).apply(); return selected;
    }
    public Set<String> recent() { return new HashSet<>(prefs.getStringSet("recent", new HashSet<>())); }
    public void played(String uri) {
        Set<String> values = recent(); values.add(uri);
        prefs.edit().putStringSet("recent", values).apply();
    }
    private JSONObject playlists() {
        try { return new JSONObject(prefs.getString("playlists", "{}")); }
        catch (Exception ignored) { return new JSONObject(); }
    }
    public ArrayList<String> names() {
        ArrayList<String> names = new ArrayList<>(); playlists().keys().forEachRemaining(names::add);
        names.sort(String.CASE_INSENSITIVE_ORDER); return names;
    }
    public Set<String> tracks(String name) {
        Set<String> result = new HashSet<>(); JSONArray values = playlists().optJSONArray(name);
        if (values != null) for (int i = 0; i < values.length(); i++) result.add(values.optString(i));
        return result;
    }
    public void add(String name, String uri) {
        try {
            JSONObject all = playlists(); Set<String> values = tracks(name);
            if (uri != null) values.add(uri);
            all.put(name, new JSONArray(values)); prefs.edit().putString("playlists", all.toString()).apply();
        } catch (Exception ignored) { }
    }
}
