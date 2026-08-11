package org.mercsmavs.frccopilot.simreplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

/**
 * Turns a failed verification into evidence an agent can act on.
 *
 * <p>{@code FAIL: MAX /Autonomous/BallsScored > 0} tells you a check failed and nothing else. The
 * next question is always the same one a human asks: <em>what did that signal actually do?</em> A
 * signal pinned at zero for the entire window means the mechanism never fired; one that climbed and
 * fell short means a tuning problem; one with no samples at all usually means the log key was
 * renamed, not that the robot misbehaved. Those three failures point at completely different files,
 * so the loop separates them rather than reporting one undifferentiated FAIL.
 */
public record Diagnosis(List<Finding> findings) {

    /** What kind of failure this is — the distinction that decides where to look next. */
    public enum Kind {
        /** The signal has no samples in the log at all. Usually a name/logging problem. */
        SIGNAL_ABSENT,
        /** The signal exists but has no samples inside the required phase window. */
        NO_SAMPLES_IN_PHASE,
        /** The signal never moved. The mechanism it reflects likely never ran. */
        SIGNAL_CONSTANT,
        /** The signal moved but did not reach the threshold. Usually tuning, not wiring. */
        SHORTFALL
    }

    public record Finding(Kind kind, String signal, String detail, List<String> suggestions) {
        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("  [").append(kind).append("] ").append(signal).append('\n');
            sb.append("      ").append(detail).append('\n');
            for (String s : suggestions) {
                sb.append("      -> ").append(s).append('\n');
            }
            return sb.toString();
        }
    }

    /** No findings — the outcome for a turn that never got as far as checking robot behaviour. */
    public static Diagnosis empty() {
        return new Diagnosis(List.of());
    }

    public boolean isEmpty() {
        return findings.isEmpty();
    }

    public String render() {
        if (findings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("diagnosis:\n");
        for (Finding f : findings) {
            sb.append(f.render());
        }
        return sb.toString();
    }

    /**
     * Explain every failed assertion in a result.
     *
     * <p>Several assertions on one signal fail together as a rule — "scores non-zero" and "meets the
     * target" are the same defect seen twice — so findings are deduplicated per signal and kind. The
     * agent gets one statement of each distinct problem rather than one per failed check.
     *
     * @param scenario the scenario that was checked (assertions align with {@code result}'s checks)
     * @param result the verification outcome
     * @param run the log that was produced
     */
    public static Diagnosis of(Scenario scenario, Verifier.LoopResult result, WpilogReader run) {
        List<Finding> findings = new ArrayList<>();
        Map<String, WpilogReader.NumericSummary> summaries = run.numericSummaries();
        Map<String, String> typesByName = signalTypes(run);

        List<Assertion> assertions = scenario.assertions();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < assertions.size() && i < result.results().size(); i++) {
            if (result.results().get(i).passed()) {
                continue;
            }
            Finding finding = explain(assertions.get(i), result.results().get(i), summaries, typesByName);
            if (seen.add(finding.kind() + "|" + finding.signal())) {
                findings.add(finding);
            }
        }
        return new Diagnosis(findings);
    }

    private static Finding explain(
            Assertion assertion,
            Assertion.Result result,
            Map<String, WpilogReader.NumericSummary> summaries,
            Map<String, String> typesByName) {
        String signal = assertion.signal();
        WpilogReader.NumericSummary s = summaries.get(signal);

        if (s == null || s.count() == 0) {
            String loggedType = typesByName.get(signal);
            if (loggedType != null) {
                // The key is in the log, but carries nothing an assertion can compare numerically.
                return new Finding(
                        Kind.SIGNAL_ABSENT,
                        signal,
                        "logged as type '" + loggedType + "', which yields no numeric value",
                        List.of(
                                "assert against a numeric signal, or log this value as a"
                                        + " double/int64/boolean"));
            }
            List<String> similar = similarNames(signal, typesByName.keySet());
            List<String> suggestions = new ArrayList<>();
            if (similar.isEmpty()) {
                suggestions.add(
                        "no similarly-named signal in the log — check that this value is being logged at all");
            } else {
                suggestions.add("did the log key change? closest names present: " + String.join(", ", similar));
            }
            return new Finding(
                    Kind.SIGNAL_ABSENT, signal, "no samples anywhere in the log", suggestions);
        }

        if (result.sampleCount() == 0) {
            // The signal exists, so the assertion's phase window is what excluded everything.
            return new Finding(
                    Kind.NO_SAMPLES_IN_PHASE,
                    signal,
                    String.format(
                            "%d samples in the log, but none while %s == %s",
                            s.count(), assertion.phaseSignal(), assertion.phaseEquals()),
                    List.of(
                            "the phase never occurred, or is spelled differently — verify "
                                    + assertion.phaseSignal() + " actually takes the value '"
                                    + assertion.phaseEquals() + "'"));
        }

        if (s.constant()) {
            return new Finding(
                    Kind.SIGNAL_CONSTANT,
                    signal,
                    String.format(
                            "held constant at %.4f across all %d samples in the log; check wanted %s %s %.4f",
                            s.last(), s.count(), assertion.aggregation(), assertion.op().symbol,
                            assertion.threshold()),
                    List.of(
                            "the value never moved — the code path that changes it likely never"
                                    + " executed (unscheduled command, unmet trigger, or an action"
                                    + " routed somewhere else)"));
        }

        double gap = Math.abs(assertion.threshold() - result.actual());
        return new Finding(
                Kind.SHORTFALL,
                signal,
                String.format(
                        "reached %s=%.4f, short of %.4f by %.4f (ranged %.4f..%.4f over %d samples in scope)",
                        assertion.aggregation(), result.actual(), assertion.threshold(), gap,
                        s.min(), s.max(), result.sampleCount()),
                List.of(
                        "the mechanism ran but fell short — this is usually gains, timing, or a"
                                + " window that ends too early, not wiring"));
    }

    /** Every signal name in the log with its declared type (a missing key may be a string signal). */
    private static Map<String, String> signalTypes(WpilogReader run) {
        Map<String, String> types = new java.util.LinkedHashMap<>();
        run.index().values().forEach(e -> types.putIfAbsent(e.name, e.type));
        return types;
    }

    /**
     * Candidate names for a signal that is absent, ranked by shared trailing path segment then by
     * shared prefix — the shape of a rename ({@code /Autonomous/BallsScored} to
     * {@code /Auto/BallsScored}) keeps the leaf and changes the prefix.
     */
    static List<String> similarNames(String missing, java.util.Collection<String> present) {
        String leaf = leafOf(missing).toLowerCase();
        return present.stream()
                .filter(name -> !name.equals(missing))
                .map(name -> Map.entry(name, similarity(missing, leaf, name)))
                .filter(e -> e.getValue() > 0)
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static int similarity(String missing, String missingLeaf, String candidate) {
        String candidateLeaf = leafOf(candidate).toLowerCase();
        if (candidateLeaf.equals(missingLeaf)) {
            return 100;
        }
        if (candidateLeaf.contains(missingLeaf) || missingLeaf.contains(candidateLeaf)) {
            return 50;
        }
        // Fall back to shared prefix length, which catches a renamed leaf under the same subsystem.
        int shared = 0;
        int limit = Math.min(missing.length(), candidate.length());
        while (shared < limit && missing.charAt(shared) == candidate.charAt(shared)) {
            shared++;
        }
        return shared > 2 ? shared : 0;
    }

    private static String leafOf(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }
}
