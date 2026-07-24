package org.mercsmavs.frccopilot.analysis;

/**
 * Detects underdamped / oscillating closed-loop behavior from a numeric signal (e.g. a swerve
 * module's drive velocity, applied voltage, or a position error). Works off whatever numeric signal
 * is available — it does not require decoded structs.
 *
 * <p>Heuristic: de-mean the signal, count sign changes of the de-meaned value; a high oscillation
 * rate with sustained amplitude suggests an underdamped response. Guidance is deliberately hedged —
 * PID intuition ("kD may be low or kP too high") is a hypothesis to check, not a verdict.
 */
public final class SwerveAnalysis {

    public record Result(
            int oscillations,
            double oscillationHz,
            double amplitude,
            boolean likelyUnderdamped,
            String assessment,
            DataQuality quality) {}

    public static Result analyze(Series signal) {
        DataQuality quality = DataQuality.of(signal.timestampsUs());
        double[] v = signal.values();
        long[] t = signal.timestampsUs();
        if (v.length < 8) {
            return new Result(0, 0, 0, false, "Too few samples to judge oscillation. " + quality.caveat(), quality);
        }

        double mean = 0;
        for (double x : v) {
            mean += x;
        }
        mean /= v.length;

        double amplitude = 0;
        for (double x : v) {
            amplitude = Math.max(amplitude, Math.abs(x - mean));
        }
        // Ignore tiny wobble relative to the signal's own scale.
        double deadband = 0.05 * amplitude;

        int crossings = 0;
        int lastSign = 0;
        for (double x : v) {
            double d = x - mean;
            if (Math.abs(d) < deadband) {
                continue;
            }
            int sign = d > 0 ? 1 : -1;
            if (lastSign != 0 && sign != lastSign) {
                crossings++;
            }
            lastSign = sign;
        }
        int oscillations = crossings / 2; // a full oscillation is two sign changes
        double spanS = (t[t.length - 1] - t[0]) / 1_000_000.0;
        double hz = spanS > 0 ? oscillations / spanS : 0;

        boolean underdamped = oscillations >= 3 && amplitude > 0 && hz > 1.0;
        String assessment;
        if (underdamped) {
            assessment = String.format(
                    "~%d oscillations (~%.1f Hz, amplitude %.3f) — this pattern suggests an underdamped"
                            + " response; kD may be too low or kP too high. Verify against the setpoint"
                            + " and check for velocity saturation before tuning. %s",
                    oscillations, hz, amplitude, quality.caveat());
        } else {
            assessment = String.format(
                    "No strong oscillation detected (~%d crossings, amplitude %.3f) — response looks"
                            + " reasonably damped. %s",
                    oscillations, amplitude, quality.caveat());
        }
        return new Result(oscillations, round(hz), round(amplitude), underdamped, assessment, quality);
    }

    private static double round(double x) {
        return Math.round(x * 1000) / 1000.0;
    }

    private SwerveAnalysis() {}
}
