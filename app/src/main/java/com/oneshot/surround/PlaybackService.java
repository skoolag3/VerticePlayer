package com.oneshot.surround;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Owns the player, repeat/shuffle modes, custom end markers, DJ transitions, and play/skip telemetry. */
public final class PlaybackService extends Service {
    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;

    private static final String CHANNEL = "vertice_playback";
    private static final int NOTIFICATION_ID = 42;
    private static FfmpegPcmPlayer player;
    private static PlaybackService instance;
    private static FfmpegPcmPlayer.Listener uiListener;
    private static final ArrayList<Uri> queue = new ArrayList<>();
    private static final ArrayList<String> titles = new ArrayList<>();
    private static final ArrayList<String> artists = new ArrayList<>();
    private static int currentIndex = -1;
    private static String currentTitle = "Vértice Player";
    private static String currentArtist = "Música local";
    private static Bitmap currentArtwork;
    private static boolean autoMixEnabled;
    private static boolean crossfadeEnabled = true;
    private static int crossfadeOverrideMs = 0; // 0 = Auto (BPM-aligned), >0 = fixed ms (2000..7000)
    private static int forcedTransitionStyle = -1; // -1 = Auto DJ, 0..3 = specific FX
    private static boolean considerTagsInAutoMix = true;
    private static String targetAutoMixTag = "";
    private static volatile boolean ignoreAudioFocusLoss = true;
    private static Context appContextRef;
    private static final ArrayList<Integer> activeScopeOrder = new ArrayList<>();
    private static final HashSet<Integer> activeScopeSet = new HashSet<>();
    private static String activeScopeName = "";
    private static boolean autoMixTransitioned;
    private static boolean currentTrackRewarded;
    private static boolean markerTriggered;
    private static int repeatMode = REPEAT_OFF;
    private static boolean shuffleEnabled;
    private static final HashSet<Integer> shufflePlayed = new HashSet<>();
    private static long lastPositionMs;
    private static long lastDurationMs;
    private static boolean jamGuest;
    private static Uri jamUri;
    private static long crossfadeCooldownUntilMs;
    private static long lastAutoMixTriggerMs;
    private static AutoMixAnalyzer autoMixAnalyzer;
    private static final AutoMixSelector autoMixSelector = new AutoMixSelector();
    private MediaSession session;
    private AudioFocusRequest focusRequest;
    private HeadphoneSafety headphoneSafety;

    private static synchronized AutoMixAnalyzer ensureAnalyzer(Context context) {
        if (context != null && appContextRef == null) {
            appContextRef = context.getApplicationContext();
        }
        if (autoMixAnalyzer == null && context != null) {
            autoMixAnalyzer = new AutoMixAnalyzer(context.getApplicationContext());
        }
        return autoMixAnalyzer;
    }

    public static synchronized FfmpegPcmPlayer getPlayer(Context context) {
        ensureAnalyzer(context);
        if (player == null) player = new FfmpegPcmPlayer(context.getApplicationContext(), new FfmpegPcmPlayer.Listener() {
            public void onMessage(String text) {
                if (uiListener != null) uiListener.onMessage(text);
                if (text.startsWith("Falha no FFmpeg") && instance != null) instance.stopSelf();
            }
            public void onProgress(long positionMs, long durationMs) {
                lastPositionMs = positionMs;
                lastDurationMs = durationMs;
                if (uiListener != null) uiListener.onProgress(positionMs, durationMs);
                if (jamGuest) return;
                if (player != null && player.isRouteMuted()) return;
                if (autoMixTransitioned && (positionMs < 12000
                        || (System.currentTimeMillis() >= crossfadeCooldownUntilMs && positionMs + 2000 < lastAutoMixTriggerMs))) {
                    autoMixTransitioned = false;
                    currentTrackRewarded = false;
                    markerTriggered = false;
                }
                long customEndMs = getActiveEndMarkerMs();
                long effectiveDur = (customEndMs > 0 && durationMs > 0)
                    ? Math.min(durationMs, customEndMs)
                    : (customEndMs > 0 ? customEndMs : durationMs);
                if (!currentTrackRewarded && effectiveDur > 4000 && positionMs >= effectiveDur * 0.60) {
                    currentTrackRewarded = true;
                    if (autoMixAnalyzer != null && currentIndex >= 0 && currentIndex < queue.size()) {
                        autoMixAnalyzer.recordFeedback(queue.get(currentIndex), false);
                    }
                }
                boolean canAutoMix = autoMixEnabled && !autoMixTransitioned && repeatMode != REPEAT_ONE
                    && (durationMs > 16000 || (customEndMs > 0 && durationMs > 4000));
                if (canAutoMix) {
                    AutoMixSelector.Features curFeat = autoMixAnalyzer != null
                        ? autoMixAnalyzer.getFeatures(currentIndex) : null;
                    int previewNext = nextIndex(false);
                    if (previewNext >= 0) {
                        AutoMixSelector.Features nextFeat = autoMixAnalyzer != null
                            ? autoMixAnalyzer.getFeatures(previewNext) : null;
                        int baseFadeMs = crossfadeOverrideMs > 0
                            ? crossfadeOverrideMs
                            : AutoMixSelector.recommendedCrossfadeMs(curFeat, nextFeat);
                        int crossfadeMs = crossfadeEnabled ? baseFadeMs : 0;
                        long cueOut = customEndMs > 0
                            ? Math.min(customEndMs, durationMs)
                            : ((curFeat != null && curFeat.cueOutMs > 8000 && curFeat.cueOutMs <= durationMs)
                                ? curFeat.cueOutMs
                                : Math.max(8000, durationMs - 1500));
                        long triggerMs = crossfadeEnabled
                            ? Math.max(1500, cueOut - crossfadeMs - 550)
                            : Math.max(1500, cueOut - 180);
                        if (positionMs >= triggerMs) {
                            autoMixTransitioned = true;
                            lastAutoMixTriggerMs = triggerMs;
                            crossfadeCooldownUntilMs = System.currentTimeMillis() + crossfadeMs + 1500L;
                            if (crossfadeEnabled) {
                                int style = forcedTransitionStyle >= 0
                                    ? forcedTransitionStyle
                                    : autoMixSelector.chooseTransitionStyle(curFeat, nextFeat);
                                crossfadeToIndex(previewNext, positionMs, curFeat, nextFeat, crossfadeMs, style);
                            } else {
                                playIndex(previewNext);
                            }
                        }
                    }
                } else if (customEndMs > 0 && positionMs >= customEndMs && !markerTriggered) {
                    markerTriggered = true;
                    advanceOnTrackEnd(true);
                } else if (customEndMs > 0 && positionMs + 1500 < customEndMs) {
                    markerTriggered = false;
                }
            }
            public void onRoute(String route) { if (uiListener != null) uiListener.onRoute(route); }
            public void onArtwork(Bitmap bitmap) { if (uiListener != null) uiListener.onArtwork(bitmap); }
            public void onMetadata(String title, String artist) {
                if (!jamGuest && currentIndex >= 0 && currentIndex < queue.size()) {
                    if (title != null) { currentTitle = title; titles.set(currentIndex, title); }
                    if (artist != null) { currentArtist = artist; artists.set(currentIndex, artist); }
                    if (instance != null) instance.updateNotification();
                }
                if (uiListener != null) uiListener.onMetadata(title, artist);
            }
            public void onFinished() {
                advanceOnTrackEnd(false);
            }
        });
        return player;
    }

    public static void setUiListener(FfmpegPcmPlayer.Listener listener) { uiListener = listener; }
    public static int getCurrentIndex() { return currentIndex; }
    public static boolean isJamGuest() { return jamGuest; }
    static boolean isJamTrack(Uri uri) { return jamGuest && uri.equals(jamUri); }
    static long jamPosition() { return lastPositionMs; }
    static JamSnapshot jamSnapshot() {
        Uri uri = jamGuest ? jamUri : currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex) : null;
        return new JamSnapshot(uri, currentTitle, currentArtist, lastPositionMs,
            player == null || !player.isRunning() || player.isPaused() || player.isRouteMuted(), player == null ? 1f : player.getSpeed());
    }
    static final class JamSnapshot {
        final Uri uri;
        final String title, artist;
        final long position;
        final boolean paused;
        final float speed;
        JamSnapshot(Uri uri, String title, String artist, long position, boolean paused, float speed) {
            this.uri = uri; this.title = title; this.artist = artist;
            this.position = position; this.paused = paused; this.speed = speed;
        }
    }
    static void beginJamGuest(Context context) {
        getPlayer(context).pause(); jamGuest = true; jamUri = null; currentArtwork = null;
        currentTitle = "Jam Wi-Fi Direct"; currentArtist = "Aguardando anfitrião";
        context.startForegroundService(new Intent(context, PlaybackService.class).setAction("START"));
    }
    static void pauseJamGuest() { if (jamGuest && player != null) { player.pause(); refresh(); refreshJamUi(); } }
    static void refreshJamUi() { if (jamGuest && uiListener != null) uiListener.onMetadata(currentTitle, currentArtist); }
    static void endJamGuest() {
        if (!jamGuest) return;
        if (player != null) { player.stop(); player.setSpeed(1f, true); }
        jamGuest = false; jamUri = null; currentIndex = -1; refresh();
        if (instance != null) instance.stopSelf();
        if (uiListener != null) {
            uiListener.onFinished();
            uiListener.onMessage("Jam encerrada · escolha uma música da sua biblioteca");
        }
    }
    static void playJamGuest(Context context, JamTransfer.Received track, long position, boolean paused) {
        if (!jamGuest || getPlayer(context).isRouteMuted()) return;
        jamUri = track.uri(context); currentIndex = -1;
        currentTitle = track.title; currentArtist = track.artist; currentArtwork = null;
        lastPositionMs = position; lastDurationMs = 0;
        Intent start = new Intent(context, PlaybackService.class).setAction("START");
        if (instance == null) context.startForegroundService(start);
        player.play(jamUri, position, paused);
        Uri expected = jamUri;
        CoverArtLoader.load(context, expected, bitmap -> {
            if (!jamGuest || !expected.equals(jamUri)) return;
            currentArtwork = bitmap; refresh();
            if (uiListener != null) uiListener.onArtwork(bitmap);
        });
        refresh();
        if (uiListener != null) uiListener.onMetadata(track.title, track.artist);
    }
    public static Bitmap getCurrentArtwork() { return currentArtwork; }
    public static void refreshLibraryMetadata(List<TrackInfo> tracks) {
        if (tracks.size() != queue.size()) return;
        for (int index = 0; index < tracks.size(); index++) {
            if (!queue.get(index).equals(tracks.get(index).uri)) return;
            titles.set(index, tracks.get(index).title);
            artists.set(index, tracks.get(index).artist);
        }
    }
    public static void setAutoMix(Context context, boolean enabled) {
        autoMixEnabled = enabled;
        ensureAnalyzer(context).setQueue(queue, currentIndex, enabled);
    }

    public static boolean isCrossfadeEnabled() { return crossfadeEnabled; }
    public static void setCrossfadeEnabled(boolean enabled) { crossfadeEnabled = enabled; }

    public static int getCrossfadeOverrideMs() { return crossfadeOverrideMs; }
    public static void setCrossfadeOverrideMs(int ms) { crossfadeOverrideMs = Math.max(0, ms); }

    public static int getForcedTransitionStyle() { return forcedTransitionStyle; }
    public static void setForcedTransitionStyle(int style) { forcedTransitionStyle = style; }

    public static boolean isConsiderTagsInAutoMix() { return considerTagsInAutoMix; }
    public static void setConsiderTagsInAutoMix(boolean enabled) { considerTagsInAutoMix = enabled; }

    public static String getTargetAutoMixTag() { return targetAutoMixTag; }
    public static void setTargetAutoMixTag(String tag) { targetAutoMixTag = tag == null ? "" : tag.trim(); }

    public static boolean isIgnoreAudioFocusLoss() { return ignoreAudioFocusLoss; }
    public static void setIgnoreAudioFocusLoss(boolean ignore) { ignoreAudioFocusLoss = ignore; }

    public static void releaseAudioFocusForExternalPlayer() {
        PlaybackService svc = instance;
        if (svc != null && svc.focusRequest != null) {
            try {
                AudioManager am = (AudioManager) svc.getSystemService(AUDIO_SERVICE);
                if (am != null) am.abandonAudioFocusRequest(svc.focusRequest);
            } catch (Throwable ignored) { }
        }
    }

    public static void setPlaybackScope(List<Integer> indices, String label) {
        activeScopeOrder.clear();
        activeScopeSet.clear();
        if (indices != null && !indices.isEmpty()) {
            for (Integer idx : indices) {
                if (idx != null && idx >= 0 && activeScopeSet.add(idx)) {
                    activeScopeOrder.add(idx);
                }
            }
            activeScopeName = label == null ? "" : label;
        } else {
            activeScopeName = "";
        }
    }

    public static String getActiveScopeName() { return activeScopeName; }
    public static int getActiveScopeSize() { return activeScopeOrder.size(); }

    public static int getRepeatMode() { return repeatMode; }
    public static void setRepeatMode(int mode) {
        repeatMode = (mode >= REPEAT_OFF && mode <= REPEAT_ONE) ? mode : REPEAT_OFF;
    }

    public static boolean isShuffleEnabled() { return shuffleEnabled; }
    public static void setShuffleEnabled(boolean enabled) {
        shuffleEnabled = enabled;
        shufflePlayed.clear();
        if (enabled && currentIndex >= 0 && currentIndex < queue.size()) {
            shufflePlayed.add(currentIndex);
        }
    }

    private static long getActiveEndMarkerMs() {
        if (autoMixAnalyzer == null || currentIndex < 0 || currentIndex >= queue.size()) return 0;
        return autoMixAnalyzer.getEndMarkerMs(queue.get(currentIndex));
    }

    public static long getEndMarkerMs(Context context, Uri uri) {
        return ensureAnalyzer(context).getEndMarkerMs(uri);
    }

    public static long toggleCurrentEndMarker(Context context, long uiPositionMs) {
        if (currentIndex < 0 || currentIndex >= queue.size()) return -1;
        long pos = uiPositionMs > 0 ? uiPositionMs : lastPositionMs;
        long result = ensureAnalyzer(context).toggleEndMarkerMs(queue.get(currentIndex), pos);
        markerTriggered = false;
        return result;
    }

    public static int getPlayCount(Context context, Uri uri) {
        return ensureAnalyzer(context).getPlayCount(uri);
    }

    public static ArrayList<TrackInfo> getMostPlayedTracks(Context context, List<TrackInfo> allTracks) {
        return ensureAnalyzer(context).getMostPlayedTracks(allTracks);
    }

    public static int peekNextForStoppedQueue(int queueSize, int fromIndex) {
        if (queueSize <= 0) return -1;
        if (shuffleEnabled && queueSize > 1) {
            int pick = ThreadLocalRandom.current().nextInt(queueSize - 1);
            return pick >= fromIndex ? pick + 1 : pick;
        }
        return (fromIndex + 1) % queueSize;
    }

    private static void advanceOnTrackEnd(boolean stopAudioIfEnded) {
        if (jamGuest) { pauseJamGuest(); return; }
        if (!currentTrackRewarded && currentIndex >= 0 && currentIndex < queue.size() && lastDurationMs > 3000) {
            currentTrackRewarded = true;
            if (autoMixAnalyzer != null) {
                autoMixAnalyzer.recordFeedback(queue.get(currentIndex), false);
            }
        }
        if (repeatMode == REPEAT_ONE && currentIndex >= 0 && currentIndex < queue.size()) {
            playIndex(currentIndex);
            return;
        }
        int next = nextIndex(false);
        if (next >= 0) {
            playIndex(next);
            return;
        }
        if (stopAudioIfEnded && player != null) player.stop();
        currentIndex = -1;
        if (instance != null) instance.stopSelf();
        if (uiListener != null) uiListener.onFinished();
    }

    public static void skipNext() {
        if (jamGuest) return;
        long customEndMs = getActiveEndMarkerMs();
        long effectiveDur = (customEndMs > 0 && lastDurationMs > 0)
            ? Math.min(lastDurationMs, customEndMs)
            : (customEndMs > 0 ? customEndMs : lastDurationMs);
        if (currentIndex >= 0 && currentIndex < queue.size() && effectiveDur > 10000) {
            if (lastPositionMs > 2500 && lastPositionMs < effectiveDur * 0.35) {
                autoMixSelector.markSessionSkip(currentIndex);
                if (autoMixAnalyzer != null) {
                    autoMixAnalyzer.recordFeedback(queue.get(currentIndex), true);
                }
            } else if (!currentTrackRewarded && lastPositionMs >= effectiveDur * 0.60) {
                currentTrackRewarded = true;
                if (autoMixAnalyzer != null) {
                    autoMixAnalyzer.recordFeedback(queue.get(currentIndex), false);
                }
            }
        }
        int next = nextIndex(true);
        if (next < 0) return;
        if (autoMixEnabled && crossfadeEnabled && player != null && player.isRunning() && !player.isPaused()) {
            AutoMixSelector.Features curFeat = autoMixAnalyzer != null ? autoMixAnalyzer.getFeatures(currentIndex) : null;
            AutoMixSelector.Features nextFeat = autoMixAnalyzer != null ? autoMixAnalyzer.getFeatures(next) : null;
            autoMixTransitioned = true;
            lastAutoMixTriggerMs = lastPositionMs;
            crossfadeCooldownUntilMs = System.currentTimeMillis() + 3000L;
            int style = forcedTransitionStyle >= 0
                ? forcedTransitionStyle
                : autoMixSelector.chooseTransitionStyle(curFeat, nextFeat);
            crossfadeToIndex(next, 0, curFeat, nextFeat, 1600, style);
        } else {
            playIndex(next);
        }
    }

    private static int nextIndex(boolean wrap) {
        if (queue.isEmpty()) return -1;
        boolean hasScope = !activeScopeSet.isEmpty();
        if (autoMixEnabled) {
            int eligibleCount = hasScope ? activeScopeSet.size() : queue.size();
            if (eligibleCount == 1) {
                int only = hasScope ? activeScopeOrder.get(0) : 0;
                return (wrap || repeatMode == REPEAT_ALL) ? only : -1;
            }
            java.util.Map<Integer, Double> tagBonuses = null;
            if (considerTagsInAutoMix && appContextRef != null && currentIndex >= 0 && currentIndex < queue.size()) {
                TrackTagDatabase tagDb = TrackTagDatabase.get(appContextRef);
                String srcUri = queue.get(currentIndex).toString();
                tagBonuses = new java.util.HashMap<>();
                for (int i = 0; i < queue.size(); i++) {
                    if (i == currentIndex) continue;
                    if (hasScope && !activeScopeSet.contains(i)) continue;
                    double bonus = tagDb.computeAutoMixTagScore(srcUri, queue.get(i).toString(), targetAutoMixTag);
                    if (Math.abs(bonus) > 0.01) {
                        tagBonuses.put(i, bonus);
                    }
                }
            }
            return autoMixSelector.choose(
                currentIndex,
                artists,
                autoMixAnalyzer == null ? Collections.emptyMap() : autoMixAnalyzer.snapshot(),
                autoMixAnalyzer == null ? Collections.emptyMap() : autoMixAnalyzer.tasteSnapshot(),
                hasScope ? activeScopeSet : null,
                tagBonuses
            );
        }
        if (shuffleEnabled) {
            return chooseShuffleIndex(wrap);
        }
        if (hasScope) {
            int pos = activeScopeOrder.indexOf(currentIndex);
            if (pos >= 0) {
                return (pos + 1 < activeScopeOrder.size())
                    ? activeScopeOrder.get(pos + 1)
                    : (wrap || repeatMode == REPEAT_ALL) ? activeScopeOrder.get(0) : -1;
            }
            return activeScopeOrder.get(0);
        }
        int next = currentIndex + 1;
        return next < queue.size() ? next : (wrap || repeatMode == REPEAT_ALL) ? 0 : -1;
    }

    public static int previousScopedIndex(int totalSize, int fromIndex) {
        if (totalSize <= 0) return -1;
        if (!activeScopeOrder.isEmpty()) {
            int pos = activeScopeOrder.indexOf(fromIndex);
            if (pos > 0) return activeScopeOrder.get(pos - 1);
            return activeScopeOrder.get(activeScopeOrder.size() - 1);
        }
        return fromIndex <= 0 ? totalSize - 1 : fromIndex - 1;
    }

    private static int chooseShuffleIndex(boolean wrap) {
        int size = queue.size();
        if (size <= 0) return -1;
        boolean hasScope = !activeScopeSet.isEmpty();
        int poolSize = hasScope ? activeScopeSet.size() : size;
        if (poolSize == 1) {
            int only = hasScope ? activeScopeOrder.get(0) : 0;
            return (wrap || repeatMode == REPEAT_ALL) ? only : -1;
        }
        ArrayList<Integer> candidates = new ArrayList<>();
        if (hasScope) {
            for (int idx : activeScopeOrder) {
                if (idx >= 0 && idx < size && idx != currentIndex && !shufflePlayed.contains(idx)) {
                    candidates.add(idx);
                }
            }
        } else {
            for (int i = 0; i < size; i++) {
                if (i != currentIndex && !shufflePlayed.contains(i)) candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            if (!wrap && repeatMode == REPEAT_OFF) return -1;
            shufflePlayed.clear();
            if (currentIndex >= 0 && currentIndex < size) shufflePlayed.add(currentIndex);
            if (hasScope) {
                for (int idx : activeScopeOrder) {
                    if (idx >= 0 && idx < size && idx != currentIndex) candidates.add(idx);
                }
            } else {
                for (int i = 0; i < size; i++) {
                    if (i != currentIndex) candidates.add(i);
                }
            }
        }
        if (candidates.isEmpty()) return -1;
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    public static void stopPlayback(Context context) {
        if (player != null) player.stop();
        currentIndex = -1;
        if (autoMixAnalyzer != null) autoMixAnalyzer.setQueue(Collections.emptyList(), -1, false);
        context.stopService(new Intent(context, PlaybackService.class));
    }

    public static void playAt(Context context, List<TrackInfo> tracks, int index) {
        if (jamGuest) {
            if (uiListener != null) uiListener.onMessage("Encerre a Jam para tocar sua biblioteca neste celular");
            return;
        }
        getPlayer(context);
        player.allowOutput();
        queue.clear();
        titles.clear();
        artists.clear();
        for (TrackInfo item : tracks) { queue.add(item.uri); titles.add(item.title); artists.add(item.artist); }
        currentIndex = index;
        autoMixTransitioned = false;
        currentTrackRewarded = false;
        markerTriggered = false;
        lastPositionMs = 0;
        lastDurationMs = 0;
        shufflePlayed.clear();
        shufflePlayed.add(index);
        autoMixSelector.reset();
        autoMixSelector.played(index);
        ensureAnalyzer(context).setQueue(queue, index, autoMixEnabled);
        currentTitle = tracks.get(index).title;
        currentArtist = tracks.get(index).artist;
        currentArtwork = tracks.get(index).artwork;
        Intent start = new Intent(context, PlaybackService.class);
        start.setAction("START");
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(start); else context.startService(start);
        player.play(queue.get(index), 0);
        loadCover(context.getApplicationContext(), index);
        if (instance != null) instance.updateNotification();
    }

    private static void crossfadeToIndex(int index, long currentPosMs,
            AutoMixSelector.Features curFeat, AutoMixSelector.Features nextFeat, int crossfadeMs, int transitionStyle) {
        if (player == null || index < 0 || index >= queue.size()) return;
        currentIndex = index;
        currentTrackRewarded = false;
        markerTriggered = false;
        lastPositionMs = 0;
        shufflePlayed.add(index);
        autoMixSelector.played(index);
        Uri uri = queue.get(index);
        currentTitle = titles.get(index);
        currentArtist = artists.get(index);
        currentArtwork = null;

        long startMs = AutoMixSelector.alignCueInToPhrase(nextFeat, crossfadeMs);
        if (currentPosMs > 0 && curFeat != null && nextFeat != null && curFeat.bpm > 0 && nextFeat.bpm > 0) {
            double beatPeriodA = 60000.0 / curFeat.bpm;
            double beatPeriodB = 60000.0 / nextFeat.bpm;
            double phaseA = ((currentPosMs - curFeat.beatOffsetMs) % beatPeriodA + beatPeriodA) % beatPeriodA;
            double phaseB = ((startMs - nextFeat.beatOffsetMs) % beatPeriodB + beatPeriodB) % beatPeriodB;
            double targetPhaseB = (phaseA / beatPeriodA) * beatPeriodB;
            double shiftMs = targetPhaseB - phaseB;
            if (shiftMs < -beatPeriodB * 0.5) shiftMs += beatPeriodB;
            if (shiftMs > beatPeriodB * 0.5) shiftMs -= beatPeriodB;
            startMs = Math.max(0, Math.round(startMs + shiftMs));
        }

        double gainRatio = AutoMixSelector.recommendedGainRatio(curFeat, nextFeat);
        player.crossfadeTo(uri, startMs, crossfadeMs, transitionStyle, gainRatio);
        if (instance != null) loadCover(instance, index);
        if (instance != null) instance.updateNotification();
        if (uiListener != null) uiListener.onFinished();
    }

    private static void playIndex(int index) {
        if (player == null || index < 0 || index >= queue.size()) return;
        currentIndex = index;
        autoMixTransitioned = false;
        currentTrackRewarded = false;
        markerTriggered = false;
        lastPositionMs = 0;
        shufflePlayed.add(index);
        autoMixSelector.played(index);
        Uri uri = queue.get(index);
        currentTitle = titles.get(index);
        currentArtist = artists.get(index);
        currentArtwork = null;
        long startMs = 0;
        if (autoMixEnabled && autoMixAnalyzer != null) {
            AutoMixSelector.Features nextFeat = autoMixAnalyzer.getFeatures(index);
            if (nextFeat != null && nextFeat.cueInMs > 150) startMs = nextFeat.cueInMs;
        }
        player.play(uri, startMs);
        if (instance != null) loadCover(instance, index);
        if (instance != null) instance.updateNotification();
        if (uiListener != null) uiListener.onFinished();
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Reprodução de música", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        session = new MediaSession(this, "vertice_session");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { control("RESUME"); }
            @Override public void onPause() { control("PAUSE"); }
            @Override public void onSkipToNext() { control("NEXT"); }
            @Override public void onSkipToPrevious() { control("PREVIOUS"); }
        });
        session.setActive(true);
        AudioManager manager = (AudioManager)getSystemService(AUDIO_SERVICE);
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(change -> {
                if (ignoreAudioFocusLoss) {
                    // Blindagem ativa: outros apps não pausam a música do Vértice!
                    return;
                }
                if (change < 0 && player != null) {
                    JamSession jam = JamService.current();
                    if (jamGuest && jam != null) jam.setGuestPaused(true);
                    player.pause();
                    updateNotification();
                }
            }).build();
        if (!ignoreAudioFocusLoss) {
            manager.requestAudioFocus(focusRequest);
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        headphoneSafety = new HeadphoneSafety(this, () -> {
            if (player == null) return;
            player.muteForHeadphoneDisconnect();
            autoMixTransitioned = true;
            updateNotification();
            if (uiListener != null) uiListener.onMessage("Fone desconectado · reprodução pausada e saída silenciada");
        });
        headphoneSafety.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "START_PROJECTION".equals(intent.getAction())) {
            int resCode = intent.getIntExtra("resultCode", 0);
            Intent projData = intent.getParcelableExtra("data");
            boolean monitor = intent.getBooleanExtra("monitor", false);
            currentTitle = "Spotify / Sistema · Live 64-Bit DSP";
            currentArtist = "Capturando e processando em tempo real";
            // Release AudioFocus so Spotify / external player keeps playing without pausing!
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null && focusRequest != null) {
                am.abandonAudioFocusRequest(focusRequest);
            }
            if (Build.VERSION.SDK_INT >= 29 && projData != null) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                android.media.projection.MediaProjectionManager mpm =
                    (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                if (mpm != null) {
                    android.media.projection.MediaProjection proj = mpm.getMediaProjection(resCode, projData);
                    getPlayer(this).startLiveCapture(proj, monitor);
                }
            }
            updateNotification();
            return START_NOT_STICKY;
        }
        if (intent != null && intent.getAction() != null && !"START".equals(intent.getAction())) control(intent.getAction());
        updateNotification();
        return START_NOT_STICKY;
    }

    private void control(String action) {
        if (player == null) return;
        if (jamGuest) {
            JamSession jam = JamService.current();
            if (jam != null && ("PAUSE".equals(action) || "RESUME".equals(action))) jam.setGuestPaused("PAUSE".equals(action));
            updateNotification(); return;
        }
        switch (action) {
            case "PAUSE": player.pause(); break;
            case "RESUME": player.resume(); break;
            case "NEXT": skipNext(); break;
            case "PREVIOUS":
                if (!queue.isEmpty()) playIndex(previousScopedIndex(queue.size(), currentIndex));
                break;
        }
        updateNotification();
    }

    public static void refresh() { if (instance != null) instance.updateNotification(); }

    private static void loadCover(Context context, int index) {
        Uri uri = queue.get(index);
        CoverArtLoader.load(context, uri, bitmap -> {
            if (index != currentIndex) return;
            currentArtwork = bitmap;
            if (instance != null) instance.updateNotification();
            if (uiListener != null) uiListener.onArtwork(bitmap);
        });
    }

    private PendingIntent action(String action, int requestCode) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        boolean paused = player != null && player.isPaused();
        Bitmap notificationArt = currentArtwork;
        if (notificationArt != null && Math.max(notificationArt.getWidth(), notificationArt.getHeight()) > 256) {
            float scale = 256f / Math.max(notificationArt.getWidth(), notificationArt.getHeight());
            notificationArt = Bitmap.createScaledBitmap(notificationArt,
                Math.max(1, Math.round(notificationArt.getWidth() * scale)),
                Math.max(1, Math.round(notificationArt.getHeight() * scale)), true);
        }
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist);
        if (notificationArt != null) metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, notificationArt);
        session.setMetadata(metadata.build());
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(paused ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1).build());
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder notification = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(currentTitle).setContentText(currentArtist)
            .setContentIntent(content).setOnlyAlertOnce(true).setOngoing(!paused)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", action("PREVIOUS", 1))
            .addAction(paused ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause,
                paused ? "Reproduzir" : "Pausar", action(paused ? "RESUME" : "PAUSE", 2))
            .addAction(android.R.drawable.ic_media_next, "Próxima", action("NEXT", 3))
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        if (notificationArt != null) notification.setLargeIcon(notificationArt);
        return notification.build();
    }

    private void updateNotification() {
        if (session != null) getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    @Override public void onDestroy() {
        if (headphoneSafety != null) headphoneSafety.close();
        instance = null;
        if (player != null) player.stop();
        AudioManager manager = (AudioManager)getSystemService(AUDIO_SERVICE);
        if (focusRequest != null && manager != null) manager.abandonAudioFocusRequest(focusRequest);
        if (session != null) { session.setActive(false); session.release(); }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
