package com.oneshot.surround;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;

public final class TrackInfo {
    public final Uri uri;
    public String title;
    public String artist = "Arquivo local";
    public String album = "Álbum desconhecido";
    public Bitmap artwork;
    private boolean metadataLoaded;

    public TrackInfo(Context context, Uri uri) { this(uri, readName(context, uri)); }
    public TrackInfo(Uri uri, String fileName) {
        this.uri = uri;
        String name = fileName.replaceFirst("(?i)\\.(flac|mp3|wav|m4a|ogg|opus|aac|ape|wv|aiff|aif|wma|caf|dsf|dff|mka)$", "");
        name = clean(name.replaceFirst("^\\s*\\d{1,3}\\s*[-._]\\s*", ""));
        String[] parts = name.split("\\s+-\\s+", 2);
        if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            artist = clean(parts[0]);
            title = clean(parts[1]);
        } else title = name;
        try {
            String documentId = DocumentsContract.getDocumentId(uri);
            int slash = documentId.lastIndexOf('/');
            if (slash > 0) {
                String parent = documentId.substring(0, slash);
                album = clean(parent.substring(Math.max(parent.lastIndexOf('/'), parent.lastIndexOf(':')) + 1));
            }
        } catch (Exception ignored) { }
    }

    public void applyTags(String foundTitle, String foundArtist, String foundAlbum) {
        if (foundTitle != null && !foundTitle.trim().isEmpty()) title = clean(foundTitle);
        if (foundArtist != null && !foundArtist.trim().isEmpty()) artist = clean(foundArtist);
        if (foundAlbum != null && !foundAlbum.trim().isEmpty()) album = clean(foundAlbum);
    }

    private static String clean(String value) {
        return value.replace('_', ' ').replaceAll("\\s+", " ").trim();
    }

    public void loadMetadata(Context context) {
        if (metadataLoaded) return;
        metadataLoaded = true;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String foundTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String foundArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (foundTitle != null && !foundTitle.isEmpty()) title = foundTitle;
            if (foundArtist != null && !foundArtist.isEmpty()) artist = foundArtist;
            byte[] bytes = retriever.getEmbeddedPicture();
            if (bytes != null) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 700) options.inSampleSize *= 2;
                artwork = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            }
        } catch (Exception ignored) { }
        finally { try { retriever.release(); } catch (Exception ignored) { } }
    }

    private static String readName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        return "Faixa local";
    }
}
