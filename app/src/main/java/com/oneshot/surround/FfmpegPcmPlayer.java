package com.oneshot.surround;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.SystemClock;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.MediaInformationSession;
import com.arthenica.ffmpegkit.StreamInformation;
import com.arthenica.ffmpegkit.ReturnCode;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import org.json.JSONObject;

/** FFmpeg native decode to float PCM, multi-FX DJ crossfader (Bass-Swap, Filter Sweep, Echo Freeze, Power Cut), double DSP. */
public final class FfmpegPcmPlayer {
    public interface Listener {
        void onMessage(String text);
        void onProgress(long positionMs, long durationMs);
        void onFinished();
        void onRoute(String route);
        void onArtwork(Bitmap bitmap);
        void onMetadata(String title, String artist);
    }

    private static final class CrossfadeDeck {
        final Uri uri;
        final long startMs;
        final int fadeDurationMs;
        final int transitionStyle;
        final double targetGainRatio;
        long trackDurationMs;
        String taggedTitle;
        String taggedArtist;
        String detectedCodec;
        String pipeName;
        FFmpegSession session;
        FileInputStream stream;
        final byte[] bytes = new byte[32768];
        final double[] pcm = new double[8192];
        int carry;
        long framesRead;
        int fadeFramesDone;
        int totalFadeFrames;
        double lpLeftA, lpRightA, lpLeftB, lpRightB;
        double midLowLA, midLowRA, midHighLA, midHighRA;
        double[] echoBufL;
        double[] echoBufR;
        int echoWritePos;
        int echoDelaySamples;

        CrossfadeDeck(Uri uri, long startMs, int fadeDurationMs, int transitionStyle, int sampleRate, double targetGainRatio) {
            this.uri = uri;
            this.startMs = Math.max(0, startMs);
            this.fadeDurationMs = Math.max(800, fadeDurationMs);
            this.transitionStyle = transitionStyle;
            this.targetGainRatio = Math.max(0.55, Math.min(1.65, targetGainRatio));
            if (transitionStyle == AutoMixSelector.STYLE_ECHO_FREEZE) {
                this.echoDelaySamples = Math.max(1024, (sampleRate * 360) / 1000);
                this.echoBufL = new double[this.echoDelaySamples];
                this.echoBufR = new double[this.echoDelaySamples];
            }
        }

        void close() {
            if (stream != null) {
                try { stream.close(); } catch (Exception ignored) { }
                stream = null;
            }
            if (session != null) {
                try { FFmpegKit.cancel(session.getSessionId()); } catch (Exception ignored) { }
                session = null;
            }
            if (pipeName != null) {
                try { FFmpegKitConfig.closeFFmpegPipe(pipeName); } catch (Exception ignored) { }
                pipeName = null;
            }
        }
    }

    private final Context context;
    private final Listener listener;
    private final AudioReactiveAnalyzer visualAnalyzer = new AudioReactiveAnalyzer();
    public AudioReactiveAnalyzer getVisualAnalyzer() { return visualAnalyzer; }
    private volatile boolean paused;
    private volatile boolean routeMuted;
    private volatile long generation;
    private volatile long seekMs = -1;
    private volatile int currentSampleRate = 48000;
    private volatile CrossfadeDeck readyCrossfade;
    private volatile int mode = SpatialProcessor.ROOM;
    private volatile int amount = 80;
    private volatile int reverbAmount;
    private volatile double[] eqGains = new double[10];
    private volatile double preampDb;
    private volatile int eqTopology = EqualizerProcessor.TOPOLOGY_STEREO;
    private volatile double eqBalanceDb = 0.0;
    private volatile float speed = 1f;
    private volatile boolean preservePitch = true;
    private volatile boolean tuning432Hz = false;
    private volatile int outputProfile;
    private volatile boolean analogWarmth;
    private volatile int saturationMode;
    private volatile boolean dynamicRestorer;
    private volatile boolean phaseInvert;
    private volatile int ditherMode; // 0 = 2nd-order psychoacoustic, 1 = Pure TPDF, 2 = Off
    private volatile boolean hiResRestorer;
    private volatile int dacFilterMode = AudiophileMasteringStage.DAC_LINEAR_PHASE;
    private volatile boolean autoGainEnabled;
    private volatile boolean cludesPultec;
    private volatile int structAdlibUnmask;
    private volatile int structCenterLock;
    private volatile int structBeatSeparate;
    private volatile int expTactileSub;
    private volatile int expHaasAdt;
    private volatile int expDimensionD;
    private volatile int expGroovePump;
    private volatile int expVenueMode;
    private volatile boolean expTurntableVinyl;
    private volatile boolean expCodecAdaptive = true;
    private volatile boolean currentSourceLossy;
    private int ditherState = 0x13579bdf;
    private double ditherErr1L, ditherErr2L;
    private double ditherErr1R, ditherErr2R;
    private volatile boolean eqEnabled = true;
    private volatile boolean cludesEnabled;
    private volatile boolean bypassEffects;
    private volatile int cludesVolume = 100;
    private volatile int cludesWidth = 100;
    private volatile int cludesBass;
    private volatile int cludesTreble;
    private volatile int cludesClarity;
    private volatile boolean cludesLoudness;
    private volatile boolean cludesEnhancer;
    private volatile double[] cludesBands = new double[5];
    private volatile String formatInfo = "Aguardando reprodução";
    private volatile String sourceBadge = "Fonte aguardando";
    private volatile String rendererBadge = "Saída aguardando";
    private volatile FFmpegSession decoderSession;
    private volatile AudioTrack track;
    private volatile FileInputStream decoderPipe;
    private volatile boolean liveCaptureActive;
    private volatile boolean liveMonitorAudioTrack;
    private Thread worker;

    public FfmpegPcmPlayer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }
    public void setSpatial(int nextMode, int nextAmount) {
        mode = nextMode;
        amount = nextAmount;
        SpotifyLiveBridge.onPlayerDspChanged();
    }
    public void setReverb(int nextAmount) {
        reverbAmount = nextAmount;
        SpotifyLiveBridge.onPlayerDspChanged();
    }
    public void setEqualizer(double[] gains, double preamp) {
        eqGains = gains.clone();
        preampDb = preamp;
        SpotifyLiveBridge.onPlayerDspChanged();
    }
    public void setEqualizerTopology(int topology) {
        eqTopology = Math.max(0, Math.min(2, topology));
        SpotifyLiveBridge.onPlayerDspChanged();
    }
    public void setChannelBalanceDb(double balanceDb) {
        eqBalanceDb = Math.max(-6.0, Math.min(6.0, balanceDb));
        SpotifyLiveBridge.onPlayerDspChanged();
    }
    public void setOutputProfile(int profile) { outputProfile = Math.max(0, Math.min(2, profile)); }
    public void setAnalogWarmth(boolean enabled) {
        analogWarmth = enabled;
        saturationMode = enabled ? OutputLimiter.SATURATION_TRIODE : OutputLimiter.SATURATION_OFF;
        SpotifyLiveBridge.onPlayerDspChanged();
    }
    public void setSaturationMode(int modeIndex) {
        saturationMode = Math.max(0, Math.min(3, modeIndex));
        analogWarmth = saturationMode != OutputLimiter.SATURATION_OFF;
        SpotifyLiveBridge.onPlayerDspChanged();
    }
    public void setDynamicRestorer(boolean enabled) {
        dynamicRestorer = enabled;
        SpotifyLiveBridge.onPlayerDspChanged();
    }
    public void setPhaseInvert(boolean enabled) {
        phaseInvert = enabled;
        SpotifyLiveBridge.onPlayerDspChanged();
    }
    public void setDitherMode(int modeIndex) { ditherMode = Math.max(0, Math.min(2, modeIndex)); }
    public void setHiResRestorer(boolean enabled) {
        hiResRestorer = enabled;
        SpotifyLiveBridge.onPlayerDspChanged();
    }
    public void setDacFilterMode(int modeIndex) {
        dacFilterMode = Math.max(0, Math.min(2, modeIndex));
        SpotifyLiveBridge.onPlayerDspChanged();
    }
    public void setAutoGainEnabled(boolean enabled) {
        autoGainEnabled = enabled;
        SpotifyLiveBridge.onPlayerDspChanged();
    }
    public void setCludesPultec(boolean enabled) {
        cludesPultec = enabled;
        SpotifyLiveBridge.onPlayerDspChanged();
    }
    public void setStructuralUnmask(int adlibUnmaskPct, int centerLockPct, int beatSeparatePct) {
        structAdlibUnmask = Math.max(0, Math.min(100, adlibUnmaskPct));
        structCenterLock = Math.max(0, Math.min(100, centerLockPct));
        structBeatSeparate = Math.max(0, Math.min(100, beatSeparatePct));
        SpotifyLiveBridge.onPlayerDspChanged();
    }
    public void setSensoryExperience(int tactileSubPct, int haasAdtPct, int dimensionDPct,
                                     int groovePumpPct, int venueMode, boolean turntableVinyl, boolean codecAdaptive) {
        expTactileSub = Math.max(0, Math.min(100, tactileSubPct));
        expHaasAdt = Math.max(0, Math.min(100, haasAdtPct));
        expDimensionD = Math.max(0, Math.min(100, dimensionDPct));
        expGroovePump = Math.max(0, Math.min(100, groovePumpPct));
        expVenueMode = Math.max(0, Math.min(3, venueMode));
        expTurntableVinyl = turntableVinyl;
        expCodecAdaptive = codecAdaptive;
        SpotifyLiveBridge.onPlayerDspChanged();
    }
    public void setTuning432Hz(boolean enabled) {
        tuning432Hz = enabled;
        AudioTrack current = track;
        if (current != null) applySpeed(current);
    }

    private double nextUnitRandom() {
        int x = ditherState;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        ditherState = x;
        return (x & 0x7fffffff) / (double)0x7fffffff;
    }
    public void setEqualizerEnabled(boolean enabled) {
        eqEnabled = enabled;
        SpotifyLiveBridge.onPlayerDspChanged();
    }
    public void setBypassEffects(boolean bypass) {
        bypassEffects = bypass;
        AudioTrack current = track;
        if (current != null) applySpeed(current);
        SpotifyLiveBridge.onPlayerDspChanged();
    }
    public void setCludes(boolean enabled, int volume, int width, int bass, int treble,
                          int clarity, boolean loudness, boolean enhancer, double[] bands) {
        cludesEnabled = enabled;
        cludesVolume = volume;
        cludesWidth = width;
        cludesBass = bass;
        cludesTreble = treble;
        cludesClarity = clarity;
        cludesLoudness = loudness;
        cludesEnhancer = enhancer;
        cludesBands = bands.clone();
        SpotifyLiveBridge.onPlayerDspChanged();
    }
    public boolean isBypassEffects() { return bypassEffects; }
    public String getFormatInfo() { return formatInfo; }
    public String getSourceBadge() { return sourceBadge; }
    public String getRendererBadge() { return rendererBadge; }
    public void setSpeed(float nextSpeed, boolean keepPitch) {
        speed = nextSpeed;
        preservePitch = keepPitch;
        AudioTrack current = track;
        if (current != null) applySpeed(current);
    }
    public boolean isPaused() { return paused; }
    public float getSpeed() { return bypassEffects ? 1f : speed; }
    public synchronized void resumeAutomatic() {
        if (routeMuted) return;
        paused = false;
        AudioTrack output = track;
        if (output != null) try { output.setVolume(1f); output.play(); } catch (IllegalStateException ignored) { }
    }
    public boolean isRunning() { return worker != null && worker.isAlive(); }
    public void pause() {
        paused = true;
        AudioTrack output = track;
        if (output != null) try { output.pause(); } catch (IllegalStateException ignored) { }
    }
    public synchronized void resume() {
        routeMuted = false; paused = false;
        AudioTrack output = track;
        if (output != null) { output.setVolume(1f); output.play(); }
    }
    public void allowOutput() { routeMuted = false; }
    public boolean isRouteMuted() { return routeMuted; }
    public synchronized void muteForHeadphoneDisconnect() {
        routeMuted = true; paused = true;
        AudioTrack output = track;
        if (output != null) {
            try { output.setVolume(0f); output.pause(); } catch (IllegalStateException ignored) { }
        }
    }
    public void seekTo(long positionMs) { seekMs = Math.max(0, positionMs); }
    public boolean isLiveCaptureActive() { return liveCaptureActive; }
    public void setLiveMonitorAudioTrack(boolean enabled) { liveMonitorAudioTrack = enabled; }
    public void stop() {
        generation++;
        liveCaptureActive = false;
        paused = routeMuted;
        CrossfadeDeck pending = readyCrossfade;
        readyCrossfade = null;
        if (pending != null) pending.close();
        FFmpegSession session = decoderSession;
        if (session != null) FFmpegKit.cancel(session.getSessionId());
        FileInputStream pipe = decoderPipe;
        if (pipe != null) try { pipe.close(); } catch (Exception ignored) { }
        AudioTrack output = track;
        if (output != null) try { output.pause(); output.flush(); } catch (Exception ignored) { }
        if (worker != null) worker.interrupt();
        worker = null;
    }
    public void play(Uri uri) {
        play(uri, 0);
    }
    public void play(Uri uri, long startMs) {
        play(uri, startMs, false);
    }
    public void play(Uri uri, long startMs, boolean initiallyPaused) {
        stop();
        paused = routeMuted || initiallyPaused;
        seekMs = -1;
        long token = generation;
        worker = new Thread(() -> decode(uri, Math.max(0, startMs), token), "ffmpeg-player");
        worker.start();
    }

    /**
     * Captures live PCM audio from Spotify / System (Android 10+ AudioPlaybackCapture)
     * excluding our own UID so there is zero feedback loop, runs the full float64 DSP chain
     * and 60 FPS 3D Visualizer / Vectorscope, and optionally outputs via AudioTrack.
     */
    public void startLiveCapture(android.media.projection.MediaProjection projection, boolean monitorOutput) {
        if (android.os.Build.VERSION.SDK_INT < 29 || projection == null) return;
        stop();
        paused = false;
        routeMuted = false;
        liveCaptureActive = true;
        liveMonitorAudioTrack = monitorOutput;
        long token = generation;
        worker = new Thread(() -> runLiveCaptureLoop(projection, token), "vertice-spotify-dsp");
        worker.start();
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void runLiveCaptureLoop(android.media.projection.MediaProjection projection, long token) {
        if (android.os.Build.VERSION.SDK_INT < 29) return;
        android.media.AudioRecord recorder = null;
        AudioTrack output = null;
        final int sampleRate = 48000;
        currentSampleRate = sampleRate;
        currentSourceLossy = true; // Engage Codec-Care / DSEE for Spotify Ogg/AAC 256kbps stream
        try {
            android.media.AudioPlaybackCaptureConfiguration captureConfig =
                new android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .excludeUid(android.os.Process.myUid())
                    .build();

            int minIn = android.media.AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
            recorder = new android.media.AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build())
                .setBufferSizeInBytes(Math.max(minIn * 4, 32768))
                .build();

            if (recorder.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioPlaybackCapture não inicializou");
            }

            output = createOutput(sampleRate, AudioFormat.ENCODING_PCM_FLOAT);
            if (output != null) {
                synchronized (this) {
                    track = output;
                    output.setVolume(1f);
                    output.play();
                }
            }

            sourceBadge = "SPOTIFY / SISTEMA · AAC/OGG 48kHz (Codec DSP ativo)";
            rendererBadge = "Live 64-bit Bridge · float32 Hi-Res";
            formatInfo = "Fonte: Captura Ao Vivo (Spotify / Sistema) • 48000 Hz • float32\n"
                + "DSP: Cadeia completa float64 + DSEE + Unmasker • Saída: AudioTrack float32";
            listener.onMessage("🟢 Spotify Live DSP ativo • 48000 Hz • 64-bit Engine");

            SpatialProcessor spatial = new SpatialProcessor(sampleRate);
            StructuralUnmasker structuralUnmasker = new StructuralUnmasker(sampleRate);
            SensoryExperienceProcessor sensoryExperience = new SensoryExperienceProcessor(sampleRate);
            EqualizerProcessor equalizer = new EqualizerProcessor(sampleRate);
            CludesProcessor cludes = new CludesProcessor(sampleRate);
            ReverbProcessor reverb = new ReverbProcessor(sampleRate);
            AudiophileMasteringStage masteringStage = new AudiophileMasteringStage(sampleRate);
            OutputLimiter limiter = new OutputLimiter(sampleRate);

            float[] inBuf = new float[2048];
            double[] pcm = new double[2048];
            float[] outBuf = new float[2048];

            recorder.startRecording();
            while (active(token) && liveCaptureActive) {
                int read = recorder.read(inBuf, 0, inBuf.length, android.media.AudioRecord.READ_BLOCKING);
                if (read <= 0) {
                    sleep(5);
                    continue;
                }
                int samples = read - (read & 1);
                for (int i = 0; i < samples; i++) pcm[i] = inBuf[i];

                boolean processing = !bypassEffects;
                if (processing) {
                    structuralUnmasker.process(pcm, samples, structAdlibUnmask, structCenterLock, structBeatSeparate);
                    spatial.configure(mode, amount);
                    spatial.process(pcm, samples);
                }
                double[] gains = eqGains;
                if (processing && (eqEnabled || Math.abs(eqBalanceDb) > 0.05)) {
                    double[] activeGains = eqEnabled ? gains : new double[10];
                    double activePreamp = eqEnabled ? preampDb : 0.0;
                    equalizer.configure(activeGains, activePreamp, eqTopology, eqBalanceDb);
                    equalizer.process(pcm, samples);
                }
                if (processing) {
                    cludes.configure(cludesEnabled, cludesVolume, cludesWidth, cludesBass, cludesTreble,
                        cludesClarity, cludesLoudness, cludesEnhancer, cludesPultec, cludesBands);
                    cludes.process(pcm, samples);
                    sensoryExperience.process(pcm, samples,
                        expTactileSub, expHaasAdt, expDimensionD, expGroovePump,
                        expVenueMode, expTurntableVinyl, expCodecAdaptive, true);
                    reverb.setAmount(reverbAmount);
                    reverb.process(pcm, samples);
                    boolean activeHiRes = hiResRestorer || expCodecAdaptive;
                    masteringStage.process(pcm, samples, activeHiRes, dacFilterMode, autoGainEnabled);
                }
                double boost = 0;
                if (processing && eqEnabled) for (double gain : gains) boost = Math.max(boost, gain);
                double headroom = (processing && mode != SpatialProcessor.OFF ? 5 : 0) + boost;
                int satMode = processing ? saturationMode : OutputLimiter.SATURATION_OFF;
                limiter.process(pcm, samples, Math.pow(10, -headroom / 20), satMode, processing && dynamicRestorer);
                if (processing && phaseInvert) {
                    for (int i = 0; i < samples; i++) pcm[i] = -pcm[i];
                }

                // Always feed the 60 FPS 3D Cover Visualizer & Vectorscope!
                visualAnalyzer.analyze(pcm, samples, sampleRate);

                if (liveMonitorAudioTrack && output != null && !paused && !routeMuted) {
                    for (int i = 0; i < samples; i++) outBuf[i] = (float) pcm[i];
                    output.write(outBuf, 0, samples, AudioTrack.WRITE_NON_BLOCKING);
                }
            }
        } catch (Throwable error) {
            if (active(token)) {
                listener.onMessage("Captura Live encerrada: " + error.getMessage());
            }
        } finally {
            liveCaptureActive = false;
            if (recorder != null) {
                try { recorder.stop(); } catch (Throwable ignored) { }
                try { recorder.release(); } catch (Throwable ignored) { }
            }
            if (output != null) {
                try { output.release(); } catch (Throwable ignored) { }
                if (track == output) track = null;
            }
            try { projection.stop(); } catch (Throwable ignored) { }
        }
    }

    public void crossfadeTo(Uri nextUri, long startMs, int fadeDurationMs) {
        crossfadeTo(nextUri, startMs, fadeDurationMs, AutoMixSelector.STYLE_BASS_SWAP, 1.0);
    }

    public void crossfadeTo(Uri nextUri, long startMs, int fadeDurationMs, int transitionStyle) {
        crossfadeTo(nextUri, startMs, fadeDurationMs, transitionStyle, 1.0);
    }

    /** Pre-buffers nextUri in background and triggers a multi-FX DJ transition with EBU R128 / RMS loudness matching. */
    public void crossfadeTo(Uri nextUri, long startMs, int fadeDurationMs, int transitionStyle, double targetGainRatio) {
        if (!isRunning() || paused || track == null) {
            play(nextUri, startMs);
            return;
        }
        final long token = generation;
        final int targetRate = currentSampleRate;
        new Thread(() -> {
            CrossfadeDeck deck = new CrossfadeDeck(nextUri, startMs, fadeDurationMs, transitionStyle, targetRate, targetGainRatio);
            try {
                String probeInput = FFmpegKitConfig.getSafParameterForRead(context, nextUri);
                MediaInformationSession probe = FFprobeKit.getMediaInformation(probeInput);
                if (!active(token)) { deck.close(); return; }
                MediaInformation media = probe.getMediaInformation();
                if (media != null) {
                    String durationText = media.getDuration();
                    if (durationText != null) deck.trackDurationMs = (long)(Double.parseDouble(durationText) * 1000);
                    deck.taggedTitle = readTag(media.getTags(), "title");
                    deck.taggedArtist = readTag(media.getTags(), "artist");
                    for (StreamInformation s : media.getStreams()) {
                        if ("audio".equals(s.getType())) {
                            deck.detectedCodec = s.getCodec();
                            if (deck.taggedTitle == null) deck.taggedTitle = readTag(s.getTags(), "title");
                            if (deck.taggedArtist == null) deck.taggedArtist = readTag(s.getTags(), "artist");
                            break;
                        }
                    }
                }
                deck.pipeName = FFmpegKitConfig.registerNewFFmpegPipe(context);
                if (deck.pipeName == null || !active(token)) { deck.close(); return; }
                String source = FFmpegKitConfig.getSafParameterForRead(context, nextUri);
                String[] args = buildAudiophileDecodeArgs(source, deck.startMs, targetRate, deck.detectedCodec, deck.pipeName);
                deck.session = FFmpegKit.executeWithArgumentsAsync(args, s -> { });
                deck.stream = new FileInputStream(deck.pipeName);
                int firstRead = deck.stream.read(deck.bytes, 0, deck.bytes.length);
                if (firstRead <= 0 || !active(token)) {
                    deck.close();
                    return;
                }
                deck.carry = firstRead;
                CrossfadeDeck old = readyCrossfade;
                readyCrossfade = deck;
                if (old != null) old.close();
            } catch (Throwable ignored) {
                deck.close();
            }
        }, "ffmpeg-crossfade-prep").start();
    }

    private boolean active(long token) { return generation == token; }
    private void applySpeed(AudioTrack output) {
        float baseSpeed = bypassEffects ? 1f : speed;
        float basePitch = (bypassEffects || preservePitch) ? 1f : speed;
        if (!bypassEffects && tuning432Hz) {
            // Natural A=432Hz concerted pitch ratio (432 / 440 = 0.9818182f)
            float ratio432 = 432f / 440f;
            baseSpeed *= ratio432;
            basePitch *= ratio432;
        }
        try {
            output.setPlaybackParams(new PlaybackParams().setSpeed(baseSpeed).setPitch(basePitch));
        } catch (Exception ignored) { }
    }

    private void decode(Uri initialUri, long initialStartMs, long token) {
        AudioTrack output = null;
        Uri uri = initialUri;
        try {
            String input = FFmpegKitConfig.getSafParameterForRead(context, uri);
            MediaInformationSession probe = FFprobeKit.getMediaInformation(input);
            if (!active(token)) return;
            MediaInformation media = probe.getMediaInformation();
            if (media == null) throw new IllegalStateException("FFprobe não conseguiu ler o arquivo");
            StreamInformation audio = null;
            for (StreamInformation stream : media.getStreams())
                if ("audio".equals(stream.getType())) { audio = stream; break; }
            if (audio == null) throw new IllegalArgumentException("Arquivo sem faixa de áudio");
            String taggedTitle = readTag(media.getTags(), "title");
            String taggedArtist = readTag(media.getTags(), "artist");
            if (taggedTitle == null) taggedTitle = readTag(audio.getTags(), "title");
            if (taggedArtist == null) taggedArtist = readTag(audio.getTags(), "artist");
            if (taggedTitle != null || taggedArtist != null) listener.onMetadata(taggedTitle, taggedArtist);
            int sampleRate = audio.getSampleRate() == null ? 48000 : Integer.parseInt(audio.getSampleRate());
            sampleRate = Math.max(8000, Math.min(192000, sampleRate));
            currentSampleRate = sampleRate;
            String durationText = media.getDuration();
            long durationMs = durationText == null ? 0 : (long)(Double.parseDouble(durationText) * 1000);
            String rawCodec = audio.getCodec();
            String codec = rawCodec == null ? "áudio" : rawCodec.toUpperCase(Locale.ROOT);
            String floatDecoder = preferredFloatDecoder(rawCodec);
            String sourceFormat = audio.getSampleFormat() == null ? "PCM decodificado" : audio.getSampleFormat();
            boolean lossless = codec.equals("FLAC") || codec.equals("ALAC") || codec.equals("APE")
                || codec.equals("WAVPACK") || codec.equals("TAK") || codec.equals("TTA")
                || codec.equals("WMALOSSLESS") || codec.startsWith("PCM_") || codec.startsWith("DSD_");
            boolean lossy = codec.equals("MP3") || codec.equals("AAC") || codec.equals("OPUS")
                || codec.equals("VORBIS") || codec.equals("WMA") || codec.equals("AC3")
                || codec.equals("EAC3");
            currentSourceLossy = lossy;
            sourceBadge = codec + (floatDecoder != null ? " (" + floatDecoder + ")" : "")
                + (lossless ? " · sem perdas" : lossy ? " · com perdas (Codec DSP ativo)" : " · formato detectado");
            int encoding = chooseOutputEncoding(sampleRate);
            output = createOutput(sampleRate, encoding);
            if (output == null && encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                encoding = AudioFormat.ENCODING_PCM_16BIT;
                output = createOutput(sampleRate, encoding);
            }
            if (output == null) throw new IllegalStateException("Saída de áudio indisponível");
            if (!active(token)) return;
            synchronized (this) {
                track = output;
                output.setVolume(routeMuted ? 0f : 1f);
                if (!paused && !routeMuted) output.play();
            }
            applySpeed(output);
            formatInfo = "Fonte: " + codec + (floatDecoder != null ? " [" + floatDecoder + "]" : "")
                + " • " + sampleRate + " Hz • " + sourceFormat
                + "\nResampler: Kaiser • DSP: float64 • Saída: AudioTrack "
                + (encoding == AudioFormat.ENCODING_PCM_FLOAT ? "float32 (24-bit mantissa)" : "PCM16 (Noise-Shaped)");
            rendererBadge = "AudioTrack " + (encoding == AudioFormat.ENCODING_PCM_FLOAT ? "float32 Hi-Res" : "PCM16");
            listener.onMessage("FFmpeg " + codec + " • " + sampleRate + " Hz • saída " + (encoding == AudioFormat.ENCODING_PCM_FLOAT ? "float32" : "PCM16"));
            boolean routeReported = reportRoute(output);

            double[] pcm = new double[8192];
            float[] floatOutput = new float[8192];
            short[] shortOutput = new short[8192];
            byte[] bytes = new byte[32768];
            long baseMs = initialStartMs;
            long finalFrames = 0;
            boolean ended = false;
            SpatialProcessor spatial = new SpatialProcessor(sampleRate);
            StructuralUnmasker structuralUnmasker = new StructuralUnmasker(sampleRate);
            SensoryExperienceProcessor sensoryExperience = new SensoryExperienceProcessor(sampleRate);
            EqualizerProcessor equalizer = new EqualizerProcessor(sampleRate);
            CludesProcessor cludes = new CludesProcessor(sampleRate);
            ReverbProcessor reverb = new ReverbProcessor(sampleRate);
            AudiophileMasteringStage masteringStage = new AudiophileMasteringStage(sampleRate);
            OutputLimiter limiter = new OutputLimiter(sampleRate);
            double alphaBassSwap = (2.0 * Math.PI * 140.0) / (2.0 * Math.PI * 140.0 + sampleRate);
            double alphaEchoFilter = (2.0 * Math.PI * 340.0) / (2.0 * Math.PI * 340.0 + sampleRate);
            double alphaVocalLow = (2.0 * Math.PI * 420.0) / (2.0 * Math.PI * 420.0 + sampleRate);
            double alphaVocalHigh = (2.0 * Math.PI * 3400.0) / (2.0 * Math.PI * 3400.0 + sampleRate);

            while (active(token) && !ended) {
                String pipeName = FFmpegKitConfig.registerNewFFmpegPipe(context);
                if (pipeName == null) throw new IllegalStateException("FFmpeg não abriu o fluxo PCM");
                FileInputStream stream = null;
                CrossfadeDeck activeDeck = null;
                long frames = 0;
                try {
                    String source = FFmpegKitConfig.getSafParameterForRead(context, uri);
                    String[] args = buildAudiophileDecodeArgs(source, baseMs, sampleRate, rawCodec, pipeName);
                    decoderSession = FFmpegKit.executeWithArgumentsAsync(args, session -> { });
                    stream = new FileInputStream(pipeName);
                    decoderPipe = stream;
                    int carry = 0;
                    long lastProgress = 0;
                    while (active(token)) {
                        if (seekMs >= 0) {
                            if (activeDeck != null) { activeDeck.close(); activeDeck = null; }
                            baseMs = seekMs;
                            seekMs = -1;
                            FFmpegKit.cancel(decoderSession.getSessionId());
                            output.pause(); output.flush(); if (!paused) output.play();
                            break;
                        }
                        if (paused) { sleep(20); continue; }

                        if (activeDeck == null && readyCrossfade != null) {
                            activeDeck = readyCrossfade;
                            readyCrossfade = null;
                            activeDeck.totalFadeFrames = Math.max(1, (int)((activeDeck.fadeDurationMs * (long)sampleRate) / 1000L));
                            if (activeDeck.taggedTitle != null || activeDeck.taggedArtist != null) {
                                listener.onMetadata(activeDeck.taggedTitle, activeDeck.taggedArtist);
                            }
                            listener.onMessage("AutoMix • " + AutoMixSelector.styleName(activeDeck.transitionStyle)
                                + " (" + String.format(Locale.US, "%.1fs", activeDeck.fadeDurationMs / 1000.0) + ")");
                        }

                        int read = stream.read(bytes, carry, bytes.length - carry);
                        if (read < 0) {
                            if (activeDeck == null && readyCrossfade != null) {
                                activeDeck = readyCrossfade;
                                readyCrossfade = null;
                            }
                            if (activeDeck != null) {
                                try { stream.close(); } catch (Exception ignored) { }
                                if (decoderSession != null) FFmpegKit.cancel(decoderSession.getSessionId());
                                FFmpegKitConfig.closeFFmpegPipe(pipeName);
                                stream = activeDeck.stream;
                                decoderPipe = stream;
                                decoderSession = activeDeck.session;
                                pipeName = activeDeck.pipeName;
                                uri = activeDeck.uri;
                                if (activeDeck.trackDurationMs > 0) durationMs = activeDeck.trackDurationMs;
                                baseMs = activeDeck.startMs;
                                frames = activeDeck.framesRead;
                                carry = activeDeck.carry;
                                if (carry > 0) System.arraycopy(activeDeck.bytes, 0, bytes, 0, carry);
                                activeDeck.stream = null;
                                activeDeck.session = null;
                                activeDeck.pipeName = null;
                                activeDeck = null;
                                continue;
                            }
                            ended = true;
                            finalFrames = frames;
                            break;
                        }
                        int available = read + carry;
                        int aligned = available - available % 8;
                        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, aligned).order(ByteOrder.LITTLE_ENDIAN);
                        int samples = aligned / 4;
                        for (int index = 0; index < samples; index++) pcm[index] = buffer.getFloat();
                        carry = available - aligned;
                        if (carry > 0) System.arraycopy(bytes, aligned, bytes, 0, carry);

                        // Multi-FX DJ Transition Engine
                        if (activeDeck != null && samples > 0) {
                            int neededBytes = samples * 4;
                            while (activeDeck.carry < neededBytes) {
                                int rB = activeDeck.stream.read(activeDeck.bytes, activeDeck.carry, activeDeck.bytes.length - activeDeck.carry);
                                if (rB <= 0) break;
                                activeDeck.carry += rB;
                            }
                            int alignedB = Math.min(neededBytes, activeDeck.carry - (activeDeck.carry % 8));
                            int samplesB = alignedB / 4;
                            ByteBuffer bufB = ByteBuffer.wrap(activeDeck.bytes, 0, alignedB).order(ByteOrder.LITTLE_ENDIAN);
                            for (int i = 0; i < samplesB; i++) activeDeck.pcm[i] = bufB.getFloat();
                            for (int i = samplesB; i < samples; i++) activeDeck.pcm[i] = 0.0;
                            int remB = activeDeck.carry - alignedB;
                            if (remB > 0) System.arraycopy(activeDeck.bytes, alignedB, activeDeck.bytes, 0, remB);
                            activeDeck.carry = remB;

                            int stereoFrames = samples / 2;
                            int style = activeDeck.transitionStyle;
                            double deckGain = activeDeck.targetGainRatio;

                            for (int f = 0; f < stereoFrames; f++) {
                                double t = Math.min(1.0, (double)activeDeck.fadeFramesDone / activeDeck.totalFadeFrames);
                                int idxL = f * 2;
                                int idxR = idxL + 1;
                                double aL = pcm[idxL], aR = pcm[idxR];
                                // Smoothly ramp incoming track from its loudness-matched gain toward unity
                                double bGainMatch = deckGain + (1.0 - deckGain) * (t * t);
                                double bL = activeDeck.pcm[idxL] * bGainMatch;
                                double bR = activeDeck.pcm[idxR] * bGainMatch;

                                // Dynamic Mid-Band Vocal Ducking (420 Hz - 3.4 kHz) on outgoing track so vocals never clash
                                activeDeck.midLowLA += alphaVocalLow * (aL - activeDeck.midLowLA);
                                activeDeck.midLowRA += alphaVocalLow * (aR - activeDeck.midLowRA);
                                activeDeck.midHighLA += alphaVocalHigh * (aL - activeDeck.midHighLA);
                                activeDeck.midHighRA += alphaVocalHigh * (aR - activeDeck.midHighRA);
                                double vocalBandAL = activeDeck.midHighLA - activeDeck.midLowLA;
                                double vocalBandAR = activeDeck.midHighRA - activeDeck.midLowRA;
                                double vocalDuck = 0.42 * Math.sin(t * Math.PI);
                                aL -= vocalBandAL * vocalDuck;
                                aR -= vocalBandAR * vocalDuck;

                                if (style == AutoMixSelector.STYLE_FILTER_SWEEP) {
                                    double cutoffOutHz = 40.0 * Math.pow(55.0, t);
                                    double cutoffInHz = 220.0 * Math.pow(80.0, t * t);
                                    double aOut = (2.0 * Math.PI * cutoffOutHz) / (2.0 * Math.PI * cutoffOutHz + sampleRate);
                                    double aIn = (2.0 * Math.PI * cutoffInHz) / (2.0 * Math.PI * cutoffInHz + sampleRate);

                                    activeDeck.lpLeftA += aOut * (aL - activeDeck.lpLeftA);
                                    activeDeck.lpRightA += aOut * (aR - activeDeck.lpRightA);
                                    activeDeck.lpLeftB += aIn * (bL - activeDeck.lpLeftB);
                                    activeDeck.lpRightB += aIn * (bR - activeDeck.lpRightB);

                                    double hpAL = aL - activeDeck.lpLeftA;
                                    double hpAR = aR - activeDeck.lpRightA;
                                    double gainA = Math.cos(t * Math.PI * 0.5);
                                    double gainB = Math.sin(t * Math.PI * 0.5);

                                    pcm[idxL] = hpAL * gainA + activeDeck.lpLeftB * gainB;
                                    pcm[idxR] = hpAR * gainA + activeDeck.lpRightB * gainB;

                                } else if (style == AutoMixSelector.STYLE_ECHO_FREEZE && activeDeck.echoBufL != null) {
                                    int pos = activeDeck.echoWritePos;
                                    double delayedL = activeDeck.echoBufL[pos];
                                    double delayedR = activeDeck.echoBufR[pos];

                                    activeDeck.lpLeftA += alphaEchoFilter * (aL - activeDeck.lpLeftA);
                                    activeDeck.lpRightA += alphaEchoFilter * (aR - activeDeck.lpRightA);
                                    double midAL = aL - activeDeck.lpLeftA;
                                    double midAR = aR - activeDeck.lpRightA;

                                    double feedInput = (t < 0.55) ? (1.0 - t * 1.4) : 0.0;
                                    double feedback = 0.64 * (1.0 - t * 0.45);
                                    activeDeck.echoBufL[pos] = Math.max(-1.0, Math.min(1.0, midAL * feedInput + delayedR * feedback));
                                    activeDeck.echoBufR[pos] = Math.max(-1.0, Math.min(1.0, midAR * feedInput + delayedL * feedback));
                                    activeDeck.echoWritePos = (pos + 1) % activeDeck.echoDelaySamples;

                                    double dryGainA = Math.max(0.0, 1.0 - t * 2.1);
                                    double echoWet = Math.sin(t * Math.PI) * 0.78;
                                    double gainB = Math.sin(Math.min(1.0, t * 1.25) * Math.PI * 0.5);

                                    pcm[idxL] = aL * dryGainA + delayedL * echoWet + bL * gainB;
                                    pcm[idxR] = aR * dryGainA + delayedR * echoWet + bR * gainB;

                                } else if (style == AutoMixSelector.STYLE_POWER_CUT) {
                                    double smooth = t * t * (3.0 - 2.0 * t);
                                    double gainA = 1.0 - smooth;
                                    double gainB = Math.sin(smooth * Math.PI * 0.5);
                                    activeDeck.lpLeftA += alphaBassSwap * (aL - activeDeck.lpLeftA);
                                    activeDeck.lpRightA += alphaBassSwap * (aR - activeDeck.lpRightA);
                                    double highAL = aL - activeDeck.lpLeftA;
                                    double highAR = aR - activeDeck.lpRightA;

                                    pcm[idxL] = highAL * gainA + bL * gainB;
                                    pcm[idxR] = highAR * gainA + bR * gainB;

                                } else {
                                    double angle = t * (Math.PI * 0.5);
                                    double gainA = Math.cos(angle);
                                    double gainB = Math.sin(angle);
                                    double bassCutA = (1.0 - t) * gainA;

                                    activeDeck.lpLeftA += alphaBassSwap * (aL - activeDeck.lpLeftA);
                                    activeDeck.lpRightA += alphaBassSwap * (aR - activeDeck.lpRightA);
                                    activeDeck.lpLeftB += alphaBassSwap * (bL - activeDeck.lpLeftB);
                                    activeDeck.lpRightB += alphaBassSwap * (bR - activeDeck.lpRightB);

                                    double highAL = aL - activeDeck.lpLeftA;
                                    double highAR = aR - activeDeck.lpRightA;
                                    double highBL = bL - activeDeck.lpLeftB;
                                    double highBR = bR - activeDeck.lpRightB;

                                    pcm[idxL] = activeDeck.lpLeftA * bassCutA + highAL * gainA + (activeDeck.lpLeftB + highBL) * gainB;
                                    pcm[idxR] = activeDeck.lpRightA * bassCutA + highAR * gainA + (activeDeck.lpRightB + highBR) * gainB;
                                }

                                activeDeck.fadeFramesDone++;
                                activeDeck.framesRead++;
                            }

                            if (activeDeck.fadeFramesDone >= activeDeck.totalFadeFrames) {
                                try { stream.close(); } catch (Exception ignored) { }
                                if (decoderSession != null) FFmpegKit.cancel(decoderSession.getSessionId());
                                FFmpegKitConfig.closeFFmpegPipe(pipeName);
                                stream = activeDeck.stream;
                                decoderPipe = stream;
                                decoderSession = activeDeck.session;
                                pipeName = activeDeck.pipeName;
                                uri = activeDeck.uri;
                                if (activeDeck.trackDurationMs > 0) durationMs = activeDeck.trackDurationMs;
                                baseMs = activeDeck.startMs;
                                frames = activeDeck.framesRead - stereoFrames;
                                carry = activeDeck.carry;
                                if (carry > 0) System.arraycopy(activeDeck.bytes, 0, bytes, 0, carry);
                                activeDeck.stream = null;
                                activeDeck.session = null;
                                activeDeck.pipeName = null;
                                activeDeck = null;
                            }
                        }

                        boolean processing = !bypassEffects;
                        if (processing) {
                            // 1. Structural Deconstruction & Ad-Lib Unmasking BEFORE and WITH 3D so zero content is lost
                            structuralUnmasker.process(pcm, samples, structAdlibUnmask, structCenterLock, structBeatSeparate);
                            spatial.configure(mode, amount);
                            spatial.process(pcm, samples);
                        }
                        double[] gains = eqGains;
                        if (processing && (eqEnabled || Math.abs(eqBalanceDb) > 0.05)) {
                            double[] activeGains = eqEnabled ? gains : new double[10];
                            double activePreamp = eqEnabled ? preampDb : 0.0;
                            equalizer.configure(activeGains, activePreamp, eqTopology, eqBalanceDb);
                            equalizer.process(pcm, samples);
                        }
                        if (processing) {
                            cludes.configure(cludesEnabled, cludesVolume, cludesWidth, cludesBass, cludesTreble,
                                cludesClarity, cludesLoudness, cludesEnhancer, cludesPultec, cludesBands);
                            cludes.process(pcm, samples);
                            sensoryExperience.process(pcm, samples,
                                expTactileSub, expHaasAdt, expDimensionD, expGroovePump,
                                expVenueMode, expTurntableVinyl, expCodecAdaptive, currentSourceLossy);
                            reverb.setAmount(reverbAmount);
                            reverb.process(pcm, samples);
                            // When Codec-Adaptive mode is active and FFprobe detects a lossy codec, automatically engage Hi-Res Spectral Restorer too
                            boolean activeHiRes = hiResRestorer || (expCodecAdaptive && currentSourceLossy);
                            masteringStage.process(pcm, samples, activeHiRes, dacFilterMode, autoGainEnabled);
                        }
                        double boost = 0;
                        if (processing && eqEnabled) for (double gain : gains) boost = Math.max(boost, gain);
                        double cludesHeadroom = 0;
                        if (processing && cludesEnabled) {
                            cludesHeadroom = Math.max(0, Math.max(cludesBass, cludesTreble));
                            for (double bandGain : cludesBands) cludesHeadroom = Math.max(cludesHeadroom, bandGain);
                            if (cludesClarity > 0) cludesHeadroom += 2;
                        }
                        double headroom = (processing && mode != SpatialProcessor.OFF ? 6 : 0) + boost + cludesHeadroom;
                        int satMode = processing ? saturationMode : OutputLimiter.SATURATION_OFF;
                        boolean restoreDyn = processing && dynamicRestorer;
                        limiter.process(pcm, samples, Math.pow(10, -headroom / 20), satMode, restoreDyn);
                        if (processing && phaseInvert) {
                            for (int index = 0; index < samples; index++) pcm[index] = -pcm[index];
                        }
                        visualAnalyzer.analyze(pcm, samples, sampleRate);
                        int activeDither = ditherMode;
                        for (int index = 0; index < samples; index++) {
                            floatOutput[index] = (float)pcm[index];
                            boolean isLeft = (index & 1) == 0;
                            double e1 = isLeft ? ditherErr1L : ditherErr1R;
                            double e2 = isLeft ? ditherErr2L : ditherErr2R;
                            double tpdf = (activeDither == 2) ? 0.0 : (nextUnitRandom() - nextUnitRandom()) / 32768.0;
                            // 2nd-order psychoacoustic noise-shaping filter (pushes quantization noise out of 1-5 kHz ear sensitivity band)
                            double shaped = (activeDither == 0) ? (pcm[index] - 1.54 * e1 + 0.62 * e2) : pcm[index];
                            double clamped = Math.max(-1.0, Math.min(0.999969, shaped + tpdf));
                            short quantized = (short)Math.round(clamped * 32768.0);
                            shortOutput[index] = quantized;
                            double err = (quantized / 32768.0) - shaped;
                            if (!Double.isFinite(err) || Math.abs(err) > 0.05) err = 0.0;
                            if (isLeft) {
                                ditherErr2L = ditherErr1L;
                                ditherErr1L = err;
                            } else {
                                ditherErr2R = ditherErr1R;
                                ditherErr1R = err;
                            }
                        }
                        int written = 0;
                        while (written < samples && active(token)) {
                            if (paused) { sleep(15); continue; }
                            int count = encoding == AudioFormat.ENCODING_PCM_FLOAT
                                ? output.write(floatOutput, written, samples - written, AudioTrack.WRITE_NON_BLOCKING)
                                : output.write(shortOutput, written, samples - written, AudioTrack.WRITE_NON_BLOCKING);
                            if (count < 0) throw new IllegalStateException("Erro de saída: " + count);
                            if (count == 0) sleep(5);
                            written += count;
                        }
                        frames += written / 2;
                        if (!routeReported && written > 0) routeReported = reportRoute(output);
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastProgress > 250) {
                            long reportPos = activeDeck != null
                                ? (activeDeck.startMs + activeDeck.framesRead * 1000 / sampleRate)
                                : (baseMs + frames * 1000 / sampleRate);
                            long reportDur = (activeDeck != null && activeDeck.trackDurationMs > 0)
                                ? activeDeck.trackDurationMs : durationMs;
                            listener.onProgress(reportPos, reportDur);
                            lastProgress = now;
                        }
                    }
                    if (ended && active(token)) {
                        FFmpegSession completed = decoderSession;
                        for (int attempt = 0; attempt < 20 && completed != null && completed.getReturnCode() == null; attempt++) sleep(10);
                        if (completed != null && !ReturnCode.isSuccess(completed.getReturnCode()))
                            throw new IllegalStateException("Decodificação falhou (código " + completed.getReturnCode() + ")");
                    }
                } finally {
                    if (activeDeck != null) activeDeck.close();
                    if (stream != null) try { stream.close(); } catch (Exception ignored) { }
                    decoderPipe = null;
                    FFmpegSession session = decoderSession;
                    if (session != null && !ended) FFmpegKit.cancel(session.getSessionId());
                    decoderSession = null;
                    if (pipeName != null) FFmpegKitConfig.closeFFmpegPipe(pipeName);
                }
            }
            if (active(token) && ended) {
                while (active(token) && output.getPlaybackHeadPosition() < finalFrames) sleep(20);
                if (active(token)) { listener.onProgress(durationMs, durationMs); listener.onFinished(); }
            }
        } catch (Throwable error) {
            if (active(token)) listener.onMessage("Falha no FFmpeg: " + error.getClass().getSimpleName() + " " + error.getMessage());
        } finally {
            CrossfadeDeck pending = readyCrossfade;
            readyCrossfade = null;
            if (pending != null) pending.close();
            if (output != null) { output.release(); if (track == output) track = null; }
        }
    }

    /**
     * Selects IEEE Floating-Point native FFmpeg decoders (mp3float, mp2float, mp1float, ac3, eac3, aac)
     * instead of default 16-bit integer decoders so sub-LSB detail and >0 dBFS inter-sample peaks are never truncated.
     */
    private static String preferredFloatDecoder(String rawCodec) {
        if (rawCodec == null) return null;
        String c = rawCodec.trim().toLowerCase(Locale.ROOT);
        if (c.equals("mp3")) return "mp3float";
        if (c.equals("mp2")) return "mp2float";
        if (c.equals("mp1")) return "mp1float";
        if (c.equals("als")) return "als";
        return null;
    }

    /**
     * Builds the audiophile FFmpeg decode pipeline with:
     * 1. Native IEEE float decoder selection (mp3float, etc.)
     * 2. High-precision 28-bit Kaiser window polyphase resampler (filter_size=64, phase_shift=10, cutoff=0.98, exact_rational=1)
     */
    private static String[] buildAudiophileDecodeArgs(String source, long startMs, int sampleRate, String rawCodec, String pipeName) {
        String floatDec = preferredFloatDecoder(rawCodec);
        String filterChain = "aresample=" + sampleRate
            + ":filter_size=64:phase_shift=10:cutoff=0.98:filter_type=kaiser:kaiser_beta=10:exact_rational=1,"
            + "aformat=sample_fmts=flt:channel_layouts=stereo";
        if (floatDec != null) {
            return new String[]{
                "-hide_banner", "-nostdin", "-loglevel", "error",
                "-ss", String.format(Locale.ROOT, "%.3f", startMs / 1000.0),
                "-c:a", floatDec,
                "-i", source,
                "-map", "0:a:0", "-vn",
                "-af", filterChain,
                "-ar", Integer.toString(sampleRate),
                "-f", "f32le", "-acodec", "pcm_f32le", "-y", pipeName
            };
        }
        return new String[]{
            "-hide_banner", "-nostdin", "-loglevel", "error",
            "-ss", String.format(Locale.ROOT, "%.3f", startMs / 1000.0),
            "-i", source,
            "-map", "0:a:0", "-vn",
            "-af", filterChain,
            "-ar", Integer.toString(sampleRate),
            "-f", "f32le", "-acodec", "pcm_f32le", "-y", pipeName
        };
    }

    private int chooseOutputEncoding(int sampleRate) {
        if (outputProfile == 1) return AudioFormat.ENCODING_PCM_16BIT;
        if (outputProfile == 2) return AudioFormat.ENCODING_PCM_FLOAT;
        // In Auto mode (0), deliver 32-bit IEEE Float (24-bit mantissa) across USB DAC, Wired Headphones,
        // Bluetooth A2DP (LDAC / aptX HD / AAC), and modern Android mixers (decode() safely falls back to PCM16 if unsupported)
        return AudioFormat.ENCODING_PCM_FLOAT;
    }
    private AudioTrack createOutput(int sampleRate, int encoding) {
        try {
            int minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, encoding);
            if (minimum <= 0) return null;
            AudioTrack output = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(encoding).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(Math.max(minimum * 4, 65536)).setTransferMode(AudioTrack.MODE_STREAM).build();
            if (output.getState() == AudioTrack.STATE_INITIALIZED) return output;
            output.release();
        } catch (Exception ignored) { }
        return null;
    }
    private boolean reportRoute(AudioTrack output) {
        AudioDeviceInfo device = output.getRoutedDevice();
        if (device == null) return false;
        int type = device.getType();
        String route = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLE_HEADSET
            ? "Bluetooth estéreo" : type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                ? "Bluetooth em modo chamada" : type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    ? "Fone com fio" : type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET
                        ? "DAC USB" : "Saída do Android";
        listener.onRoute(route);
        return true;
    }
    private void sleep(long milliseconds) {
        try { Thread.sleep(milliseconds); } catch (InterruptedException ignored) { }
    }
    private String readTag(JSONObject tags, String key) {
        if (tags == null) return null;
        String value = tags.optString(key, tags.optString(key.toUpperCase(Locale.ROOT), ""));
        return value.trim().isEmpty() ? null : value.trim();
    }
}
