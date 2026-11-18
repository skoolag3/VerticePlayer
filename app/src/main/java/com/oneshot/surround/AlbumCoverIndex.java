package com.oneshot.surround;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Indexes small colour samples per track, retaining no album-sized bitmaps. */
final class AlbumCoverIndex {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> signatures = new HashMap<>();

    void load(Context context, List<TrackInfo> tracks, CoverArtLoader.Callback result) {
        new Scan(context.getApplicationContext(), tracks, result).advance();
    }

    private final class Scan {
        final Context app;
        final List<TrackInfo> tracks;
        final CoverArtLoader.Callback result;
        final Map<String, Integer> votes = new LinkedHashMap<>();
        final Map<String, TrackInfo> examples = new HashMap<>();
        int next;

        Scan(Context app, List<TrackInfo> tracks, CoverArtLoader.Callback result) {
            this.app = app;
            this.tracks = tracks == null ? java.util.Collections.emptyList()
                : tracks.subList(0, Math.min(tracks.size(), 4));
            this.result = result;
        }

        void advance() {
            while (next < tracks.size()) {
                TrackInfo track = tracks.get(next++);
                String key = track.uri.toString();
                if (track.artwork != null && !signatures.containsKey(key)) {
                    signatures.put(key, signature(track.artwork));
                }
                if (signatures.containsKey(key)) {
                    vote(track, signatures.get(key));
                    continue;
                }
                CoverArtLoader.load(app, track.uri, bitmap -> main.post(() -> {
                    String value = bitmap == null ? null : signature(bitmap);
                    signatures.put(key, value);
                    vote(track, value);
                    advance();
                }));
                return;
            }
            String winner = null;
            int best = 0;
            for (Map.Entry<String, Integer> entry : votes.entrySet()) {
                if (entry.getValue() > best) {
                    winner = entry.getKey();
                    best = entry.getValue();
                }
            }
            if (winner == null) return;
            TrackInfo track = examples.get(winner);
            if (track.artwork != null) result.onLoaded(track.artwork);
            else CoverArtLoader.load(app, track.uri, bitmap -> main.post(() -> result.onLoaded(bitmap)));
        }

        void vote(TrackInfo track, String value) {
            if (value == null) return;
            votes.merge(value, 1, Integer::sum);
            examples.putIfAbsent(value, track);
        }
    }

    private static String signature(Bitmap bitmap) {
        char[] colors = new char[64];
        for (int i = 0; i < colors.length; i++) {
            int x = (2 * (i % 8) + 1) * bitmap.getWidth() / 16;
            int y = (2 * (i / 8) + 1) * bitmap.getHeight() / 16;
            int rgb = bitmap.getPixel(x, y);
            colors[i] = (char) (((rgb >> 12) & 0xf00) | ((rgb >> 8) & 0xf0) | ((rgb >> 4) & 0xf));
        }

        return new String(colors);
    }
}
