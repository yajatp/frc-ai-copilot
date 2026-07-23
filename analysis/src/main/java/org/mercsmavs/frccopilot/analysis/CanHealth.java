package org.mercsmavs.frccopilot.analysis;

/**
 * CAN bus health from an error-count signal (e.g. receive/transmit error counters or bus-off
 * counts). Rising counts during a match suggest wiring/termination/load issues on the bus.
 */
public final class CanHealth {

    public record Result(
            double maxErrorCount, double totalIncrease, boolean risingErrors, String assessment, DataQuality quality) {}

    public static Result analyze(Series errorCounts) {
        DataQuality quality = DataQuality.of(errorCounts.timestampsUs());
        if (errorCounts.isEmpty()) {
            return new Result(0, 0, false, "No CAN error-count samples available.", quality);
        }
        double[] v = errorCounts.values();
        double max = v[0];
        double first = v[0];
        double increase = 0;
        for (int i = 0; i < v.length; i++) {
            max = Math.max(max, v[i]);
            if (i > 0 && v[i] > v[i - 1]) {
                increase += v[i] - v[i - 1]; // only count upward steps (counters can reset)
            }
        }
        boolean rising = increase > 0;

        String assessment;
        if (!rising) {
            assessment = "No CAN error growth observed (max count " + round(max) + "). " + quality.caveat();
        } else {
            assessment =
                    "CAN error count increased by " + round(increase) + " over the log (max " + round(max)
                            + "). This may indicate bus wiring/termination or an overloaded bus — worth a pit check. "
                            + quality.caveat();
        }
        return new Result(max, increase, rising, assessment, quality);
    }

    private static double round(double x) {
        return Math.round(x * 100) / 100.0;
    }

    private CanHealth() {}
}
