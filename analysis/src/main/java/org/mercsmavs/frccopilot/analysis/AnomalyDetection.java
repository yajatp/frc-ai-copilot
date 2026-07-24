package org.mercsmavs.frccopilot.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Robust outlier detection over a {@link Series} using the median / MAD (median absolute deviation)
 * modified z-score — resistant to the very spikes we're trying to find (unlike mean/stddev).
 */
public final class AnomalyDetection {

    public record Anomaly(double timeSeconds, double value, double score) {}

    public record Result(List<Anomaly> anomalies, double threshold, String assessment, DataQuality quality) {
        public boolean any() {
            return !anomalies.isEmpty();
        }
    }

    public static Result detect(Series series) {
        return detect(series, 3.5); // Iglewicz–Hoaglin default
    }

    public static Result detect(Series series, double threshold) {
        DataQuality quality = DataQuality.of(series.timestampsUs());
        double[] v = series.values();
        if (v.length < 3) {
            return new Result(List.of(), threshold, "Too few samples to judge anomalies. " + quality.caveat(), quality);
        }
        double median = median(v);
        double[] absDev = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            absDev[i] = Math.abs(v[i] - median);
        }
        double mad = median(absDev);
        List<Anomaly> anomalies = new ArrayList<>();
        if (mad > 0) {
            for (int i = 0; i < v.length; i++) {
                double score = 0.6745 * (v[i] - median) / mad;
                if (Math.abs(score) > threshold) {
                    anomalies.add(new Anomaly(series.timestampsUs()[i] / 1_000_000.0, v[i], round(score)));
                }
            }
        } else {
            // Degenerate case: the data is (near-)constant, so MAD is 0. Any real deviation from the
            // constant is itself the outlier (e.g. a single glitch in an otherwise flat signal).
            for (int i = 0; i < v.length; i++) {
                if (Math.abs(v[i] - median) > 1e-9) {
                    anomalies.add(new Anomaly(series.timestampsUs()[i] / 1_000_000.0, v[i], round(v[i] - median)));
                }
            }
        }
        String assessment = anomalies.isEmpty()
                ? "No outliers beyond modified z=" + threshold + ". " + quality.caveat()
                : anomalies.size() + " outlier(s) detected (|modified z| > " + threshold
                        + "); this may indicate glitches or real transient events. " + quality.caveat();
        return new Result(anomalies, threshold, assessment, quality);
    }

    private static double median(double[] in) {
        double[] s = in.clone();
        Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2.0;
    }

    private static double round(double x) {
        return Math.round(x * 100) / 100.0;
    }

    private AnomalyDetection() {}
}
