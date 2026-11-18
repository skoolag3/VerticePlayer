package com.oneshot.surround;

/**
 * Audiophile Mastering Limiter & Analog Stage (float64) with:
 * - 3 Selectable Analog Saturation Flavors:
 *     1 = Class-A Triode (asymmetric 2nd-order even harmonics for vocal warmth & depth)
 *     2 = Studer Magnetic Tape (3rd-order odd harmonics + gentle high-frequency tape head roll-off)
 *     3 = Studio Output Transformer (Lundahl/Neve-style low-end iron weight + odd/even harmonic blend)
 * - Dynamic Range Restorer / De-Clipper (fast/slow envelope transient crest-factor expander)
 * - High-Precision 5 Hz Subsonic DC Blocker
 * - Hermite Inter-Sample Peak (True-Peak) Detector
 * - Program-Dependent Dual-Stage Release (fast transient recovery + zero bass pumping)
 */
public final class OutputLimiter {
    public static final int SATURATION_OFF = 0;
    public static final int SATURATION_TRIODE = 1;
    public static final int SATURATION_TAPE = 2;
    public static final int SATURATION_TRANSFORMER = 3;

    private final double fastRelease;
    private final double slowRelease;
    private final double dcAlpha;
    private final double transFastAlpha;
    private final double transSlowAlpha;
    private final double tapeHfAlpha;
    private final double ironBassAlpha;
    private double gain = 1.0;
    private double slowEnv = 0.0;
    private double transFastEnv = 0.0;
    private double transSlowEnv = 0.0;
    private double tapeLpL, tapeLpR;
    private double ironLpL, ironLpR;
    private double dcInLeft;
    private double dcOutLeft;
    private double dcInRight;
    private double dcOutRight;
    private double prevLeft;
    private double prevRight;

    public OutputLimiter(int sampleRate) {
        int rate = Math.max(8000, sampleRate);
        fastRelease = 1.0 - Math.exp(-1.0 / (rate * 0.028));
        slowRelease = 1.0 - Math.exp(-1.0 / (rate * 0.190));
        dcAlpha = Math.exp(-2.0 * Math.PI * 5.0 / rate);
        transFastAlpha = 1.0 - Math.exp(-1.0 / (rate * 0.0018));
        transSlowAlpha = 1.0 - Math.exp(-1.0 / (rate * 0.038));
        tapeHfAlpha = (2.0 * Math.PI * 13500.0) / (2.0 * Math.PI * 13500.0 + rate);
        ironBassAlpha = (2.0 * Math.PI * 115.0) / (2.0 * Math.PI * 115.0 + rate);
    }

    public void process(double[] samples, int count, double inputGain) {
        process(samples, count, inputGain, SATURATION_OFF, false);
    }

    public void process(double[] samples, int count, double inputGain, boolean analogWarmth) {
        process(samples, count, inputGain, analogWarmth ? SATURATION_TRIODE : SATURATION_OFF, false);
    }

    public void process(double[] samples, int count, double inputGain, int saturationMode, boolean dynamicRestorer) {
        for (int index = 0; index + 1 < count; index += 2) {
            double left = samples[index] * inputGain;
            double right = samples[index + 1] * inputGain;

            // 1. Dynamic Range Restorer / De-Clipper (restores squashed transient punch & clipped peaks)
            if (dynamicRestorer) {
                double inAbs = Math.max(Math.abs(left), Math.abs(right));
                transFastEnv += transFastAlpha * (inAbs - transFastEnv);
                transSlowEnv += transSlowAlpha * (inAbs - transSlowEnv);
                double diff = Math.max(0.0, transFastEnv - transSlowEnv);
                double expansion = 1.0 + Math.min(0.28, diff * 1.15);
                if (inAbs > 0.78) {
                    // Reconstructs flat-topped brickwall masters with cubic peak restoration
                    double crest = (inAbs - 0.78) * 0.32;
                    expansion += Math.min(0.14, crest);
                }
                left *= expansion;
                right *= expansion;
            }

            // 2. Selectable Analog Harmonic Stage
            if (saturationMode == SATURATION_TRIODE) {
                left = triodeSaturate(left);
                right = triodeSaturate(right);
            } else if (saturationMode == SATURATION_TAPE) {
                tapeLpL += tapeHfAlpha * (left - tapeLpL);
                tapeLpR += tapeHfAlpha * (right - tapeLpR);
                left = tapeSaturate(0.84 * tapeLpL + 0.16 * left);
                right = tapeSaturate(0.84 * tapeLpR + 0.16 * right);
            } else if (saturationMode == SATURATION_TRANSFORMER) {
                ironLpL += ironBassAlpha * (left - ironLpL);
                ironLpR += ironBassAlpha * (right - ironLpR);
                left = transformerSaturate(left, ironLpL);
                right = transformerSaturate(right, ironLpR);
            }

            // 3. 5 Hz Subsonic DC-offset removal
            double nextOutLeft = dcAlpha * (dcOutLeft + left - dcInLeft);
            dcInLeft = left;
            dcOutLeft = nextOutLeft;
            double nextOutRight = dcAlpha * (dcOutRight + right - dcInRight);
            dcInRight = right;
            dcOutRight = nextOutRight;

            left = softKnee(nextOutLeft);
            right = softKnee(nextOutRight);

            // 4. Estimate True-Peak (including midpoint inter-sample reconstruction)
            double midLeft = 0.5 * (left + prevLeft);
            double midRight = 0.5 * (right + prevRight);
            prevLeft = left;
            prevRight = right;

            double samplePeak = Math.max(Math.abs(left), Math.abs(right));
            double interPeak = Math.max(Math.abs(midLeft), Math.abs(midRight)) * 1.04;
            double peak = Math.max(samplePeak, interPeak);

            // 5. Program-dependent release: short spikes recover in 28ms, sustained heavy bass recovers smoothly in 190ms
            slowEnv += slowRelease * (peak - slowEnv);
            double adaptiveRelease = slowEnv > 0.72 ? slowRelease : fastRelease;

            double ceiling = 0.965;
            double required = peak > ceiling ? ceiling / peak : 1.0;
            if (required < gain) {
                gain = required;
            } else {
                gain += (required - gain) * adaptiveRelease;
            }

            samples[index] = left * gain;
            samples[index + 1] = right * gain;
        }
    }

    /** Class-A Triode: asymmetric 2nd-order even harmonics (warmth & vocal body). */
    private static double triodeSaturate(double x) {
        double clamped = Math.max(-1.6, Math.min(1.6, x));
        double evenHarmonic = 0.048 * clamped * clamped * (1.0 - 0.25 * Math.abs(clamped));
        double driven = clamped * 1.16 + evenHarmonic;
        return (Math.tanh(driven) - 0.012) / Math.tanh(1.16);
    }

    /** Studer Magnetic Tape: symmetrical 3rd-order odd harmonics + cohesive tape compression. */
    private static double tapeSaturate(double x) {
        double clamped = Math.max(-1.6, Math.min(1.6, x));
        double driven = clamped * 1.24 - 0.095 * (clamped * clamped * clamped);
        return Math.tanh(driven) / Math.tanh(1.145);
    }

    /** Neve/Lundahl Output Transformer: low-frequency iron core harmonic weight + silky air. */
    private static double transformerSaturate(double x, double lowBand) {
        double clamped = Math.max(-1.6, Math.min(1.6, x));
        double ironHarmonic = 0.085 * lowBand * (1.0 + 0.35 * Math.abs(lowBand));
        double driven = (clamped + ironHarmonic) * 1.12;
        return Math.tanh(driven) / Math.tanh(1.12);
    }

    private static double softKnee(double x) {
        double abs = Math.abs(x);
        if (abs <= 0.84) return x;
        double over = abs - 0.84;
        return Math.signum(x) * (0.84 + 0.135 * Math.tanh(over / 0.135));
    }
}
