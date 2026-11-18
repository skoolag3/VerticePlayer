package com.oneshot.surround;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.ReturnCode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Analyzes harmonic, rhythmic, timbral, cue-point features and persists user affinity and custom end markers. */
public final class AutoMixAnalyzer {
    private static final String CACHE_FILE = "automix_cache_v4.tsv";
    private static final String AFFINITY_FILE = "automix_affinity_v1.tsv";
    private static final String MARKERS_FILE = "track_markers_v1.tsv";
    private static final int SAMPLE_RATE = 8000;
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final HashMap<String, AutoMixSelector.Features> cache = new HashMap<>();
    private final HashMap<Integer, AutoMixSelector.Features> indexed = new HashMap<>();
    private final HashMap<String, AutoMixSelector.TasteStats> affinityByUri = new HashMap<>();
    private final HashMap<String, Long> endMarkersByUri = new HashMap<>();
    private ArrayList<Uri> activeQueue = new ArrayList<>();
    private int generation;

    public AutoMixAnalyzer(Context context) {
        this.context = context.getApplicationContext();
        loadCache();
        loadAffinity();
        loadMarkers();
    }

    public synchronized Map<Integer, AutoMixSelector.Features> snapshot() {
        return new HashMap<>(indexed);
    }

    public synchronized Map<Integer, AutoMixSelector.TasteStats> tasteSnapshot() {
        HashMap<Integer, AutoMixSelector.TasteStats> map = new HashMap<>();
        for (int i = 0; i < activeQueue.size(); i++) {
            AutoMixSelector.TasteStats s = affinityByUri.get(activeQueue.get(i).toString());
            if (s != null) map.put(i, s);
        }
        return map;
    }

    public synchronized AutoMixSelector.Features getFeatures(int index) {
        return indexed.get(index);
    }

    /** Records a positive listen (track played past 60% or transitioned naturally) or an early skip. */
    public synchronized void recordFeedback(Uri uri, boolean skippedEarly) {
        if (uri == null) return;
        String key = uri.toString();
        AutoMixSelector.TasteStats stats = affinityByUri.get(key);
        if (stats == null) {
            stats = new AutoMixSelector.TasteStats(0, 0, System.currentTimeMillis());
            affinityByUri.put(key, stats);
        }
        if (skippedEarly) stats.skips++;
        else stats.plays++;
        stats.lastActionMs = System.currentTimeMillis();
        saveAffinityAsync();
    }

    /** Returns the stored custom end position in ms for uri, or 0 if unset. */
    public synchronized long getEndMarkerMs(Uri uri) {
        if (uri == null) return 0;
        Long value = endMarkersByUri.get(uri.toString());
        return value != null && value > 0 ? value : 0;
    }

    /** Saves or clears (when endMs <= 0) a custom end marker in track_markers_v1.tsv. */
    public synchronized void setEndMarkerMs(Uri uri, long endMs) {
        if (uri == null) return;
        String key = uri.toString();
        if (endMs <= 0) endMarkersByUri.remove(key);
        else endMarkersByUri.put(key, endMs);
        saveMarkersAsync();
    }

    /** Toggles a custom end marker: removes if already set, otherwise sets positionMs (returns -1 if < 2s). */
    public synchronized long toggleEndMarkerMs(Uri uri, long positionMs) {
        if (uri == null) return -1;
        String key = uri.toString();
        if (endMarkersByUri.containsKey(key)) {
            endMarkersByUri.remove(key);
            saveMarkersAsync();
            return 0;
        }
        if (positionMs < 2000) return -1;
        endMarkersByUri.put(key, positionMs);
        saveMarkersAsync();
        return positionMs;
    }

    /** Returns the recorded positive play count for uri. */
    public synchronized int getPlayCount(Uri uri) {
        if (uri == null) return 0;
        AutoMixSelector.TasteStats stats = affinityByUri.get(uri.toString());
        return stats != null ? stats.plays : 0;
    }

    /** Builds a Most Played playlist ordered by completed/high-retention plays descending. */
    public synchronized ArrayList<TrackInfo> getMostPlayedTracks(List<TrackInfo> allTracks) {
        ArrayList<TrackInfo> ranked = new ArrayList<>();
        if (allTracks == null || allTracks.isEmpty()) return ranked;
        for (TrackInfo track : allTracks) {
            if (track == null || track.uri == null) continue;
            AutoMixSelector.TasteStats stats = affinityByUri.get(track.uri.toString());
            if (stats != null && stats.plays > 0) ranked.add(track);
        }
        ranked.sort(new Comparator<TrackInfo>() {
            @Override public int compare(TrackInfo a, TrackInfo b) {
                AutoMixSelector.TasteStats sa = affinityByUri.get(a.uri.toString());
                AutoMixSelector.TasteStats sb = affinityByUri.get(b.uri.toString());
                int cmp = Integer.compare(sb.plays, sa.plays);
                if (cmp != 0) return cmp;
                cmp = Integer.compare(sb.plays - sb.skips, sa.plays - sa.skips);
                if (cmp != 0) return cmp;
                cmp = Long.compare(sb.lastActionMs, sa.lastActionMs);
                if (cmp != 0) return cmp;
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        return ranked;
    }

    public synchronized void setQueue(List<Uri> queue, int current, boolean enabled) {
        generation++;
        indexed.clear();
        activeQueue = queue == null ? new ArrayList<>() : new ArrayList<>(queue);
        if (!enabled || activeQueue.isEmpty()) return;
        int token = generation;
        ArrayList<Uri> copy = new ArrayList<>(activeQueue);
        for (int index = 0; index < copy.size(); index++) {
            AutoMixSelector.Features known = cache.get(copy.get(index).toString());
            if (known != null) indexed.put(index, known);
        }
        worker.execute(() -> {
            for (int offset = 0; offset < copy.size(); offset++) {
                int index = (Math.max(0, current) + offset) % copy.size();
                synchronized (this) { if (token != generation) return; }
                Uri uri = copy.get(index);
                if (snapshot().containsKey(index)) continue;
                AutoMixSelector.Features result = analyze(uri);
                synchronized (this) {
                    if (token != generation) return;
                    if (result != null) {
                        cache.put(uri.toString(), result);
                        indexed.put(index, result);
                        appendCache(uri.toString(), result);
                    }
                }
            }
        });
    }

    private synchronized void loadCache() {
        File file = new File(context.getFilesDir(), CACHE_FILE);
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length != 16) continue;
                cache.put(parts[0], new AutoMixSelector.Features(
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]),
                    Integer.parseInt(parts[4]),
                    Integer.parseInt(parts[5]),
                    Boolean.parseBoolean(parts[6]),
                    Double.parseDouble(parts[7]),
                    Double.parseDouble(parts[8]),
                    Double.parseDouble(parts[9]),
                    Double.parseDouble(parts[10]),
                    Integer.parseInt(parts[11]),
                    Integer.parseInt(parts[12]),
                    Integer.parseInt(parts[13]),
                    Double.parseDouble(parts[14]),
                    Double.parseDouble(parts[15])
                ));
            }
        } catch (Throwable ignored) {}
    }

    private synchronized void appendCache(String key, AutoMixSelector.Features f) {
        if (key.indexOf('\t') >= 0 || key.indexOf('\n') >= 0) return;
        File file = new File(context.getFilesDir(), CACHE_FILE);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, true))) {
            writer.write(String.format(Locale.US,
                "%s\t%.6f\t%.6f\t%.6f\t%d\t%d\t%b\t%.6f\t%.4f\t%.4f\t%.4f\t%d\t%d\t%d\t%.4f\t%.4f%n",
                key, f.energy, f.introEnergy, f.outroEnergy, f.bpm, f.key, f.minor, f.keyConfidence,
                f.bassRatio, f.introVocalRatio, f.outroVocalRatio, f.cueInMs, f.cueOutMs, f.beatOffsetMs,
                f.brightnessRatio, f.dynamicPunch));
        } catch (Throwable ignored) {}
    }

    private synchronized void loadAffinity() {
        File file = new File(context.getFilesDir(), AFFINITY_FILE);
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length != 4) continue;
                affinityByUri.put(parts[0], new AutoMixSelector.TasteStats(
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Long.parseLong(parts[3])
                ));
            }
        } catch (Throwable ignored) {}
    }

    private void saveAffinityAsync() {
        final HashMap<String, AutoMixSelector.TasteStats> copy;
        synchronized (this) {
            copy = new HashMap<>(affinityByUri);
        }
        worker.execute(() -> {
            File file = new File(context.getFilesDir(), AFFINITY_FILE);
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, false))) {
                for (Map.Entry<String, AutoMixSelector.TasteStats> e : copy.entrySet()) {
                    String k = e.getKey();
                    if (k.indexOf('\t') >= 0 || k.indexOf('\n') >= 0) continue;
                    AutoMixSelector.TasteStats s = e.getValue();
                    writer.write(String.format(Locale.US, "%s\t%d\t%d\t%d%n", k, s.plays, s.skips, s.lastActionMs));
                }
            } catch (Throwable ignored) {}
        });
    }

    private synchronized void loadMarkers() {
        File file = new File(context.getFilesDir(), MARKERS_FILE);
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length != 2) continue;
                long endMs = Long.parseLong(parts[1]);
                if (endMs > 0) endMarkersByUri.put(parts[0], endMs);
            }
        } catch (Throwable ignored) {}
    }

    private void saveMarkersAsync() {
        final HashMap<String, Long> copy;
        synchronized (this) {
            copy = new HashMap<>(endMarkersByUri);
        }
        worker.execute(() -> {
            File file = new File(context.getFilesDir(), MARKERS_FILE);
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, false))) {
                for (Map.Entry<String, Long> e : copy.entrySet()) {
                    String k = e.getKey();
                    if (k.indexOf('\t') >= 0 || k.indexOf('\n') >= 0) continue;
                    writer.write(String.format(Locale.US, "%s\t%d%n", k, e.getValue()));
                }
            } catch (Throwable ignored) {}
        });
    }

    private double probeDurationSec(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (raw != null) return Long.parseLong(raw) / 1000.0;
        } catch (Throwable ignored) {}
        finally { try { retriever.release(); } catch (Throwable ignored) {} }
        return 0;
    }

    private double[] decodePcmSlice(Uri uri, double startSec, int durationSec) {
        File sample = null;
        try {
            String input = FFmpegKitConfig.getSafParameterForRead(context, uri);
            if (input == null || input.isEmpty()) return null;
            sample = File.createTempFile("automix-", ".pcm", context.getCacheDir());
            ArrayList<String> cmd = new ArrayList<>();
            cmd.add("-hide_banner"); cmd.add("-nostdin"); cmd.add("-loglevel"); cmd.add("error");
            if (startSec > 0.25) {
                cmd.add("-ss"); cmd.add(String.format(Locale.US, "%.2f", startSec));
            }
            cmd.add("-i"); cmd.add(input);
            cmd.add("-map"); cmd.add("0:a:0"); cmd.add("-vn");
            cmd.add("-t"); cmd.add(String.valueOf(durationSec));
            cmd.add("-ac"); cmd.add("1"); cmd.add("-ar"); cmd.add(String.valueOf(SAMPLE_RATE));
            cmd.add("-f"); cmd.add("s16le"); cmd.add("-acodec"); cmd.add("pcm_s16le");
            cmd.add("-y"); cmd.add(sample.getAbsolutePath());
            if (!ReturnCode.isSuccess(FFmpegKit.executeWithArguments(cmd.toArray(new String[0])).getReturnCode())) return null;
            if (sample.length() < 8000 || sample.length() > 400000) return null;
            byte[] bytes = new byte[(int) sample.length()];
            try (FileInputStream stream = new FileInputStream(sample)) {
                int read = 0;
                while (read < bytes.length) {
                    int count = stream.read(bytes, read, bytes.length - read);
                    if (count < 0) break;
                    read += count;
                }
            }
            double[] pcm = new double[bytes.length / 2];
            for (int index = 0; index < pcm.length; index++) {
                short value = (short) ((bytes[index * 2] & 255) | (bytes[index * 2 + 1] << 8));
                pcm[index] = value / 32768.0;
            }
            return pcm;
        } catch (Throwable ignored) { return null; }
        finally { if (sample != null) sample.delete(); }
    }

    private static double rms(double[] pcm, int start, int end) {
        int s = Math.max(0, start);
        int e = Math.min(pcm.length, end);
        if (e <= s) return 0;
        double sum = 0;
        for (int i = s; i < e; i++) sum += pcm[i] * pcm[i];
        return Math.sqrt(sum / (e - s));
    }

    private static double rms(double[] pcm) {
        return rms(pcm, 0, pcm.length);
    }

    /** Extracts [bassRatio (<160Hz), vocalRatio (320-3000Hz), brightnessRatio (>1800Hz), dynamicPunch]. */
    private static double[] spectralAndPunchMetrics(double[] pcm) {
        if (pcm == null || pcm.length == 0) return new double[]{0.25, 0.35, 0.20, 0.50};
        double alphaLow160 = (2 * Math.PI * 160.0) / (2 * Math.PI * 160.0 + SAMPLE_RATE);
        double alphaLow320 = (2 * Math.PI * 320.0) / (2 * Math.PI * 320.0 + SAMPLE_RATE);
        double alphaLow1800 = (2 * Math.PI * 1800.0) / (2 * Math.PI * 1800.0 + SAMPLE_RATE);
        double alphaLow3000 = (2 * Math.PI * 3000.0) / (2 * Math.PI * 3000.0 + SAMPLE_RATE);

        double lp160 = 0, lp320 = 0, lp1800 = 0, lp3000 = 0;
        double totalSq = 1e-9, bassSq = 0, vocalSq = 0, highSq = 0;
        double peakEnv = 0;
        for (double x : pcm) {
            lp160 += alphaLow160 * (x - lp160);
            lp320 += alphaLow320 * (x - lp320);
            lp1800 += alphaLow1800 * (x - lp1800);
            lp3000 += alphaLow3000 * (x - lp3000);
            double midBand = lp3000 - lp320;
            double highBand = x - lp1800;
            totalSq += x * x;
            bassSq += lp160 * lp160;
            vocalSq += midBand * midBand;
            highSq += highBand * highBand;
            double abs = Math.abs(x);
            peakEnv = Math.max(abs, peakEnv * 0.9992);
        }
        double globalRms = Math.sqrt(totalSq / pcm.length);
        double crest = globalRms > 1e-5 ? Math.min(1.0, (peakEnv / globalRms) / 6.0) : 0.5;
        return new double[]{
            Math.min(1.0, Math.sqrt(bassSq / totalSq)),
            Math.min(1.0, Math.sqrt(vocalSq / totalSq)),
            Math.min(1.0, Math.sqrt(highSq / totalSq)),
            crest
        };
    }

    private static int detectCueInMs(double[] introPcm, double bodyRms) {
        if (introPcm == null || introPcm.length == 0) return 0;
        int win = SAMPLE_RATE / 10;
        double threshold = Math.max(0.008, bodyRms * 0.11);
        for (int i = 0; i + win <= introPcm.length; i += win) {
            if (rms(introPcm, i, i + win) >= threshold) {
                return Math.max(0, (i * 1000) / SAMPLE_RATE - 80);
            }
        }
        return 0;
    }

    private static int detectCueOutMs(double[] outroPcm, double outroStartSec, double durationSec, double bodyRms) {
        int totalMs = (int) Math.round(durationSec * 1000.0);
        if (outroPcm == null || outroPcm.length == 0 || durationSec <= 20) return Math.max(0, totalMs - 1500);
        int win = SAMPLE_RATE / 5;
        double threshold = Math.max(0.012, bodyRms * 0.22);
        int lastActiveSample = outroPcm.length;
        for (int i = outroPcm.length - win; i >= 0; i -= win) {
            if (rms(outroPcm, i, i + win) >= threshold) {
                lastActiveSample = i + win;
                break;
            }
        }
        int cueOutMs = (int) Math.round(outroStartSec * 1000.0) + (lastActiveSample * 1000) / SAMPLE_RATE;
        return Math.max(totalMs - 14000, Math.min(totalMs - 400, cueOutMs));
    }

    private AutoMixSelector.Features analyze(Uri uri) {
        try {
            double durationSec = probeDurationSec(uri);
            double[] body;
            double[] intro;
            double[] outro;
            double outroStartSec = 0;

            if (durationSec >= 45.0) {
                body = decodePcmSlice(uri, durationSec * 0.35, 18);
                if (body == null) return null;
                intro = decodePcmSlice(uri, 0, 12);
                outroStartSec = Math.max(0, durationSec - 16.0);
                outro = decodePcmSlice(uri, outroStartSec, 16);
            } else {
                body = decodePcmSlice(uri, 0, 18);
                if (body == null) return null;
                intro = body;
                outro = body;
            }

            double energy = rms(body);
            double introEnergy = intro != null ? rms(intro, Math.min(intro.length / 4, SAMPLE_RATE * 2), intro.length) : energy;
            double outroEnergy = outro != null ? rms(outro, 0, Math.max(1, outro.length - SAMPLE_RATE * 2)) : energy;

            double[] bodySpec = spectralAndPunchMetrics(body);
            double[] introSpec = intro != null ? spectralAndPunchMetrics(intro) : bodySpec;
            double[] outroSpec = outro != null ? spectralAndPunchMetrics(outro) : bodySpec;

            int cueInMs = detectCueInMs(intro, energy);
            int cueOutMs = detectCueOutMs(outro, outroStartSec, durationSec, energy);

            int[] rhythm = estimateBpmAndPhase(body, intro != null ? intro : body);
            int bpm = rhythm[0];
            int beatOffsetMs = rhythm[1];

            double[] chroma = chroma(body);
            double[] major = {6.6, 2.0, 3.5, 2.3, 4.6, 4.0, 2.5, 5.2, 2.4, 3.7, 2.3, 3.4};
            double[] minor = {6.5, 2.7, 3.5, 5.4, 2.6, 3.5, 2.5, 5.1, 4.0, 2.7, 4.3, 3.2};
            int key = 0;
            boolean isMinor = false;
            double best = Double.NEGATIVE_INFINITY;
            double second = Double.NEGATIVE_INFINITY;
            for (int root = 0; root < 12; root++) {
                for (int scale = 0; scale < 2; scale++) {
                    double score = 0;
                    for (int note = 0; note < 12; note++) {
                        score += chroma[(root + note) % 12] * (scale == 0 ? major[note] : minor[note]);
                    }
                    if (score > best) {
                        second = best;
                        best = score;
                        key = root;
                        isMinor = scale == 1;
                    } else if (score > second) {
                        second = score;
                    }
                }
            }
            double confidence = best <= 0 ? 0 : Math.max(0, Math.min(1, (best - second) / best * 18));

            return new AutoMixSelector.Features(
                energy, introEnergy, outroEnergy,
                bpm, key, isMinor, confidence,
                bodySpec[0], introSpec[1], outroSpec[1],
                cueInMs, cueOutMs, beatOffsetMs,
                bodySpec[2], bodySpec[3]
            );
        } catch (Throwable ignored) { return null; }
    }

    private static int[] estimateBpmAndPhase(double[] bodyPcm, double[] introPcm) {
        int hop = 160;
        int frames = bodyPcm.length / hop;
        if (frames < 120) return new int[]{0, 0};

        double[] onset = new double[frames];
        double prevLow = 0, prevHigh = 0, lp = 0;
        double alphaKick = (2 * Math.PI * 150.0) / (2 * Math.PI * 150.0 + SAMPLE_RATE);

        for (int frame = 0; frame < frames; frame++) {
            double lowE = 0, highE = 0;
            for (int i = frame * hop; i < (frame + 1) * hop; i++) {
                double x = bodyPcm[i];
                lp += alphaKick * (x - lp);
                double hp = x - lp;
                lowE += Math.abs(lp);
                highE += Math.abs(hp);
            }
            lowE /= hop;
            highE /= hop;
            onset[frame] = Math.max(0, lowE - prevLow) * 2.2 + Math.max(0, highE - prevHigh);
            prevLow = lowE;
            prevHigh = highE;
        }

        double[] scores = new double[185];
        for (int candidate = 68; candidate <= 184; candidate++) {
            double exactLag = (60.0 * SAMPLE_RATE) / (candidate * hop);
            int lag1 = (int) Math.round(exactLag);
            int lag2 = (int) Math.round(exactLag * 2);
            if (lag1 <= 0 || lag2 >= frames) continue;
            double score = 0;
            for (int f = lag2; f < frames; f++) {
                score += onset[f] * onset[f - lag1] + 0.5 * onset[f] * onset[f - lag2];
            }
            double prior = Math.exp(-0.5 * Math.pow((candidate - 122.0) / 38.0, 2));
            scores[candidate] = (score / (frames - lag2)) * (0.75 + 0.25 * prior);
        }

        double best = 0;
        int bpm = 120;
        for (int candidate = 72; candidate <= 180; candidate++) {
            double combined = scores[candidate];
            if (candidate * 2 <= 184) combined += 0.30 * scores[candidate * 2];
            if (candidate / 2 >= 68) combined += 0.30 * scores[candidate / 2];
            if (combined > best) { best = combined; bpm = candidate; }
        }

        int beatPeriodFrames = Math.max(1, (int) Math.round((60.0 * SAMPLE_RATE) / (bpm * hop)));
        int introFrames = Math.min(frames, introPcm.length / hop);
        int bestPhase = 0;
        double bestPhaseScore = -1;
        for (int phase = 0; phase < beatPeriodFrames; phase++) {
            double sum = 0;
            for (int f = phase; f < introFrames; f += beatPeriodFrames) {
                sum += onset[f];
            }
            if (sum > bestPhaseScore) {
                bestPhaseScore = sum;
                bestPhase = phase;
            }
        }
        int beatOffsetMs = (bestPhase * hop * 1000) / SAMPLE_RATE;
        return new int[]{bpm, beatOffsetMs};
    }

    private static double[] chroma(double[] pcm) {
        double[] notes = new double[12];
        int window = 4096;
        int stride = Math.max(window / 2, pcm.length / 24);
        for (int start = 0; start + window <= pcm.length; start += stride) {
            for (int midi = 48; midi <= 83; midi++) {
                double frequency = 440.0 * Math.pow(2, (midi - 69) / 12.0);
                double factor = 2 * Math.cos(2 * Math.PI * frequency / SAMPLE_RATE);
                double previous = 0, before = 0;
                for (int i = 0; i < window; i++) {
                    double x = pcm[start + i] - (start + i > 0 ? 0.94 * pcm[start + i - 1] : 0);
                    double hann = 0.5 * (1.0 - Math.cos(2 * Math.PI * i / (window - 1)));
                    double next = (x * hann) + factor * previous - before;
                    before = previous;
                    previous = next;
                }
                double power = previous * previous + before * before - factor * previous * before;
                notes[midi % 12] += Math.sqrt(Math.max(0, power));
            }
        }
        double total = 0;
        for (double value : notes) total += value;
        if (total > 0) for (int index = 0; index < 12; index++) notes[index] /= total;
        return notes;
    }
}
