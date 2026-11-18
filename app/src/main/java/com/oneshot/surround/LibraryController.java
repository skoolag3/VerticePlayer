package com.oneshot.surround;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Rect;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.text.TextWatcher;
import android.text.Editable;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import org.json.JSONArray;

import static android.content.Context.*;
import static com.oneshot.surround.MainActivity.*;
import com.oneshot.surround.MainActivity.ThumbnailRequest;
import com.oneshot.surround.MainActivity.ValueChanged;

/** Extracted responsibility of the player activity. */
final class LibraryController {
    private final MainActivity host;
    LibraryController(MainActivity host) { this.host = host; }

    void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        host.startActivityForResult(intent, host.PICK_AUDIO);
    }

    void openFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        host.startActivityForResult(intent, host.PICK_FOLDER);
    }

    void loadRoots() {
        try {
            String old = host.getPreferences(MODE_PRIVATE).getString("folderUri", null);
            JSONArray saved = new JSONArray(host.getPreferences(MODE_PRIVATE).getString("rootFolders", "[]"));
            for (int index = 0; index < saved.length(); index++) host.rootFolders.add(Uri.parse(saved.getString(index)));
            if (host.rootFolders.isEmpty() && old != null) { host.rootFolders.add(Uri.parse(old)); host.saveRoots(); }
            JSONArray manual = new JSONArray(host.getPreferences(MODE_PRIVATE).getString("manualTracks", "[]"));
            for (int index = 0; index < manual.length(); index++) {
                Uri uri = Uri.parse(manual.getString(index));
                if (!LibraryScanner.isPlaylist(uri.getLastPathSegment(), null)) host.manualTracks.add(uri);
            }
            if (manual.length() == 0) {
                JSONArray oldQueue = new JSONArray(host.getPreferences(MODE_PRIVATE).getString("queue", "[]"));
                for (int index = 0; index < oldQueue.length(); index++) {
                    Uri uri = Uri.parse(oldQueue.getString(index));
                    boolean inRoot = false;
                    for (Uri root : host.rootFolders) if (host.belongsToRoot(uri, root)) { inRoot = true; break; }
                    if (!inRoot) host.manualTracks.add(uri);
                }
                host.saveManualTracks();
            }
        } catch (Exception ignored) { }
    }

    boolean belongsToRoot(Uri file, Uri root) {
        try {
            String document = DocumentsContract.getDocumentId(file);
            String folder = DocumentsContract.getTreeDocumentId(root);
            return document.equals(folder) || document.startsWith(folder + "/");
        } catch (Exception ignored) { return false; }
    }

    void saveRoots() {
        JSONArray saved = new JSONArray();
        for (Uri root : host.rootFolders) saved.put(root.toString());
        host.getPreferences(MODE_PRIVATE).edit().putString("rootFolders", saved.toString())
            .remove("folderUri").apply();
    }

    void saveManualTracks() {
        JSONArray saved = new JSONArray();
        for (Uri uri : host.manualTracks) saved.put(uri.toString());
        host.getPreferences(MODE_PRIVATE).edit().putString("manualTracks", saved.toString()).apply();
    }

    void refreshLibrary() {
        host.status.setText("Lendo pastas raiz…");
        ArrayList<TrackInfo> found = new ArrayList<>();
        for (Uri uri : host.manualTracks) found.add(new TrackInfo(host, uri));
        host.scanRoot(0, found, ++host.libraryScanGeneration);
    }

    void scanRoot(int index, ArrayList<TrackInfo> found, int token) {
        if (token != host.libraryScanGeneration) return;
        if (index >= host.rootFolders.size()) {
            HashSet<String> seen = new HashSet<>();
            found.removeIf(track -> !seen.add(track.uri.toString()));
            found.sort(Comparator.comparing(track -> track.title.toLowerCase(Locale.ROOT)));
            host.metadataLoader.cancel();
            PlaybackService.stopPlayback(host);
            host.queue.clear();
            host.queue.addAll(found);
            host.currentIndex = -1;
            host.playing = false;
            host.playButton.setText("▶");
            host.artwork.setPlaying(false);
            host.selectedGroup = null;
            host.saveQueue();
            host.loadMetadata();
            host.renderQueue();
            host.status.setText(found.size() + " músicas na biblioteca");
            return;
        }
        LibraryScanner.scan(host, host.rootFolders.get(index), (tracks, error) -> host.runOnUiThread(() -> {
            if (token != host.libraryScanGeneration) return;
            if (error != null) { host.status.setText("Falha ao ler " + host.rootName(host.rootFolders.get(index)) + ": " + error); return; }
            found.addAll(tracks);
            host.scanRoot(index + 1, found, token);
        }));
    }

    String rootName(Uri uri) {
        try {
            String name = DocumentsContract.getTreeDocumentId(uri);
            return name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf(':')) + 1);
        } catch (Exception ignored) { return "Pasta"; }
    }

    void showLibrarySettings() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(host).setTitle("Opções de músicas")
            .setItems(new String[]{"Biblioteca · pastas raiz", "Adicionar arquivos", "Atualizar biblioteca"},
                (dialog, option) -> {
                    if (option == 0) host.showRootFolders();
                    if (option == 1) host.openPicker();
                    if (option == 2) host.refreshLibrary();
                }).show();
    }

    void showRootFolders() {
        ArrayList<String> labels = new ArrayList<>();
        for (Uri root : host.rootFolders) labels.add(host.rootName(root));
        labels.add("＋ Adicionar pasta raiz");
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(host).setTitle("Biblioteca · pastas raiz")
            .setItems(labels.toArray(new String[0]), (dialog, selected) -> {
                if (selected == host.rootFolders.size()) { host.openFolder(); return; }
                Uri root = host.rootFolders.get(selected);
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(host).setTitle(host.rootName(root))
                    .setMessage("Remover esta pasta da biblioteca?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Remover", (confirm, which) -> {
                        host.rootFolders.remove(root);
                        host.saveRoots();
                        host.refreshLibrary();
                    }).show();
            }).show();
    }

    void loadMetadata() {
        host.metadataLoader.load(host.queue, () -> host.runOnUiThread(() -> {
            PlaybackService.refreshLibraryMetadata(host.queue);
            host.renderQueue();
            int current = PlaybackService.getCurrentIndex();
            if (current >= 0 && current < host.queue.size()) {
                host.title.setText(host.queue.get(current).title);
                host.artist.setText(host.queue.get(current).artist);
            }
        }));
    }

    void addTrack(Uri uri) {
        String mime = host.getContentResolver().getType(uri);
        if (LibraryScanner.isPlaylist(uri.getLastPathSegment(), mime)) return;
        try { host.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
        for (TrackInfo item : host.queue) if (item.uri.equals(uri)) return;
        TrackInfo info = new TrackInfo(host, uri);
        if (LibraryScanner.isPlaylist(info.title, mime)) return;
        host.queue.add(info);
        if (!host.manualTracks.contains(uri)) { host.manualTracks.add(uri); host.saveManualTracks(); }
        host.saveQueue();
    }

    void saveQueue() {
        JSONArray paths = new JSONArray();
        for (TrackInfo item : host.queue) paths.put(item.uri.toString());
        host.getPreferences(MODE_PRIVATE).edit().putString("queue", paths.toString()).apply();
    }

    void restoreQueue() {
        try {
            JSONArray paths = new JSONArray(host.getPreferences(MODE_PRIVATE).getString("queue", "[]"));
            for (int index = 0; index < paths.length(); index++) host.queue.add(new TrackInfo(host, Uri.parse(paths.getString(index))));
            host.currentIndex = Math.min(host.getPreferences(MODE_PRIVATE).getInt("currentIndex", -1), host.queue.size() - 1);
            if (host.currentIndex >= 0) {
                TrackInfo saved = host.queue.get(host.currentIndex);
                host.title.setText(saved.title);
                host.artist.setText(saved.artist);
                host.artwork.setCover(saved.artwork);
                host.status.setText("Pronto para reproduzir");
                CoverArtLoader.load(host, saved.uri, bitmap -> host.runOnUiThread(() -> {
                    if (host.currentIndex >= 0 && host.currentIndex < host.queue.size()
                        && host.queue.get(host.currentIndex).uri.equals(saved.uri) && bitmap != null) {
                        host.queue.get(host.currentIndex).artwork = bitmap;
                        host.artwork.setCover(bitmap);
                    }
                }));
            }
        } catch (Exception ignored) { host.queue.clear(); host.currentIndex = -1; }
    }
}
