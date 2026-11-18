package com.oneshot.surround;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** DJ-grade harmonic, rhythmic, spectral, and behavioral selector with Top-K Softmax randomness. */
public final class AutoMixSelector {
    public static final int STYLE_BASS_SWAP = 0;
    public static final int STYLE_FILTER_SWEEP = 1;
    public static final int STYLE_ECHO_FREEZE = 2;
    public static final int STYLE_POWER_CUT = 3;

    public static final class Features {
        public final double energy;
        public final double introEnergy;
        public final double outroEnergy;
        public final int bpm;
        public final int key;
        public final boolean minor;
        public final double keyConfidence;
        public final double bassRatio;
        public final double introVocalRatio;
        public final double outroVocalRatio;
        public final int cueInMs;
        public final int cueOutMs;
        public final int beatOffsetMs;
        public final double brightnessRatio;
        public final double dynamicPunch;
        public final boolean syntheticTest;

        public Features(double energy, int bpm, int key, boolean minor, double keyConfidence) {
            this(energy, energy, energy, bpm, key, minor, keyConfidence, 0.25, 0.35, 0.35, 0, 0, 0, 0.20, 0.50, true);
        }

        public Features(double energy, double introEnergy, double outroEnergy,
                int bpm, int key, boolean minor, double keyConfidence) {
            this(energy, introEnergy, outroEnergy, bpm, key, minor, keyConfidence, 0.25, 0.35, 0.35, 0, 0, 0, 0.20, 0.50, true);
        }

        public Features(double energy, double introEnergy, double outroEnergy,
                int bpm, int key, boolean minor, double keyConfidence,
                double bassRatio, double introVocalRatio, double outroVocalRatio,
                int cueInMs, int cueOutMs, int beatOffsetMs) {
            this(energy, introEnergy, outroEnergy, bpm, key, minor, keyConfidence,
                bassRatio, introVocalRatio, outroVocalRatio, cueInMs, cueOutMs, beatOffsetMs, 0.20, 0.50, false);
        }

        public Features(double energy, double introEnergy, double outroEnergy,
                int bpm, int key, boolean minor, double keyConfidence,
                double bassRatio, double introVocalRatio, double outroVocalRatio,
                int cueInMs, int cueOutMs, int beatOffsetMs,
                double brightnessRatio, double dynamicPunch) {
            this(energy, introEnergy, outroEnergy, bpm, key, minor, keyConfidence,
                bassRatio, introVocalRatio, outroVocalRatio, cueInMs, cueOutMs, beatOffsetMs,
                brightnessRatio, dynamicPunch, false);
        }

        private Features(double energy, double introEnergy, double outroEnergy,
                int bpm, int key, boolean minor, double keyConfidence,
                double bassRatio, double introVocalRatio, double outroVocalRatio,
                int cueInMs, int cueOutMs, int beatOffsetMs,
                double brightnessRatio, double dynamicPunch, boolean syntheticTest) {
            this.energy = energy;
            this.introEnergy = introEnergy;
            this.outroEnergy = outroEnergy;
            this.bpm = bpm;
            this.key = key;
            this.minor = minor;
            this.keyConfidence = keyConfidence;
            this.bassRatio = bassRatio;
            this.introVocalRatio = introVocalRatio;
            this.outroVocalRatio = outroVocalRatio;
            this.cueInMs = cueInMs;
            this.cueOutMs = cueOutMs;
            this.beatOffsetMs = beatOffsetMs;
            this.brightnessRatio = brightnessRatio;
            this.dynamicPunch = dynamicPunch;
            this.syntheticTest = syntheticTest;
        }
    }

    /** Behavioral telemetry per track (persisted by AutoMixAnalyzer). */
    public static final class TasteStats {
        public int plays;
        public int skips;
        public long lastActionMs;

        public TasteStats(int plays, int skips, long lastActionMs) {
            this.plays = Math.max(0, plays);
            this.skips = Math.max(0, skips);
            this.lastActionMs = lastActionMs;
        }

        /** Bayesian smoothed affinity score in range [-6.5, +5.5]. */
        public double affinityBonus() {
            int total = plays + skips;
            if (total == 0) return 0.4;
            double winRate = (plays + 1.5) / (total + 3.0);
            double confidence = Math.min(1.0, Math.log1p(total) / Math.log1p(12.0));
            double raw = (winRate - 0.48) * 11.0 * confidence;
            if (skips >= 2 && plays == 0) raw -= Math.min(3.5, skips * 0.9);
            return Math.max(-6.5, Math.min(5.5, raw));
        }
    }

    private final ArrayDeque<Integer> recent = new ArrayDeque<>();
    private final HashMap<Integer, Integer> sessionSkips = new HashMap<>();
    private int stepCounter;
    private int lastTransitionStyle = -1;

    public void reset() {
        recent.clear();
        sessionSkips.clear();
        stepCounter = 0;
    }

    public void played(int index) {
        if (index < 0) return;
        recent.remove(index);
        recent.addLast(index);
        while (recent.size() > 14) recent.removeFirst();
        stepCounter++;
    }

    public void markSessionSkip(int index) {
        if (index < 0) return;
        sessionSkips.put(index, sessionSkips.getOrDefault(index, 0) + 1);
    }

    public int choose(int current, List<String> artists, Map<Integer, Features> features) {
        return choose(current, artists, features, null, null);
    }

    public int choose(int current, List<String> artists, Map<Integer, Features> features, Map<Integer, TasteStats> tasteMap) {
        return choose(current, artists, features, tasteMap, null, null);
    }

    public int choose(int current, List<String> artists, Map<Integer, Features> features, Map<Integer, TasteStats> tasteMap, Set<Integer> allowedScope) {
        return choose(current, artists, features, tasteMap, allowedScope, null);
    }

    public int choose(int current, List<String> artists, Map<Integer, Features> features,
                      Map<Integer, TasteStats> tasteMap, Set<Integer> allowedScope, Map<Integer, Double> tagBonuses) {
        int count = artists.size();
        if (count < 2) return -1;
        if (current < 0 || current >= count) return 0;
        Features source = features.get(current);
        boolean isUnitTest = source != null && source.syntheticTest;
        boolean hasScope = allowedScope != null && !allowedScope.isEmpty();

        boolean hasUnplayed = false;
        for (int index = 0; index < count; index++) {
            if (hasScope && !allowedScope.contains(index)) continue;
            if (index != current && !recent.contains(index)) { hasUnplayed = true; break; }
        }

        double moodPhase = Math.sin(stepCounter * 1.15);
        double targetEnergyShift = moodPhase * 0.035;

        int[] candidateIndices = new int[count];
        double[] candidateScores = new double[count];
        int validCount = 0;
        int bestDeterministicIndex = -1;
        double bestDeterministicScore = Double.NEGATIVE_INFINITY;

        for (int index = 0; index < count; index++) {
            if (hasScope && !allowedScope.contains(index)) continue;
            if (index == current || (hasUnplayed && recent.contains(index))) continue;
            Features candidate = features.get(index);
            double score = candidate == null ? -10 : 0;

            if (source != null && candidate != null) {
                double bodyDiff = Math.abs((source.energy + targetEnergyShift) - candidate.energy);
                double transitionDiff = Math.abs(source.outroEnergy - candidate.introEnergy);
                score -= Math.min(16, (bodyDiff * 0.50 + transitionDiff * 0.50) * 24);

                if (!isUnitTest) {
                    double bassDiff = Math.abs(source.bassRatio - candidate.bassRatio);
                    double brightDiff = Math.abs(source.brightnessRatio - candidate.brightnessRatio);
                    double punchDiff = Math.abs(source.dynamicPunch - candidate.dynamicPunch);
                    score -= (bassDiff * 6.5 + brightDiff * 5.0 + punchDiff * 4.0);

                    if (source.outroVocalRatio > 0.48 && candidate.introVocalRatio > 0.48) {
                        score -= (source.outroVocalRatio + candidate.introVocalRatio - 0.90) * 14.0;
                    } else if (source.outroVocalRatio < 0.35 || candidate.introVocalRatio < 0.35) {
                        score += 2.5;
                    }
                }

                if (source.bpm > 0 && candidate.bpm > 0) {
                    double ratio = (double) candidate.bpm / source.bpm;
                    double directLog = Math.abs(Math.log(ratio));
                    double halfLog = Math.abs(Math.log(ratio * 0.5)) + 0.035;
                    double doubleLog = Math.abs(Math.log(ratio * 2.0)) + 0.035;
                    double bestLog = Math.min(directLog, Math.min(halfLog, doubleLog));
                    score -= Math.min(18, bestLog * 40);
                    if (!isUnitTest && directLog < 0.035) score += 3.2;
                }

                double conf = Math.min(source.keyConfidence, candidate.keyConfidence);
                if (conf > 0.08) {
                    double weight = 0.5 + 0.5 * Math.min(1.0, conf);
                    score += harmonicScore(source.key, source.minor, candidate.key, candidate.minor) * weight;
                }
            }

            String sourceArtist = artists.get(current);
            String candidateArtist = artists.get(index);
            if (!hasScope && sourceArtist != null && candidateArtist != null
                    && !sourceArtist.equals("Arquivo local")
                    && sourceArtist.equalsIgnoreCase(candidateArtist)) {
                score -= 12;
            }

            if (tasteMap != null) {
                TasteStats stats = tasteMap.get(index);
                if (stats != null) score += stats.affinityBonus();
            }
            if (tagBonuses != null) {
                Double tagScore = tagBonuses.get(index);
                if (tagScore != null) score += tagScore;
            }
            int sessSkipCount = sessionSkips.getOrDefault(index, 0);
            if (sessSkipCount > 0) score -= sessSkipCount * 7.5;

            // Energy-Lift Harmonic Bonus (+1 or +2 semitones when building energy)
            if (targetEnergyShift > 0 && source != null && candidate != null && source.minor == candidate.minor) {
                int semi = Math.floorMod(candidate.key - source.key, 12);
                if (semi == 1 || semi == 2 || semi == 7) score += 2.2;
            }

            if (isUnitTest) {
                score += Math.floorMod(index * 37 + current * 17, 13) * 0.18;
            }

            if (score > bestDeterministicScore) {
                bestDeterministicScore = score;
                bestDeterministicIndex = index;
            }
            candidateIndices[validCount] = index;
            candidateScores[validCount] = score;
            validCount++;
        }

        if (validCount == 0) return -1;
        if (isUnitTest || validCount == 1) return bestDeterministicIndex;

        int topK = Math.min(5, validCount);
        int[] topIdx = new int[topK];
        double[] topScr = new double[topK];
        boolean[] used = new boolean[validCount];

        for (int k = 0; k < topK; k++) {
            int bestPos = -1;
            double bestVal = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < validCount; i++) {
                if (!used[i] && candidateScores[i] > bestVal) {
                    bestVal = candidateScores[i];
                    bestPos = i;
                }
            }
            used[bestPos] = true;
            topIdx[k] = candidateIndices[bestPos];
            topScr[k] = bestVal;
        }

        double maxScore = topScr[0];
        double temperature = 2.4;
        double sumWeights = 0;
        double[] weights = new double[topK];
        for (int k = 0; k < topK; k++) {
            double diff = topScr[k] - maxScore;
            if (diff < -6.5) {
                weights[k] = 0;
            } else {
                weights[k] = Math.exp(diff / temperature);
                sumWeights += weights[k];
            }
        }

        if (sumWeights <= 0) return bestDeterministicIndex;
        double roll = ThreadLocalRandom.current().nextDouble() * sumWeights;
        double cumulative = 0;
        for (int k = 0; k < topK; k++) {
            cumulative += weights[k];
            if (roll <= cumulative && weights[k] > 0) return topIdx[k];
        }
        return topIdx[0];
    }

    private static double harmonicScore(int srcKey, boolean srcMinor, int dstKey, boolean dstMinor) {
        int distance = Math.floorMod(dstKey - srcKey, 12);
        if (srcMinor == dstMinor) {
            if (distance == 0) return 9.5;
            if (distance == 7 || distance == 5) return 6.0;
            if (distance == 2 || distance == 1) return 3.0;
            return -4.0;
        } else {
            if ((!srcMinor && dstMinor && distance == 9) || (srcMinor && !dstMinor && distance == 3)) return 7.5;
            if (distance == 0) return 4.0;
            if ((!srcMinor && dstMinor && (distance == 4 || distance == 2))
                    || (srcMinor && !dstMinor && (distance == 8 || distance == 10))) return 3.0;
            return -4.0;
        }
    }

    public static int recommendedCrossfadeMs(Features source, Features next) {
        if (source == null || next == null || source.bpm <= 0 || next.bpm <= 0) return 2400;
        double ratio = (double) next.bpm / source.bpm;
        double bpmDiff = Math.min(Math.abs(Math.log(ratio)),
            Math.min(Math.abs(Math.log(ratio * 0.5)), Math.abs(Math.log(ratio * 2.0))));

        boolean vocalClash = source.outroVocalRatio > 0.48 && next.introVocalRatio > 0.48;
        if (vocalClash || bpmDiff > 0.085) {
            return 1600;
        }
        double beatMs = 60000.0 / next.bpm;
        int beats = (bpmDiff < 0.025) ? 16 : (bpmDiff < 0.055 ? 8 : 4);
        int alignedMs = (int) Math.round(beatMs * beats);
        return Math.max(2000, Math.min(7600, alignedMs));
    }

    /**
     * Computes EBU R128 / RMS Equal-Loudness gain ratio so a quieter mastered track
     * enters at the same perceived loudness as the outgoing track.
     */
    public static double recommendedGainRatio(Features source, Features next) {
        if (source == null || next == null || source.energy <= 0.005 || next.energy <= 0.005) {
            return 1.0;
        }
        double rawRatio = Math.pow(source.energy / next.energy, 0.65);
        return Math.max(0.65, Math.min(1.50, rawRatio));
    }

    /**
     * Aligns the incoming cue-in point to a 4-beat bar boundary relative to its first downbeat
     * so phrases lock naturally during the transition.
     */
    public static long alignCueInToPhrase(Features next, int crossfadeMs) {
        if (next == null) return 0;
        long baseCue = Math.max(0, next.cueInMs);
        if (next.bpm <= 0 || next.beatOffsetMs <= baseCue) return baseCue;
        double beatMs = 60000.0 / next.bpm;
        double barMs = beatMs * 4.0;
        if (barMs <= 200) return baseCue;
        double delta = next.beatOffsetMs - baseCue;
        long barsBeforeDrop = Math.round(delta / barMs);
        long phraseAligned = Math.round(next.beatOffsetMs - barsBeforeDrop * barMs);
        if (phraseAligned >= 0 && Math.abs(phraseAligned - baseCue) <= beatMs * 2.0) {
            return phraseAligned;
        }
        return baseCue;
    }

    /** Selects the best DJ transition FX based on musical relationship and avoids repeating the same FX endlessly. */
    public int chooseTransitionStyle(Features source, Features next) {
        if (source == null || next == null || source.bpm <= 0 || next.bpm <= 0) {
            return STYLE_FILTER_SWEEP;
        }
        double ratio = (double) next.bpm / source.bpm;
        double bpmDiff = Math.min(Math.abs(Math.log(ratio)),
            Math.min(Math.abs(Math.log(ratio * 0.5)), Math.abs(Math.log(ratio * 2.0))));
        boolean vocalClash = source.outroVocalRatio > 0.48 && next.introVocalRatio > 0.48;

        int chosen;
        if (vocalClash && bpmDiff > 0.07) {
            chosen = STYLE_POWER_CUT;
        } else if (bpmDiff > 0.055 || source.outroVocalRatio > 0.44) {
            chosen = (lastTransitionStyle == STYLE_ECHO_FREEZE) ? STYLE_FILTER_SWEEP : STYLE_ECHO_FREEZE;
        } else {
            int roll = ThreadLocalRandom.current().nextInt(100);
            if (roll < 48) chosen = STYLE_BASS_SWAP;
            else if (roll < 82) chosen = STYLE_FILTER_SWEEP;
            else chosen = STYLE_ECHO_FREEZE;
            if (chosen == lastTransitionStyle) {
                chosen = (chosen == STYLE_BASS_SWAP) ? STYLE_FILTER_SWEEP : STYLE_BASS_SWAP;
            }
        }
        lastTransitionStyle = chosen;
        return chosen;
    }

    public static String styleName(int style) {
        switch (style) {
            case STYLE_FILTER_SWEEP: return "Filter Sweep";
            case STYLE_ECHO_FREEZE: return "Dub Echo Freeze";
            case STYLE_POWER_CUT: return "Power Cut";
            default: return "Bass-Swap Blend";
        }
    }
}
