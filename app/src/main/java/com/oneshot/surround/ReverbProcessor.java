package com.oneshot.surround;

/**
 * Audiophile Hadamard Feedback Delay Network (FDN) Stereo Reverb
 * with 18ms Acoustic Pre-Delay, Cascaded Input All-Pass Diffusers,
 * Sub-Bass High-Pass Cleanup (>130 Hz), and Air Absorption Damping.
 */
public final class ReverbProcessor {
    private final double[][] lines = new double[4][];
    private final int[] offsets = new int[4];
    private final double[] damp = new double[4];

    // 18ms Pre-delay buffer so transients remain crisp before reverb tail blooms
    private final double[] preDelayL;
    private final double[] preDelayR;
    private int prePos;

    // 2-stage Schroeder All-Pass Diffusers per channel
    private final double[] diff1L;
    private final double[] diff2L;
    private final double[] diff1R;
    private final double[] diff2R;
    private int d1PosL, d2PosL, d1PosR, d2PosR;

    // High-pass filter state to keep < 130 Hz sub-bass out of the reverb tank
    private final double hpAlpha;
    private final double dampAlpha;
    private double lowCutL, lowCutR;

    private volatile double wet;

    public ReverbProcessor(int sampleRate) {
        int rate = Math.max(8000, sampleRate);
        // Mutually prime FDN delay lengths in samples (~31.3ms, ~39.7ms, ~47.9ms, ~59.3ms)
        double[] ms = {31.3, 39.7, 47.9, 59.3};
        for (int i = 0; i < 4; i++) {
            lines[i] = new double[Math.max(8, (int) Math.round(rate * ms[i] / 1000.0))];
        }

        int preSamples = Math.max(4, (int) Math.round(rate * 0.018));
        preDelayL = new double[preSamples];
        preDelayR = new double[preSamples];

        diff1L = new double[Math.max(4, (int) Math.round(rate * 0.0047))];
        diff2L = new double[Math.max(6, (int) Math.round(rate * 0.0079))];
        diff1R = new double[Math.max(5, (int) Math.round(rate * 0.0053))];
        diff2R = new double[Math.max(7, (int) Math.round(rate * 0.0089))];

        hpAlpha = 1.0 - Math.exp(-2.0 * Math.PI * 135.0 / rate);
        dampAlpha = 1.0 - Math.exp(-2.0 * Math.PI * 3600.0 / rate);
    }

    public void setAmount(int percent) {
        wet = Math.max(0, Math.min(100, percent)) / 100.0;
    }

    public void process(double[] samples, int count) {
        double w = wet;
        if (w <= 0.0001) return;

        double decay = 0.64 + w * 0.19; // Smoothly scales RT60 decay time with reverb amount

        for (int sample = 0; sample + 1 < count; sample += 2) {
            double left = samples[sample];
            double right = samples[sample + 1];

            // 1. Remove sub-bass (<135 Hz) from reverb send so kicks/bass never muddy the room
            lowCutL += hpAlpha * (left - lowCutL);
            lowCutR += hpAlpha * (right - lowCutR);
            double sendL = left - lowCutL;
            double sendR = right - lowCutR;

            // 2. 18ms Acoustic Pre-Delay
            double delayedInL = preDelayL[prePos];
            double delayedInR = preDelayR[prePos];
            preDelayL[prePos] = sendL;
            preDelayR[prePos] = sendR;
            prePos = (prePos + 1) % preDelayL.length;

            // 3. Cascaded All-Pass Input Diffusion (smears sharp transients into velvet tail)
            double diffL = allPass(delayedInL, diff1L, d1PosL, 0.62);
            d1PosL = (d1PosL + 1) % diff1L.length;
            diffL = allPass(diffL, diff2L, d2PosL, -0.54);
            d2PosL = (d2PosL + 1) % diff2L.length;

            double diffR = allPass(delayedInR, diff1R, d1PosR, 0.62);
            d1PosR = (d1PosR + 1) % diff1R.length;
            diffR = allPass(diffR, diff2R, d2PosR, -0.54);
            d2PosR = (d2PosR + 1) % diff2R.length;

            // 4. Read 4 FDN delay lines and apply natural HF air damping
            double d0 = lines[0][offsets[0]];
            double d1 = lines[1][offsets[1]];
            double d2 = lines[2][offsets[2]];
            double d3 = lines[3][offsets[3]];

            damp[0] += dampAlpha * (d0 - damp[0]);
            damp[1] += dampAlpha * (d1 - damp[1]);
            damp[2] += dampAlpha * (d2 - damp[2]);
            damp[3] += dampAlpha * (d3 - damp[3]);

            // 5. Orthogonal 4x4 Hadamard Mixing Matrix (scatters energy across all 4 lines evenly)
            double s0 = damp[0] + damp[1];
            double s1 = damp[0] - damp[1];
            double s2 = damp[2] + damp[3];
            double s3 = damp[2] - damp[3];

            double m0 = (s0 + s2) * 0.5 * decay;
            double m1 = (s1 + s3) * 0.5 * decay;
            double m2 = (s0 - s2) * 0.5 * decay;
            double m3 = (s1 - s3) * 0.5 * decay;

            lines[0][offsets[0]] = diffL * 0.28 + m0;
            lines[1][offsets[1]] = diffR * 0.28 + m1;
            lines[2][offsets[2]] = (diffL - diffR) * 0.20 + m2;
            lines[3][offsets[3]] = (diffL + diffR) * 0.20 + m3;

            for (int i = 0; i < 4; i++) {
                offsets[i] = (offsets[i] + 1) % lines[i].length;
            }

            double wetL = d0 * 0.45 + d2 * 0.35 - d1 * 0.20;
            double wetR = d1 * 0.45 - d2 * 0.35 + d3 * 0.20;

            samples[sample] = left * (1.0 - w * 0.26) + wetL * w * 0.52;
            samples[sample + 1] = right * (1.0 - w * 0.26) + wetR * w * 0.52;
        }
    }

    private static double allPass(double input, double[] buffer, int pos, double coeff) {
        double delayed = buffer[pos];
        double output = -coeff * input + delayed;
        buffer[pos] = input + coeff * output;
        return output;
    }
}
