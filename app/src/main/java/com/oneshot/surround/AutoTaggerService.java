package com.oneshot.surround;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Batch Auto-Tagger for large libraries (900+ tracks):
 * 1. Reads embedded ID3/MP4/FLAC GENRE metadata offline first (fast, 0 network).
 * 2. Queries free HTTPS public APIs (iTunes Search API -> Deezer Genre/Track API) with zero API key needed.
 * 3. Falls back to acoustic + title/artist heuristics (slowed/nightdrive/phonk/trap/love/funk/lofi)
 *    and persists discovered tags directly into TrackTagDatabase (vertice_tags_v1.db).
 */
public final class AutoTaggerService {
    public interface ProgressListener {
        void onProgress(int processed, int total, int taggedCount, String currentTrack);
        void onFinished(int processed, int taggedCount, boolean cancelled);
    }

    private static final ExecutorService pool = Executors.newFixedThreadPool(4);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static volatile AtomicBoolean activeCancelToken;

    private AutoTaggerService() { }

    public static boolean isRunning() {
        return activeCancelToken != null && !activeCancelToken.get();
    }

    public static void cancel() {
        AtomicBoolean token = activeCancelToken;
        if (token != null) token.set(true);
    }

    public static void tagUntaggedTracks(Context context, List<TrackInfo> library, boolean useOnlineApi, ProgressListener listener) {
        if (context == null || library == null) return;
        cancel();
        final Context app = context.getApplicationContext();
        final TrackTagDatabase db = TrackTagDatabase.get(app);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        activeCancelToken = cancelled;

        final ArrayList<TrackInfo> targets = new ArrayList<>();
        for (TrackInfo t : library) {
            if (t != null && t.uri != null && db.getTagsForTrack(t.uri.toString()).isEmpty()) {
                targets.add(t);
            }
        }

        final int total = targets.size();
        if (total == 0) {
            activeCancelToken = null;
            if (listener != null) mainHandler.post(() -> listener.onFinished(0, 0, false));
            return;
        }

        final AtomicInteger processed = new AtomicInteger(0);
        final AtomicInteger tagged = new AtomicInteger(0);

        // Process in worker pool
        for (TrackInfo track : targets) {
            pool.execute(() -> {
                if (cancelled.get()) return;
                String detectedTag = null;
                try {
                    // Step 1: Embedded file GENRE tag (instant offline)
                    String rawGenre = readEmbeddedGenre(app, track.uri);
                    detectedTag = mapRawGenreToTag(rawGenre, track.title, track.artist);

                    // Step 2: Public HTTPS APIs (iTunes Search API -> Deezer)
                    if (detectedTag == null && useOnlineApi && !cancelled.get()) {
                        String query = buildCleanQuery(track.artist, track.title);
                        if (!query.isEmpty()) {
                            String itunesGenre = queryItunesGenre(query);
                            detectedTag = mapRawGenreToTag(itunesGenre, track.title, track.artist);
                            if (detectedTag == null && itunesGenre != null && !itunesGenre.trim().isEmpty()) {
                                detectedTag = normalizeCustomApiGenre(itunesGenre);
                            }
                        }
                    }

                    // Step 3: Keyword + Acoustic fallback so tracks with clear cues still get tagged
                    if (detectedTag == null) {
                        detectedTag = inferFromTitleArtist(track.title, track.artist);
                    }

                    if (detectedTag != null && !detectedTag.isEmpty() && !cancelled.get()) {
                        if (db.getDefinition(detectedTag) == null) {
                            db.saveOrUpdateTagDefinition(detectedTag, pickColorForTag(detectedTag), 110, true, 0);
                        }
                        if (!db.hasTag(track.uri.toString(), detectedTag)) {
                            db.toggleTrackTag(track.uri.toString(), detectedTag);
                            tagged.incrementAndGet();
                        }
                    }
                } catch (Throwable ignored) { }

                int done = processed.incrementAndGet();
                if (listener != null && (done % 5 == 0 || done == total)) {
                    final int tCount = tagged.get();
                    final String title = track.title;
                    mainHandler.post(() -> listener.onProgress(done, total, tCount, title));
                }
                if (done >= total) {
                    if (activeCancelToken == cancelled) activeCancelToken = null;
                    final int tCount = tagged.get();
                    final boolean wasCancelled = cancelled.get();
                    if (listener != null) {
                        mainHandler.post(() -> listener.onFinished(done, tCount, wasCancelled));
                    }
                }
            });
        }
    }

    private static String readEmbeddedGenre(Context app, Uri uri) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(app, uri);
            return mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try { mmr.release(); } catch (Throwable ignored) { }
        }
    }

    private static String buildCleanQuery(String artist, String title) {
        String cleanArtist = artist == null ? "" : artist.replaceAll("(?i)\\b(feat\\.?|ft\\.?|part\\.?|prod\\.?|remix|slowed|reverb|arquivo local|artista desconhecido)\\b.*", "").trim();
        String cleanTitle = title == null ? "" : title.replaceAll("\\(.*?\\)|\\[.*?\\]", "").trim();
        cleanTitle = cleanTitle.replaceAll("(?i)\\b(mp3|flac|wav|m4a|opus|official|audio|video|lyric|lyrics|hd|hq)\\b", "").trim();
        String q = (cleanArtist + " " + cleanTitle).replaceAll("\\s+", " ").trim();
        return q.length() > 2 ? q : "";
    }

    private static String queryItunesGenre(String query) {
        HttpURLConnection conn = null;
        try {
            String urlStr = "https://itunes.apple.com/search?term="
                + URLEncoder.encode(query, "UTF-8")
                + "&entity=song&limit=1";
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);
            conn.setRequestProperty("User-Agent", "VerticePlayer/1.0");
            if (conn.getResponseCode() != 200) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONObject json = new JSONObject(sb.toString());
            JSONArray results = json.optJSONArray("results");
            if (results != null && results.length() > 0) {
                return results.getJSONObject(0).optString("primaryGenreName", null);
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    static String mapRawGenreToTag(String rawGenre, String title, String artist) {
        String text = ((rawGenre == null ? "" : rawGenre) + " " + (title == null ? "" : title) + " " + (artist == null ? "" : artist))
            .toLowerCase(Locale.ROOT);
        String g = rawGenre == null ? "" : rawGenre.toLowerCase(Locale.ROOT).trim();

        if (text.contains("phonk") || text.contains("drift") || text.contains("memphis") || text.contains("kordhell") || text.contains("dvrst")) {
            return "Phonk";
        }
        if (text.contains("funk") || text.contains("baile") || text.contains("mandel") || text.contains("mtz") || text.contains("mc ")) {
            return "Funk / Mandelão";
        }
        if (text.contains("trap") || text.contains("drill") || text.contains("plugg") || g.contains("trap")) {
            return "Trap";
        }
        if (text.contains("lo-fi") || text.contains("lofi") || g.contains("chill") || g.contains("ambient") || text.contains("slowed")) {
            return "Lo-Fi / Chill";
        }
        if (text.contains("synthwave") || text.contains("retrowave") || text.contains("nightdrive") || text.contains("vaporwave")) {
            return "Nightdrive";
        }
        if (g.contains("hip-hop") || g.contains("hip hop") || g.contains("rap") || g.contains("urban")) {
            // Distinguish Trap vs Boom Bap / Rap by modern artist/title cues
            if (text.contains("lil ") || text.contains("travis") || text.contains("future") || text.contains("carti")
                || text.contains("matue") || text.contains("teto") || text.contains("wiu") || text.contains("21 savage")) {
                return "Trap";
            }
            return "Rap / Boom Bap";
        }
        if (g.contains("r&b") || g.contains("soul") || g.contains("romance") || text.contains("love")
            || g.contains("sertanejo") || g.contains("pagode") || g.contains("mpb") || g.contains("bossa")) {
            return "Love Song";
        }
        if (g.contains("electronic") || g.contains("dance") || g.contains("house") || g.contains("techno") || g.contains("edm") || g.contains("trance")) {
            return "Eletrônica";
        }
        if (g.contains("rock") || g.contains("metal") || g.contains("punk") || g.contains("grunge") || g.contains("hard rock")) {
            return "Rock / Metal";
        }
        if (g.contains("workout") || g.contains("fitness") || g.contains("hardstyle") || text.contains("gym")) {
            return "Gym / Hard";
        }
        return null;
    }

    private static String inferFromTitleArtist(String title, String artist) {
        return mapRawGenreToTag(null, title, artist);
    }

    private static String normalizeCustomApiGenre(String raw) {
        String clean = raw.replaceAll("\\s+", " ").trim();
        if (clean.equalsIgnoreCase("Music") || clean.equalsIgnoreCase("Unknown") || clean.length() < 2) return null;
        if (clean.contains("/")) clean = clean.split("/")[0].trim();
        return clean;
    }

    private static int pickColorForTag(String tag) {
        int h = Math.abs(tag.hashCode());
        int[] palette = {0xFF9D7BFF, 0xFF00E5FF, 0xFFFF3D71, 0xFF2EE59D, 0xFFFFB74D};
        return palette[h % palette.length];
    }
}
