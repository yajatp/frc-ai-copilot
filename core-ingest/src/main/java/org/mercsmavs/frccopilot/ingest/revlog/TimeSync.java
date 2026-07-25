package org.mercsmavs.frccopilot.ingest.revlog;

/**
 * Fine timestamp alignment between two recordings (e.g. a WPILOG signal and a REV {@code .revlog}
 * signal) via Pearson cross-correlation: slide one signal against the other and find the lag that
 * maximizes correlation. This is the valuable, format-independent core of REV-log correlation.
 *
 * <p>Signals are assumed to be (roughly) uniformly sampled at a shared rate; the caller supplies a
 * coarse alignment first (filename/systemTime) so the search window can stay small.
 */
public final class TimeSync {

    public enum Confidence {
        HIGH,
        MEDIUM,
        LOW,
        FAILED
    }

    public record SyncResult(double offsetMs, double drift, Confidence confidence, double peakCorrelation) {}

    /**
     * @param a first signal values (regularly sampled)
     * @param b second signal values (same sample rate as {@code a})
     * @param samplePeriodMs the sampling period of both signals, ms
     * @param maxLagMs how far to search in each direction
     * @return the lag (offsetMs) of {@code b} relative to {@code a} that best aligns them
     */
    public static SyncResult crossCorrelate(double[] a, double[] b, double samplePeriodMs, double maxLagMs) {
        int maxLag = (int) Math.round(maxLagMs / samplePeriodMs);
        double bestCorr = -2;
        int bestLag = 0;
        for (int lag = -maxLag; lag <= maxLag; lag++) {
            double corr = pearsonAtLag(a, b, lag);
            if (corr > bestCorr) {
                bestCorr = corr;
                bestLag = lag;
            }
        }
        Confidence confidence;
        if (bestCorr >= 0.9) {
            confidence = Confidence.HIGH;
        } else if (bestCorr >= 0.7) {
            confidence = Confidence.MEDIUM;
        } else if (bestCorr >= 0.4) {
            confidence = Confidence.LOW;
        } else {
            confidence = Confidence.FAILED;
        }
        double offsetMs = bestLag * samplePeriodMs;
        return new SyncResult(offsetMs, 0.0, confidence, round(bestCorr));
    }

    /** Pearson correlation of a[i] vs b[i+lag] over their overlapping range. */
    private static double pearsonAtLag(double[] a, double[] b, int lag) {
        int start = Math.max(0, -lag);
        int end = Math.min(a.length, b.length - lag);
        int n = end - start;
        if (n < 3) {
            return -2;
        }
        double meanA = 0;
        double meanB = 0;
        for (int i = start; i < end; i++) {
            meanA += a[i];
            meanB += b[i + lag];
        }
        meanA /= n;
        meanB /= n;
        double num = 0;
        double denA = 0;
        double denB = 0;
        for (int i = start; i < end; i++) {
            double da = a[i] - meanA;
            double db = b[i + lag] - meanB;
            num += da * db;
            denA += da * da;
            denB += db * db;
        }
        if (denA == 0 || denB == 0) {
            return -2;
        }
        return num / Math.sqrt(denA * denB);
    }

    private static double round(double x) {
        return Math.round(x * 1000) / 1000.0;
    }

    private TimeSync() {}
}
