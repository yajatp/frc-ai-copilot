package org.mercsmavs.frccopilot.analysis;

import java.util.Arrays;

/** Primitive statistics over a numeric series, always paired with a {@link DataQuality} block. */
public final class Statistics {

    public record Result(
            double min,
            double max,
            double mean,
            double median,
            double stdDev,
            double p95,
            DataQuality quality) {}

    public static Result of(Series series) {
        double[] v = series.values();
        if (v.length == 0) {
            return new Result(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    DataQuality.of(series.timestampsUs()));
        }
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double x : v) {
            sum += x;
            min = Math.min(min, x);
            max = Math.max(max, x);
        }
        double mean = sum / v.length;

        double sq = 0;
        for (double x : v) {
            sq += (x - mean) * (x - mean);
        }
        double stdDev = Math.sqrt(sq / v.length);

        double[] sorted = v.clone();
        Arrays.sort(sorted);
        return new Result(
                min, max, mean, percentile(sorted, 50), stdDev, percentile(sorted, 95),
                DataQuality.of(series.timestampsUs()));
    }

    /** Linear-interpolated percentile over already-sorted values. */
    static double percentile(double[] sorted, double p) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double rank = (p / 100.0) * (sorted.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) {
            return sorted[lo];
        }
        double frac = rank - lo;
        return sorted[lo] * (1 - frac) + sorted[hi] * frac;
    }

    private Statistics() {}
}
