package org.mercsmavs.frccopilot.analysis;

/**
 * Battery-health indicator from voltage (and optionally current) across a match. Estimates the
 * voltage-under-load trend and a rough effective internal resistance, then gives a HEDGED
 * end-of-match sag prediction.
 *
 * <p>Strong caveat by design: a single match is never enough to conclude a battery is bad — this is
 * an indicator to watch, matching the 2026 energy-management meta, not a verdict.
 */
public final class BatteryHealth {

    public static final double BROWNOUT_VOLTS = 6.8;

    public record Result(
            double startVolts,
            double endVolts,
            double droopVolts,
            double estInternalResistanceOhms,
            double projectedEndVolts,
            String assessment,
            DataQuality quality) {}

    public static Result analyze(Series voltage, Series currentOrNull) {
        DataQuality quality = DataQuality.of(voltage.timestampsUs());
        double[] v = voltage.values();
        if (v.length < 5) {
            return new Result(Double.NaN, Double.NaN, 0, Double.NaN, Double.NaN,
                    "Too few voltage samples for a battery indicator. " + quality.caveat(), quality);
        }
        double startV = average(v, 0, Math.max(1, v.length / 20));
        double endV = average(v, v.length - Math.max(1, v.length / 20), v.length);
        double droop = startV - endV;

        // Rough internal resistance from voltage vs current spread, if current is available.
        double resistance = Double.NaN;
        if (currentOrNull != null && currentOrNull.values().length == v.length) {
            resistance = estimateResistance(v, currentOrNull.values());
        }

        // Naive linear projection of the droop trend to a full 150 s match.
        double spanS = (voltage.timestampsUs()[v.length - 1] - voltage.timestampsUs()[0]) / 1_000_000.0;
        double projected = endV;
        if (spanS > 1) {
            double droopPerSec = droop / spanS;
            // Clamp to a physically sane range; a very short log extrapolated to 150s can overshoot.
            projected = Math.max(0, Math.min(startV, startV - droopPerSec * 150.0));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Voltage %.2f V -> %.2f V (droop %.2f V).", startV, endV, droop));
        if (!Double.isNaN(resistance)) {
            sb.append(String.format(" Est. internal resistance ~%.3f ohm%s.", resistance,
                    resistance > 0.02 ? " (elevated — battery may be tired)" : ""));
        }
        if (projected < BROWNOUT_VOLTS + 0.5) {
            sb.append(String.format(" Extrapolating the droop, a full match may approach the %.1f V"
                    + " brownout floor (~%.2f V) — consider a fresher battery or load-shedding.", BROWNOUT_VOLTS, projected));
        } else {
            sb.append(" Droop trend looks acceptable for a full match.");
        }
        sb.append(" This is a single-match indicator, not a verdict. ").append(quality.caveat());

        return new Result(round(startV), round(endV), round(droop), round(resistance), round(projected), sb.toString(), quality);
    }

    private static double estimateResistance(double[] volts, double[] amps) {
        // Simple slope of V vs I (V = Voc - I*R) via least squares; R = -slope.
        int n = volts.length;
        double sumI = 0;
        double sumV = 0;
        double sumII = 0;
        double sumIV = 0;
        for (int i = 0; i < n; i++) {
            sumI += amps[i];
            sumV += volts[i];
            sumII += amps[i] * amps[i];
            sumIV += amps[i] * volts[i];
        }
        double denom = n * sumII - sumI * sumI;
        if (Math.abs(denom) < 1e-9) {
            return Double.NaN;
        }
        double slope = (n * sumIV - sumI * sumV) / denom;
        return Math.max(0, -slope);
    }

    private static double average(double[] v, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) {
            s += v[i];
        }
        return s / (to - from);
    }

    private static double round(double x) {
        return Double.isNaN(x) ? x : Math.round(x * 1000) / 1000.0;
    }

    private BatteryHealth() {}
}
