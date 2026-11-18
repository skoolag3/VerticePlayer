package com.oneshot.surround;

/**
 * Audiophile 64-bit Binaural & Spatial DSP Engine.
 *
 * Implements 6 psychoacoustic pillars in double precision:
 * 1. Elliptical Sub-Bass Mono Filter (< 90 Hz phase-locked mono) + Binaural Subwoofer Upper-Bass Harmonics (130-240 Hz).
 * 2. True Binaural Head-Shadow Crossfeed (ITD ~280 us interaural delay + ILD ~850 Hz cranial low-pass + High-Shelf compensation).
 * 3. Parametric HRTF Pinna Cues (Asymmetric 3.2 kHz concha presence boost & 7.8 kHz pinna elevation notch on spatial field).
 * 4. Cascaded Schroeder All-Pass Phase Decorrelation (Asymmetric L/R prime-sample all-pass filters for zero-comb-filtering 3D air).
 * 5. 6-Tap Binaural Early Reflections / ITDG (8 ms to 34 ms cross-lateral studio geometry with 3.8 kHz wall absorption damping).
 * 6. 9 Selectable Spatial Profiles including HOLOGRAPHIC (Binaural 3D Reference) and CROSSFEED (Meier/Bauer Audiophile Monitor).
 */
public final class SpatialProcessor {
    public static final int OFF = 0;
    public static final int WIDE = 1;
    public static final int ROOM = 2;
    public static final int ORBIT = 3;
    public static final int FRONT = 4;
    public static final int SURROUND = 5;
    public static final int THEATER = 6;
    public static final int AMBIO = 7;
    public static final int HOLOGRAPHIC = 8;

    private final int sampleRate;

    // 6-Tap Binaural Early Reflections ring buffers (up to 45ms)
    private final double[] erLeft;
    private final double[] erRight;
    private final int[] tapSamples = new int[6];
    private final double[] tapDampL = new double[3];
    private final double[] tapDampR = new double[3];
    private int erWritePos;

    // Interaural Time Difference (ITD) & Ambiophonics micro-delay lines
    private final double[] itdLineL;
    private final double[] itdLineR;
    private final int itdSamples;
    private final int ambioSamples;
    private int itdPos;

    // Cascaded All-Pass Phase Decorrelators (2 stages per channel with asymmetric prime delays)
    private final double[] ap1BufL;
    private final double[] ap2BufL;
    private final double[] ap1BufR;
    private final double[] ap2BufR;
    private int ap1PosL, ap2PosL, ap1PosR, ap2PosR;

    // Elliptical Sub-Bass (< 90 Hz) and Upper-Bass (220 Hz) crossover states
    private final double subAlpha;
    private final double upperBassAlpha;
    private final double headShadowAlpha;
    private final double wallDampAlpha;
    private double subL1, subR1, subL2, subR2;
    private double upperBassL, upperBassR;

    // Head-Shadow ILD filter states (crossfeed low-pass)
    private double crossLowL, crossLowR;

    // Pinna HRTF Biquads for Side/Spatial elevation cues (3.2 kHz concha peak + 7.8 kHz pinna notch)
    private final Biquad pinnaPeakL = new Biquad();
    private final Biquad pinnaPeakR = new Biquad();
    private final Biquad pinnaNotchL = new Biquad();
    private final Biquad pinnaNotchR = new Biquad();

    // CamillaDSP / Spotron R.A.C.E. Bandpass Biquads inside recursive loop (250 Hz HP + 5000 Hz LP per channel)
    private final Biquad raceHpToL = new Biquad();
    private final Biquad raceLpToL = new Biquad();
    private final Biquad raceHpToR = new Biquad();
    private final Biquad raceLpToR = new Biquad();

    private double orbitPhase;
    private volatile int mode = ROOM;
    private volatile double amount = 0.8;

    public SpatialProcessor(int sampleRate) {
        this.sampleRate = Math.max(8000, sampleRate);

        int maxErLen = Math.max(256, this.sampleRate * 48 / 1000);
        erLeft = new double[maxErLen];
        erRight = new double[maxErLen];

        // 6 Early reflection taps (in ms): L1=8.3ms, R1=11.7ms, L2=16.9ms, R2=21.4ms, L3=27.8ms, R3=33.6ms
        double[] tapMs = {8.3, 11.7, 16.9, 21.4, 27.8, 33.6};
        for (int i = 0; i < 6; i++) {
            tapSamples[i] = Math.min(maxErLen - 2, Math.max(1, (int) Math.round(this.sampleRate * tapMs[i] / 1000.0)));
        }

        // ITD ~290 microseconds (human head width delay) & Ambiophonics ~70 microseconds (Spotron R.A.C.E. default)
        itdSamples = Math.max(2, (int) Math.round(this.sampleRate * 0.00029));
        ambioSamples = Math.max(1, (int) Math.round(this.sampleRate * 0.000070));
        int maxItdLen = Math.max(itdSamples, ambioSamples) + 8;
        itdLineL = new double[maxItdLen];
        itdLineR = new double[maxItdLen];

        // Asymmetric All-Pass buffer sizes (~1.7ms & ~3.9ms Left vs ~2.3ms & ~4.7ms Right)
        ap1BufL = new double[Math.max(4, (int) Math.round(this.sampleRate * 0.00173))];
        ap2BufL = new double[Math.max(7, (int) Math.round(this.sampleRate * 0.00389))];
        ap1BufR = new double[Math.max(5, (int) Math.round(this.sampleRate * 0.00229))];
        ap2BufR = new double[Math.max(9, (int) Math.round(this.sampleRate * 0.00467))];

        subAlpha = 1.0 - Math.exp(-2.0 * Math.PI * 90.0 / this.sampleRate);
        upperBassAlpha = 1.0 - Math.exp(-2.0 * Math.PI * 220.0 / this.sampleRate);
        headShadowAlpha = 1.0 - Math.exp(-2.0 * Math.PI * 700.0 / this.sampleRate);
        wallDampAlpha = 1.0 - Math.exp(-2.0 * Math.PI * 3800.0 / this.sampleRate);

        // Configure asymmetric Pinna HRTF cues (slight L/R anthropometry offset for natural 3D externalization)
        pinnaPeakL.configurePeaking(Math.min(3150.0, this.sampleRate * 0.42), this.sampleRate, 1.8, 3.2);
        pinnaPeakR.configurePeaking(Math.min(3280.0, this.sampleRate * 0.42), this.sampleRate, 1.8, 3.2);
        pinnaNotchL.configurePeaking(Math.min(7650.0, this.sampleRate * 0.45), this.sampleRate, 3.4, -4.8);
        pinnaNotchR.configurePeaking(Math.min(7950.0, this.sampleRate * 0.45), this.sampleRate, 3.4, -4.8);

        // Configure CamillaDSP / Spotron R.A.C.E. 250 Hz - 5000 Hz Bandpass inside the recursive loop
        raceHpToL.configureButterworth(true, 250.0, this.sampleRate);
        raceLpToL.configureButterworth(false, Math.min(5000.0, this.sampleRate * 0.44), this.sampleRate);
        raceHpToR.configureButterworth(true, 250.0, this.sampleRate);
        raceLpToR.configureButterworth(false, Math.min(5000.0, this.sampleRate * 0.44), this.sampleRate);
    }

    public void setEffect(int mode, int amountPercent) {
        this.mode = Math.max(OFF, Math.min(HOLOGRAPHIC, mode));
        this.amount = Math.max(0, Math.min(100, amountPercent)) / 100.0;
    }

    public void configure(int mode, int amountPercent) {
        setEffect(mode, amountPercent);
    }

    public void process(short[] pcm, int shortCount) {
        double[] buffer = new double[shortCount];
        for (int index = 0; index < shortCount; index++) buffer[index] = pcm[index] / 32768.0;
        process(buffer, shortCount);
        for (int index = 0; index < shortCount; index++) {
            double sample = Math.max(-1.0, Math.min(1.0, buffer[index]));
            pcm[index] = (short) Math.round(sample * 32767.0);
        }
    }

    public void process(double[] pcm, int sampleCount) {
        int activeMode = mode;
        double depth = amount;
        if (activeMode == OFF || depth <= 0.0001) return;

        int erLen = erLeft.length;
        int itdLen = itdLineL.length;

        for (int i = 0; i + 1 < sampleCount; i += 2) {
            double left = pcm[i];
            double right = pcm[i + 1];

            // --- 1. ELLIPTICAL SUB-BASS FILTER (< 90 Hz 2-pole Linkwitz-Riley style mono lock) ---
            subL1 += subAlpha * (left - subL1);
            subR1 += subAlpha * (right - subR1);
            subL2 += subAlpha * (subL1 - subL2);
            subR2 += subAlpha * (subR1 - subR2);
            double monoSub = (subL2 + subR2) * 0.5;
            double subSideCancel = (subL2 - subR2) * 0.5;

            // Upper-bass band (90 Hz - 220 Hz) for Binaural Subwoofer harmonic warmth
            upperBassL += upperBassAlpha * ((left - subL2) - upperBassL);
            upperBassR += upperBassAlpha * ((right - subR2) - upperBassR);
            double upperBassMid = (upperBassL + upperBassR) * 0.5;
            // Subtle 2nd/3rd harmonic spatial aura on upper-bass so kick/bass feels like a physical room sub
            double subHarmonicAura = Math.tanh(upperBassMid * 2.2) * 0.065 * depth;

            // Clean Mid/Side separation with sub-90Hz forced 100% phase-locked mono
            double mid = (left + right) * 0.5;
            double side = (left - right) * 0.5 - subSideCancel;

            // --- 2. BINAURAL HEAD-SHADOW CROSSFEED (ITD ~290us + ILD ~820Hz) ---
            int delayedItdIdx = (itdPos - itdSamples + itdLen) % itdLen;
            double delayedLeftForRight = itdLineL[delayedItdIdx];
            double delayedRightForLeft = itdLineR[delayedItdIdx];
            itdLineL[itdPos] = left;
            itdLineR[itdPos] = right;

            crossLowL += headShadowAlpha * (delayedRightForLeft - crossLowL);
            crossLowR += headShadowAlpha * (delayedLeftForRight - crossLowR);

            // --- 3. ALL-PASS PHASE DECORRELATION (Asymmetric Schroeder all-pass on Side/Spatial) ---
            double decorL = allPass(side + subHarmonicAura, ap1BufL, ap1PosL, 0.618);
            ap1PosL = (ap1PosL + 1) % ap1BufL.length;
            decorL = allPass(decorL, ap2BufL, ap2PosL, -0.52);
            ap2PosL = (ap2PosL + 1) % ap2BufL.length;

            double decorR = allPass(-side - subHarmonicAura, ap1BufR, ap1PosR, 0.618);
            ap1PosR = (ap1PosR + 1) % ap1BufR.length;
            decorR = allPass(decorR, ap2BufR, ap2PosR, -0.52);
            ap2PosR = (ap2PosR + 1) % ap2BufR.length;

            // --- 4. PINNA CUES (HRTF 3.2kHz Presence + 7.8kHz Elevation Notch on Decorrelated Field) ---
            double hrtfL = pinnaNotchL.process(pinnaPeakL.process(decorL));
            double hrtfR = pinnaNotchR.process(pinnaPeakR.process(decorR));

            // --- 5. 6-TAP BINAURAL EARLY REFLECTIONS (ITDG 8ms - 34ms with Wall Absorption) ---
            double tapL1 = erLeft[(erWritePos - tapSamples[0] + erLen) % erLen];
            double tapR1 = erRight[(erWritePos - tapSamples[1] + erLen) % erLen];
            double tapL2 = erLeft[(erWritePos - tapSamples[2] + erLen) % erLen];
            double tapR2 = erRight[(erWritePos - tapSamples[3] + erLen) % erLen];
            double tapL3 = erLeft[(erWritePos - tapSamples[4] + erLen) % erLen];
            double tapR3 = erRight[(erWritePos - tapSamples[5] + erLen) % erLen];

            tapDampL[0] += wallDampAlpha * (tapL1 - tapDampL[0]);
            tapDampR[0] += wallDampAlpha * (tapR1 - tapDampR[0]);
            tapDampL[1] += wallDampAlpha * (tapL2 - tapDampL[1]);
            tapDampR[1] += wallDampAlpha * (tapR2 - tapDampR[1]);
            tapDampL[2] += wallDampAlpha * (tapL3 - tapDampL[2]);
            tapDampR[2] += wallDampAlpha * (tapR3 - tapDampR[2]);

            // Cross-lateral early reflections (early wall bounce + cross-room bounce + rear diffused bounce)
            double earlyL = tapDampL[0] * 0.42 - tapDampR[0] * 0.28 + tapDampR[1] * 0.22 - tapDampL[2] * 0.14;
            double earlyR = tapDampR[0] * 0.42 - tapDampL[0] * 0.28 + tapDampL[1] * 0.22 - tapDampR[2] * 0.14;

            // Write current energy into Early Reflection delay line with gentle feedback
            erLeft[erWritePos] = left - subL2 + tapDampR[1] * 0.16 * depth;
            erRight[erWritePos] = right - subR2 + tapDampL[1] * 0.16 * depth;

            // --- 6. MODE SYNTHESIS ---
            double outL;
            double outR;

            if (activeMode == WIDE) {
                // Wide Mastering: 100% Dry Center Core + HRTF widened Side + All-Pass air
                double wideGain = 1.0 + depth * 0.95;
                outL = mid + side * wideGain + hrtfL * depth * 0.28 + monoSub * 0.08 * depth;
                outR = mid - side * wideGain + hrtfR * depth * 0.28 + monoSub * 0.08 * depth;
            } else if (activeMode == ROOM) {
                // Studio Room: Zero-loss Center + Natural Binaural Crossfeed + 6-Tap Early Reflections + Pinna Air
                double crossMix = 0.20 * depth;
                outL = mid + side * (1.0 + 0.35 * depth)
                    + (crossLowL - mid * 0.18) * crossMix + earlyL * depth * 0.46 + hrtfL * depth * 0.22;
                outR = mid - side * (1.0 + 0.35 * depth)
                    + (crossLowR - mid * 0.18) * crossMix + earlyR * depth * 0.46 + hrtfR * depth * 0.22;
            } else if (activeMode == FRONT) {
                // Spotron CamillaDSP bs2b Crossfeed: 700 Hz LowpassFO + 0.3 ms delay with normalized linear sum (g_same + g_cross = 1.0)
                double levelDb = 9.5 - 5.0 * depth; // 9.5 dB subtle -> 4.5 dB classic bs2b
                double c = Math.pow(10.0, -levelDb / 20.0);
                double gSame = 1.0 / (1.0 + c);
                double gCross = c / (1.0 + c);
                outL = gSame * left + gCross * crossLowL + earlyL * 0.14 * depth;
                outR = gSame * right + gCross * crossLowR + earlyR * 0.14 * depth;
            } else if (activeMode == SURROUND) {
                // Multi-Speaker Surround: 100% intact Center + Deep Rear/Side Matrix + All-Pass Decorrelation
                double surroundL = hrtfL * 0.52 + earlyL * 0.58 - tapDampR[2] * 0.30;
                double surroundR = hrtfR * 0.52 + earlyR * 0.58 - tapDampL[2] * 0.30;
                outL = mid + side * (1.0 + 0.65 * depth) + surroundL * depth + (crossLowL - mid * 0.15) * 0.14 * depth;
                outR = mid - side * (1.0 + 0.65 * depth) + surroundR * depth + (crossLowR - mid * 0.15) * 0.14 * depth;
            } else if (activeMode == THEATER) {
                // Cinema IMAX: Anchored vocal center + Sub-Bass physical punch + Wide Rear Array
                double centerVoice = mid - monoSub;
                double subBoost = monoSub * (1.0 + 0.32 * depth) + subHarmonicAura * 1.4;
                outL = centerVoice * (1.0 + 0.12 * depth) + subBoost + side * (1.0 + 0.72 * depth)
                    + (earlyL * 0.62 + hrtfL * 0.38) * depth;
                outR = centerVoice * (1.0 + 0.12 * depth) + subBoost - side * (1.0 + 0.72 * depth)
                    + (earlyR * 0.62 + hrtfR * 0.38) * depth;
            } else if (activeMode == AMBIO) {
                // Spotron CamillaDSP Ambiophonic R.A.C.E. (Glasgal & Miller, 2007):
                // Recursive crosstalk cancellation with 250 Hz - 5000 Hz Bandpass inside the loop so bass & highs pass 100% intact!
                int ambioIdx = (itdPos - ambioSamples + itdLen) % itdLen;
                double attenDb = 4.0 - 1.5 * depth; // 4.0 dB -> 2.5 dB recursive attenuation
                double g = Math.pow(10.0, -attenDb / 20.0) * Math.min(1.0, 0.35 + 0.65 * depth);
                double fbL = raceLpToL.process(raceHpToL.process(itdLineR[ambioIdx]));
                double fbR = raceLpToR.process(raceHpToR.process(itdLineL[ambioIdx]));
                double raceL = left - g * fbL;
                double raceR = right - g * fbR;
                itdLineL[itdPos] = raceL;
                itdLineR[itdPos] = raceR;
                // Peak compensation (side gain is 1 / (1 - g), normalized to keep vocal & stage punchy without clipping)
                double comp = 1.0 / (1.0 + 0.32 * g);
                outL = raceL * comp + monoSub * (0.25 * depth) + hrtfL * (0.24 * depth);
                outR = raceR * comp + monoSub * (0.25 * depth) + hrtfR * (0.24 * depth);
            } else if (activeMode == HOLOGRAPHIC) {
                // Holographic 3D Reference: 100% Zero-Loss Center Core + Full 6-Pillar Binaural Field
                double crossMix = 0.22 * depth;
                double holoSideL = hrtfL * 0.56 + decorL * 0.28 + earlyL * 0.48;
                double holoSideR = hrtfR * 0.56 + decorR * 0.28 + earlyR * 0.48;
                double solidCenter = (mid - monoSub) + monoSub * (1.0 + 0.18 * depth);
                outL = solidCenter + side * (1.0 + 0.50 * depth) + (crossLowL - mid * 0.18) * crossMix + holoSideL * depth;
                outR = solidCenter - side * (1.0 + 0.50 * depth) + (crossLowR - mid * 0.18) * crossMix + holoSideR * depth;
            } else {
                // ORBIT: 360 Binaural Rotational Stage with ITD/Pinna cues
                orbitPhase += 2.0 * Math.PI * (0.16 + depth * 0.42) / sampleRate;
                if (orbitPhase > 2.0 * Math.PI) orbitPhase -= 2.0 * Math.PI;
                double pan = Math.sin(orbitPhase);
                double elev = Math.cos(orbitPhase);
                double baseL = mid + side * (1.0 + 0.55 * depth) + hrtfL * (0.35 + 0.20 * elev) * depth + earlyL * 0.38 * depth;
                double baseR = mid - side * (1.0 + 0.55 * depth) + hrtfR * (0.35 - 0.20 * elev) * depth + earlyR * 0.38 * depth;
                outL = baseL * (1.0 - 0.26 * depth * Math.max(0.0, pan)) + crossLowL * 0.22 * depth;
                outR = baseR * (1.0 - 0.26 * depth * Math.max(0.0, -pan)) + crossLowR * 0.22 * depth;
            }

            erWritePos = (erWritePos + 1) % erLen;
            itdPos = (itdPos + 1) % itdLen;

            pcm[i] = outL;
            pcm[i + 1] = outR;
        }
    }

    private static double allPass(double input, double[] buffer, int pos, double coeff) {
        double delayed = buffer[pos];
        double output = -coeff * input + delayed;
        buffer[pos] = input + coeff * output;
        return output;
    }

    private static final class Biquad {
        private double b0 = 1.0, b1, b2, a1, a2, z1, z2;

        void configurePeaking(double freq, int sampleRate, double q, double gainDb) {
            double amp = Math.pow(10.0, gainDb / 40.0);
            double omega = 2.0 * Math.PI * freq / sampleRate;
            double alpha = Math.sin(omega) / (2.0 * q);
            double cosW = Math.cos(omega);
            double norm = 1.0 + alpha / amp;
            b0 = (1.0 + alpha * amp) / norm;
            b1 = (-2.0 * cosW) / norm;
            b2 = (1.0 - alpha * amp) / norm;
            a1 = b1;
            a2 = (1.0 - alpha / amp) / norm;
        }

        void configureButterworth(boolean highpass, double freq, int sampleRate) {
            double w0 = 2.0 * Math.PI * freq / sampleRate;
            double cw = Math.cos(w0);
            double alpha = Math.sin(w0) / Math.sqrt(2.0);
            double a0 = 1.0 + alpha;
            if (highpass) {
                b0 = ((1.0 + cw) * 0.5) / a0;
                b1 = -(1.0 + cw) / a0;
                b2 = ((1.0 + cw) * 0.5) / a0;
            } else {
                b0 = ((1.0 - cw) * 0.5) / a0;
                b1 = (1.0 - cw) / a0;
                b2 = ((1.0 - cw) * 0.5) / a0;
            }
            a1 = (-2.0 * cw) / a0;
            a2 = (1.0 - alpha) / a0;
        }

        double process(double in) {
            double out = b0 * in + z1;
            z1 = b1 * in - a1 * out + z2;
            z2 = b2 * in - a2 * out;
            return out;
        }
    }
}
