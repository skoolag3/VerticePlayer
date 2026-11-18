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
final class PlaybackController {
    private final MainActivity host;
    PlaybackController(MainActivity host) { this.host = host; }

    void playTrack(int index) {
        if (PlaybackService.isJamGuest()) { host.status.setText("Encerre a Jam para tocar sua biblioteca"); return; }
        if (index < 0 || index >= host.queue.size()) return;
        SpotifyLiveBridge.setSpotifyControlFocus(false);
        host.currentIndex = index;
        host.getPreferences(MODE_PRIVATE).edit().putInt("currentIndex", index).apply();
        TrackInfo track = host.queue.get(index);
        host.collections.played(track.uri.toString());
        host.refreshFavorite();
        host.title.setText(track.title);
        host.artist.setText(track.artist);
        host.artwork.setCover(track.artwork);
        host.artwork.setPlaying(true);
        host.status.setText("Carregando " + track.title + "…");
        host.durationMs = 0;
        host.currentPositionMs = 0;
        host.refreshEndMarker();
        host.showProgress(0, 0);
        try { PlaybackService.playAt(host, host.queue, index); }
        catch (Throwable error) {
            host.status.setText("Falha ao iniciar: " + error.getClass().getSimpleName() + " " + error.getMessage());
            host.artwork.setPlaying(false);
            host.playing = false;
            host.playButton.setText("▶");
            return;
        }
        host.playing = true;
        host.playButton.setText("❚❚");
        host.renderQueue();
        host.refreshRoute();
    }

    void showTrack(int index) {
        host.currentIndex = index;
        host.getPreferences(MODE_PRIVATE).edit().putInt("currentIndex", index).apply();
        TrackInfo track = host.queue.get(index);
        host.collections.played(track.uri.toString());
        host.refreshFavorite();
        host.refreshEndMarker();
        host.currentPositionMs = 0;
        host.title.setText(track.title);
        host.artist.setText(track.artist);
        host.artwork.setCover(track.artwork != null ? track.artwork : PlaybackService.getCurrentArtwork());
        host.artwork.setPlaying(true);
        host.playing = true;
        host.playButton.setText("❚❚");
        host.renderQueue();
    }

    void toggle() {
        if (PlaybackService.isJamGuest()) {
            JamSession session = JamService.current();
            if (session != null) session.toggleGuest();
            host.showJamTrack(); return;
        }
        if (SpotifyLiveBridge.isSpotifyControlActive(host)) {
            SpotifyLiveBridge.toggleSpotifyPlayPause(host);
            return;
        }
        if (host.queue.isEmpty()) { host.openPicker(); return; }
        if (!host.player.isRunning()) { host.playTrack(Math.max(0, host.currentIndex)); return; }
        if (host.player.isPaused()) { host.player.resume(); host.playing = true; }
        else { host.player.pause(); host.playing = false; }
        PlaybackService.refresh();
        host.playButton.setText(host.playing ? "❚❚" : "▶");
        host.artwork.setPlaying(host.playing);
    }

    void previousTrack() {
        if (PlaybackService.isJamGuest()) { host.status.setText("Quem criou a Jam controla as faixas"); return; }
        if (SpotifyLiveBridge.isSpotifyControlActive(host)) {
            SpotifyLiveBridge.previousSpotifyTrack(host);
            return;
        }
        if (host.queue.isEmpty()) return;
        int prev = PlaybackService.previousScopedIndex(host.queue.size(), host.currentIndex);
        if (prev >= 0) host.playTrack(prev);
    }

    void nextTrack(boolean completed) {
        if (PlaybackService.isJamGuest()) { host.status.setText("Quem criou a Jam controla as faixas"); return; }
        if (!completed && SpotifyLiveBridge.isSpotifyControlActive(host)) {
            SpotifyLiveBridge.nextSpotifyTrack(host);
            return;
        }
        if (host.queue.isEmpty()) return;
        if (!completed && host.player.isRunning()) {
            PlaybackService.skipNext();
            return;
        }
        if (host.currentIndex + 1 >= host.queue.size() && completed) {
            host.playing = false;
            host.playButton.setText("▶");
            host.artwork.setPlaying(false);
            host.status.setText("Fila concluída");
            return;
        }
        int next = PlaybackService.peekNextForStoppedQueue(host.queue.size(), host.currentIndex);
        if (next >= 0) host.playTrack(next);
    }

    void toggleShuffleMode() {
        host.shuffleEnabled = !host.shuffleEnabled;
        if (host.shuffleEnabled && host.autoMixToggle != null && host.autoMixToggle.isChecked()) {
            host.autoMixToggle.setChecked(false);
        }
        host.getPreferences(MODE_PRIVATE).edit().putBoolean("shuffleEnabled", host.shuffleEnabled).apply();
        PlaybackService.setShuffleEnabled(host.shuffleEnabled);
        host.refreshPlaybackModes();
        host.status.setText(host.shuffleEnabled ? "Aleatório puro ativado" : "Aleatório desligado");
    }

    void cycleRepeatMode() {
        host.repeatMode = (host.repeatMode + 1) % 3;
        host.getPreferences(MODE_PRIVATE).edit().putInt("repeatMode", host.repeatMode).apply();
        PlaybackService.setRepeatMode(host.repeatMode);
        host.refreshPlaybackModes();
        host.status.setText(host.repeatMode == PlaybackService.REPEAT_ONE
            ? "Loop: repetir esta música"
            : host.repeatMode == PlaybackService.REPEAT_ALL
                ? "Loop: fila / álbum"
                : "Loop desligado");
    }

    void refreshPlaybackModes() {
        if (host.shuffleButton != null) {
            MaterialUi.buttonState(host.shuffleButton,host.shuffleEnabled);
        }
        if (host.repeatButton != null) {
            boolean active = host.repeatMode != PlaybackService.REPEAT_OFF;
            host.repeatButton.setText(host.repeatMode == PlaybackService.REPEAT_ONE ? "↻1" : "↻");
            MaterialUi.buttonState(host.repeatButton,active);
        }
    }

    void toggleEndMarker() {
        if (host.currentIndex < 0 || host.currentIndex >= host.queue.size()) {
            host.status.setText("Escolha uma música para marcar o fim");
            return;
        }
        long marker = PlaybackService.toggleCurrentEndMarker(host, host.currentPositionMs);
        if (marker < 0) {
            host.status.setText("Avance pelo menos 2s da música para marcar o fim");
            return;
        }
        host.refreshEndMarker();
        host.status.setText(marker == 0
            ? "Marcador de fim removido"
            : "Fim marcado em " + host.formatTime(marker) + " · toque de novo para remover");
    }

    void refreshEndMarker() {
        if (host.endMarkerButton == null) return;
        long marker = (host.currentIndex >= 0 && host.currentIndex < host.queue.size())
            ? PlaybackService.getEndMarkerMs(host, host.queue.get(host.currentIndex).uri) : 0;
        boolean active = marker > 0;
        host.endMarkerButton.setText(active ? "Fim " + host.formatTime(marker) : "Marcar fim");
        MaterialUi.buttonState(host.endMarkerButton,active);
    }

    void showProgress(long positionMs, long totalMs) {
        host.durationMs = Math.max(0, totalMs);
        host.currentPositionMs = Math.max(0, positionMs);
        if (!host.changingProgress) {
            host.progress.setProgress(host.durationMs == 0 ? 0 : (int)Math.min(1000, positionMs * 1000 / host.durationMs));
            host.elapsed.setText(host.formatTime(positionMs));
        }
        host.duration.setText(host.formatTime(host.durationMs));
    }

    void refreshFavorite() {
        if (host.favoriteButton == null) return;
        if (host.currentIndex < 0 || host.currentIndex >= host.queue.size()) {
            host.favoriteButton.setText("🏷 Tags");
            host.favoriteButton.setTextColor(host.MUTED);
            return;
        }
        String uri = host.queue.get(host.currentIndex).uri.toString();
        java.util.Set<String> tags = TrackTagDatabase.get(host).getTagsForTrack(uri);
        boolean isFav = host.collections.favorites().contains(uri);
        if (!tags.isEmpty()) {
            String firstTag = tags.iterator().next();
            String label = tags.size() == 1 ? ("🏷 " + firstTag) : ("🏷 " + firstTag + " +" + (tags.size() - 1));
            host.favoriteButton.setText(label);
            host.favoriteButton.setTextColor(MainActivity.TECH_CYAN);
        } else {
            host.favoriteButton.setText(isFav ? "♥ Tags" : "🏷 Tags");
            host.favoriteButton.setTextColor(isFav ? host.ACCENT : host.MUTED);
        }
    }

    void showPlaybackQueue() {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(host);
        LinearLayout sheet = host.column(); sheet.setPadding(host.dp(18), host.dp(18), host.dp(18), host.dp(18));
        sheet.setBackground(host.borderedBackground(host.SURFACE, host.BORDER, 20));
        host.label(sheet, "Fila de reprodução", 22, host.TEXT, true);
        host.label(sheet, host.queue.size() + " faixas · toque para reproduzir", 12, host.MUTED, false);
        ScrollView list = new ScrollView(host); LinearLayout rows = host.column(); list.addView(rows);
        sheet.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        int first = Math.max(0, host.currentIndex - 2), last = Math.min(host.queue.size(), first + 100);
        for (int index = first; index < last; index++) {
            int selected = index; TrackInfo track = host.queue.get(index);
            LinearLayout item = host.row(); item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(host.dp(8), host.dp(9), host.dp(8), host.dp(9));
            if (index == host.currentIndex) item.setBackground(host.borderedBackground(host.SURFACE_TOP, host.ACCENT, 10));
            rows.addView(item);
            ImageView cover = new ImageView(host); cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setImageResource(R.drawable.ic_app);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(host.dp(44), host.dp(44)); size.rightMargin = host.dp(12);
            item.addView(cover, size);
            if (track.artwork != null) cover.setImageBitmap(track.artwork);
            else if (index < first + 10) CoverArtLoader.load(host, track.uri, bitmap -> host.runOnUiThread(() -> { if (bitmap != null) cover.setImageBitmap(bitmap); }));
            LinearLayout text = host.column(); item.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView name = host.label(text, track.title, 13, host.TEXT, true); name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            host.label(text, host.primaryArtist(track.artist), 11, host.MUTED, false);
            host.label(item, index == host.currentIndex ? "▥" : "›", 19, index == host.currentIndex ? host.ACCENT : host.MUTED, false);
            item.setOnClickListener(v -> { host.playTrack(selected); dialog.dismiss(); });
        }
        if (last < host.queue.size()) host.label(rows, "As próximas 100 faixas são exibidas a partir da música atual.", 12, host.MUTED, false);
        Button close = host.pill("Fechar fila", false); sheet.addView(close); close.setOnClickListener(v -> dialog.dismiss());
        int height=(int)(host.getResources().getDisplayMetrics().heightPixels*.83);
        dialog.setContentView(sheet,new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,height));
        dialog.show(); dialog.getBehavior().setPeekHeight(height);
        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
    }
}
