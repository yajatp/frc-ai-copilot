package org.mercsmavs.frccopilot.smallmodel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

/**
 * Turns a {@code .wpilog} plus a handful of human-marked timestamps into a labeled dataset.
 *
 * <p>This is the piece that makes the "big AI trains a small AI" technique reachable at all. The
 * workflow it serves is a person marking ~30 moments in a log — "here is a good time to press the
 * button" — and an agent learning a predictor from them. Without this, training takes a raw numeric
 * matrix, which means whoever calls it has to transcribe feature vectors out of a log by hand.
 *
 * <p>Two decisions shape what comes out:
 *
 * <ul>
 *   <li><b>A feature vector is the signal values in force at a timestamp</b> — the most recent sample
 *       at or before it, per signal. Every signal in a wpilog is its own sparse stream written at its
 *       own cadence, so "the value at time t" is a held sample, not an interpolation; interpolating
 *       would invent readings the robot never saw.
 *   <li><b>Negative examples are drawn from the rest of the log</b> when they are not supplied. A
 *       person marks the good moments and does not enumerate the thousands of bad ones. Sampling the
 *       remainder at a stride gives the trainer something to contrast against, and the result is
 *       heavily imbalanced by nature — which is why the model's decision threshold is tunable and
 *       reported rather than fixed at 0.5.
 * </ul>
 */
public final class LogFeatures {

    /** Timestamps nearer than this to a positive mark are excluded from auto-drawn negatives. */
    private static final long DEFAULT_EXCLUSION_US = 100_000; // 100 ms

    /**
     * Build a labeled dataset.
     *
     * @param signals the signal names used as features, in order
     * @param positiveTsUs timestamps (microseconds) a human marked as the positive class
     * @param negativeTsUs explicit negative timestamps, or empty to draw them from the log
     * @param negativeStride when drawing negatives, take every Nth candidate timestamp
     */
    public static Dataset build(
            WpilogReader reader,
            List<String> signals,
            Collection<Long> positiveTsUs,
            Collection<Long> negativeTsUs,
            int negativeStride) {
        if (signals.isEmpty()) {
            throw new IllegalArgumentException("no feature signals given");
        }
        if (positiveTsUs.isEmpty()) {
            throw new IllegalArgumentException(
                    "no positive examples given — mark at least a few moments to learn from");
        }

        // One sorted timeline per feature signal, so a value can be looked up as "latest at or before".
        List<TreeMap<Long, Double>> timelines = new ArrayList<>(signals.size());
        for (String signal : signals) {
            TreeMap<Long, Double> timeline = new TreeMap<>();
            for (WpilogReader.Sample sample : reader.read(signal)) {
                if (sample.value() instanceof Number n) {
                    timeline.put(sample.timestampUs(), n.doubleValue());
                } else if (sample.value() instanceof Boolean b) {
                    timeline.put(sample.timestampUs(), b ? 1.0 : 0.0);
                }
            }
            if (timeline.isEmpty()) {
                throw new IllegalArgumentException(
                        "signal '" + signal + "' has no numeric samples in this log — check the name"
                                + " (log_entries lists them) and that it is not a string or struct");
            }
            timelines.add(timeline);
        }

        Set<Long> positives = new LinkedHashSet<>(positiveTsUs);
        Set<Long> negatives = new LinkedHashSet<>(negativeTsUs);
        if (negatives.isEmpty()) {
            negatives = drawNegatives(timelines.get(0).navigableKeySet(), positives, negativeStride);
        }
        negatives.removeAll(positives); // an explicit positive always wins

        List<double[]> rows = new ArrayList<>(positives.size() + negatives.size());
        List<Integer> labels = new ArrayList<>(rows.size());
        for (long ts : positives) {
            rows.add(featuresAt(timelines, ts));
            labels.add(1);
        }
        for (long ts : negatives) {
            rows.add(featuresAt(timelines, ts));
            labels.add(0);
        }

        int[] y = new int[labels.size()];
        for (int i = 0; i < y.length; i++) {
            y[i] = labels.get(i);
        }
        return Dataset.standardized(signals, rows.toArray(new double[0][]), y);
    }

    /** The signal values in force at {@code tsUs}: the latest sample at or before it, per signal. */
    public static double[] featuresAt(List<TreeMap<Long, Double>> timelines, long tsUs) {
        double[] row = new double[timelines.size()];
        for (int j = 0; j < timelines.size(); j++) {
            TreeMap<Long, Double> timeline = timelines.get(j);
            var entry = timeline.floorEntry(tsUs);
            // Before the first sample there is nothing in force yet, so fall back to the earliest
            // known value rather than a zero that would read as a real measurement.
            row[j] = entry != null ? entry.getValue() : timeline.firstEntry().getValue();
        }
        return row;
    }

    /** Build the per-signal timelines alone, for scoring a log against an already-trained model. */
    public static List<TreeMap<Long, Double>> timelines(WpilogReader reader, List<String> signals) {
        List<TreeMap<Long, Double>> out = new ArrayList<>(signals.size());
        for (String signal : signals) {
            TreeMap<Long, Double> timeline = new TreeMap<>();
            for (WpilogReader.Sample sample : reader.read(signal)) {
                if (sample.value() instanceof Number n) {
                    timeline.put(sample.timestampUs(), n.doubleValue());
                } else if (sample.value() instanceof Boolean b) {
                    timeline.put(sample.timestampUs(), b ? 1.0 : 0.0);
                }
            }
            if (timeline.isEmpty()) {
                throw new IllegalArgumentException("signal '" + signal + "' has no numeric samples");
            }
            out.add(timeline);
        }
        return out;
    }

    private static Set<Long> drawNegatives(
            Collection<Long> candidates, Set<Long> positives, int stride) {
        int step = Math.max(1, stride);
        Set<Long> negatives = new LinkedHashSet<>();
        int i = 0;
        for (long ts : candidates) {
            if (i++ % step != 0) {
                continue;
            }
            boolean nearPositive = positives.stream()
                    .anyMatch(p -> Math.abs(p - ts) <= DEFAULT_EXCLUSION_US);
            if (!nearPositive) {
                negatives.add(ts);
            }
        }
        return negatives;
    }

    private LogFeatures() {}
}
