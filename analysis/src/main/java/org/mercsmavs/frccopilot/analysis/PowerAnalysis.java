package org.mercsmavs.frccopilot.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * Battery / brownout analysis — the centerpiece of the 2026 "energy management" meta (per Team 254,
 * the hidden meta of REBUILT is not popping breakers or browning out).
 *
 * <p>All output is hedged and quality-scored; a single match is never treated as proof.
 */
public final class PowerAnalysis {

    /** roboRIO brownout behavior begins around 6.8 V; below this, outputs are cut. */
    public static final double DEFAULT_BROWNOUT_VOLTS = 6.8;
    /** Ignore dips shorter than this to avoid flagging transient sensor noise. */
    public static final double DEFAULT_MIN_DURATION_MS = 20.0;

    public record BrownoutEvent(double startSeconds, double endSeconds, double minVolts) {
        public double durationMs() {
            return (endSeconds - startSeconds) * 1000.0;
        }
    }

    public record Result(
            double minVolts,
            double meanVolts,
            List<BrownoutEvent> events,
            String assessment,
            DataQuality quality) {
        public boolean brownoutRisk() {
            return !events.isEmpty();
        }
    }

    public static Result analyze(Series voltage) {
        return analyze(voltage, DEFAULT_BROWNOUT_VOLTS, DEFAULT_MIN_DURATION_MS);
    }

    public static Result analyze(Series voltage, double thresholdVolts, double minDurationMs) {
        DataQuality quality = DataQuality.of(voltage.timestampsUs());
        if (voltage.isEmpty()) {
            return new Result(Double.NaN, Double.NaN, List.of(),
                    "No voltage samples available; cannot assess brownout risk.", quality);
        }

        double[] v = voltage.values();
        long[] t = voltage.timestampsUs();
        double min = Double.POSITIVE_INFINITY;
        double sum = 0;
        for (double x : v) {
            min = Math.min(min, x);
            sum += x;
        }
        double mean = sum / v.length;

        // Find contiguous runs below threshold that last at least minDurationMs.
        List<BrownoutEvent> events = new ArrayList<>();
        int runStart = -1;
        double runMin = Double.POSITIVE_INFINITY;
        for (int i = 0; i < v.length; i++) {
            boolean below = v[i] < thresholdVolts;
            if (below) {
                if (runStart < 0) {
                    runStart = i;
                    runMin = v[i];
                } else {
                    runMin = Math.min(runMin, v[i]);
                }
            }
            boolean lastSample = i == v.length - 1;
            if ((!below || lastSample) && runStart >= 0) {
                int runEnd = below ? i : i - 1;
                double startS = t[runStart] / 1_000_000.0;
                double endS = t[runEnd] / 1_000_000.0;
                if ((endS - startS) * 1000.0 >= minDurationMs || runEnd == runStart) {
                    // Single-sample dips are kept only if the threshold is clearly breached.
                    if ((endS - startS) * 1000.0 >= minDurationMs) {
                        events.add(new BrownoutEvent(startS, endS, runMin));
                    }
                }
                runStart = -1;
                runMin = Double.POSITIVE_INFINITY;
            }
        }

        String assessment = buildAssessment(min, thresholdVolts, events, quality);
        return new Result(min, mean, events, assessment, quality);
    }

    private static String buildAssessment(
            double min, double threshold, List<BrownoutEvent> events, DataQuality quality) {
        StringBuilder sb = new StringBuilder();
        if (min < MIN_PLAUSIBLE_BUS_VOLTS) {
            // Below this the robot was not running, so the reading is a missing measurement rather
            // than a low battery. Calling 0 V "approaching the brownout floor" is both wrong and
            // actionable in the worst way: it sends someone to change a healthy battery.
            sb.append("Voltage channel read ").append(round(min))
                    .append(" V, which is not a running robot — treat this as a missing measurement")
                    .append(" rather than a brownout (check the PDP/PDH is on the bus and logged)");
        } else if (!events.isEmpty()) {
            sb.append(events.size())
                    .append(events.size() == 1 ? " brownout-risk event" : " brownout-risk events")
                    .append(" detected (voltage below ").append(threshold).append(" V; min ")
                    .append(round(min)).append(" V). This may indicate battery/wiring/current-draw issues");
        } else if (min < threshold + 1.0) {
            sb.append("No sustained brownout, but voltage approached the ").append(threshold)
                    .append(" V floor (min ").append(round(min)).append(" V) — worth watching");
        } else {
            sb.append("No brownout indications; min voltage ").append(round(min)).append(" V");
        }
        sb.append(". ").append(quality.caveat());
        return sb.toString();
    }

    /**
     * The lowest bus voltage a running robot could report. The roboRIO itself stops well above
     * this, so anything lower is an unpowered or unpublished channel, not a battery reading.
     */
    public static final double MIN_PLAUSIBLE_BUS_VOLTS = 4.0;

    private static double round(double x) {
        return Math.round(x * 100) / 100.0;
    }

    private PowerAnalysis() {}
}
