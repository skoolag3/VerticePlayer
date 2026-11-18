package com.oneshot.surround;

/**
 * AudiophileMasteringStage (float64) implementing:
 * 1. Hi-Res Spectral Restorer (Sony DSEE Ultimate style):
 *    Extracts the 7.5 kHz - 14.5 kHz upper-treble band, rectifies transient harmonic envelopes,
 *    and synthesizes phase-coherent octave air harmonics above 14.5 kHz to restore lost MP3/AAC/Opus air.
 * 2. DAC Digital Reconstruction Filter Emulation (Chord / AKM / R2R NOS):
 *    - FILTER_LINEAR_PHASE (0): Crisp transparent reference pass-through
 *    - FILTER_MINIMUM_PHASE (1): Causal 1st/2nd-order all-pass impulse shaping (zero pre-ringing, organic transient attack)
 *    - FILTER_NOS_R2R (2): Non-Oversampling R2R Ladder DAC simulation (natural Butterworth HF silk roll-off + micro-linearity warmth)
 * 3. EBU R128 / ReplayGain Continuous Auto-Gain Normalizer:
 *    Slow-integrating LUFS/RMS loudness tracker with silence gate that gently normalizes quiet vintage tracks
 *    and loud modern masters toward a consistent target loudness when enabled.
 */
public final class AudiophileMasteringStage {
    public static final int DAC_LINEAR_PHASE = 0;
    public static final int DAC_MINIMUM_PHASE = 1;
    public static final int DAC_NOS_R2R = 2;

    private final double lp7kAlpha;
    private final double lp14kAlpha;
    private final double airEnvAlpha;
    private final double nosRollAlpha;
    private final double lufsSlowAlpha;

    // Hi-Res Spectral Restorer states (L/R)
    private double lp7L, lp7R;
    private double lp14L, lp14R;
    private double airSmoothL, airSmoothR;
    private double airEnvL, airEnvR;

    // DAC Filter states (Minimum Phase all-pass & NOS R2R ladder)
    private double apInL, apOutL, apInR, apOutR;
    private double nosLpL, nosLpR;

    // Auto-Gain EBU R128 states
    private double integratedPower = 0.035;
    private double smoothAutoGain = 1.0;

    public AudiophileMasteringStage(int sampleRate) {
        int rate = Math.max(8000, sampleRate);
        lp7kAlpha = 1.0 - Math.exp(-2.0 * Math.PI * Math.min(7200.0, rate * 0.28) / rate);
        lp14kAlpha = 1.0 - Math.exp(-2.0 * Math.PI * Math.min(14200.0, rate * 0.44) / rate);
        airEnvAlpha = 1.0 - Math.exp(-1.0 / (rate * 0.008));
        nosRollAlpha = 1.0 - Math.exp(-2.0 * Math.PI * Math.min(16800.0, rate * 0.46) / rate);
        lufsSlowAlpha = 1.0 - Math.exp(-1.0 / (rate * 0.45));
    }

    public void resetAutoGain() {
        integratedPower = 0.035;
        smoothAutoGain = 1.0;
    }

    public void process(double[] pcm, int count, boolean spectralRestorer, int dacFilterMode, boolean autoGainEnabled) {
        if (!spectralRestorer && dacFilterMode == DAC_LINEAR_PHASE && !autoGainEnabled) {
            return;
        }

        for (int i = 0; i + 1 < count; i += 2) {
            double left = pcm[i];
            double right = pcm[i + 1];

            // 1. EBU R128 Perceptual Auto-Gain Normalizer (gated at -48 dBFS so silence is never boosted)
            if (autoGainEnabled) {
                double framePower = 0.5 * (left * left + right * right);
                if (framePower > 0.000016) {
                    integratedPower += lufsSlowAlpha * (framePower - integratedPower);
                    double targetPower = 0.038; // ~ -14 LUFS audiophile reference
                    double desiredGain = Math.pow(targetPower / Math.max(0.0004, integratedPower), 0.38);
                    desiredGain = Math.max(0.62, Math.min(1.58, desiredGain));
                    smoothAutoGain += 0.00025 * (desiredGain - smoothAutoGain);
                }
                left *= smoothAutoGain;
                right *= smoothAutoGain;
            }

            // 2. Hi-Res Spectral Restorer (DSEE-style Upper-Octave Harmonic Air Synthesizer)
            if (spectralRestorer) {
                lp7L += lp7kAlpha * (left - lp7L);
                lp7R += lp7kAlpha * (right - lp7R);
                lp14L += lp14kAlpha * (left - lp14L);
                lp14R += lp14kAlpha * (right - lp14R);

                // Extract 7.2 kHz .. 14.2 kHz seed band
                double seedL = lp14L - lp7L;
                double seedR = lp14R - lp7R;

                airEnvL += airEnvAlpha * (Math.abs(seedL) - airEnvL);
                airEnvR += airEnvAlpha * (Math.abs(seedR) - airEnvR);

                // Generate even + odd harmonic overtones via soft non-linear waveshaping, then high-pass above 14.2 kHz
                double rawHarmL = seedL * Math.abs(seedL) * 2.4 + Math.tanh(seedL * 2.8) * 0.25 * airEnvL;
                double rawHarmR = seedR * Math.abs(seedR) * 2.4 + Math.tanh(seedR * 2.8) * 0.25 * airEnvR;

                airSmoothL += lp14kAlpha * (rawHarmL - airSmoothL);
                airSmoothR += lp14kAlpha * (rawHarmR - airSmoothR);

                double synthAirL = rawHarmL - airSmoothL;
                double synthAirR = rawHarmR - airSmoothR;

                left += synthAirL * 0.38;
                right += synthAirR * 0.38;
            }

            // 3. DAC Digital Reconstruction Filter Emulation
            if (dacFilterMode == DAC_MINIMUM_PHASE) {
                // Causal Minimum-Phase All-Pass group-delay contour: eliminates pre-ringing edge glare on transients
                double coeff = 0.28;
                double mpL = -coeff * left + apInL + coeff * apOutL;
                apInL = left;
                apOutL = mpL;
                double mpR = -coeff * right + apInR + coeff * apOutR;
                apInR = right;
                apOutR = mpR;
                left = 0.72 * left + 0.28 * mpL;
                right = 0.72 * right + 0.28 * mpR;
            } else if (dacFilterMode == DAC_NOS_R2R) {
                // Non-Oversampling R2R Ladder DAC: silky zero-ringing HF roll-off + resistor-ladder 2nd-order micro-warmth
                nosLpL += nosRollAlpha * (left - nosLpL);
                nosLpR += nosRollAlpha * (right - nosLpR);
                double r2rL = 0.86 * nosLpL + 0.14 * left;
                double r2rR = 0.86 * nosLpR + 0.14 * right;
                left = r2rL + 0.014 * r2rL * r2rL * Math.signum(r2rL);
                right = r2rR + 0.014 * r2rR * r2rR * Math.signum(r2rR);
            }

            pcm[i] = left;
            pcm[i + 1] = right;
        }
    }
}
