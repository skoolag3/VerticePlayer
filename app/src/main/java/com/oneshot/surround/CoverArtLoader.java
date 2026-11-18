package com.oneshot.surround;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.LruCache;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * High-Speed Persistent Disk & RAM Cover Art Cache:
 * 1. 32 MB RAM LruCache for instant zero-latency UI binds.
 * 2. Persistent Disk Cache in app.getFilesDir()/covers_v2/<sha1>.jpg (survives app restarts & cache cleanups)
 *    plus negative marker (.none) so tracks without embedded art never re-trigger extraction.
 * 3. Fast native MediaMetadataRetriever.getEmbeddedPicture() first (extracts cover in ~2-5ms without
 *    spawning a full FFmpeg process), with automatic FFmpeg fallback for exotic containers (APE, WV, TAK, DSD).
 */
public final class CoverArtLoader {
    public interface Callback {
        void onLoaded(Bitmap bitmap);
    }

    private static final ExecutorService workers = Executors.newFixedThreadPool(4);
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(32 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };
    private static final HashSet<String> missing = new HashSet<>();
    private static final HashMap<String, ArrayList<Callback>> pending = new HashMap<>();

    public static Bitmap peek(Uri uri) {
        if (uri == null) return null;
        synchronized (cache) {
            return cache.get(uri.toString());
        }
    }

    public static void load(Context context, Uri uri, Callback callback) {
        if (context == null || uri == null) {
            if (callback != null) callback.onLoaded(null);
            return;
        }
        String key = uri.toString();
        synchronized (cache) {
            Bitmap ready = cache.get(key);
            if (ready != null && !ready.isRecycled()) {
                callback.onLoaded(ready);
                return;
            }
            if (missing.contains(key)) {
                callback.onLoaded(null);
                return;
            }
            ArrayList<Callback> waiting = pending.get(key);
            if (waiting != null) {
                waiting.add(callback);
                return;
            }
            waiting = new ArrayList<>();
            waiting.add(callback);
            pending.put(key, waiting);
        }

        Context app = context.getApplicationContext();
        workers.execute(() -> {
            Bitmap bitmap = null;
            try {
                File dir = new File(app.getFilesDir(), "covers_v2");
                if (!dir.exists()) dir.mkdirs();
                String hash = safeHash(key);
                File coverFile = new File(dir, hash + ".jpg");
                File noneMarker = new File(dir, hash + ".none");

                if (coverFile.exists() && coverFile.length() > 32) {
                    bitmap = decodeScaledFile(coverFile.getAbsolutePath(), 512);
                } else if (!noneMarker.exists()) {
                    // Step 1: Fast native MediaMetadataRetriever (extracts embedded ID3/MP4/FLAC/Vorbis picture in milliseconds)
                    byte[] rawArt = extractViaMediaRetriever(app, uri);
                    if (rawArt != null && rawArt.length > 32) {
                        bitmap = decodeScaledBytes(rawArt, 512);
                    }

                    // Step 2: Fallback to FFmpeg stream copy if MediaMetadataRetriever couldn't read the container
                    if (bitmap == null) {
                        File tempRaw = new File(dir, hash + ".raw");
                        try {
                            String source = FFmpegKitConfig.getSafParameterForRead(app, uri);
                            String[] args = {
                                "-hide_banner", "-nostdin", "-loglevel", "error",
                                "-i", source, "-map", "0:v:0?", "-frames:v", "1",
                                "-c:v", "copy", "-f", "image2", "-y", tempRaw.getAbsolutePath()
                            };
                            FFmpegKit.executeWithArguments(args);
                            if (tempRaw.exists() && tempRaw.length() > 32) {
                                bitmap = decodeScaledFile(tempRaw.getAbsolutePath(), 512);
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            if (tempRaw.exists()) tempRaw.delete();
                        }
                    }

                    // Persist normalized compressed JPEG thumbnail on disk so future launches load in <1ms
                    if (bitmap != null) {
                        try (FileOutputStream out = new FileOutputStream(coverFile)) {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 86, out);
                        } catch (Throwable ignored) { }
                    } else {
                        try { noneMarker.createNewFile(); } catch (Throwable ignored) { }
                    }
                }
            } catch (Throwable ignored) { }

            ArrayList<Callback> waiting;
            synchronized (cache) {
                if (bitmap == null) {
                    missing.add(key);
                } else {
                    cache.put(key, bitmap);
                }
                waiting = pending.remove(key);
            }
            if (waiting != null) {
                for (Callback listener : waiting) {
                    if (listener != null) listener.onLoaded(bitmap);
                }
            }
        });
    }

    private static byte[] extractViaMediaRetriever(Context app, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(app, uri);
            return retriever.getEmbeddedPicture();
        } catch (Throwable ignored) {
            return null;
        } finally {
            try { retriever.release(); } catch (Throwable ignored) { }
        }
    }

    private static Bitmap decodeScaledBytes(byte[] data, int maxDim) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / opts.inSampleSize > maxDim) {
            opts.inSampleSize *= 2;
        }
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    private static Bitmap decodeScaledFile(String path, int maxDim) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / opts.inSampleSize > maxDim) {
            opts.inSampleSize *= 2;
        }
        return BitmapFactory.decodeFile(path, opts);
    }

    private static String safeHash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return Integer.toHexString(key.hashCode());
        }
    }
}
