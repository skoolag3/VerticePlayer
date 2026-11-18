package com.oneshot.surround;

/**
 * Ten-band Parametric Equalizer in double precision with selectable Topology:
 * - TOPOLOGY_STEREO (0): Classic linked Left/Right 10-band EQ
 * - TOPOLOGY_MID_ONLY (1): Applies EQ curve only to the Center (Mid = (L+R)/2) — vocals, kick, snare, bass
 * - TOPOLOGY_SIDE_ONLY (2): Applies EQ curve only to the Spatial Sides (Side = (L-R)/2) — guitars, synths, air
 * Plus precision L/R Channel Balance trim (-6.0 dB to +6.0 dB).
 */
public final class EqualizerProcessor {
    public static final int TOPOLOGY_STEREO = 0;
    public static final int TOPOLOGY_MID_ONLY = 1;
    public static final int TOPOLOGY_SIDE_ONLY = 2;

    public static final int[] FREQUENCIES = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    private final Band[][] bands = new Band[2][10];
    private final int sampleRate;
    private double preamp = 1.0;
    private volatile int topology = TOPOLOGY_STEREO;
    private volatile double leftTrim = 1.0;
    private volatile double rightTrim = 1.0;

    public EqualizerProcessor(int sampleRate) {
        this.sampleRate = Math.max(8000, sampleRate);
        for (int channel = 0; channel < 2; channel++) {
            for (int index = 0; index < 10; index++) {
                bands[channel][index] = new Band();
            }
        }
    }

    public void configure(double[] gains, double preampDb) {
        configure(gains, preampDb, topology, 0.0);
    }

    public void configure(double[] gains, double preampDb, int nextTopology, double balanceDb) {
        preamp = Math.pow(10.0, preampDb / 20.0);
        topology = Math.max(TOPOLOGY_STEREO, Math.min(TOPOLOGY_SIDE_ONLY, nextTopology));
        if (balanceDb < -0.05) {
            leftTrim = 1.0;
            rightTrim = Math.pow(10.0, balanceDb / 20.0);
        } else if (balanceDb > 0.05) {
            leftTrim = Math.pow(10.0, -balanceDb / 20.0);
            rightTrim = 1.0;
        } else {
            leftTrim = 1.0;
            rightTrim = 1.0;
        }
        for (int index = 0; index < 10; index++) {
            double frequency = Math.min(FREQUENCIES[index], sampleRate * 0.45);
            for (int channel = 0; channel < 2; channel++) {
                bands[channel][index].configure(frequency, sampleRate, gains[index]);
            }
        }
    }

    public void process(double[] samples, int count) {
        int activeTopo = topology;
        double lTrim = leftTrim;
        double rTrim = rightTrim;

        if (activeTopo == TOPOLOGY_STEREO) {
            for (int index = 0; index + 1 < count; index += 2) {
                double valL = samples[index] * preamp;
                double valR = samples[index + 1] * preamp;
                for (int b = 0; b < 10; b++) {
                    valL = bands[0][b].process(valL);
                    valR = bands[1][b].process(valR);
                }
                samples[index] = valL * lTrim;
                samples[index + 1] = valR * rTrim;
            }
        } else {
            for (int index = 0; index + 1 < count; index += 2) {
                double left = samples[index];
                double right = samples[index + 1];
                double mid = (left + right) * 0.5;
                double side = (left - right) * 0.5;

                if (activeTopo == TOPOLOGY_MID_ONLY) {
                    mid *= preamp;
                    for (int b = 0; b < 10; b++) {
                        mid = bands[0][b].process(mid);
                    }
                } else {
                    side *= preamp;
                    for (int b = 0; b < 10; b++) {
                        side = bands[1][b].process(side);
                    }
                }
                samples[index] = (mid + side) * lTrim;
                samples[index + 1] = (mid - side) * rTrim;
            }
        }
    }

    private static final class Band {
        private double b0 = 1, b1, b2, a1, a2, z1, z2;

        void configure(double frequency, int sampleRate, double gainDb) {
            double amplitude = Math.pow(10, gainDb / 40);
            double omega = 2 * Math.PI * frequency / sampleRate;
            double alpha = Math.sin(omega) / (2 * 1.25);
            double base = 1 + alpha / amplitude;
            b0 = (1 + alpha * amplitude) / base;
            b1 = (-2 * Math.cos(omega)) / base;
            b2 = (1 - alpha * amplitude) / base;
            a1 = b1;
            a2 = (1 - alpha / amplitude) / base;
        }

        double process(double input) {
            double output = b0 * input + z1;
            z1 = b1 * input - a1 * output + z2;
            z2 = b2 * input - a2 * output;
            return output;
        }
    }
}
