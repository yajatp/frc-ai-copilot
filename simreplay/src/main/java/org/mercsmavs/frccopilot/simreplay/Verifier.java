package org.mercsmavs.frccopilot.simreplay;

import java.util.ArrayList;
import java.util.List;

/** Runs a {@link Scenario}'s assertions against a signal source and reports pass/fail per check. */
public final class Verifier {

    public record LoopResult(String scenario, boolean allPassed, List<Assertion.Result> results) {
        public long failed() {
            return results.stream().filter(r -> !r.passed()).count();
        }

        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append(allPassed ? "PASS " : "FAIL ").append(scenario).append('\n');
            for (Assertion.Result r : results) {
                sb.append("  ").append(r.message()).append('\n');
            }
            if (!allPassed) {
                sb.append("  => ").append(failed()).append(" of ").append(results.size())
                        .append(" checks failed\n");
            }
            return sb.toString();
        }
    }

    public static LoopResult verify(Scenario scenario, SignalSource source) {
        List<Assertion.Result> results = new ArrayList<>();
        boolean all = true;
        for (Assertion a : scenario.assertions()) {
            Assertion.Result r = a.evaluate(source);
            results.add(r);
            all &= r.passed();
        }
        return new LoopResult(scenario.name(), all, results);
    }

    private Verifier() {}
}
