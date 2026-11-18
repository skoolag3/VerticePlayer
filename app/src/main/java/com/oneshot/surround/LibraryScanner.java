package com.oneshot.surround;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class LibraryScanner {
    public interface Listener {
        void onFinished(List<TrackInfo> tracks, String error);
    }

    public static void scan(Context context, Uri treeUri, Listener listener) {
        new Thread(() -> {
            ArrayList<TrackInfo> found = new ArrayList<>();
            String error = null;
            try {
                ArrayDeque<String> folders = new ArrayDeque<>();
                folders.add(DocumentsContract.getTreeDocumentId(treeUri));
                while (!folders.isEmpty() && found.size() < 10000) {
                    String folderId = folders.removeFirst();
                    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId);
                    String[] columns = {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    };
                    try (Cursor cursor = context.getContentResolver().query(children, columns, null, null, null)) {
                        if (cursor == null) continue;
                        while (cursor.moveToNext()) {
                            String documentId = cursor.getString(0);
                            String name = cursor.getString(1);
                            String mime = cursor.getString(2);
                            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) folders.addLast(documentId);
                            else if (isAudio(name, mime)) {
                                Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                                found.add(new TrackInfo(fileUri, name == null ? "Faixa local" : name));
                            }
                        }
                    }
                }
                Collections.sort(found, Comparator.comparing(item -> item.title.toLowerCase(Locale.ROOT)));
            } catch (Exception exception) {
                error = exception.getMessage();
            }
            listener.onFinished(found, error);
        }, "library-scanner").start();
    }

    public static boolean isPlaylist(String name, String mime) {
        if (mime != null) {
            String m = mime.toLowerCase(Locale.ROOT);
            if (m.contains("mpegurl") || m.contains("scpls") || m.contains("xspf")) return true;
        }
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".m3u") || lower.endsWith(".m3u8") || lower.endsWith(".pls")
            || lower.endsWith(".cue") || lower.endsWith(".xspf") || lower.endsWith(".asx")
            || lower.endsWith(".wpl");
    }

    private static boolean isAudio(String name, String mime) {
        if (isPlaylist(name, mime)) return false;
        if (mime != null && mime.startsWith("audio/")) return true;
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".flac") || lower.endsWith(".mp3") || lower.endsWith(".wav")
            || lower.endsWith(".m4a") || lower.endsWith(".ogg") || lower.endsWith(".opus")
            || lower.endsWith(".aac") || lower.endsWith(".ape") || lower.endsWith(".wv")
            || lower.endsWith(".aiff") || lower.endsWith(".aif") || lower.endsWith(".wma")
            || lower.endsWith(".caf") || lower.endsWith(".dsf") || lower.endsWith(".dff")
            || lower.endsWith(".mka");
    }
}
