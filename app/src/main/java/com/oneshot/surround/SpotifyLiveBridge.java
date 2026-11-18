package com.oneshot.surround;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.BassBoost;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.EnvironmentalReverb;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Virtualizer;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import android.view.KeyEvent;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Complete Spotify & Spotron DSP Bridge:
 * 1. Full App Effect Control on Spotify's Audio Session (Priority 10000):
 *    Hooks EVERY Vértice effect (Equalizer 10-band + AutoEQ + L/R Balance, CludesAmp Volume/Width/Bass/Treble/Clarity/5-Band/Presets/Loudness/Pultec,
 *    8 Binaural 3D Modes + 8D Orbit, Structural Ad-Lib Unmasker/Vocal Lock/Beat Snap, Tactile Sub/Haas ADT/Dimension D/Groove/Venues/Vinyl,
 *    DSEE Hi-Res Air, Analog Saturation, De-Clipper, and A/B Bypass) into `DynamicsProcessing` + `Virtualizer` + `BassBoost` + `LoudnessEnhancer` + `EnvironmentalReverb`.
 * 2. Full Spotify Transport Control (`▶/❚❚`, `❮❮`, `❯❯` from Vértice's Main Screen & Mini-Player):
 *    Dispatches native hardware MediaKeyEvents directly to Spotify and syncs live track title, artist, play state, and 600x600 cover art.
 * 3. Live WebSocket Remote Control & Exporter for `dmrangel/spotron` (`camilladsp.exe -p 1234`).
 */
public final class SpotifyLiveBridge {
    private static final int EFFECT_PRIORITY = 10000;
    private static final ExecutorService coverWorker = Executors.newSingleThreadExecutor();
    private static final ExecutorService wsWorker = Executors.newSingleThreadExecutor();

    private static DynamicsProcessing directDsp;
    private static Virtualizer directVirtualizer;
    private static BassBoost directBassBoost;
    private static LoudnessEnhancer directLoudness;
    private static EnvironmentalReverb directReverb;
    private static Thread orbitAnimatorThread;

    private static volatile boolean directDspEnabled;
    private static volatile boolean spotifyControlFocused;
    private static volatile boolean liveCamillaSyncEnabled;
    private static volatile String camillaHostIp = "";
    private static boolean receiverRegistered;
    private static int spotifySession;
    private static boolean directRequested;
    private static String directStatus = "Abra o Spotify e inicie uma música.";

    private static String lastSpotifyId = "";
    private static String lastSpotifyTrack = "";
    private static String lastSpotifyArtist = "";
    private static Bitmap lastSpotifyCover;
    private static long lastSpotifyDurationMs = 0L;
    private static long lastSpotifyPosMs = 0L;
    private static long lastSpotifyPosTimestampMs = 0L;
    private static long lastManualPauseClickMs = 0L;
    private static boolean spotifyPlaying;

    private static final android.os.Handler progressHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (spotifyPlaying && activeHost != null) {
                if (!activeLocalPlayback(activeHost)) {
                    long delta = Math.max(0L, SystemClock.elapsedRealtime() - lastSpotifyPosTimestampMs);
                    long currentPos = lastSpotifyPosMs + delta;
                    long totalDur = lastSpotifyDurationMs;
                    if (totalDur > 0 && currentPos > totalDur) currentPos = totalDur;
                    activeHost.durationMs = totalDur;
                    activeHost.showProgress(currentPos, totalDur);
                }
            }
            if (spotifyPlaying) {
                progressHandler.postDelayed(this, 250L);
            }
        }
    };

    public static String getDirectStatus() { return directStatus; }
    public static boolean isSpotifyPlaying() { return spotifyPlaying; }
    public static Bitmap getLastSpotifyCover() { return lastSpotifyCover; }
    public static long getLastSpotifyDurationMs() { return lastSpotifyDurationMs; }
    public static long getCurrentSpotifyPositionMs() {
        if (!spotifyPlaying) return lastSpotifyPosMs;
        long pos = lastSpotifyPosMs + Math.max(0L, SystemClock.elapsedRealtime() - lastSpotifyPosTimestampMs);
        return (lastSpotifyDurationMs > 0 && pos > lastSpotifyDurationMs) ? lastSpotifyDurationMs : pos;
    }

    public static void setSpotifyControlFocus(boolean focused) {
        spotifyControlFocused = focused;
        if (focused) {
            PlaybackService.releaseAudioFocusForExternalPlayer();
        }
    }

    /**
     * Returns true when Vértice's transport buttons (`▶/❚❚`, `❮❮`, `❯❯`) should control Spotify
     * instead of the local file queue.
     */
    public static boolean isSpotifyControlActive(MainActivity host) {
        if (host == null) return false;
        if (activeLocalPlayback(host)) return false;
        return directDspEnabled || spotifyControlFocused || spotifyPlaying
            || (host.player != null && host.player.isLiveCaptureActive());
    }

    private static boolean activeLocalPlayback(MainActivity host) {
        return host.player != null && host.player.isRunning() && !host.player.isPaused() && !host.player.isLiveCaptureActive();
    }

    public static void toggleSpotifyPlayPause(MainActivity host) {
        spotifyControlFocused = true;
        PlaybackService.releaseAudioFocusForExternalPlayer();
        lastManualPauseClickMs = SystemClock.elapsedRealtime();
        sendMediaKey(host, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        spotifyPlaying = !spotifyPlaying;
        lastSpotifyPosMs = getCurrentSpotifyPositionMs();
        lastSpotifyPosTimestampMs = SystemClock.elapsedRealtime();
        progressHandler.removeCallbacks(progressRunnable);
        if (spotifyPlaying) progressHandler.post(progressRunnable);
        if (host != null) {
            host.playing = spotifyPlaying;
            if (host.playButton != null) host.playButton.setText(spotifyPlaying ? "❚❚" : "▶");
            if (host.artwork != null) host.artwork.setPlaying(spotifyPlaying);
            if (host.status != null) {
                host.status.setText(spotifyPlaying ? "🟢 Spotify: Reproduzindo com DSP Vértice" : "🟢 Spotify: Pausado");
            }
            host.playerUi.syncMiniPlayerFromMain();
        }
    }

    public static void nextSpotifyTrack(MainActivity host) {
        spotifyControlFocused = true;
        PlaybackService.releaseAudioFocusForExternalPlayer();
        sendMediaKey(host, KeyEvent.KEYCODE_MEDIA_NEXT);
        spotifyPlaying = true;
        lastSpotifyPosMs = 0L;
        lastSpotifyPosTimestampMs = SystemClock.elapsedRealtime();
        progressHandler.removeCallbacks(progressRunnable);
        progressHandler.post(progressRunnable);
        if (host != null) {
            host.playing = true;
            if (host.playButton != null) host.playButton.setText("❚❚");
            if (host.artwork != null) host.artwork.setPlaying(true);
            if (host.status != null) host.status.setText("🟢 Spotify: Próxima faixa ❯❯");
            host.playerUi.syncMiniPlayerFromMain();
        }
    }

    public static void previousSpotifyTrack(MainActivity host) {
        spotifyControlFocused = true;
        PlaybackService.releaseAudioFocusForExternalPlayer();
        sendMediaKey(host, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        spotifyPlaying = true;
        lastSpotifyPosMs = 0L;
        lastSpotifyPosTimestampMs = SystemClock.elapsedRealtime();
        progressHandler.removeCallbacks(progressRunnable);
        progressHandler.post(progressRunnable);
        if (host != null) {
            host.playing = true;
            if (host.playButton != null) host.playButton.setText("❚❚");
            if (host.artwork != null) host.artwork.setPlaying(true);
            if (host.status != null) host.status.setText("🟢 Spotify: Faixa anterior ❮❮");
            host.playerUi.syncMiniPlayerFromMain();
        }
    }

    private static void sendMediaKey(Context context, int keyCode) {
        if (context == null) return;
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
            KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0);
            am.dispatchMediaKeyEvent(down);
            am.dispatchMediaKeyEvent(up);
        } catch (Throwable ignored) { }
    }

    private static long extractLongExtra(Intent intent, String key, long fallback) {
        if (intent == null || intent.getExtras() == null) return fallback;
        Object raw = intent.getExtras().get(key);
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof String) {
            try { return Long.parseLong((String) raw); } catch (Throwable ignored) { }
        }
        return fallback;
    }

    /**
     * Called automatically whenever ANY knob, slider, switch, or preset in Vértice is adjusted!
     */
    public static void onPlayerDspChanged() {
        MainActivity h = activeHost;
        if (h != null) {
            syncDirectDspParameters(h);
        }
    }

    private static final BroadcastReceiver spotifyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(action)) {
                String pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
                if (context != null && context.getPackageName().equals(pkg)) return;
                int session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
                if (session <= 0) return;
                onExternalAudioSessionOpened(session, pkg);
                return;
            }
            if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(action)) {
                int closed = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
                if (closed == spotifySession && closed > 0) {
                    boolean requested = directRequested;
                    releaseEffectsOnly();
                    directRequested = requested;
                    directStatus = "Sessão pausada · aguardando próxima faixa do Spotify.";
                }
                return;
            }
            if ("com.spotify.music.metadatachanged".equals(action)) {
                String id = intent.getStringExtra("id");
                String track = intent.getStringExtra("track");
                String artist = intent.getStringExtra("artist");
                String album = intent.getStringExtra("album");
                long length = extractLongExtra(intent, "length", 0L);
                long pos = extractLongExtra(intent, "playbackPosition", 0L);
                if (track != null && !track.isEmpty()) {
                    boolean changedTrack = !track.equals(lastSpotifyTrack) || (id != null && !id.equals(lastSpotifyId));
                    if (id != null) lastSpotifyId = id;
                    lastSpotifyTrack = track;
                    lastSpotifyArtist = artist == null ? "Spotify" : artist;
                    if (length > 0) lastSpotifyDurationMs = length;
                    if (changedTrack || pos >= 0) {
                        lastSpotifyPosMs = Math.max(0L, pos);
                        lastSpotifyPosTimestampMs = SystemClock.elapsedRealtime();
                    }
                    spotifyControlFocused = true;
                    PlaybackService.releaseAudioFocusForExternalPlayer();
                    if (activeHost != null) {
                        activeHost.runOnUiThread(() -> {
                            if (!activeLocalPlayback(activeHost)) {
                                activeHost.title.setText(lastSpotifyTrack);
                                activeHost.artist.setText(lastSpotifyArtist + (album != null ? " • " + album : " • Spotify DSP"));
                                if (lastSpotifyDurationMs > 0) {
                                    activeHost.durationMs = lastSpotifyDurationMs;
                                    activeHost.showProgress(getCurrentSpotifyPositionMs(), lastSpotifyDurationMs);
                                }
                                activeHost.playerUi.syncMiniPlayerFromMain();
                            }
                        });
                        fetchOnlineArtwork(activeHost, lastSpotifyId, lastSpotifyArtist, lastSpotifyTrack);
                    }
                }
            } else if ("com.spotify.music.playbackstatechanged".equals(action)) {
                boolean wasPlaying = spotifyPlaying;
                boolean nowPlaying = intent.getBooleanExtra("playing", false);
                long pos = extractLongExtra(intent, "playbackPosition", -1L);
                long length = extractLongExtra(intent, "length", 0L);
                if (length > 0) lastSpotifyDurationMs = length;
                if (pos >= 0) {
                    lastSpotifyPosMs = pos;
                    lastSpotifyPosTimestampMs = SystemClock.elapsedRealtime();
                }

                // Anti-Pause Shield: if another app interrupted Spotify while Shield + Direct DSP are active, resume!
                if (wasPlaying && !nowPlaying && directDspEnabled && PlaybackService.isIgnoreAudioFocusLoss()
                        && (SystemClock.elapsedRealtime() - lastManualPauseClickMs > 3000L) && context != null) {
                    progressHandler.postDelayed(() -> {
                        if (!spotifyPlaying && directDspEnabled && (SystemClock.elapsedRealtime() - lastManualPauseClickMs > 3000L)) {
                            sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY);
                        }
                    }, 220L);
                }

                spotifyPlaying = nowPlaying;
                if (spotifyPlaying) {
                    spotifyControlFocused = true;
                    PlaybackService.releaseAudioFocusForExternalPlayer();
                    progressHandler.removeCallbacks(progressRunnable);
                    progressHandler.post(progressRunnable);
                } else {
                    progressHandler.removeCallbacks(progressRunnable);
                }
                if (activeHost != null) {
                    activeHost.runOnUiThread(() -> {
                        if (!activeLocalPlayback(activeHost)) {
                            activeHost.playing = spotifyPlaying;
                            if (activeHost.playButton != null) activeHost.playButton.setText(spotifyPlaying ? "❚❚" : "▶");
                            if (activeHost.artwork != null) activeHost.artwork.setPlaying(spotifyPlaying || directDspEnabled);
                            activeHost.showProgress(getCurrentSpotifyPositionMs(), lastSpotifyDurationMs);
                            activeHost.playerUi.syncMiniPlayerFromMain();
                        }
                    });
                }
            }
        }
    };

    private static MainActivity activeHost;

    private SpotifyLiveBridge() { }

    public static void attachHost(MainActivity host) {
        activeHost = host;
        if (host != null) {
            camillaHostIp = host.getPreferences(Context.MODE_PRIVATE).getString("spotronPcIp", "");
            if (spotifySession <= 0) {
                spotifySession = host.getPreferences(Context.MODE_PRIVATE).getInt("lastSpotifySessionId", 0);
            }
            if (!receiverRegistered) {
                try {
                    IntentFilter filter = new IntentFilter();
                    filter.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
                    filter.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
                    filter.addAction("com.spotify.music.metadatachanged");
                    filter.addAction("com.spotify.music.playbackstatechanged");
                    filter.addAction("com.spotify.music.queuechanged");
                    if (Build.VERSION.SDK_INT >= 33) {
                        host.registerReceiver(spotifyReceiver, filter, Context.RECEIVER_EXPORTED);
                    } else {
                        androidx.core.content.ContextCompat.registerReceiver(host, spotifyReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
                    }
                    receiverRegistered = true;
                } catch (Throwable ignored) { }
            }
        }
    }

    public static void detachHost(MainActivity host) {
        if (activeHost == host) {
            if (receiverRegistered && host != null) {
                try { host.unregisterReceiver(spotifyReceiver); } catch (Throwable ignored) { }
                receiverRegistered = false;
            }
            activeHost = null;
        }
    }

    public static boolean isDirectDspEnabled() { return directDspEnabled; }
    public static boolean isLiveCamillaSyncEnabled() { return liveCamillaSyncEnabled; }
    public static String getCamillaHostIp() { return camillaHostIp; }
    public static void setCamillaHostIp(MainActivity host, String ip, boolean autoSync) {
        camillaHostIp = ip == null ? "" : ip.trim();
        liveCamillaSyncEnabled = autoSync && !camillaHostIp.isEmpty();
        if (host != null) {
            host.getPreferences(Context.MODE_PRIVATE).edit().putString("spotronPcIp", camillaHostIp).apply();
        }
        if (liveCamillaSyncEnabled && host != null) {
            pushConfigToSpotronPc(host, null);
        }
    }

    public static String getLastSpotifyTrack() { return lastSpotifyTrack; }
    public static String getLastSpotifyArtist() { return lastSpotifyArtist; }

    public static synchronized void onExternalAudioSessionOpened(int session, String pkg) {
        if (session <= 0) return;
        boolean requested = directRequested || directDspEnabled;
        if (spotifySession != session) {
            releaseEffectsOnly();
            spotifySession = session;
        }
        if (activeHost != null) {
            activeHost.getPreferences(Context.MODE_PRIVATE).edit().putInt("lastSpotifySessionId", session).apply();
        }
        directStatus = "Sessão conectada (" + (pkg != null ? pkg : "Spotify") + " • ID " + session + ").";
        spotifyControlFocused = true;
        if (requested && activeHost != null) {
            setDirectSpotifyDsp(activeHost, true);
        }
    }

    public static void inspectIncomingIntent(MainActivity host, Intent intent) {
        if (intent == null) return;
        int session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
        String pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
        if (session > 0) {
            directRequested = true;
            spotifyControlFocused = true;
            onExternalAudioSessionOpened(session, pkg != null ? pkg : "com.spotify.music");
            if (host != null && host.status != null) {
                host.status.setText("🟢 Sessão do Spotify conectada (ID " + session + ") · Todos os efeitos ativos!");
            }
        }
    }

    private static synchronized void releaseEffectsOnly() {
        directDspEnabled = false;
        if (orbitAnimatorThread != null) {
            orbitAnimatorThread.interrupt();
            orbitAnimatorThread = null;
        }
        if (directDsp != null && Build.VERSION.SDK_INT >= 28) {
            try { directDsp.setEnabled(false); directDsp.release(); } catch (Throwable ignored) { }
            directDsp = null;
        }
        if (directVirtualizer != null) {
            try { directVirtualizer.setEnabled(false); directVirtualizer.release(); } catch (Throwable ignored) { }
            directVirtualizer = null;
        }
        if (directBassBoost != null) {
            try { directBassBoost.setEnabled(false); directBassBoost.release(); } catch (Throwable ignored) { }
            directBassBoost = null;
        }
        if (directLoudness != null) {
            try { directLoudness.setEnabled(false); directLoudness.release(); } catch (Throwable ignored) { }
            directLoudness = null;
        }
        if (directReverb != null) {
            try { directReverb.setEnabled(false); directReverb.release(); } catch (Throwable ignored) { }
            directReverb = null;
        }
    }

    public static synchronized boolean setDirectSpotifyDsp(MainActivity host, boolean enabled) {
        directRequested = enabled;
        if (!enabled) {
            directStatus = "DSP desligado.";
            releaseEffectsOnly();
            return false;
        }

        spotifyControlFocused = true;
        if (Build.VERSION.SDK_INT < 28) {
            directStatus = "DSP requer Android 9 ou superior.";
            return false;
        }
        if (spotifySession <= 0 && host != null) {
            spotifySession = host.getPreferences(Context.MODE_PRIVATE).getInt("lastSpotifySessionId", 0);
        }

        int[] candidateSessions = spotifySession > 0 ? new int[]{spotifySession, 0} : new int[]{0};
        for (int targetSession : candidateSessions) {
            try {
                releaseEffectsOnly();
                DynamicsProcessing.Config.Builder builder = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2, true, 10, true, 6, true, 10, true
                );
                int prio = targetSession == 0 ? 0 : EFFECT_PRIORITY;
                directDsp = new DynamicsProcessing(prio, targetSession, builder.build());
                try { directVirtualizer = new Virtualizer(prio, targetSession); } catch (Throwable ignored) { }
                try { directReverb = new EnvironmentalReverb(prio, targetSession); } catch (Throwable ignored) { }

                directDspEnabled = true;
                syncDirectDspParameters(host);
                if (directDsp.setEnabled(true) != AudioEffect.SUCCESS) {
                    throw new IllegalStateException("Sessão recusada");
                }
                directStatus = "🟢 CamillaDSP / Vértice 3D ativo (Sessão " + (targetSession == 0 ? "Global 0" : ("Spotify ID " + targetSession)) + ")!";
                ensureOrbitAnimator();
                return true;
            } catch (Throwable ignored) {
                releaseEffectsOnly();
            }
        }

        if (host != null && spotifySession > 0) {
            try {
                directVirtualizer = new Virtualizer(EFFECT_PRIORITY, spotifySession);
                if (directVirtualizer.hasControl()) {
                    directDspEnabled = true;
                    syncDirectDspParameters(host);
                    if (directDspEnabled) return true;
                }
            } catch (RuntimeException ignored) { }
            releaseEffectsOnly();
        }
        directStatus = "Aguardando sessão compatível. Inicie uma faixa no Spotify.";
        return false;
    }

    private static void ensureOrbitAnimator() {
        if (orbitAnimatorThread != null && orbitAnimatorThread.isAlive()) return;
        orbitAnimatorThread = new Thread(() -> {
            double phase = 0.0;
            while (directDspEnabled && Build.VERSION.SDK_INT >= 28) {
                try {
                    Thread.sleep(45);
                    MainActivity h = activeHost;
                    if (h != null && directDsp != null && (h.mode == SpatialProcessor.ORBIT || h.cludesOrbit)) {
                        double depth = h.intensity / 100.0;
                        phase += 0.09 + depth * 0.14;
                        if (phase > Math.PI * 2.0) phase -= Math.PI * 2.0;
                        float pan = (float) Math.sin(phase);
                        float elev = (float) Math.cos(phase);
                        float attenL = (float) (-Math.max(0.0, pan) * 6.5 * depth);
                        float attenR = (float) (-Math.max(0.0, -pan) * 6.5 * depth);
                        float[] cutoffs = {45f, 90f, 180f, 355f, 710f, 1400f, 2800f, 5600f, 11200f, 20000f};
                        synchronized (SpotifyLiveBridge.class) {
                            if (directDsp != null && Build.VERSION.SDK_INT >= 28) {
                                for (int b = 2; b < 10; b++) {
                                    float pinnaL = (b == 6 ? 2.5f * elev : (b == 8 ? -2.0f * elev : 0f));
                                    float pinnaR = (b == 6 ? -2.5f * elev : (b == 8 ? 2.0f * elev : 0f));
                                    directDsp.setPostEqBandByChannelIndex(0, b, new DynamicsProcessing.EqBand(true, cutoffs[b], attenL + pinnaL));
                                    directDsp.setPostEqBandByChannelIndex(1, b, new DynamicsProcessing.EqBand(true, cutoffs[b], attenR + pinnaR));
                                }
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) { }
            }
        }, "spotify-8d-orbit");
        orbitAnimatorThread.setDaemon(true);
        orbitAnimatorThread.start();
    }

    public static synchronized void syncDirectDspParameters(MainActivity host) {
        if (host != null && liveCamillaSyncEnabled && !camillaHostIp.isEmpty()) {
            pushConfigToSpotronPc(host, null);
        }
        if (directDspEnabled && directDsp == null && directVirtualizer != null && host != null) {
            boolean active = host.mode != SpatialProcessor.OFF && host.intensity > 0
                && (host.player == null || !host.player.isBypassEffects());
            try {
                if (active && directVirtualizer.getStrengthSupported()) {
                    directVirtualizer.setStrength((short) Math.min(1000, host.intensity * 10));
                }
                if (directVirtualizer.setEnabled(active) != AudioEffect.SUCCESS
                    || directVirtualizer.getEnabled() != active) throw new IllegalStateException();
                directStatus = active ? "Espacialização básica ativa · Virtualizer do Android"
                    : "Espacialização básica conectada · efeito desligado";
            } catch (RuntimeException unavailable) {
                releaseEffectsOnly();
                directStatus = "Espacialização indisponível nesta saída.";
            }
            return;
        }
        if (!directDspEnabled || directDsp == null || host == null || Build.VERSION.SDK_INT < 28) return;
        try {
            boolean bypass = host.player != null && host.player.isBypassEffects();
            if (bypass) {
                directDsp.setInputGainAllChannelsTo(0f);
                float[] cutoffs = {45f, 90f, 180f, 355f, 710f, 1400f, 2800f, 5600f, 11200f, 20000f};
                for (int b = 0; b < 10; b++) {
                    directDsp.setPreEqBandAllChannelsTo(b, new DynamicsProcessing.EqBand(true, cutoffs[b], 0f));
                    directDsp.setPostEqBandAllChannelsTo(b, new DynamicsProcessing.EqBand(true, cutoffs[b], 0f));
                }
                if (directVirtualizer != null) try { directVirtualizer.setStrength((short) 0); } catch (Throwable ignored) { }
                if (directReverb != null) try { directReverb.setEnabled(false); } catch (Throwable ignored) { }
                return;
            }

            boolean eqOn = host.eqToggle == null || host.eqToggle.isChecked();
            boolean cludesOn = host.cludesToggle != null && host.cludesToggle.isChecked();
            float preamp = (eqOn && host.preampSlider != null) ? (host.preampSlider.getProgress() - 12f) : 0f;
            int balSteps = host.getPreferences(Context.MODE_PRIVATE).getInt("eqBalanceSteps", 0);
            float balDb = balSteps * 0.5f;
            float trimL = balDb > 0.05f ? -balDb : 0f;
            float trimR = balDb < -0.05f ? balDb : 0f;

            boolean pultec = host.getPreferences(Context.MODE_PRIVATE).getBoolean("cludesPultec", false);
            boolean dsee = host.getPreferences(Context.MODE_PRIVATE).getBoolean("hiResRestorer", false)
                || host.getPreferences(Context.MODE_PRIVATE).getBoolean("expCodecAdaptive", true);
            int adlibUnmask = host.getPreferences(Context.MODE_PRIVATE).getInt("structAdlibUnmask", 45);
            int centerLock = host.getPreferences(Context.MODE_PRIVATE).getInt("structCenterLock", 40);
            int beatSeparate = host.getPreferences(Context.MODE_PRIVATE).getInt("structBeatSeparate", 25);
            int tactileSub = host.getPreferences(Context.MODE_PRIVATE).getInt("expTactileSub", 0);
            int haasAdt = host.getPreferences(Context.MODE_PRIVATE).getInt("expHaasAdt", 0);
            int dimD = host.getPreferences(Context.MODE_PRIVATE).getInt("expDimensionD", 0);
            int venueMode = host.getPreferences(Context.MODE_PRIVATE).getInt("expVenueMode", 0);
            int reverbAmt = host.getPreferences(Context.MODE_PRIVATE).getInt("reverb", 0);
            int spatialMode = host.mode;
            float depth = (spatialMode != SpatialProcessor.OFF) ? (host.intensity / 100f) : 0f;
            float cludesWidthExtra = cludesOn ? Math.max(0f, (host.cludesWidth - 100) / 100f) : 0f;

            if (directVirtualizer != null) {
                try {
                    boolean useVirt = spatialMode != SpatialProcessor.OFF || cludesWidthExtra > 0.01f || haasAdt > 0 || dimD > 0;
                    directVirtualizer.setEnabled(useVirt);
                    if (useVirt) {
                        int vMode = (spatialMode == SpatialProcessor.AMBIO || spatialMode == SpatialProcessor.FRONT)
                            ? Virtualizer.VIRTUALIZATION_MODE_TRANSAURAL
                            : Virtualizer.VIRTUALIZATION_MODE_BINAURAL;
                        try { directVirtualizer.forceVirtualizationMode(vMode); } catch (Throwable ignored) { }
                        float modeMult = (spatialMode == SpatialProcessor.WIDE || spatialMode == SpatialProcessor.HOLOGRAPHIC || spatialMode == SpatialProcessor.SURROUND)
                            ? 1.0f : (spatialMode == SpatialProcessor.FRONT ? 0.55f : 0.85f);
                        int extraWidth = Math.round((haasAdt + dimD) * 2.5f) + Math.round(cludesWidthExtra * 400f);
                        short strength = (short) Math.min(1000, Math.max(0, Math.round(depth * 1000f * modeMult) + extraWidth));
                        directVirtualizer.setStrength(strength);
                    }
                } catch (Throwable ignored) { }
            }

            if (directReverb != null) {
                try {
                    boolean roomMode = spatialMode == SpatialProcessor.ROOM
                        || spatialMode == SpatialProcessor.THEATER
                        || spatialMode == SpatialProcessor.SURROUND
                        || spatialMode == SpatialProcessor.HOLOGRAPHIC
                        || venueMode > 0
                        || reverbAmt > 0;
                    directReverb.setEnabled(roomMode);
                    if (roomMode) {
                        short decayMs = 850;
                        short earlyDelay = 12;
                        short roomLevel = (short) (-1800 + Math.round(depth * 900) + reverbAmt * 8);
                        if (venueMode == SensoryExperienceProcessor.VENUE_CAR_CABIN) {
                            decayMs = 280; earlyDelay = 4; roomLevel = -900;
                        } else if (venueMode == SensoryExperienceProcessor.VENUE_ARENA_PA || spatialMode == SpatialProcessor.THEATER) {
                            decayMs = 1950; earlyDelay = 28; roomLevel = -700;
                        } else if (venueMode == SensoryExperienceProcessor.VENUE_JAZZ_CLUB || spatialMode == SpatialProcessor.ROOM) {
                            decayMs = 680; earlyDelay = 10; roomLevel = -1000;
                        }
                        directReverb.setDecayTime(decayMs);
                        directReverb.setReflectionsDelay(earlyDelay);
                        directReverb.setRoomLevel(roomLevel);
                        directReverb.setDiffusion((short) 1000);
                        directReverb.setDensity((short) 1000);
                    }
                } catch (Throwable ignored) { }
            }

            float cludesVolDb = 0f;
            if (cludesOn && host.cludesVolume != 100) {
                cludesVolDb = (float) (20.0 * Math.log10(Math.max(0.1, host.cludesVolume / 100.0)));
                cludesVolDb = Math.max(-12f, Math.min(9f, cludesVolDb));
            }
            directDsp.setInputGainAllChannelsTo(preamp + cludesVolDb);

            double[] cludesMapped = new double[10];
            if (cludesOn && host.cludesBands != null && host.cludesBands.length >= 5) {
                cludesMapped[0] = host.cludesBands[0];
                cludesMapped[1] = host.cludesBands[0] * 0.75;
                cludesMapped[2] = host.cludesBands[1];
                cludesMapped[3] = host.cludesBands[1] * 0.65;
                cludesMapped[4] = host.cludesBands[2];
                cludesMapped[5] = host.cludesBands[2] * 0.65;
                cludesMapped[6] = host.cludesBands[3];
                cludesMapped[7] = host.cludesBands[3] * 0.65;
                cludesMapped[8] = host.cludesBands[4];
                cludesMapped[9] = host.cludesBands[4];
            }

            float[] cutoffs = {45f, 90f, 180f, 355f, 710f, 1400f, 2800f, 5600f, 11200f, 20000f};
            for (int b = 0; b < 10; b++) {
                float gain = eqOn ? (float) host.eqGains[b] : 0f;
                gain += (float) cludesMapped[b];
                if (cludesOn) {
                    if (b <= 2) gain += host.cludesBass + (host.cludesLoudness ? 3.0f : 0f) + (host.cludesEnhancer ? 2.5f : 0f);
                    if (b >= 6) gain += host.cludesTreble + (host.cludesClarity / 100f) * 3.8f + (host.cludesLoudness ? 1.8f : 0f);
                }
                if (pultec && b == 1) gain += 4.2f;
                if (pultec && b == 2) gain -= 3.0f;
                if (tactileSub > 0 && b == 1) gain += (tactileSub / 100f) * 4.0f;
                if (venueMode == SensoryExperienceProcessor.VENUE_CAR_CABIN && b == 0) gain += 4.5f;
                if (spatialMode == SpatialProcessor.THEATER && b == 0) gain += 3.2f * depth;
                if (dsee && b == 9) gain += 2.8f;

                directDsp.setPreEqBandByChannelIndex(0, b, new DynamicsProcessing.EqBand(true, cutoffs[b], gain + trimL));
                directDsp.setPreEqBandByChannelIndex(1, b, new DynamicsProcessing.EqBand(true, cutoffs[b], gain + trimR));

                float postGainL = 0f;
                float postGainR = 0f;
                if (spatialMode != SpatialProcessor.OFF && spatialMode != SpatialProcessor.ORBIT) {
                    if (b == 6) {
                        postGainL += 2.4f * depth; postGainR += 2.9f * depth;
                    } else if (b == 7) {
                        postGainL += 1.2f * depth; postGainR -= 0.8f * depth;
                    } else if (b == 8) {
                        postGainL -= 2.2f * depth; postGainR -= 1.4f * depth;
                    } else if (b == 9) {
                        postGainL += 2.0f * depth; postGainR += 2.4f * depth;
                    }
                }
                if (centerLock > 0 && (b == 4 || b == 5)) {
                    float vocalLift = (centerLock / 100f) * 2.0f;
                    postGainL += vocalLift; postGainR += vocalLift;
                }
                if (spatialMode != SpatialProcessor.ORBIT) {
                    directDsp.setPostEqBandByChannelIndex(0, b, new DynamicsProcessing.EqBand(true, cutoffs[b], postGainL));
                    directDsp.setPostEqBandByChannelIndex(1, b, new DynamicsProcessing.EqBand(true, cutoffs[b], postGainR));
                }
            }

            float[] mbcCutoffs = {120f, 320f, 1200f, 3800f, 7500f, 20000f};
            float unmaskBoostDb = (adlibUnmask / 100f) * 4.8f;
            float beatBoostDb = (beatSeparate / 100f) * 2.5f;
            for (int m = 0; m < 6; m++) {
                boolean isFormantOrAir = (m >= 2 && m <= 4);
                float postGain = isFormantOrAir ? unmaskBoostDb : (m == 0 ? beatBoostDb : 0f);
                DynamicsProcessing.MbcBand mbcBand = new DynamicsProcessing.MbcBand(
                    true, mbcCutoffs[m], m == 0 ? 8.0f : 3.5f, 65.0f,
                    isFormantOrAir ? 1.35f : 1.15f, -16.0f, 4.0f, -65.0f, 1.0f, 0.0f, postGain
                );
                directDsp.setMbcBandAllChannelsTo(m, mbcBand);
            }

            DynamicsProcessing.Limiter lim = new DynamicsProcessing.Limiter(
                true, true, 0, 1.5f, 35.0f, 10.0f, -0.3f, 0.0f
            );
            directDsp.setLimiterAllChannelsTo(lim);
        } catch (Throwable ignored) { }
    }

    public static void pushConfigToSpotronPc(MainActivity host, Runnable onSuccess) {
        final String targetAddress = camillaHostIp.isEmpty() ? "127.0.0.1:12340" : camillaHostIp;
        final String camillaJson = generateSpotronCamillaJson(host);
        final String yaml = generateSpotronCamillaYaml(host);
        wsWorker.execute(() -> {
            String clean = targetAddress.replace("ws://", "").replace("http://", "").trim();
            String ip = clean.contains(":") ? clean.split(":")[0] : clean;
            int[] candidatePorts = clean.contains(":")
                ? new int[]{Integer.parseInt(clean.split(":")[1]), 12340, 1234}
                : new int[]{12340, 1234};

            Throwable lastErr = null;
            for (int port : candidatePorts) {
                Socket socket = null;
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(ip, port), 2000);
                    socket.setSoTimeout(2000);

                    byte[] keyBytes = new byte[16];
                    new SecureRandom().nextBytes(keyBytes);
                    String wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP);

                    OutputStream out = socket.getOutputStream();
                    InputStream in = socket.getInputStream();

                    String handshake = "GET / HTTP/1.1\r\n"
                        + "Host: " + ip + ":" + port + "\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                        + "Sec-WebSocket-Version: 13\r\n\r\n";
                    out.write(handshake.getBytes(StandardCharsets.UTF_8));
                    out.flush();

                    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    String statusLine = reader.readLine();
                    if (statusLine == null || !statusLine.contains("101")) {
                        throw new IllegalStateException("Handshake WebSocket recusado: " + statusLine);
                    }
                    String headerLine;
                    while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) { }

                    // Spotron Fase 2 (`src-tauri/src/dsp/mod.rs`) uses SetConfigJson
                    JSONObject cmdJson = new JSONObject();
                    cmdJson.put("SetConfigJson", camillaJson);
                    sendWebSocketTextFrame(out, cmdJson.toString());

                    JSONObject cmdYaml = new JSONObject();
                    cmdYaml.put("SetConfig", yaml);
                    sendWebSocketTextFrame(out, cmdYaml.toString());

                    final int connectedPort = port;
                    if (host != null) {
                        host.runOnUiThread(() -> {
                            host.status.setText("⚡ CamillaDSP no PC (" + ip + ":" + connectedPort + ") atualizado ao vivo!");
                            if (onSuccess != null) onSuccess.run();
                        });
                    }
                    return;
                } catch (Throwable e) {
                    lastErr = e;
                } finally {
                    if (socket != null) {
                        try { socket.close(); } catch (Throwable ignored) { }
                    }
                }
            }
            if (host != null && onSuccess != null && lastErr != null) {
                final String msg = lastErr.getMessage();
                host.runOnUiThread(() -> host.status.setText("Falha ao conectar em ws://" + targetAddress + " (" + msg + ")"));
            }
        });
    }

    private static void sendWebSocketTextFrame(OutputStream out, String text) throws Exception {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        int len = payload.length;
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);

        out.write(0x81);
        if (len <= 125) {
            out.write(0x80 | len);
        } else if (len <= 65535) {
            out.write(0x80 | 126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((len >> (8 * i)) & 0xFF));
            }
        }
        out.write(mask);
        byte[] masked = new byte[len];
        for (int i = 0; i < len; i++) {
            masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        out.write(masked);
        out.flush();
    }

    public static final class SpotifySearchItem {
        public final String title;
        public final String artist;
        public final String album;
        public final long durationMs;
        public final String coverUrl;
        public final String previewUrl;

        public SpotifySearchItem(String title, String artist, String album, long durationMs, String coverUrl, String previewUrl) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.durationMs = durationMs;
            this.coverUrl = coverUrl;
            this.previewUrl = previewUrl;
        }
    }

    public interface CatalogSearchCallback {
        void onResults(java.util.List<SpotifySearchItem> items);
    }

    public static void searchSpotifyCatalogAsync(MainActivity host, String query, CatalogSearchCallback callback) {
        if (query == null || query.trim().isEmpty()) {
            if (callback != null) callback.onResults(java.util.Collections.emptyList());
            return;
        }
        final String cleanQuery = query.trim();
        coverWorker.execute(() -> {
            java.util.ArrayList<SpotifySearchItem> list = new java.util.ArrayList<>();
            try {
                String q = URLEncoder.encode(cleanQuery, "UTF-8");
                String json = httpGetText("https://api.deezer.com/search?q=" + q + "&limit=18");
                if (json != null && !json.isEmpty()) {
                    JSONArray data = new JSONObject(json).optJSONArray("data");
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject item = data.getJSONObject(i);
                            String t = item.optString("title", "");
                            JSONObject artObj = item.optJSONObject("artist");
                            String a = artObj != null ? artObj.optString("name", "Spotify") : "Spotify";
                            JSONObject albObj = item.optJSONObject("album");
                            String alb = albObj != null ? albObj.optString("title", "") : "";
                            String cov = albObj != null ? albObj.optString("cover_xl", albObj.optString("cover_big", "")) : "";
                            long durMs = item.optLong("duration", 0L) * 1000L;
                            String prev = item.optString("preview", "");
                            if (!t.isEmpty()) {
                                list.add(new SpotifySearchItem(t, a, alb, durMs, cov, prev));
                            }
                        }
                    }
                }
            } catch (Throwable ignored) { }

            if (list.isEmpty()) {
                try {
                    String q = URLEncoder.encode(cleanQuery, "UTF-8");
                    String json = httpGetText("https://itunes.apple.com/search?term=" + q + "&entity=song&limit=18");
                    if (json != null && !json.isEmpty()) {
                        JSONArray results = new JSONObject(json).optJSONArray("results");
                        if (results != null) {
                            for (int i = 0; i < results.length(); i++) {
                                JSONObject item = results.getJSONObject(i);
                                String t = item.optString("trackName", "");
                                String a = item.optString("artistName", "Spotify");
                                String alb = item.optString("collectionName", "");
                                String cov = item.optString("artworkUrl100", "").replace("100x100bb", "600x600bb");
                                long durMs = item.optLong("trackTimeMillis", 0L);
                                String prev = item.optString("previewUrl", "");
                                if (!t.isEmpty()) {
                                    list.add(new SpotifySearchItem(t, a, alb, durMs, cov, prev));
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) { }
            }

            if (host != null && callback != null) {
                host.runOnUiThread(() -> callback.onResults(list));
            }
        });
    }

    public static void playSearchItemInSpotify(MainActivity host, SpotifySearchItem item) {
        if (host == null || item == null) return;
        lastSpotifyTrack = item.title;
        lastSpotifyArtist = item.artist;
        lastSpotifyDurationMs = item.durationMs;
        lastSpotifyPosMs = 0L;
        lastSpotifyPosTimestampMs = SystemClock.elapsedRealtime();
        spotifyPlaying = true;
        spotifyControlFocused = true;
        PlaybackService.releaseAudioFocusForExternalPlayer();

        host.playing = true;
        if (host.playButton != null) host.playButton.setText("❚❚");
        if (host.artwork != null) host.artwork.setPlaying(true);
        if (host.title != null) host.title.setText(item.title);
        if (host.artist != null) host.artist.setText(item.artist + (!item.album.isEmpty() ? " • " + item.album : " • Spotify DSP"));
        host.durationMs = item.durationMs;
        host.showProgress(0L, item.durationMs);
        host.playerUi.syncMiniPlayerFromMain();

        progressHandler.removeCallbacks(progressRunnable);
        progressHandler.post(progressRunnable);

        if (item.coverUrl != null && !item.coverUrl.isEmpty()) {
            coverWorker.execute(() -> {
                Bitmap bmp = downloadBitmap(item.coverUrl);
                if (bmp != null) {
                    lastSpotifyCover = bmp;
                    if (activeHost != null) {
                        activeHost.runOnUiThread(() -> {
                            activeHost.artwork.setCover(bmp);
                            activeHost.playerUi.syncArtworkTheme(bmp);
                            activeHost.playerUi.syncMiniPlayerFromMain();
                        });
                    }
                }
            });
        } else {
            fetchOnlineArtwork(host, "", item.artist, item.title);
        }

        if (!directDspEnabled) {
            setDirectSpotifyDsp(host, true);
        }

        String searchUri = "spotify:search:" + Uri.encode(item.title + " " + item.artist);
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(searchUri));
            intent.setPackage("com.spotify.music");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            host.startActivity(intent);
        } catch (Throwable e) {
            String webUrl = "https://open.spotify.com/search/" + Uri.encode(item.title + " " + item.artist);
            host.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)));
        }
    }

    private static String httpGetText(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile)");
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);
            if (conn.getResponseCode() != 200) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Bitmap downloadBitmap(String artUrl) {
        if (artUrl == null || artUrl.isEmpty()) return null;
        HttpURLConnection imgConn = null;
        try {
            imgConn = (HttpURLConnection) new URL(artUrl).openConnection();
            imgConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile)");
            imgConn.setConnectTimeout(3500);
            imgConn.setReadTimeout(3500);
            try (InputStream in = imgConn.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (imgConn != null) imgConn.disconnect();
        }
    }

    private static void fetchOnlineArtwork(MainActivity host, String spotifyId, String artist, String title) {
        coverWorker.execute(() -> {
            String artUrl = "";
            long resolvedDurationMs = 0L;

            // Tier 1: Official Spotify oEmbed API (100% exact Spotify cover when track ID is broadcast)
            if (spotifyId != null && spotifyId.contains("track")) {
                try {
                    String oembedUrl = "https://open.spotify.com/oembed?url=" + URLEncoder.encode(spotifyId, "UTF-8");
                    String json = httpGetText(oembedUrl);
                    if (json != null) {
                        artUrl = new JSONObject(json).optString("thumbnail_url", "");
                    }
                } catch (Throwable ignored) { }
            }

            // Clean title for catalog search fallback
            String cleanTitle = title == null ? "" : title.replaceAll("\\s*\\([^)]*\\)", "").replaceAll("\\s*-\\s*.*", "").trim();
            if (cleanTitle.isEmpty() && title != null) cleanTitle = title;

            // Tier 2: Deezer Search API (also provides exact track duration in seconds!)
            if (artUrl.isEmpty() || lastSpotifyDurationMs <= 0) {
                try {
                    String q = URLEncoder.encode((artist != null ? artist + " " : "") + cleanTitle, "UTF-8");
                    String json = httpGetText("https://api.deezer.com/search?q=" + q + "&limit=1");
                    if (json != null) {
                        JSONArray data = new JSONObject(json).optJSONArray("data");
                        if (data != null && data.length() > 0) {
                            JSONObject item = data.getJSONObject(0);
                            resolvedDurationMs = item.optLong("duration", 0L) * 1000L;
                            if (artUrl.isEmpty()) {
                                JSONObject alb = item.optJSONObject("album");
                                if (alb != null) {
                                    artUrl = alb.optString("cover_xl", alb.optString("cover_big", ""));
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) { }
            }

            // Tier 3: iTunes Search API fallback
            if (artUrl.isEmpty() || (lastSpotifyDurationMs <= 0 && resolvedDurationMs <= 0)) {
                try {
                    String q = URLEncoder.encode((artist != null ? artist + " " : "") + cleanTitle, "UTF-8");
                    String json = httpGetText("https://itunes.apple.com/search?term=" + q + "&entity=song&limit=1");
                    if (json != null) {
                        JSONArray results = new JSONObject(json).optJSONArray("results");
                        if (results != null && results.length() > 0) {
                            JSONObject item = results.getJSONObject(0);
                            if (resolvedDurationMs <= 0) {
                                resolvedDurationMs = item.optLong("trackTimeMillis", 0L);
                            }
                            if (artUrl.isEmpty()) {
                                artUrl = item.optString("artworkUrl100", "").replace("100x100bb", "600x600bb");
                            }
                        }
                    }
                } catch (Throwable ignored) { }
            }

            final long finalDur = resolvedDurationMs;
            if (finalDur > 0 && lastSpotifyDurationMs <= 0) {
                lastSpotifyDurationMs = finalDur;
                if (activeHost != null) {
                    activeHost.runOnUiThread(() -> {
                        if (!activeLocalPlayback(activeHost)) {
                            activeHost.durationMs = finalDur;
                            activeHost.showProgress(getCurrentSpotifyPositionMs(), finalDur);
                        }
                    });
                }
            }

            if (!artUrl.isEmpty()) {
                Bitmap bmp = downloadBitmap(artUrl);
                if (bmp != null) {
                    lastSpotifyCover = bmp;
                    if (activeHost != null) {
                        activeHost.runOnUiThread(() -> {
                            if (!activeLocalPlayback(activeHost)) {
                                activeHost.artwork.setCover(bmp);
                                activeHost.playerUi.syncArtworkTheme(bmp);
                                activeHost.playerUi.syncMiniPlayerFromMain();
                            }
                        });
                    }
                }
            }
        });
    }

    public static String generateSpotronCamillaYaml(MainActivity host) {
        double preamp = host.preampSlider != null ? (host.preampSlider.getProgress() - 12.0) : -4.0;
        boolean pultec = host.getPreferences(Context.MODE_PRIVATE).getBoolean("cludesPultec", false);
        boolean dsee = host.getPreferences(Context.MODE_PRIVATE).getBoolean("hiResRestorer", false)
            || host.getPreferences(Context.MODE_PRIVATE).getBoolean("expCodecAdaptive", true);
        int adlibUnmask = host.getPreferences(Context.MODE_PRIVATE).getInt("structAdlibUnmask", 45);
        double depth = host.mode != SpatialProcessor.OFF ? (host.intensity / 100.0) : 0.0;
        boolean isAmbioRace = host.mode == SpatialProcessor.AMBIO;
        String modeLabel = host.modeName();

        StringBuilder yml = new StringBuilder();
        yml.append("---\n");
        yml.append("# Gerado pelo VÉRTICE AUDIO STUDIO para dmrangel/spotron (CamillaDSP 4.1.3)\n");
        yml.append("# Modo 3D Ativo: ").append(modeLabel).append(" (").append(host.intensity).append("%)\n");
        if (isAmbioRace) {
            yml.append("# Inclui: Ambiophonic R.A.C.E. via 4 FIRs Recursivos Truncados (Conv Values 250-5000Hz) + Pultec + AutoEQ\n");
        } else {
            yml.append("# Inclui: Matriz 4-Canais Binaural ITD/ILD + All-Pass 3D + Pinna HRTF + Pultec + AutoEQ\n");
        }
        yml.append("title: \"Vertice 64-Bit (").append(modeLabel).append(") -> Spotron CamillaDSP 4.1.3\"\n");
        yml.append("devices:\n");
        yml.append("  samplerate: 48000\n");
        yml.append("  capture_samplerate: 48000\n");
        yml.append("  chunksize: 1024\n");
        yml.append("  queuelimit: 4\n");
        yml.append("  enable_rate_adjust: true\n");
        yml.append("  target_level: 1024\n");
        yml.append("  adjust_period: 10\n");
        yml.append("  resampler:\n");
        yml.append("    type: AsyncSinc\n");
        yml.append("    profile: Accurate\n");
        yml.append("  silence_threshold: -100\n");
        yml.append("  silence_timeout: 3.0\n");
        yml.append("  capture:\n");
        yml.append("    type: Wasapi\n");
        yml.append("    channels: 2\n");
        yml.append("    device: \"CABLE Output (VB-Audio Virtual Cable)\"\n");
        yml.append("    format: F32\n");
        yml.append("    exclusive: false\n");
        yml.append("    loopback: false\n");
        yml.append("  playback:\n");
        yml.append("    type: Wasapi\n");
        yml.append("    channels: 2\n");
        yml.append("    device: null\n");
        yml.append("    format: F32\n");
        yml.append("    exclusive: false\n\n");

        double sideBoostDb = depth > 0.05 ? (-9.5 + depth * 4.5) : -60.0;
        double crossGainDb = isAmbioRace ? 0.0 : (depth > 0.05 ? (-11.0 + depth * 4.0) : -60.0);
        yml.append("mixers:\n");
        yml.append("  expand_3d_stage:\n");
        yml.append("    channels:\n");
        yml.append("      in: 2\n");
        yml.append("      out: 4\n");
        yml.append("    mapping:\n");
        yml.append("      - dest: 0\n");
        yml.append("        sources:\n");
        yml.append("          - channel: 0\n");
        yml.append("            gain: 0.0\n");
        if (!isAmbioRace && depth > 0.05) {
            yml.append("          - channel: 1\n");
            yml.append(String.format(Locale.US, "            gain: %.1f\n", sideBoostDb));
            yml.append("            inverted: true\n");
        }
        yml.append("      - dest: 1\n");
        yml.append("        sources:\n");
        yml.append("          - channel: 1\n");
        yml.append("            gain: 0.0\n");
        if (!isAmbioRace && depth > 0.05) {
            yml.append("          - channel: 0\n");
            yml.append(String.format(Locale.US, "            gain: %.1f\n", sideBoostDb));
            yml.append("            inverted: true\n");
        }
        yml.append("      - dest: 2\n");
        yml.append("        sources:\n");
        yml.append("          - channel: 1\n");
        yml.append(String.format(Locale.US, "            gain: %.1f\n", crossGainDb));
        yml.append("      - dest: 3\n");
        yml.append("        sources:\n");
        yml.append("          - channel: 0\n");
        yml.append(String.format(Locale.US, "            gain: %.1f\n", crossGainDb));
        yml.append("  recombine_3d_stage:\n");
        yml.append("    channels:\n");
        yml.append("      in: 4\n");
        yml.append("      out: 2\n");
        yml.append("    mapping:\n");
        yml.append("      - dest: 0\n");
        yml.append("        sources:\n");
        yml.append("          - channel: 0\n");
        yml.append("            gain: 0.0\n");
        yml.append("          - channel: 2\n");
        yml.append("            gain: 0.0\n");
        yml.append("      - dest: 1\n");
        yml.append("        sources:\n");
        yml.append("          - channel: 1\n");
        yml.append("            gain: 0.0\n");
        yml.append("          - channel: 3\n");
        yml.append("            gain: 0.0\n\n");

        yml.append("filters:\n");
        yml.append("  preamp:\n");
        yml.append("    type: Gain\n");
        yml.append("    parameters:\n");
        yml.append(String.format(Locale.US, "      gain: %.1f\n", Math.min(-2.0, preamp)));
        yml.append("      scale: dB\n");

        if (isAmbioRace) {
            double a = 0.68 * Math.max(0.2, depth);
            double[] hSame = new double[25];
            double[] hCross = new double[25];
            for (int m = 0; m < 3; m++) {
                hSame[m * 8] = Math.pow(a * a, m);
                hCross[m * 8 + 4] = -a * Math.pow(a * a, m);
            }
            yml.append("  race_fir_same:\n");
            yml.append("    type: Conv\n");
            yml.append("    parameters:\n");
            yml.append("      type: Values\n");
            yml.append("      values: ").append(formatDoubleArray(hSame)).append("\n");

            yml.append("  race_fir_cross:\n");
            yml.append("    type: Conv\n");
            yml.append("    parameters:\n");
            yml.append("      type: Values\n");
            yml.append("      values: ").append(formatDoubleArray(hCross)).append("\n");

            yml.append("  race_band_hp:\n");
            yml.append("    type: Biquad\n");
            yml.append("    parameters:\n");
            yml.append("      type: Highpass\n");
            yml.append("      freq: 250\n");
            yml.append("      q: 0.707\n");

            yml.append("  race_band_lp:\n");
            yml.append("    type: Biquad\n");
            yml.append("    parameters:\n");
            yml.append("      type: Lowpass\n");
            yml.append("      freq: 5000\n");
            yml.append("      q: 0.707\n");
        } else {
            double delayMs = (host.mode == SpatialProcessor.ROOM) ? 8.3 : 0.29;
            yml.append("  binaural_itd_delay:\n");
            yml.append("    type: Delay\n");
            yml.append("    parameters:\n");
            yml.append(String.format(Locale.US, "      delay: %.3f\n", delayMs));
            yml.append("      unit: ms\n");
            yml.append("      subsample: false\n");

            yml.append("  head_shadow_ild:\n");
            yml.append("    type: Biquad\n");
            yml.append("    parameters:\n");
            yml.append("      type: Lowpass\n");
            yml.append("      freq: ").append(host.mode == SpatialProcessor.ROOM ? 3600 : 820).append("\n");
            yml.append("      q: 0.707\n");

            yml.append("  allpass_decor_l:\n");
            yml.append("    type: Biquad\n");
            yml.append("    parameters:\n");
            yml.append("      type: Allpass\n");
            yml.append("      freq: 640\n");
            yml.append("      q: 0.707\n");

            yml.append("  allpass_decor_r:\n");
            yml.append("    type: Biquad\n");
            yml.append("    parameters:\n");
            yml.append("      type: Allpass\n");
            yml.append("      freq: 920\n");
            yml.append("      q: 0.707\n");
        }

        yml.append("  subsonic_guard:\n");
        yml.append("    type: Biquad\n");
        yml.append("    parameters:\n");
        yml.append("      type: Highpass\n");
        yml.append("      freq: 12\n");
        yml.append("      q: 0.707\n");

        yml.append("  pultec_sub_boost:\n");
        yml.append("    type: Biquad\n");
        yml.append("    parameters:\n");
        yml.append("      type: Lowshelf\n");
        yml.append("      freq: 55\n");
        yml.append(String.format(Locale.US, "      gain: %.1f\n", pultec ? 4.5 : 0.0));
        yml.append("      q: 0.71\n");

        yml.append("  pultec_mud_dip:\n");
        yml.append("    type: Biquad\n");
        yml.append("    parameters:\n");
        yml.append("      type: Peaking\n");
        yml.append("      freq: 118\n");
        yml.append(String.format(Locale.US, "      gain: %.1f\n", pultec ? -3.2 : 0.0));
        yml.append("      q: 1.4\n");

        int[] freqs = EqualizerProcessor.FREQUENCIES;
        for (int i = 0; i < 10; i++) {
            double g = host.eqGains[i];
            if (i == 9 && dsee) g += 2.5;
            yml.append("  eq_band_").append(freqs[i]).append(":\n");
            yml.append("    type: Biquad\n");
            yml.append("    parameters:\n");
            yml.append("      type: Peaking\n");
            yml.append("      freq: ").append(freqs[i]).append("\n");
            yml.append(String.format(Locale.US, "      gain: %.1f\n", g));
            yml.append("      q: 1.25\n");
        }

        double pinnaGain = depth * 3.2 + (adlibUnmask / 100.0) * 2.2;
        yml.append("  pinna_concha_3d:\n");
        yml.append("    type: Biquad\n");
        yml.append("    parameters:\n");
        yml.append("      type: Peaking\n");
        yml.append("      freq: 3200\n");
        yml.append(String.format(Locale.US, "      gain: %.1f\n", pinnaGain));
        yml.append("      q: 1.8\n");

        yml.append("  pinna_notch_3d:\n");
        yml.append("    type: Biquad\n");
        yml.append("    parameters:\n");
        yml.append("      type: Peaking\n");
        yml.append("      freq: 7800\n");
        yml.append(String.format(Locale.US, "      gain: %.1f\n", -depth * 4.2));
        yml.append("      q: 3.4\n");

        yml.append("  true_peak_limiter:\n");
        yml.append("    type: Limiter\n");
        yml.append("    parameters:\n");
        yml.append("      clip_limit: -0.2\n");
        yml.append("      soft_clip: true\n\n");

        yml.append("pipeline:\n");
        yml.append("  - type: Mixer\n");
        yml.append("    name: expand_3d_stage\n");
        if (isAmbioRace) {
            yml.append("  - type: Filter\n");
            yml.append("    description: \"Ambiophonic R.A.C.E. Direct FIR (L->L, R->R)\"\n");
            yml.append("    channels: [0, 1]\n");
            yml.append("    names:\n");
            yml.append("      - race_fir_same\n");
            yml.append("  - type: Filter\n");
            yml.append("    description: \"Ambiophonic R.A.C.E. Recursive Crosstalk FIR (250-5000 Hz)\"\n");
            yml.append("    channels: [2, 3]\n");
            yml.append("    names:\n");
            yml.append("      - race_band_hp\n");
            yml.append("      - race_band_lp\n");
            yml.append("      - race_fir_cross\n");
        } else {
            yml.append("  - type: Filter\n");
            yml.append("    description: \"Binaural ITD + ILD Head-Shadow + All-Pass 3D\"\n");
            yml.append("    channels: [2, 3]\n");
            yml.append("    names:\n");
            yml.append("      - binaural_itd_delay\n");
            yml.append("      - head_shadow_ild\n");
            yml.append("  - type: Filter\n");
            yml.append("    channels: [0]\n");
            yml.append("    names:\n");
            yml.append("      - allpass_decor_l\n");
            yml.append("  - type: Filter\n");
            yml.append("    channels: [1]\n");
            yml.append("    names:\n");
            yml.append("      - allpass_decor_r\n");
        }
        yml.append("  - type: Mixer\n");
        yml.append("    name: recombine_3d_stage\n");
        yml.append("  - type: Filter\n");
        yml.append("    description: \"Cadeia Completa Vertice 64-Bit -> Spotron\"\n");
        yml.append("    channels: [0, 1]\n");
        yml.append("    names:\n");
        yml.append("      - preamp\n");
        yml.append("      - subsonic_guard\n");
        yml.append("      - pultec_sub_boost\n");
        yml.append("      - pultec_mud_dip\n");
        for (int f : freqs) {
            yml.append("      - eq_band_").append(f).append("\n");
        }
        yml.append("      - pinna_concha_3d\n");
        yml.append("      - pinna_notch_3d\n");
        yml.append("      - true_peak_limiter\n");

        return yml.toString();
    }

    private static String formatDoubleArray(double[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format(Locale.US, "%.4f", arr[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Exact Java port of `dmrangel/spotron` Fase 2 (`src-tauri/src/dsp/spatial.rs`, `extras.rs`, `preset.rs`):
     * Generates the CamillaDSP 4.1.3 JSON config (`SetConfigJson`) with:
     * - `race_impulse` & `ambiophonics` (RBJ 250-5000 Hz bandpass inside recursive loop + 240-point `peak_db` compensation)
     * - `crossfeed` (bs2b `LowpassFO` + `0.3 ms` subsample delay + normalized linear gains `1/(1+c)`, `c/(1+c)`)
     * - `protection` (Butterworth 4th-order highpass/lowpass) & `loudness` (Fader `Main`)
     */
    public static String generateSpotronCamillaJson(MainActivity host) {
        try {
            double fs = 48000.0;
            double preamp = host.preampSlider != null ? (host.preampSlider.getProgress() - 12.0) : 0.0;
            boolean pultec = host.getPreferences(Context.MODE_PRIVATE).getBoolean("cludesPultec", false);
            boolean dsee = host.getPreferences(Context.MODE_PRIVATE).getBoolean("hiResRestorer", false)
                || host.getPreferences(Context.MODE_PRIVATE).getBoolean("expCodecAdaptive", true);
            int adlibUnmask = host.getPreferences(Context.MODE_PRIVATE).getInt("structAdlibUnmask", 45);
            double depth = host.mode != SpatialProcessor.OFF ? (host.intensity / 100.0) : 0.0;
            boolean isAmbioRace = host.mode == SpatialProcessor.AMBIO;

            JSONObject filters = new JSONObject();
            JSONObject mixers = new JSONObject();
            JSONArray pipeline = new JSONArray();
            JSONArray eqNames = new JSONArray();

            if (Math.abs(preamp) > 0.01) {
                filters.put("preamp", new JSONObject()
                    .put("type", "Gain")
                    .put("parameters", new JSONObject().put("gain", preamp).put("scale", "dB")));
                eqNames.put("preamp");
            }

            if (pultec) {
                filters.put("pultec_sub", new JSONObject()
                    .put("type", "Biquad")
                    .put("parameters", new JSONObject().put("type", "Lowshelf").put("freq", 55.0).put("gain", 4.5).put("q", 0.71)));
                eqNames.put("pultec_sub");
                filters.put("pultec_dip", new JSONObject()
                    .put("type", "Biquad")
                    .put("parameters", new JSONObject().put("type", "Peaking").put("freq", 118.0).put("gain", -3.2).put("q", 1.4)));
                eqNames.put("pultec_dip");
            }

            int[] freqs = EqualizerProcessor.FREQUENCIES;
            for (int i = 0; i < 10; i++) {
                double g = host.eqGains[i];
                if (i == 9 && dsee) g += 2.5;
                if (Math.abs(g) > 0.05) {
                    String n = "eq_" + i;
                    filters.put(n, new JSONObject()
                        .put("type", "Biquad")
                        .put("parameters", new JSONObject().put("type", "Peaking").put("freq", (double) freqs[i]).put("gain", g).put("q", 1.25)));
                    eqNames.put(n);
                }
            }

            if (depth > 0.02) {
                double pinnaGain = depth * 3.2 + (adlibUnmask / 100.0) * 2.2;
                filters.put("pinna_concha", new JSONObject()
                    .put("type", "Biquad")
                    .put("parameters", new JSONObject().put("type", "Peaking").put("freq", 3200.0).put("gain", pinnaGain).put("q", 1.8)));
                eqNames.put("pinna_concha");
                filters.put("pinna_notch", new JSONObject()
                    .put("type", "Biquad")
                    .put("parameters", new JSONObject().put("type", "Peaking").put("freq", 7800.0).put("gain", -depth * 4.2).put("q", 3.4)));
                eqNames.put("pinna_notch");
            }

            if (eqNames.length() > 0) {
                pipeline.put(new JSONObject()
                    .put("type", "Filter")
                    .put("description", "EQ")
                    .put("channels", new JSONArray().put(0).put(1))
                    .put("names", eqNames));
            }

            if (isAmbioRace && depth > 0.02) {
                // Exact Spotron `spatial::race_impulse` & `spatial::ambiophonics`
                double delayUs = 70.0;
                double attenDb = 4.0 - 1.5 * depth;
                double g = Math.pow(10.0, -attenDb / 20.0);
                int d = Math.max(1, (int) Math.round(delayUs * 1e-6 * fs));
                int maxLen = 2048;
                double[] lo = new double[maxLen];
                double[] ro = new double[maxLen];
                double[] hpL = rbjButterworthCoeffs(true, 250.0, fs);
                double[] lpL = rbjButterworthCoeffs(false, 5000.0, fs);
                double[] hpR = rbjButterworthCoeffs(true, 250.0, fs);
                double[] lpR = rbjButterworthCoeffs(false, 5000.0, fs);
                int quiet = 0;
                int len = maxLen;
                for (int n = 0; n < maxLen; n++) {
                    double x = (n == 0) ? 1.0 : 0.0;
                    double rDel = (n >= d) ? ro[n - d] : 0.0;
                    double lDel = (n >= d) ? lo[n - d] : 0.0;
                    double fbL = runBiquad(lpL, runBiquad(hpL, rDel));
                    double fbR = runBiquad(lpR, runBiquad(hpR, lDel));
                    lo[n] = x - g * fbL;
                    ro[n] = -g * fbR;
                    if (n > 64 && Math.abs(lo[n]) < 1e-5 && Math.abs(ro[n]) < 1e-5) {
                        quiet++;
                        if (quiet > 4 * d + 256) {
                            len = n + 1;
                            break;
                        }
                    } else {
                        quiet = 0;
                    }
                }
                double[] mid = new double[len];
                double[] side = new double[len];
                JSONArray sameArr = new JSONArray();
                JSONArray crossArr = new JSONArray();
                for (int n = 0; n < len; n++) {
                    mid[n] = lo[n] + ro[n];
                    side[n] = lo[n] - ro[n];
                    sameArr.put(lo[n]);
                    crossArr.put(ro[n]);
                }
                double peak = Math.max(0.0, Math.max(peakDb(mid, len, fs), peakDb(side, len, fs)));
                double comp = Math.pow(10.0, -peak / 20.0);

                filters.put("race_same", new JSONObject()
                    .put("type", "Conv")
                    .put("parameters", new JSONObject().put("type", "Values").put("values", sameArr)));
                filters.put("race_cross", new JSONObject()
                    .put("type", "Conv")
                    .put("parameters", new JSONObject().put("type", "Values").put("values", crossArr)));
                mixers.put("race_split", spotronSplitMixer());
                mixers.put("race_merge", spotronMergeMixer(comp, comp));
                pipeline.put(new JSONObject().put("type", "Mixer").put("name", "race_split"));
                pipeline.put(new JSONObject().put("type", "Filter").put("channels", new JSONArray().put(0).put(1)).put("names", new JSONArray().put("race_same")));
                pipeline.put(new JSONObject().put("type", "Filter").put("channels", new JSONArray().put(2).put(3)).put("names", new JSONArray().put("race_cross")));
                pipeline.put(new JSONObject().put("type", "Mixer").put("name", "race_merge"));
            } else if (depth > 0.02) {
                // Exact Spotron `spatial::crossfeed` (bs2b normalized linear gains)
                double levelDb = 9.5 - 5.0 * depth;
                double c = Math.pow(10.0, -levelDb / 20.0);
                double gSame = 1.0 / (1.0 + c);
                double gCross = c / (1.0 + c);
                filters.put("xf_lp", new JSONObject()
                    .put("type", "Biquad")
                    .put("parameters", new JSONObject().put("type", "LowpassFO").put("freq", 700.0)));
                filters.put("xf_delay", new JSONObject()
                    .put("type", "Delay")
                    .put("parameters", new JSONObject().put("delay", 0.3).put("unit", "ms").put("subsample", true)));
                mixers.put("xf_split", spotronSplitMixer());
                mixers.put("xf_merge", spotronMergeMixer(gSame, gCross));
                pipeline.put(new JSONObject().put("type", "Mixer").put("name", "xf_split"));
                pipeline.put(new JSONObject().put("type", "Filter").put("channels", new JSONArray().put(2).put(3)).put("names", new JSONArray().put("xf_lp").put("xf_delay")));
                pipeline.put(new JSONObject().put("type", "Mixer").put("name", "xf_merge"));
            }

            filters.put("limiter", new JSONObject()
                .put("type", "Limiter")
                .put("parameters", new JSONObject().put("clip_limit", -0.1).put("soft_clip", false)));
            pipeline.put(new JSONObject()
                .put("type", "Filter")
                .put("channels", new JSONArray().put(0).put(1))
                .put("names", new JSONArray().put("limiter")));

            JSONObject devices = new JSONObject()
                .put("samplerate", 48000)
                .put("capture_samplerate", 48000)
                .put("chunksize", 1024)
                .put("queuelimit", 4)
                .put("enable_rate_adjust", true)
                .put("target_level", 1024)
                .put("adjust_period", 10)
                .put("resampler", new JSONObject().put("type", "AsyncSinc").put("profile", "Balanced"))
                .put("silence_threshold", -100)
                .put("silence_timeout", 3.0)
                .put("capture", new JSONObject()
                    .put("type", "Wasapi")
                    .put("channels", 2)
                    .put("device", "CABLE Output (VB-Audio Virtual Cable)")
                    .put("format", "F32")
                    .put("exclusive", false)
                    .put("loopback", false))
                .put("playback", new JSONObject()
                    .put("type", "Wasapi")
                    .put("channels", 2)
                    .put("device", JSONObject.NULL)
                    .put("format", "F32")
                    .put("exclusive", false));

            JSONObject cfg = new JSONObject()
                .put("title", "Spotron - Vértice " + host.modeName())
                .put("devices", devices)
                .put("filters", filters)
                .put("pipeline", pipeline);
            if (mixers.length() > 0) {
                cfg.put("mixers", mixers);
            }
            return cfg.toString();
        } catch (Throwable e) {
            return "{}";
        }
    }

    private static JSONObject spotronSplitMixer() throws Exception {
        JSONArray mapping = new JSONArray();
        int[] srcCh = {0, 1, 1, 0};
        for (int dest = 0; dest < 4; dest++) {
            mapping.put(new JSONObject()
                .put("dest", dest)
                .put("sources", new JSONArray().put(new JSONObject()
                    .put("channel", srcCh[dest])
                    .put("gain", 0.0)
                    .put("scale", "dB"))));
        }
        return new JSONObject()
            .put("channels", new JSONObject().put("in", 2).put("out", 4))
            .put("mapping", mapping);
    }

    private static JSONObject spotronMergeMixer(double gSame, double gCross) throws Exception {
        JSONArray mapping = new JSONArray();
        mapping.put(new JSONObject()
            .put("dest", 0)
            .put("sources", new JSONArray()
                .put(new JSONObject().put("channel", 0).put("gain", gSame).put("scale", "linear"))
                .put(new JSONObject().put("channel", 2).put("gain", gCross).put("scale", "linear"))));
        mapping.put(new JSONObject()
            .put("dest", 1)
            .put("sources", new JSONArray()
                .put(new JSONObject().put("channel", 1).put("gain", gSame).put("scale", "linear"))
                .put(new JSONObject().put("channel", 3).put("gain", gCross).put("scale", "linear"))));
        return new JSONObject()
            .put("channels", new JSONObject().put("in", 4).put("out", 2))
            .put("mapping", mapping);
    }

    private static double[] rbjButterworthCoeffs(boolean highpass, double freq, double fs) {
        double w0 = 2.0 * Math.PI * freq / fs;
        double cw = Math.cos(w0);
        double alpha = Math.sin(w0) / Math.sqrt(2.0);
        double a0 = 1.0 + alpha;
        double b0, b1, b2;
        if (highpass) {
            b0 = (1.0 + cw) / 2.0;
            b1 = -(1.0 + cw);
            b2 = (1.0 + cw) / 2.0;
        } else {
            b0 = (1.0 - cw) / 2.0;
            b1 = 1.0 - cw;
            b2 = (1.0 - cw) / 2.0;
        }
        return new double[]{b0 / a0, b1 / a0, b2 / a0, -2.0 * cw / a0, (1.0 - alpha) / a0, 0.0, 0.0};
    }

    private static double runBiquad(double[] c, double x) {
        double y = c[0] * x + c[5];
        c[5] = c[1] * x - c[3] * y + c[6];
        c[6] = c[2] * x - c[4] * y;
        return y;
    }

    private static double peakDb(double[] h, int len, double fs) {
        double peak = 0.0;
        for (int i = 0; i < 240; i++) {
            double f = 20.0 * Math.pow(1000.0, i / 239.0);
            double w = 2.0 * Math.PI * f / fs;
            double re = 0.0;
            double im = 0.0;
            for (int n = 0; n < len; n++) {
                re += h[n] * Math.cos(w * n);
                im -= h[n] * Math.sin(w * n);
            }
            double mag = Math.sqrt(re * re + im * im);
            if (mag > peak) peak = mag;
        }
        return 20.0 * Math.log10(Math.max(1e-12, peak));
    }

    public static String generateSpotronPresetJson(MainActivity host) {
        try {
            JSONObject root = new JSONObject();
            int presetIdx = host.getPreferences(Context.MODE_PRIVATE).getInt("preset", 0);
            String presetName = (presetIdx >= 0 && presetIdx < MainActivity.PRESET_NAMES.length)
                ? MainActivity.PRESET_NAMES[presetIdx] : "Vertice Custom";
            root.put("name", "Vértice · " + presetName + " + " + host.modeName());
            double preamp = host.preampSlider != null ? (host.preampSlider.getProgress() - 12.0) : -2.0;
            root.put("preamp_db", preamp);

            JSONArray filtersArr = new JSONArray();
            boolean pultec = host.getPreferences(Context.MODE_PRIVATE).getBoolean("cludesPultec", false);
            if (pultec) {
                filtersArr.put(new JSONObject().put("type", "lowshelf").put("freq", 55).put("gain_db", 4.5).put("q", 0.71).put("enabled", true));
                filtersArr.put(new JSONObject().put("type", "peaking").put("freq", 118).put("gain_db", -3.2).put("q", 1.4).put("enabled", true));
            }
            for (int i = 0; i < 10; i++) {
                filtersArr.put(new JSONObject()
                    .put("type", "peaking")
                    .put("freq", EqualizerProcessor.FREQUENCIES[i])
                    .put("gain_db", host.eqGains[i])
                    .put("q", 1.25)
                    .put("enabled", true));
            }
            root.put("filters", filtersArr);
            root.put("correction", JSONObject.NULL);
            root.put("limiter", new JSONObject().put("enabled", true).put("clip_limit_db", -0.1));

            double depth = host.mode != SpatialProcessor.OFF ? (host.intensity / 100.0) : 0.0;
            JSONObject crossfeed = new JSONObject();
            crossfeed.put("enabled", host.mode != SpatialProcessor.OFF && host.mode != SpatialProcessor.AMBIO);
            crossfeed.put("cutoff_hz", 700.0);
            crossfeed.put("level_db", Math.round((9.5 - 5.0 * depth) * 10.0) / 10.0);
            root.put("crossfeed", crossfeed);

            JSONObject ambio = new JSONObject();
            ambio.put("enabled", host.mode == SpatialProcessor.AMBIO);
            ambio.put("delay_us", 70.0);
            ambio.put("attenuation_db", 3.0);
            ambio.put("low_hz", 250.0);
            ambio.put("high_hz", 5000.0);
            root.put("ambiophonics", ambio);

            int balSteps = host.getPreferences(Context.MODE_PRIVATE).getInt("eqBalanceSteps", 0);
            boolean phaseInv = host.getPreferences(Context.MODE_PRIVATE).getBoolean("phaseInvert", false);
            root.put("channels", new JSONObject()
                .put("balance", Math.max(-1.0, Math.min(1.0, balSteps / 12.0)))
                .put("mono", false)
                .put("swap", false)
                .put("invert_left", phaseInv)
                .put("invert_right", phaseInv)
                .put("delay_left_ms", 0.0)
                .put("delay_right_ms", 0.0));

            root.put("protection", new JSONObject()
                .put("enabled", true)
                .put("subsonic_hz", 20.0)
                .put("ultrasonic_hz", 0.0));

            boolean loudnessOn = host.cludesToggle != null && host.cludesToggle.isChecked() && host.cludesLoudness;
            root.put("loudness", new JSONObject()
                .put("enabled", loudnessOn)
                .put("reference_level_db", -25.0)
                .put("low_boost_db", 10.0)
                .put("high_boost_db", 7.0));

            return root.toString(2);
        } catch (Throwable e) {
            return "{}";
        }
    }
}
