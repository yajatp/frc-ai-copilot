package org.mercsmavs.frccopilot.analysis;

import java.util.Arrays;

/**
 * Loop-timing analysis from a loop-period / code-runtime signal (values in milliseconds). Robot
 * code runs a fixed periodic (usually 20 ms); sustained overruns cause missed control cycles.
 */
public final class LoopTiming {

    public static final double DEFAULT_OVERRUN_MS = 20.0;

    public record Result(
            double p95Ms, double maxMs, int overruns, double overrunFraction, String assessment, DataQuality quality) {}

    public static Result analyze(Series loopMs) {
        return analyze(loopMs, DEFAULT_OVERRUN_MS);
    }

    public static Result analyze(Series loopMs, double overrunThresholdMs) {
        DataQuality quality = DataQuality.of(loopMs.timestampsUs());
        double[] v = loopMs.values();
        if (v.length == 0) {
            return new Result(0, 0, 0, 0, "No loop-timing samples available.", quality);
        }
        double[] sorted = v.clone();
        Arrays.sort(sorted);
        double p95 = Statistics.percentile(sorted, 95);
        double max = sorted[sorted.length - 1];
        int overruns = 0;
        for (double x : v) {
            if (x > overrunThresholdMs) {
                overruns++;
            }
        }
        double frac = (double) overruns / v.length;
        String assessment = overruns == 0
                ? String.format("No loop overruns (p95 %.2f ms, max %.2f ms, threshold %.0f ms). %s",
                        p95, max, overrunThresholdMs, quality.caveat())
                : String.format("%d loop overrun(s) (%.1f%% of cycles > %.0f ms; p95 %.2f ms, max %.2f ms)."
                        + " This may indicate the code is doing too much per cycle. %s",
                        overruns, frac * 100, overrunThresholdMs, p95, max, quality.caveat());
        return new Result(round(p95), round(max), overruns, round(frac), assessment, quality);
    }

    private static double round(double x) {
        return Math.round(x * 100) / 100.0;
    }

    private LoopTiming() {}
}
