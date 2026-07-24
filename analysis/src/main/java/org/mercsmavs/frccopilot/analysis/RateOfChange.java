package org.mercsmavs.frccopilot.analysis;

/** Derivative statistics of a {@link Series} — useful for spikes/jerk (units per second). */
public final class RateOfChange {

    public record Result(
            double maxSlope, double minSlope, double meanAbsSlope, double maxSlopeTimeSeconds, DataQuality quality) {}

    public static Result of(Series series) {
        DataQuality quality = DataQuality.of(series.timestampsUs());
        double[] v = series.values();
        long[] t = series.timestampsUs();
        if (v.length < 2) {
            return new Result(0, 0, 0, 0, quality);
        }
        double maxSlope = Double.NEGATIVE_INFINITY;
        double minSlope = Double.POSITIVE_INFINITY;
        double sumAbs = 0;
        double maxTime = 0;
        int count = 0;
        for (int i = 1; i < v.length; i++) {
            double dt = (t[i] - t[i - 1]) / 1_000_000.0;
            if (dt <= 0) {
                continue;
            }
            double slope = (v[i] - v[i - 1]) / dt;
            if (slope > maxSlope) {
                maxSlope = slope;
                maxTime = t[i] / 1_000_000.0;
            }
            minSlope = Math.min(minSlope, slope);
            sumAbs += Math.abs(slope);
            count++;
        }
        double meanAbs = count == 0 ? 0 : sumAbs / count;
        return new Result(maxSlope, minSlope, meanAbs, maxTime, quality);
    }

    private RateOfChange() {}
}
