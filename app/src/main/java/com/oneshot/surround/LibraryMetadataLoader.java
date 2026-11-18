package com.oneshot.surround;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.StreamInformation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reads library tags off the UI thread and remembers them across launches.
 * Uses fast native MediaMetadataRetriever first (with FFprobe fallback) and commits in batches.
 */
public final class LibraryMetadataLoader {
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private int generation;

    public LibraryMetadataLoader(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void load(List<TrackInfo> tracks, Runnable onBatch) {
        generation++;
        int token = generation;
        ArrayList<TrackInfo> copy = new ArrayList<>(tracks);
        SharedPreferences cache = context.getSharedPreferences("library_tags_v1", Context.MODE_PRIVATE);
        for (TrackInfo track : copy) {
            applyCached(cache, track);
            if (track.artwork == null) {
                track.artwork = CoverArtLoader.peek(track.uri);
            }
        }
        worker.execute(() -> {
            int changed = 0;
            SharedPreferences.Editor batchEditor = cache.edit();
            for (TrackInfo track : copy) {
                synchronized (this) {
                    if (token != generation) return;
                }
                String key = track.uri.toString();
                if (cache.contains(key)) continue;
                try {
                    String title = null;
                    String artist = null;
                    String album = null;

                    // 1. Fast native MediaMetadataRetriever
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    try {
                        mmr.setDataSource(context, track.uri);
                        title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                        artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                        if (artist == null || artist.trim().isEmpty()) {
                            artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
                        }
                        album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                    } catch (Throwable ignored) {
                    } finally {
                        try { mmr.release(); } catch (Throwable ignored) { }
                    }

                    // 2. Fallback to FFprobe if MediaMetadataRetriever didn't find tags
                    if (title == null && artist == null && album == null) {
                        String source = FFmpegKitConfig.getSafParameterForRead(context, track.uri);
                        MediaInformation media = FFprobeKit.getMediaInformation(source).getMediaInformation();
                        if (media != null) {
                            title = tag(media.getTags(), "title");
                            artist = tag(media.getTags(), "artist");
                            album = tag(media.getTags(), "album");
                            if (media.getStreams() != null) {
                                for (StreamInformation stream : media.getStreams()) {
                                    if (!"audio".equals(stream.getType())) continue;
                                    if (title == null) title = tag(stream.getTags(), "title");
                                    if (artist == null) artist = tag(stream.getTags(), "artist");
                                    if (album == null) album = tag(stream.getTags(), "album");
                                    break;
                                }
                            }
                        }
                    }

                    track.applyTags(title, artist, album);
                    JSONArray saved = new JSONArray();
                    saved.put(track.title).put(track.artist).put(track.album);
                    batchEditor.putString(key, saved.toString());
                    changed++;
                    if (changed % 25 == 0) {
                        batchEditor.apply();
                        batchEditor = cache.edit();
                        onBatch.run();
                    }
                } catch (Throwable ignored) { }
            }
            if (changed > 0) {
                batchEditor.apply();
                onBatch.run();
            }
        });
    }

    public synchronized void cancel() {
        generation++;
    }

    private static void applyCached(SharedPreferences cache, TrackInfo track) {
        String saved = cache.getString(track.uri.toString(), null);
        if (saved == null) return;
        try {
            JSONArray values = new JSONArray(saved);
            track.applyTags(values.optString(0), values.optString(1), values.optString(2));
        } catch (Exception ignored) { }
    }

    private static String tag(JSONObject tags, String name) {
        if (tags == null) return null;
        java.util.Iterator<String> keys = tags.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (name.equals(key.toLowerCase(Locale.ROOT))) return tags.optString(key, null);
        }
        return null;
    }
}
