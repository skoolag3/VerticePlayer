package com.oneshot.surround;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Persistent SQLite Database (vertice_tags_v1.db) for Music Style/Mood Tags and AutoMix Aggregation.
 *
 * Pre-populates core genres/vibes ("Phonk", "Trap", "Love Song", "Funk / Mandelão", "Synthwave / Nightdrive",
 * "Lo-Fi / Chill", "Gym / Hard", "Boom Bap / Rap", "Eletrônica / Club", "Rock / Metal") and allows the user
 * to create custom tags with configurable AutoMix properties:
 * - autoMixChancePct (0..200%): Probability / weight multiplier in AutoMix selection
 * - strictCluster (boolean): When true, strongly prefers staying inside the same tag during AutoMix
 * - energyBias (-2..+2): Target energy drift when this tag is active
 */
public final class TrackTagDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "vertice_tags_v1.db";
    private static final int DB_VERSION = 1;

    public static final class TagDefinition {
        public final String name;
        public final int color;
        public final int autoMixChancePct; // 0..200% (100% = normal, 150% = high priority, 30% = rare)
        public final boolean strictCluster; // true = strongly locks AutoMix to tracks sharing this tag
        public final int energyBias;        // -2 (Chill) .. 0 (Neutral) .. +2 (Hype/Aggressive)
        public final boolean builtIn;

        public TagDefinition(String name, int color, int autoMixChancePct, boolean strictCluster, int energyBias, boolean builtIn) {
            this.name = name;
            this.color = color;
            this.autoMixChancePct = Math.max(0, Math.min(200, autoMixChancePct));
            this.strictCluster = strictCluster;
            this.energyBias = Math.max(-2, Math.min(2, energyBias));
            this.builtIn = builtIn;
        }
    }

    private static TrackTagDatabase instance;

    private final LinkedHashMap<String, TagDefinition> defsCache = new LinkedHashMap<>();
    private final HashMap<String, HashSet<String>> trackTagsCache = new HashMap<>();

    public static synchronized TrackTagDatabase get(Context context) {
        if (instance == null) {
            instance = new TrackTagDatabase(context.getApplicationContext());
        }
        return instance;
    }

    private TrackTagDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        loadAllIntoMemory();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS tag_defs ("
            + "name TEXT PRIMARY KEY COLLATE NOCASE, "
            + "color INTEGER NOT NULL, "
            + "chance_pct INTEGER NOT NULL DEFAULT 100, "
            + "strict_cluster INTEGER NOT NULL DEFAULT 0, "
            + "energy_bias INTEGER NOT NULL DEFAULT 0, "
            + "built_in INTEGER NOT NULL DEFAULT 0)");

        db.execSQL("CREATE TABLE IF NOT EXISTS track_tags ("
            + "uri TEXT NOT NULL, "
            + "tag_name TEXT NOT NULL COLLATE NOCASE, "
            + "PRIMARY KEY (uri, tag_name))");

        seedDefaultTags(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onCreate(db);
    }

    private void seedDefaultTags(SQLiteDatabase db) {
        Object[][] defaults = {
            {"Phonk",           0xFFFF3D71, 135, 1,  2},
            {"Trap",            0xFF9D7BFF, 125, 1,  1},
            {"Love Song",       0xFFFF7EB6, 115, 1, -1},
            {"Funk / Mandelão", 0xFF00E5FF, 130, 1,  2},
            {"Nightdrive",      0xFF7C4DFF, 110, 0,  0},
            {"Lo-Fi / Chill",   0xFF2EE59D, 100, 1, -2},
            {"Gym / Hard",      0xFFFFB74D, 145, 1,  2},
            {"Rap / Boom Bap",  0xFF40C4FF, 115, 0,  0},
            {"Eletrônica",      0xFF18FFFF, 120, 0,  1},
            {"Rock / Metal",    0xFFFF5252, 120, 1,  1}
        };
        for (Object[] row : defaults) {
            ContentValues cv = new ContentValues();
            cv.put("name", (String) row[0]);
            cv.put("color", (Integer) row[1]);
            cv.put("chance_pct", (Integer) row[2]);
            cv.put("strict_cluster", (Integer) row[3]);
            cv.put("energy_bias", (Integer) row[4]);
            cv.put("built_in", 1);
            db.insertWithOnConflict("tag_defs", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    private synchronized void loadAllIntoMemory() {
        defsCache.clear();
        trackTagsCache.clear();
        SQLiteDatabase db = getWritableDatabase();
        seedDefaultTags(db);

        try (Cursor c = db.rawQuery(
                "SELECT name, color, chance_pct, strict_cluster, energy_bias, built_in FROM tag_defs ORDER BY built_in DESC, name ASC",
                null)) {
            while (c.moveToNext()) {
                String name = c.getString(0);
                TagDefinition def = new TagDefinition(
                    name, c.getInt(1), c.getInt(2),
                    c.getInt(3) != 0, c.getInt(4), c.getInt(5) != 0
                );
                defsCache.put(normalizeKey(name), def);
            }
        } catch (Throwable ignored) { }

        try (Cursor c = db.rawQuery("SELECT uri, tag_name FROM track_tags", null)) {
            while (c.moveToNext()) {
                String uri = c.getString(0);
                String tagName = c.getString(1);
                TagDefinition def = defsCache.get(normalizeKey(tagName));
                String canonical = def != null ? def.name : tagName;
                trackTagsCache.computeIfAbsent(uri, k -> new HashSet<>()).add(canonical);
            }
        } catch (Throwable ignored) { }
    }

    private static String normalizeKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    public synchronized ArrayList<TagDefinition> getAllDefinitions() {
        return new ArrayList<>(defsCache.values());
    }

    public synchronized TagDefinition getDefinition(String tagName) {
        return defsCache.get(normalizeKey(tagName));
    }

    public synchronized void saveOrUpdateTagDefinition(String name, int color, int chancePct, boolean strictCluster, int energyBias) {
        if (name == null || name.trim().isEmpty()) return;
        String cleanName = name.trim();
        String key = normalizeKey(cleanName);
        TagDefinition existing = defsCache.get(key);
        boolean builtIn = existing != null && existing.builtIn;
        String finalName = existing != null ? existing.name : cleanName;

        TagDefinition updated = new TagDefinition(finalName, color, chancePct, strictCluster, energyBias, builtIn);
        defsCache.put(key, updated);

        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("name", finalName);
        cv.put("color", updated.color);
        cv.put("chance_pct", updated.autoMixChancePct);
        cv.put("strict_cluster", updated.strictCluster ? 1 : 0);
        cv.put("energy_bias", updated.energyBias);
        cv.put("built_in", builtIn ? 1 : 0);
        db.insertWithOnConflict("tag_defs", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void deleteCustomTag(String name) {
        String key = normalizeKey(name);
        TagDefinition def = defsCache.get(key);
        if (def == null || def.builtIn) return;
        defsCache.remove(key);
        for (HashSet<String> set : trackTagsCache.values()) {
            set.removeIf(t -> normalizeKey(t).equals(key));
        }
        SQLiteDatabase db = getWritableDatabase();
        db.delete("tag_defs", "LOWER(name) = ?", new String[]{key});
        db.delete("track_tags", "LOWER(tag_name) = ?", new String[]{key});
    }

    public synchronized Set<String> getTagsForTrack(String uri) {
        if (uri == null) return Collections.emptySet();
        HashSet<String> set = trackTagsCache.get(uri);
        return set == null ? Collections.emptySet() : new HashSet<>(set);
    }

    public synchronized boolean hasTag(String uri, String tagName) {
        if (uri == null || tagName == null) return false;
        HashSet<String> set = trackTagsCache.get(uri);
        if (set == null) return false;
        String target = normalizeKey(tagName);
        for (String s : set) {
            if (normalizeKey(s).equals(target)) return true;
        }
        return false;
    }

    public synchronized boolean toggleTrackTag(String uri, String tagName) {
        if (uri == null || tagName == null || tagName.trim().isEmpty()) return false;
        TagDefinition def = defsCache.get(normalizeKey(tagName));
        String canonical = def != null ? def.name : tagName.trim();
        HashSet<String> set = trackTagsCache.computeIfAbsent(uri, k -> new HashSet<>());
        String existingMatch = null;
        for (String s : set) {
            if (normalizeKey(s).equals(normalizeKey(canonical))) {
                existingMatch = s;
                break;
            }
        }
        SQLiteDatabase db = getWritableDatabase();
        if (existingMatch != null) {
            set.remove(existingMatch);
            db.delete("track_tags", "uri = ? AND LOWER(tag_name) = ?", new String[]{uri, normalizeKey(canonical)});
            return false;
        } else {
            set.add(canonical);
            ContentValues cv = new ContentValues();
            cv.put("uri", uri);
            cv.put("tag_name", canonical);
            db.insertWithOnConflict("track_tags", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
            return true;
        }
    }

    public synchronized int countTracksWithTag(String tagName, List<TrackInfo> library) {
        if (tagName == null || library == null) return 0;
        int count = 0;
        for (TrackInfo t : library) {
            if (hasTag(t.uri.toString(), tagName)) count++;
        }
        return count;
    }

    /** Formats a short badge string of tags for a track row (e.g., "🏷 Phonk • Trap"). */
    public synchronized String formatTrackTagsBadge(String uri) {
        Set<String> tags = getTagsForTrack(uri);
        if (tags.isEmpty()) return "";
        ArrayList<String> sorted = new ArrayList<>(tags);
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        StringBuilder sb = new StringBuilder("🏷 ");
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append(" • ");
            sb.append(sorted.get(i));
        }
        return sb.toString();
    }

    /**
     * Computes the AutoMix tag affinity score bonus/penalty between the current track and a candidate track,
     * plus custom tag probability weighting and optional target tag filter.
     *
     * @param sourceUri       URI of the currently playing track
     * @param candidateUri    URI of the candidate track
     * @param targetFilterTag Optional specific tag locked in AutoMix settings ("" = any/follow current track's tags)
     */
    public synchronized double computeAutoMixTagScore(String sourceUri, String candidateUri, String targetFilterTag) {
        if (candidateUri == null) return 0.0;
        Set<String> srcTags = getTagsForTrack(sourceUri);
        Set<String> dstTags = getTagsForTrack(candidateUri);

        double score = 0.0;

        // 1. If the user selected a specific Target Tag in AutoMix (e.g. "Phonk" or "Love Song")
        if (targetFilterTag != null && !targetFilterTag.isEmpty()) {
            if (hasTag(candidateUri, targetFilterTag)) {
                TagDefinition def = getDefinition(targetFilterTag);
                double chanceMult = def != null ? (def.autoMixChancePct / 100.0) : 1.0;
                score += 11.5 * chanceMult;
            } else {
                score -= 14.0;
            }
        }

        // 2. Tag aggregation between current track and candidate track (Mood/Genre continuity)
        if (!srcTags.isEmpty()) {
            boolean sharedAny = false;
            boolean sourceHasStrict = false;
            for (String sTag : srcTags) {
                TagDefinition def = getDefinition(sTag);
                if (def != null && def.strictCluster) sourceHasStrict = true;
                if (hasTag(candidateUri, sTag)) {
                    sharedAny = true;
                    double chanceMult = def != null ? (def.autoMixChancePct / 100.0) : 1.0;
                    double clusterBoost = (def != null && def.strictCluster) ? 8.5 : 5.5;
                    score += clusterBoost * chanceMult;
                }
            }
            if (!sharedAny && sourceHasStrict && !dstTags.isEmpty()) {
                // Avoid jarring jump (e.g. from "Phonk" straight into "Love Song") when strictCluster is on
                score -= 9.5;
            } else if (!sharedAny && sourceHasStrict) {
                score -= 4.5;
            }
        }

        // 3. Apply candidate's own custom Tag Chance % weight even if source track had no tags
        if (!dstTags.isEmpty()) {
            double maxChancePct = 100.0;
            double minChancePct = 100.0;
            for (String dTag : dstTags) {
                TagDefinition def = getDefinition(dTag);
                if (def != null) {
                    maxChancePct = Math.max(maxChancePct, def.autoMixChancePct);
                    minChancePct = Math.min(minChancePct, def.autoMixChancePct);
                }
            }
            if (maxChancePct > 100.0) {
                score += ((maxChancePct - 100.0) / 100.0) * 5.0;
            } else if (minChancePct < 100.0) {
                score -= ((100.0 - minChancePct) / 100.0) * 6.0;
            }
        }

        return score;
    }
}
