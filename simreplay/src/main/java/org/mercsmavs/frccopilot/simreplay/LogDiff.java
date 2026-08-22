package org.mercsmavs.frccopilot.simreplay;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

/**
 * Ranks how far a run diverged from a known-good baseline, signal by signal.
 *
 * <p>A failing assertion says <em>that</em> the robot is wrong; it rarely says <em>where</em>. The
 * signal that moved most relative to a baseline usually sits much closer to the cause — a swerve
 * module that stopped reporting velocity, a current draw that collapsed, a pose that never left the
 * start. This is the evidence an agent needs to choose which file to open.
 *
 * <p>Divergence is scored on each signal's mean and last value, normalized by the baseline's own
 * observed range so signals in different units stay comparable. Signals present in only one of the
 * two logs are reported separately — an appeared/disappeared signal is a strong hint on its own
 * (a renamed log key, or a subsystem that never initialized).
 */
public final class LogDiff {

    /** One signal's divergence between baseline and candidate. */
    public record SignalDelta(
            String signal,
            double baselineMean,
            double runMean,
            double baselineLast,
            double runLast,
            double score) {

        public String render() {
            return String.format(
                    "  %-44s mean %10.4f -> %-10.4f  last %10.4f -> %-10.4f  (divergence %.2f)",
                    signal, baselineMean, runMean, baselineLast, runLast, score);
        }
    }

    public record Result(
            List<SignalDelta> diverged, List<String> onlyInBaseline, List<String> onlyInRun) {

        public String render(int limit) {
            StringBuilder sb = new StringBuilder();
            if (!onlyInBaseline.isEmpty()) {
                sb.append("  signals missing from this run (present in baseline): ")
                        .append(String.join(", ", onlyInBaseline))
                        .append('\n');
            }
            if (!onlyInRun.isEmpty()) {
                sb.append("  signals new in this run (absent from baseline): ")
                        .append(String.join(", ", onlyInRun))
                        .append('\n');
            }
            if (diverged.isEmpty()) {
                sb.append("  no numeric signal diverged from the baseline\n");
                return sb.toString();
            }
            sb.append("  most-diverged signals vs baseline:\n");
            for (SignalDelta d : diverged.subList(0, Math.min(limit, diverged.size()))) {
                sb.append(d.render()).append('\n');
            }
            return sb.toString();
        }
    }

    /** Divergences below this normalized score are treated as run-to-run noise, not signal. */
    private static final double NOISE_FLOOR = 0.01;

    public static Result compare(WpilogReader baseline, WpilogReader run) {
        return compare(baseline.numericSummaries(), run.numericSummaries());
    }

    static Result compare(
            Map<String, WpilogReader.NumericSummary> baseline,
            Map<String, WpilogReader.NumericSummary> run) {
        List<SignalDelta> deltas = new ArrayList<>();
        List<String> onlyInBaseline = new ArrayList<>();
        Set<String> shared = new LinkedHashSet<>();

        for (Map.Entry<String, WpilogReader.NumericSummary> e : baseline.entrySet()) {
            if (run.containsKey(e.getKey())) {
                shared.add(e.getKey());
            } else {
                onlyInBaseline.add(e.getKey());
            }
        }
        List<String> onlyInRun = new ArrayList<>();
        for (String name : run.keySet()) {
            if (!baseline.containsKey(name)) {
                onlyInRun.add(name);
            }
        }

        for (String name : shared) {
            if (Bookkeeping.isBookkeeping(name)) {
                // A wall clock differs between any two runs, by a lot, every time — it would
                // otherwise take the top of this ranking and push the real divergence off the list.
                continue;
            }
            WpilogReader.NumericSummary b = baseline.get(name);
            WpilogReader.NumericSummary r = run.get(name);
            double scale = scaleOf(b);
            double score;
            if (isAccumulator(b) && isAccumulator(r)) {
                // An accumulating signal — a wheel's total travel, a cycle counter — is a running
                // integral, so its absolute value at any instant is mostly a statement about how
                // much of the window elapsed. Two honest runs of identical code differ hugely, and
                // that lands them at the top of a ranking meant to surface what the edit changed.
                // Compare how much they accumulated instead, which is the part the edit can move.
                double baseTravel = Math.abs(b.last() - b.first());
                double runTravel = Math.abs(r.last() - r.first());
                double travelScale = Math.max(Math.max(baseTravel, runTravel), 1e-9);
                score = Math.abs(baseTravel - runTravel) / travelScale;
            } else {
                double meanDelta = Math.abs(b.mean() - r.mean()) / scale;
                double lastDelta = Math.abs(b.last() - r.last()) / scale;
                score = Math.max(meanDelta, lastDelta);
            }
            if (Double.isFinite(score) && score > NOISE_FLOOR) {
                deltas.add(new SignalDelta(name, b.mean(), r.mean(), b.last(), r.last(), score));
            }
        }
        deltas.sort((a, b) -> Double.compare(b.score(), a.score()));
        return new Result(deltas, onlyInBaseline, onlyInRun);
    }

    /**
     * True for a signal that only ever moves one way — odometry distance, an error counter, a score
     * tally. Its instantaneous value carries elapsed time as much as it carries behaviour.
     */
    private static boolean isAccumulator(WpilogReader.NumericSummary s) {
        return s.monotonicIncreasing() || s.monotonicDecreasing();
    }

    /**
     * Normalizer for a signal: its own observed range, falling back to its magnitude so a baseline
     * that held constant (range 0) still yields a finite, comparable score when the run moves.
     */
    private static double scaleOf(WpilogReader.NumericSummary b) {
        double range = b.max() - b.min();
        if (range > 1e-9) {
            return range;
        }
        double magnitude = Math.abs(b.mean());
        return magnitude > 1e-9 ? magnitude : 1.0;
    }

    private LogDiff() {}
}
