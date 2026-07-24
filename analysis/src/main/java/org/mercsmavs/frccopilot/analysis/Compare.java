package org.mercsmavs.frccopilot.analysis;

/**
 * Compare summary statistics of two {@link Series} — e.g. the same signal across two matches, or
 * two symmetric mechanisms (left vs right). Reports deltas with hedged interpretation.
 */
public final class Compare {

    public record Result(
            Statistics.Result a,
            Statistics.Result b,
            double meanDelta,
            double maxDelta,
            String assessment) {}

    public static Result of(Series a, Series b) {
        Statistics.Result sa = Statistics.of(a);
        Statistics.Result sb = Statistics.of(b);
        double meanDelta = sb.mean() - sa.mean();
        double maxDelta = sb.max() - sa.max();
        String assessment = String.format(
                "mean %.3f -> %.3f (%+.3f), max %.3f -> %.3f (%+.3f). %s",
                sa.mean(), sb.mean(), meanDelta, sa.max(), sb.max(), maxDelta,
                Math.abs(meanDelta) < 1e-9
                        ? "No meaningful difference in means."
                        : "The difference may indicate a real change; corroborate before concluding.");
        return new Result(sa, sb, round(meanDelta), round(maxDelta), assessment);
    }

    private static double round(double x) {
        return Math.round(x * 1000) / 1000.0;
    }

    private Compare() {}
}
