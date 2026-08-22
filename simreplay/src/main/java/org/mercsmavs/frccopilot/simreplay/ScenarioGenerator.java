package org.mercsmavs.frccopilot.simreplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

/**
 * Derives a regression scenario from a run that is known to be good.
 *
 * <p>The compounding value of the harness comes from banking every verified fix as a standing check,
 * but only if writing one is nearly free — a team that has to hand-author YAML after each fix will
 * do it twice and then stop. This reads a good log and proposes the checks that log justifies.
 *
 * <p>It is deliberately selective. Asserting an envelope around every signal produces a suite that
 * fails on ordinary run-to-run variation and gets deleted within a week, so only signals with a
 * shape worth defending are used:
 *
 * <ul>
 *   <li><b>counters</b> (monotonically increasing) — the robot must still achieve about this much
 *   <li><b>battery voltage</b> — must not sag further than it did here
 *   <li><b>loop period</b> — must not get slower than it was here
 *   <li><b>converging errors</b> (a distance/error signal that ends far below its peak) — must
 *       still settle
 * </ul>
 *
 * <p>Generated thresholds carry a tolerance and are meant to be reviewed, not trusted blindly; the
 * emitted description says which log and tolerance produced them.
 */
public final class ScenarioGenerator {

    /** Default slack on a generated threshold — wide enough to absorb honest run-to-run noise. */
    public static final double DEFAULT_TOLERANCE = 0.10;

    /**
     * Samples a signal must have inside the window before its shape is trusted.
     *
     * <p>AdvantageKit and NetworkTables both log on change, so a signal that settled early has a
     * handful of samples spanning the whole match. Two rising samples are indistinguishable from a
     * counter, and that is exactly how a suite ends up asserting that a stator current "still
     * reaches 72 A" — a check that means nothing and fails at random. Requiring a real series
     * costs a few honest checks and removes almost all of the junk.
     */
    public static final int MIN_SAMPLES = 20;

    /**
     * Half-width of the box a generated endpoint check puts around where the robot finished, in
     * metres and in degrees. Wide enough to absorb honest run-to-run variation, tight enough that
     * an auto drifting by a scoring position fails.
     */
    private static final double ENDPOINT_TOLERANCE_M = 0.20;

    private static final double ENDPOINT_TOLERANCE_DEG = 6.0;

    /**
     * A signal counts as settled when its final per-sample step is under this fraction of the
     * endpoint window. At a tenth, a value still drifting by more than 2 cm per 20 ms cycle does
     * not get an endpoint check, because window jitter alone would move it across the box.
     */
    private static final double SETTLED_FRACTION = 0.10;

    /** Name fragments that mark a signal as an error/deviation that ought to converge. */
    private static final List<String> ERROR_LIKE =
            List.of("error", "distancetotarget", "deviation", "offset", "residual");

    /**
     * Propose a scenario from a known-good log.
     *
     * @param reader the good run
     * @param name scenario name
     * @param phaseSignal optional phase signal to scope every check to (e.g. {@code /Robot/State})
     * @param phaseEquals the phase value to scope to (e.g. {@code AUTO})
     * @param tolerance fractional slack on each threshold (see {@link #DEFAULT_TOLERANCE})
     */
    public static Scenario generate(
            WpilogReader reader,
            String name,
            String phaseSignal,
            String phaseEquals,
            double tolerance) {
        // Measure over exactly the window the generated assertions will be evaluated in, so a
        // generated scenario always passes on the log that produced it.
        Map<String, WpilogReader.NumericSummary> summaries;
        if (phaseSignal == null) {
            summaries = reader.numericSummaries();
        } else {
            PhaseWindows windows = PhaseWindows.of(reader::read, phaseSignal, phaseEquals);
            summaries = reader.numericSummaries(windows::contains);
        }
        List<Assertion> assertions = new ArrayList<>();
        List<String> unsettled = new ArrayList<>();

        for (WpilogReader.NumericSummary s : summaries.values()) {
            if (s.constant()) {
                continue; // a signal that never moved defends nothing
            }
            if (Bookkeeping.isBookkeeping(s.name)) {
                continue; // a wall clock always "still reaches" its old value
            }
            if (s.count() < MIN_SAMPLES) {
                continue; // see MIN_SAMPLES — too thin to tell shape from coincidence
            }
            if (isRadianCompanion(s)) {
                continue; // its degree twin says the same thing, in the units a human writes
            }
            assertions.addAll(propose(s, phaseSignal, phaseEquals, tolerance, unsettled));
        }

        StringBuilder description = new StringBuilder(String.format(
                "Generated from a known-good run (%s) with %.0f%% tolerance%s."
                        + " Review the thresholds before banking this — they encode one run, not a spec.",
                reader.path(), tolerance * 100,
                phaseSignal == null ? "" : ", scoped to " + phaseSignal + " == " + phaseEquals));
        if (!unsettled.isEmpty()) {
            // Say what was left out and why. Silently omitting the endpoint check on the axis the
            // robot happened to still be moving along is worse than not offering endpoint checks at
            // all: the suite looks like it defends where the auto finished, and along that axis it
            // does not.
            description.append(" NOT checked, because the run was still moving along ")
                    .append(unsettled.size() == 1 ? "it" : "them")
                    .append(" when the window closed — the routine had not finished, so its last")
                    .append(" value records where the window ended rather than where the robot got")
                    .append(" to: ")
                    .append(String.join(", ", unsettled))
                    .append(". Give the run long enough to come to rest and regenerate to cover")
                    .append(" these.");
        }
        return new Scenario(name, description.toString(), assertions);
    }

    /**
     * The checks one signal justifies, if any.
     *
     * <p>Order matters, and it is the opposite of what "most specific last" would suggest. Almost
     * any signal is non-decreasing over some window, so testing for a counter first captures
     * batteries, errors and poses alike and emits the wrong check for all three — including the
     * inversion that asks an error term to <em>stay large</em>. The recognised roles are therefore
     * matched by name first, and "counter" is the fallback it should always have been.
     */
    private static List<Assertion> propose(
            WpilogReader.NumericSummary s,
            String phaseSignal,
            String phaseEquals,
            double tolerance,
            List<String> unsettled) {
        String lower = s.name.toLowerCase();

        if (matchesAny(lower, org.mercsmavs.frccopilot.analysis.SignalResolver.VOLTAGE)) {
            // Battery: must not sag further than the good run did.
            double threshold = round(s.min() - Math.max(0.2, Math.abs(s.min()) * tolerance));
            return List.of(new Assertion(s.name, Assertion.Aggregation.MIN, Assertion.Op.GE, threshold,
                    phaseSignal, phaseEquals,
                    "battery does not sag below " + trim(threshold) + " V (observed min "
                            + trim(s.min()) + " V)"));
        }

        if (matchesAny(lower, org.mercsmavs.frccopilot.analysis.SignalResolver.LOOP_PERIOD)) {
            // Loop timing: must not overrun the control period.
            //
            // Deliberately not `observed max * 1.1`. The maximum of a loop-period series is set by
            // its worst outlier — a GC pause, the JIT still warming up, another process taking the
            // core — and on a developer laptop that varies by several milliseconds between two runs
            // of identical code. A threshold cut that fine fails constantly for reasons that have
            // nothing to do with the robot, which is precisely how a suite loses its credibility.
            // The deadline that actually matters is the control period, so the floor is WPILib's
            // own overrun threshold, and a run that was already slower than that keeps its headroom.
            double floor = loopPeriodFloor(s.max());
            double threshold = round(Math.max(s.max() * (1 + tolerance), floor));
            return List.of(new Assertion(s.name, Assertion.Aggregation.MAX, Assertion.Op.LE, threshold,
                    phaseSignal, phaseEquals,
                    "no loop overrun: period stays under " + trim(threshold) + " (observed max "
                            + trim(s.max()) + ")"));
        }

        if (isErrorLike(lower) && convergent(s)) {
            // A deviation that ended well below its peak: require it still settles.
            double threshold = round(Math.abs(s.last()) + Math.max(0.05, Math.abs(s.last()) * tolerance));
            return List.of(new Assertion(s.name, Assertion.Aggregation.LAST, Assertion.Op.LE, threshold,
                    phaseSignal, phaseEquals,
                    s.name + " settles to within " + trim(threshold) + " (was " + trim(s.last()) + ")"));
        }

        List<Assertion> endpoint = proposeEndpoint(s, phaseSignal, phaseEquals, unsettled);
        if (!endpoint.isEmpty()) {
            return endpoint;
        }

        if (s.type != null && s.type.startsWith("struct:")) {
            // A geometry field that did not earn an endpoint check earns nothing. It reaches the
            // counter fallback below purely because it happened to increase across the window — a
            // wheel angle sweeping one way, a setpoint travelling down the path — and "still
            // reaches 74 degrees" is not a fact anyone wants to defend.
            return List.of();
        }

        if (s.monotonicIncreasing() && s.last() > 0) {
            // A counter: require it still reaches roughly the same total. Integer-typed signals get
            // a whole-number threshold — "at least 4.5 game pieces" reads as a mistake.
            double threshold = discrete(s)
                    ? Math.floor(s.last() * (1 - tolerance))
                    : round(s.last() * (1 - tolerance));
            return List.of(new Assertion(s.name, Assertion.Aggregation.LAST, Assertion.Op.GE, threshold,
                    phaseSignal, phaseEquals,
                    s.name + " still reaches " + trim(threshold) + " (was " + trim(s.last()) + ")"));
        }

        return List.of();
    }

    /**
     * Where the robot finished, boxed on both sides.
     *
     * <p>This is the check a team actually wants out of an autonomous run and the one a
     * single-sided threshold cannot express: an auto that ends half a metre short passes any
     * "still reaches" bound taken from a good run, because it did still move. Pose fields (see
     * {@code StructFields}) get a symmetric window around the observed endpoint instead — two
     * assertions, because one assertion carries one operator.
     */
    private static List<Assertion> proposeEndpoint(
            WpilogReader.NumericSummary s,
            String phaseSignal,
            String phaseEquals,
            List<String> unsettled) {
        Double slack = endpointSlack(s);
        if (slack == null || Double.isNaN(s.last())) {
            return List.of();
        }
        // Only for a value that has come to rest. A commanded trajectory setpoint is a pose too,
        // but it is still travelling when autonomous ends, so its final value is a reading of where
        // the window closed rather than of where the robot got to — worth 0.2 m of run-to-run
        // variation on its own, which is the entire budget for the check.
        if (!Double.isNaN(s.lastStep()) && s.lastStep() > slack * SETTLED_FRACTION) {
            unsettled.add(s.name);
            return List.of();
        }
        double low = round(s.last() - slack);
        double high = round(s.last() + slack);
        String unit = s.name.endsWith("Deg") ? "°" : " m";
        String where = "ends within " + trim(slack) + unit + " of " + trim(s.last()) + unit;
        return List.of(
                new Assertion(s.name, Assertion.Aggregation.LAST, Assertion.Op.GE, low,
                        phaseSignal, phaseEquals, s.name + " " + where + " (lower bound)"),
                new Assertion(s.name, Assertion.Aggregation.LAST, Assertion.Op.LE, high,
                        phaseSignal, phaseEquals, s.name + " " + where + " (upper bound)"));
    }

    /**
     * The endpoint window for a field of a robot <em>position</em>, or {@code null} if this signal
     * is not one.
     *
     * <p>Gated on the struct the number was projected from, not on the field name. Every swerve
     * module publishes its azimuth as a {@code Rotation2d}, so keying on "an angle field" boxes
     * sixteen wheel angles to within 6° at the end of autonomous — checks that defend nothing and
     * fail on ordinary variation. Only a pose or a translation says where the robot got to.
     */
    private static Double endpointSlack(WpilogReader.NumericSummary s) {
        String type = s.type == null ? "" : s.type;
        boolean position = type.startsWith("struct:Pose")
                || type.startsWith("struct:Translation")
                || type.startsWith("struct:Transform");
        if (!position) {
            return null;
        }
        int split = s.name.lastIndexOf('/');
        if (split < 0) {
            return null;
        }
        return switch (s.name.substring(split + 1)) {
            case "X", "Y", "Z" -> ENDPOINT_TOLERANCE_M;
            case "RotationDeg", "YawDeg" -> ENDPOINT_TOLERANCE_DEG;
            default -> null;
        };
    }

    /**
     * True for the radian half of an angle that is also published in degrees. Asserting both states
     * one fact twice, and the radian form is the one nobody reads.
     */
    private static boolean isRadianCompanion(WpilogReader.NumericSummary s) {
        if (s.type == null || !s.type.startsWith("struct:")) {
            return false;
        }
        int split = s.name.lastIndexOf('/');
        if (split < 0) {
            return false;
        }
        return switch (s.name.substring(split + 1)) {
            case "Rotation", "Yaw", "Roll", "Pitch", "Angle", "Omega", "Dtheta", "Rx", "Ry", "Rz" ->
                    true;
            default -> false;
        };
    }

    /** Ended at less than half its peak deviation — the signature of a controller that converged. */
    private static boolean convergent(WpilogReader.NumericSummary s) {
        double peak = Math.max(Math.abs(s.max()), Math.abs(s.min()));
        return peak > 1e-6 && Math.abs(s.last()) < peak * 0.5;
    }

    private static boolean isErrorLike(String lower) {
        return ERROR_LIKE.stream().anyMatch(lower::contains);
    }

    private static boolean matchesAny(String lower, List<String> candidates) {
        return candidates.stream().anyMatch(c -> lower.contains(c.toLowerCase()));
    }

    /**
     * The overrun deadline for a loop-period signal, in whatever unit it is logged in.
     *
     * <p>WPILib's threshold is 20 ms. Logs record the period in milliseconds or in seconds
     * depending on the framework, and nothing in the log says which, so it is inferred from
     * magnitude: a periodic loop running anywhere near its deadline reads about 20 in milliseconds
     * and about 0.02 in seconds. Values far above that are already something other than a period,
     * and fall back to the observed maximum.
     */
    private static double loopPeriodFloor(double observedMax) {
        double overrunMs = org.mercsmavs.frccopilot.analysis.LoopTiming.DEFAULT_OVERRUN_MS;
        if (observedMax < 1.0) {
            return overrunMs / 1000.0; // seconds
        }
        if (observedMax < 1000.0) {
            return overrunMs; // milliseconds
        }
        return observedMax; // microseconds or something else; do not invent a deadline
    }

    /** Whole-number signals — a scored-piece count, not a pose coordinate. */
    private static boolean discrete(WpilogReader.NumericSummary s) {
        return "int64".equals(s.type) || "boolean".equals(s.type);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.3f", v);
    }

    private ScenarioGenerator() {}
}
