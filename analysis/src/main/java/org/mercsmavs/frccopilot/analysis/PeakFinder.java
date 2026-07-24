package org.mercsmavs.frccopilot.analysis;

import java.util.ArrayList;
import java.util.List;

/** Finds local maxima in a {@link Series} whose prominence exceeds a threshold. */
public final class PeakFinder {

    public record Peak(double timeSeconds, double value) {}

    public record Result(List<Peak> peaks, String assessment, DataQuality quality) {}

    public static Result find(Series series, double minProminence) {
        DataQuality quality = DataQuality.of(series.timestampsUs());
        double[] v = series.values();
        long[] t = series.timestampsUs();
        List<Peak> peaks = new ArrayList<>();
        for (int i = 1; i < v.length - 1; i++) {
            if (v[i] > v[i - 1] && v[i] >= v[i + 1]) {
                double prominence = v[i] - Math.min(troughLeft(v, i), troughRight(v, i));
                if (prominence >= minProminence) {
                    peaks.add(new Peak(t[i] / 1_000_000.0, v[i]));
                }
            }
        }
        String assessment = peaks.isEmpty()
                ? "No peaks above prominence " + minProminence + ". " + quality.caveat()
                : peaks.size() + " peak(s) found (prominence >= " + minProminence + "). " + quality.caveat();
        return new Result(peaks, assessment, quality);
    }

    private static double troughLeft(double[] v, int peak) {
        double min = v[peak];
        for (int i = peak - 1; i >= 0 && v[i] < v[peak]; i--) {
            min = Math.min(min, v[i]);
        }
        return min;
    }

    private static double troughRight(double[] v, int peak) {
        double min = v[peak];
        for (int i = peak + 1; i < v.length && v[i] < v[peak]; i++) {
            min = Math.min(min, v[i]);
        }
        return min;
    }

    private PeakFinder() {}
}
