package com.oneshot.surround;

/**
 * CludesAmp Audiophile Psychoacoustic Tone & Mastering Processor (float64):
 * - Elliptical Mid/Side Stereo Widener with < 95 Hz Sub-Bass Mono Anchor
 * - Dynamic Sub-Harmonic Bass Exciter (2nd + 3rd order virtual subwoofer harmonics)
 * - Pultec EQP-1A Low-End Trick (Simultaneous 55 Hz Sub-Bass Boost + 118 Hz Anti-Mud Attenuation Dip)
 * - Fletcher-Munson Iso-Loudness Contour (compensates both Sub-Bass < 110 Hz and Air > 6.5 kHz)
 * - Transient Micro-Contrast & Air Exciter (SPL Vitalizer / Aphex style harmonic presence)
 * - 10-Band Precision Biquad Tone Shaping
 */
public final class CludesProcessor {
    private final EqualizerProcessor fiveBand;
    private final int sampleRate;
    private final double[] subLow = new double[2];
    private final double[] low = new double[2];
    private final double[] highLow = new double[2];
    private final double[] clarityLow = new double[2];
    private final double[] fastEnv = new double[2];
    private final double[] slowEnv = new double[2];
    // Pultec EQP-1A states: 55 Hz resonant sub shelf + 118 Hz mud-cleanup dip
    private final double[] pultecSub = new double[2];
    private final double[] pultecMudLow = new double[2];
    private final double[] pultecMudHigh = new double[2];

    private double bassGain = 1;
    private double trebleGain = 1;
    private double clarity;
    private double loudness;
    private double volume = 1;
    private double width = 1;
    private boolean bassEnhancer;
    private boolean pultecSubTrick;
    private boolean enabled;

    public CludesProcessor(int sampleRate) {
        this.sampleRate = Math.max(8000, sampleRate);
        fiveBand = new EqualizerProcessor(this.sampleRate);
    }

    public void configure(boolean active, int volumePercent, int widthPercent, int bassDb, int trebleDb,
                          int clarityPercent, boolean loudnessOn, boolean enhancerOn, double[] bands) {
        configure(active, volumePercent, widthPercent, bassDb, trebleDb,
            clarityPercent, loudnessOn, enhancerOn, pultecSubTrick, bands);
    }

    public void configure(boolean active, int volumePercent, int widthPercent, int bassDb, int trebleDb,
                          int clarityPercent, boolean loudnessOn, boolean enhancerOn, boolean pultecOn, double[] bands) {
        enabled = active;
        volume = Math.max(0, Math.min(600, volumePercent)) / 100.0;
        width = Math.max(0, Math.min(200, widthPercent)) / 100.0;
        bassEnhancer = enhancerOn;
        pultecSubTrick = pultecOn;
        bassGain = Math.pow(10, bassDb / 20.0);
        trebleGain = Math.pow(10, trebleDb / 20.0);
        clarity = Math.max(0, Math.min(100, clarityPercent)) / 100.0;
        loudness = loudnessOn ? 0.16 + Math.max(0, 1.0 - Math.min(1.2, volume)) * 0.28 : 0;
        double[] mapped = {bands[0], bands[0] * 0.65, bands[1], bands[1] * 0.55,
            bands[2], bands[2] * 0.55, bands[3], bands[3] * 0.55, bands[4], bands[4]};
        fiveBand.configure(mapped, 0);
    }

    public void process(double[] pcm, int count) {
        if (!enabled) return;

        double subAlpha = 1.0 - Math.exp(-2.0 * Math.PI * 95.0 / sampleRate);
        double lowAlpha = 1.0 - Math.exp(-2.0 * Math.PI * 165.0 / sampleRate);
        double highAlpha = 1.0 - Math.exp(-2.0 * Math.PI * 4600.0 / sampleRate);
        double clarityAlpha = 1.0 - Math.exp(-2.0 * Math.PI * 2800.0 / sampleRate);
        double fastAtt = 1.0 - Math.exp(-1.0 / (sampleRate * 0.0025));
        double slowRel = 1.0 - Math.exp(-1.0 / (sampleRate * 0.045));
        double pultec55Alpha = 1.0 - Math.exp(-2.0 * Math.PI * 58.0 / sampleRate);
        double pultec90Alpha = 1.0 - Math.exp(-2.0 * Math.PI * 88.0 / sampleRate);
        double pultec160Alpha = 1.0 - Math.exp(-2.0 * Math.PI * 165.0 / sampleRate);

        // 1. Stereo M/S Width with Elliptical Sub-95Hz Mono Lock
        for (int index = 0; index + 1 < count; index += 2) {
            double left = pcm[index];
            double right = pcm[index + 1];
            subLow[0] += subAlpha * (left - subLow[0]);
            subLow[1] += subAlpha * (right - subLow[1]);
            double subSide = (subLow[0] - subLow[1]) * 0.5;

            double middle = (left + right) * 0.5;
            double highSide = ((left - right) * 0.5 - subSide) * width;
            pcm[index] = middle + highSide + subSide * Math.min(1.0, width);
            pcm[index + 1] = middle - highSide - subSide * Math.min(1.0, width);
        }

        // 2. Psychoacoustic Bass, Pultec EQP-1A Trick, Fletcher-Munson Loudness, Transient Micro-Contrast & Air Exciter
        for (int index = 0; index < count; index++) {
            int channel = index & 1;
            double input = pcm[index];

            low[channel] += lowAlpha * (input - low[channel]);
            highLow[channel] += highAlpha * (input - highLow[channel]);
            clarityLow[channel] += clarityAlpha * (input - clarityLow[channel]);

            double bass = low[channel];
            double high = input - highLow[channel];
            double presence = input - clarityLow[channel];

            double pultecDelta = 0.0;
            if (pultecSubTrick) {
                pultecSub[channel] += pultec55Alpha * (input - pultecSub[channel]);
                pultecMudLow[channel] += pultec90Alpha * (input - pultecMudLow[channel]);
                pultecMudHigh[channel] += pultec160Alpha * (input - pultecMudHigh[channel]);
                double mudBand = pultecMudHigh[channel] - pultecMudLow[channel]; // ~118 Hz muddy upper-bass band
                // Simultaneous +4.5 dB deep 55 Hz sub boost + -3.2 dB surgical 118 Hz mud dip
                pultecDelta = pultecSub[channel] * 0.58 - mudBand * 0.36;
            }

            // Dynamic envelope for transient micro-contrast & dynamic bass punch
            double absIn = Math.abs(input);
            fastEnv[channel] += fastAtt * (absIn - fastEnv[channel]);
            slowEnv[channel] += slowRel * (absIn - slowEnv[channel]);
            double transientBoost = Math.max(0.0, fastEnv[channel] - slowEnv[channel]);

            // Virtual Subwoofer 2nd + 3rd harmonic exciter with soft saturation
            double harmonic = 0.0;
            if (bassEnhancer) {
                double drivenBass = Math.max(-1.2, Math.min(1.2, bass * 1.6));
                double evenHarm = 0.11 * drivenBass * Math.abs(drivenBass);
                double oddHarm = 0.07 * (drivenBass - (drivenBass * drivenBass * drivenBass) * 0.22);
                harmonic = evenHarm + oddHarm;
            }

            // Aphex-style high-frequency harmonic air exciter + transient clarity
            double airExciter = clarity > 0.001
                ? clarity * (0.26 * presence + 0.12 * Math.tanh(high * 2.4) + 0.18 * transientBoost * presence)
                : 0.0;

            pcm[index] = (input
                + (bassGain - 1.0 + loudness * 1.15) * bass
                + pultecDelta
                + harmonic
                + (trebleGain - 1.0 + loudness * 0.58) * high
                + airExciter) * volume;
        }

        fiveBand.process(pcm, count);
    }
}
