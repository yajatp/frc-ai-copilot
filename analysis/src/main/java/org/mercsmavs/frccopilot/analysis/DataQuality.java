package org.mercsmavs.frccopilot.analysis;

import java.util.Arrays;

/**
 * The epistemic-guardrail primitive: every analysis result carries one of these so the agent can
 * see how much the data actually supports a conclusion. Mirrors wpilog-mcp's design principle that
 * a single match is rarely enough to be definitive.
 */
public record DataQuality(
        int sampleCount, double spanSeconds, double medianPeriodMs, double maxGapMs, Confidence confidence) {

    public enum Confidence {
        LOW,
        MEDIUM,
        HIGH
    }

    /**
     * Assess quality from a set of sample timestamps (microseconds).
     *
     * <p>Heuristic: HIGH needs a healthy sample count and no gap much larger than the typical
     * period (i.e. data is dense and regular); sparse or gappy data is downgraded. Deliberately
     * conservative — it should be hard to earn HIGH from one short, choppy signal.
     */
    public static DataQuality of(long[] timestampsUs) {
        int n = timestampsUs.length;
        if (n < 2) {
            return new DataQuality(n, 0, 0, 0, Confidence.LOW);
        }
        double spanS = (timestampsUs[n - 1] - timestampsUs[0]) / 1_000_000.0;

        double[] periodsMs = new double[n - 1];
        double maxGapMs = 0;
        for (int i = 1; i < n; i++) {
            double dtMs = (timestampsUs[i] - timestampsUs[i - 1]) / 1_000.0;
            periodsMs[i - 1] = dtMs;
            maxGapMs = Math.max(maxGapMs, dtMs);
        }
        double[] sorted = periodsMs.clone();
        Arrays.sort(sorted);
        double medianPeriodMs = sorted[sorted.length / 2];

        boolean regular = medianPeriodMs > 0 && maxGapMs <= 4 * medianPeriodMs;
        Confidence confidence;
        if (n >= 100 && regular) {
            confidence = Confidence.HIGH;
        } else if (n >= 20) {
            confidence = Confidence.MEDIUM;
        } else {
            confidence = Confidence.LOW;
        }
        return new DataQuality(n, spanS, medianPeriodMs, maxGapMs, confidence);
    }

    /** A short, honest, hedged phrase to append to any assessment. */
    public String caveat() {
        return switch (confidence) {
            case HIGH -> "(" + sampleCount + " samples, regular timing — reasonably well supported)";
            case MEDIUM -> "(" + sampleCount + " samples — suggestive, not conclusive; corroborate across matches)";
            case LOW -> "(only " + sampleCount + " samples" + (maxGapMs > 0 ? " / gaps up to "
                    + Math.round(maxGapMs) + "ms" : "") + " — treat as a weak signal only)";
        };
    }
}
