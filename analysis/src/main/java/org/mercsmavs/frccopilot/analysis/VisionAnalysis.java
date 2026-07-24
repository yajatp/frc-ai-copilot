package org.mercsmavs.frccopilot.analysis;

/**
 * Vision-detection health from numeric signals. Two common shapes are supported:
 * a detection signal (boolean hasTarget, or a tag/target count) and an optional ambiguity signal.
 * Reports how often the camera saw a target and the dropout rate.
 */
public final class VisionAnalysis {

    public record Result(
            double detectionRate, int dropouts, double meanAmbiguity, String assessment, DataQuality quality) {}

    /** @param detection hasTarget (0/1) or a tag count (>0 means a target). */
    public static Result analyze(Series detection, Series ambiguityOrNull) {
        DataQuality quality = DataQuality.of(detection.timestampsUs());
        double[] v = detection.values();
        if (v.length == 0) {
            return new Result(0, 0, Double.NaN, "No vision detection samples available.", quality);
        }
        int seen = 0;
        int dropouts = 0; // transitions from seeing -> not seeing
        boolean prevSeen = false;
        for (int i = 0; i < v.length; i++) {
            boolean has = v[i] > 0;
            if (has) {
                seen++;
            }
            if (i > 0 && prevSeen && !has) {
                dropouts++;
            }
            prevSeen = has;
        }
        double rate = (double) seen / v.length;

        double meanAmbiguity = Double.NaN;
        if (ambiguityOrNull != null && !ambiguityOrNull.isEmpty()) {
            double sum = 0;
            for (double a : ambiguityOrNull.values()) {
                sum += a;
            }
            meanAmbiguity = sum / ambiguityOrNull.values().length;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Target visible in %.0f%% of frames, %d dropout(s).", rate * 100, dropouts));
        if (rate < 0.5) {
            sb.append(" Low visibility may indicate mounting/exposure/field-of-view issues.");
        }
        if (!Double.isNaN(meanAmbiguity)) {
            sb.append(String.format(" Mean pose ambiguity %.3f%s.", meanAmbiguity,
                    meanAmbiguity > 0.2 ? " (high — poses may be unreliable)" : ""));
        }
        sb.append(" ").append(quality.caveat());
        return new Result(round(rate), dropouts, round(meanAmbiguity), sb.toString(), quality);
    }

    private static double round(double x) {
        return Double.isNaN(x) ? x : Math.round(x * 1000) / 1000.0;
    }

    private VisionAnalysis() {}
}
