package com.oneshot.surround;

/**
 * Read-only metering of the decoded PCM; never modifies playback samples.
 * Computes sub-bass punch, transient beat onset flux, stereo balance,
 * 24-band logarithmic spectrum, 64-point oscilloscope waveform,
 * plus Goniometer / Lissajous X-Y Stereo Vectorscope, Phase Correlation (-1..+1),
 * and Dynamic Range / Crest Factor (DR in dB).
 */
public final class AudioReactiveAnalyzer {
    public static final class Frame {
        final float bass, balance, beatPulse, highAir;
        final float phaseCorrelation; // -1.0 (out of phase) to +1.0 (mono/in-phase)
        final float dynamicRangeDb;   // Crest factor (Peak/RMS in dB, typically 4..18 dB)
        final float[] bands, wave;
        final float[] lissajousL, lissajousR;

        Frame(float bass, float balance, float[] bands, float[] wave) {
            this(bass, balance, 0f, 0f, 1f, 8f, bands, wave, new float[64], new float[64]);
        }

        Frame(float bass, float balance, float beatPulse, float highAir, float[] bands, float[] wave) {
            this(bass, balance, beatPulse, highAir, 1f, 8f, bands, wave, new float[64], new float[64]);
        }

        Frame(float bass, float balance, float beatPulse, float highAir,
              float phaseCorrelation, float dynamicRangeDb,
              float[] bands, float[] wave, float[] lissajousL, float[] lissajousR) {
            this.bass = bass;
            this.balance = balance;
            this.beatPulse = beatPulse;
            this.highAir = highAir;
            this.phaseCorrelation = phaseCorrelation;
            this.dynamicRangeDb = dynamicRangeDb;
            this.bands = bands;
            this.wave = wave;
            this.lissajousL = lissajousL;
            this.lissajousR = lissajousR;
        }
    }

    private volatile Frame frame = new Frame(0, 0, new float[24], new float[64]);
    private long lastUpdate;
    private double low;
    private double slowBassEnv;
    private double pulseHold;
    private double smoothCorr = 1.0;
    private double smoothDr = 10.0;

    public Frame frame() {
        return frame;
    }

    public void analyze(double[] pcm, int count, int rate) {
        long now = System.nanoTime();
        if (now - lastUpdate < 28_000_000L || count < 512) return;
        lastUpdate = now;

        int safeRate = Math.max(8000, rate);
        int length = Math.min(count / 2, 1024);
        double[] mono = new double[length];
        double leftSq = 0, rightSq = 0, crossLR = 0;
        double bassEnergy = 0, highEnergy = 0;
        double peakAbs = 0, sumSq = 0;
        double alpha = 1.0 - Math.exp(-2.0 * Math.PI * 145.0 / safeRate);
        double prevMono = 0;

        for (int i = 0; i < length; i++) {
            double l = pcm[i * 2];
            double r = pcm[i * 2 + 1];
            double m = (l + r) * 0.5;
            mono[i] = m;
            leftSq += l * l;
            rightSq += r * r;
            crossLR += l * r;
            double absMax = Math.max(Math.abs(l), Math.abs(r));
            if (absMax > peakAbs) peakAbs = absMax;
            sumSq += (l * l + r * r) * 0.5;
            low += alpha * (m - low);
            bassEnergy += low * low;
            double diff = m - prevMono;
            highEnergy += diff * diff;
            prevMono = m;
        }

        float[] bands = new float[24];
        float[] wave = new float[64];
        float[] lissL = new float[64];
        float[] lissR = new float[64];

        for (int band = 0; band < bands.length; band++) {
            double frequency = 42.0 * Math.pow(Math.min(14500.0, safeRate * 0.45) / 42.0, band / 23.0);
            double coefficient = 2.0 * Math.cos(2.0 * Math.PI * frequency / safeRate);
            double previous = 0, before = 0;
            for (int i = 0; i < length; i++) {
                double value = mono[i] * (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (length - 1)));
                double next = value + coefficient * previous - before;
                before = previous;
                previous = next;
            }
            double amplitude = Math.sqrt(Math.max(0, previous * previous + before * before - coefficient * previous * before)) / length;
            bands[band] = (float) Math.max(0.0, Math.min(1.0, (20.0 * Math.log10(amplitude + 1e-8) + 64.0) / 54.0));
        }

        for (int i = 0; i < wave.length; i++) {
            int idx = i * (length - 1) / (wave.length - 1);
            wave[i] = (float) mono[idx];
            lissL[i] = (float) pcm[idx * 2];
            lissR[i] = (float) pcm[idx * 2 + 1];
        }

        double rawBass = Math.min(1.0, Math.sqrt(bassEnergy / length) * 5.2);
        slowBassEnv = slowBassEnv * 0.84 + rawBass * 0.16;
        double onset = Math.max(0.0, rawBass - slowBassEnv * 0.92) * 2.8;
        pulseHold = Math.max(onset, pulseHold * 0.72);
        float beatPulse = (float) Math.max(0.0, Math.min(1.0, pulseHold));
        float highAir = (float) Math.max(0.0, Math.min(1.0, Math.sqrt(highEnergy / length) * 6.0));

        double denom = Math.sqrt(leftSq * rightSq) + 1e-9;
        double rawCorr = Math.max(-1.0, Math.min(1.0, crossLR / denom));
        smoothCorr = smoothCorr * 0.80 + rawCorr * 0.20;

        double rms = Math.sqrt(sumSq / length) + 1e-7;
        double crestDb = 20.0 * Math.log10(Math.max(1.0, peakAbs / rms));
        smoothDr = smoothDr * 0.85 + Math.max(2.0, Math.min(20.0, crestDb)) * 0.15;

        frame = new Frame(
            (float) rawBass,
            (float) ((rightSq - leftSq) / (rightSq + leftSq + 1e-8)),
            beatPulse,
            highAir,
            (float) smoothCorr,
            (float) smoothDr,
            bands,
            wave,
            lissL,
            lissR
        );
    }
}
