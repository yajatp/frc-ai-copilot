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

        for (WpilogReader.NumericSummary s : summaries.values()) {
            if (s.count() < 2 || s.constant()) {
                continue; // a signal that never moved defends nothing
            }
            Assertion a = propose(s, phaseSignal, phaseEquals, tolerance);
            if (a != null) {
                assertions.add(a);
            }
        }

        String description = String.format(
                "Generated from a known-good run (%s) with %.0f%% tolerance%s."
                        + " Review the thresholds before banking this — they encode one run, not a spec.",
                reader.path(), tolerance * 100,
                phaseSignal == null ? "" : ", scoped to " + phaseSignal + " == " + phaseEquals);
        return new Scenario(name, description, assertions);
    }

    private static Assertion propose(
            WpilogReader.NumericSummary s, String phaseSignal, String phaseEquals, double tolerance) {
        String lower = s.name.toLowerCase();

        if (s.monotonicIncreasing() && s.last() > 0) {
            // A counter: require it still reaches roughly the same total. Integer-typed signals get
            // a whole-number threshold — "at least 4.5 game pieces" reads as a mistake.
            double threshold = discrete(s)
                    ? Math.floor(s.last() * (1 - tolerance))
                    : round(s.last() * (1 - tolerance));
            return new Assertion(s.name, Assertion.Aggregation.LAST, Assertion.Op.GE, threshold,
                    phaseSignal, phaseEquals,
                    s.name + " still reaches " + trim(threshold) + " (was " + trim(s.last()) + ")");
        }

        if (matchesAny(lower, org.mercsmavs.frccopilot.analysis.SignalResolver.VOLTAGE)) {
            // Battery: must not sag further than the good run did.
            double threshold = round(s.min() - Math.max(0.2, Math.abs(s.min()) * tolerance));
            return new Assertion(s.name, Assertion.Aggregation.MIN, Assertion.Op.GE, threshold,
                    phaseSignal, phaseEquals,
                    "battery does not sag below " + trim(threshold) + " V (observed min "
                            + trim(s.min()) + " V)");
        }

        if (matchesAny(lower, org.mercsmavs.frccopilot.analysis.SignalResolver.LOOP_PERIOD)) {
            // Loop timing: must not get slower.
            double threshold = round(s.max() * (1 + tolerance));
            return new Assertion(s.name, Assertion.Aggregation.MAX, Assertion.Op.LE, threshold,
                    phaseSignal, phaseEquals,
                    "loop period stays under " + trim(threshold) + " (observed max "
                            + trim(s.max()) + ")");
        }

        if (isErrorLike(lower) && convergent(s)) {
            // A deviation that ended well below its peak: require it still settles.
            double threshold = round(Math.abs(s.last()) + Math.max(0.05, Math.abs(s.last()) * tolerance));
            return new Assertion(s.name, Assertion.Aggregation.LAST, Assertion.Op.LE, threshold,
                    phaseSignal, phaseEquals,
                    s.name + " settles to within " + trim(threshold) + " (was " + trim(s.last()) + ")");
        }

        return null;
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
