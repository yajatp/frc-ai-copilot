package org.mercsmavs.frccopilot.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * Cycle-time analysis from a monotonically-increasing scoring/cycle counter: the time between
 * successive increments is one cycle. Reports how fast the robot completed scoring cycles.
 */
public final class CycleTime {

    public record Result(
            int cycles, double meanSeconds, double medianSeconds, double minSeconds, String assessment, DataQuality quality) {}

    public static Result analyze(Series counter) {
        DataQuality quality = DataQuality.of(counter.timestampsUs());
        double[] v = counter.values();
        long[] t = counter.timestampsUs();
        List<Double> cycleSeconds = new ArrayList<>();
        long lastIncrementTs = -1;
        double last = v.length > 0 ? v[0] : 0;
        for (int i = 0; i < v.length; i++) {
            if (v[i] > last) {
                if (lastIncrementTs >= 0) {
                    cycleSeconds.add((t[i] - lastIncrementTs) / 1_000_000.0);
                }
                lastIncrementTs = t[i];
                last = v[i];
            }
        }
        if (cycleSeconds.isEmpty()) {
            return new Result(0, 0, 0, 0, "No completed cycles observed. " + quality.caveat(), quality);
        }
        double mean = cycleSeconds.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double[] sorted = cycleSeconds.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double median = sorted[sorted.length / 2];
        double min = sorted[0];
        String assessment = String.format(
                "%d cycle(s): mean %.2fs, median %.2fs, fastest %.2fs. %s",
                cycleSeconds.size(), mean, median, min, quality.caveat());
        return new Result(cycleSeconds.size(), round(mean), round(median), round(min), assessment, quality);
    }

    private static double round(double x) {
        return Math.round(x * 100) / 100.0;
    }

    private CycleTime() {}
}
