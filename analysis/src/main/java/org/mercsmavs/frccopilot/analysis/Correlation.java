package org.mercsmavs.frccopilot.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * Pearson correlation between two {@link Series}. The two signals rarely share timestamps, so we
 * align by nearest-timestamp before correlating.
 */
public final class Correlation {

    public record Result(double pearson, int pairs, String assessment, DataQuality quality) {}

    public static Result of(Series a, Series b) {
        List<double[]> aligned = alignNearest(a, b);
        DataQuality quality = DataQuality.of(aTimestamps(aligned));
        if (aligned.size() < 3) {
            return new Result(Double.NaN, aligned.size(), "Too few aligned pairs to correlate. " + quality.caveat(), quality);
        }
        double meanA = 0;
        double meanB = 0;
        for (double[] p : aligned) {
            meanA += p[0];
            meanB += p[1];
        }
        meanA /= aligned.size();
        meanB /= aligned.size();

        double num = 0;
        double denA = 0;
        double denB = 0;
        for (double[] p : aligned) {
            double da = p[0] - meanA;
            double db = p[1] - meanB;
            num += da * db;
            denA += da * da;
            denB += db * db;
        }
        double r = (denA == 0 || denB == 0) ? 0 : num / Math.sqrt(denA * denB);
        String assessment = describe(r) + String.format(" (r=%.3f over %d pairs). ", r, aligned.size()) + quality.caveat();
        return new Result(round(r), aligned.size(), assessment, quality);
    }

    private static String describe(double r) {
        double m = Math.abs(r);
        String strength = m > 0.8 ? "strong" : m > 0.5 ? "moderate" : m > 0.2 ? "weak" : "little to no";
        String dir = r >= 0 ? "positive" : "negative";
        return "The signals show " + strength + " " + dir + " correlation, which may suggest a relationship";
    }

    /** For each sample in the shorter series, pair it with the nearest-in-time sample of the other. */
    private static List<double[]> alignNearest(Series a, Series b) {
        List<double[]> out = new ArrayList<>();
        Series shorter = a.size() <= b.size() ? a : b;
        Series other = shorter == a ? b : a;
        long[] ot = other.timestampsUs();
        double[] ov = other.values();
        for (int i = 0; i < shorter.size(); i++) {
            long ts = shorter.timestampsUs()[i];
            int j = nearest(ot, ts);
            double sv = shorter.values()[i];
            // preserve (a,b) order regardless of which was shorter
            if (shorter == a) {
                out.add(new double[] {sv, ov[j]});
            } else {
                out.add(new double[] {ov[j], sv});
            }
        }
        return out;
    }

    private static int nearest(long[] ts, long target) {
        int best = 0;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < ts.length; i++) {
            long d = Math.abs(ts[i] - target);
            if (d < bestDiff) {
                bestDiff = d;
                best = i;
            }
        }
        return best;
    }

    private static long[] aTimestamps(List<double[]> aligned) {
        long[] fake = new long[aligned.size()];
        for (int i = 0; i < fake.length; i++) {
            fake[i] = i * 20_000L; // synthetic 20ms spacing for quality scoring of pair count
        }
        return fake;
    }

    private static double round(double x) {
        return Math.round(x * 1000) / 1000.0;
    }

    private Correlation() {}
}
