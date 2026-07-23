package org.mercsmavs.frccopilot.simreplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A standing library of scenarios re-checked on every change. Every verified fix should add a
 * scenario here so regressions are caught automatically — the value compounds across a season.
 */
public final class RegressionSuite {

    public static List<Scenario> load(Path dir) throws IOException {
        List<Scenario> scenarios = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return scenarios;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted().toList()) {
                scenarios.add(Scenario.load(f));
            }
        }
        return scenarios;
    }

    public record SuiteResult(List<Verifier.LoopResult> results) {
        public boolean allPassed() {
            return results.stream().allMatch(Verifier.LoopResult::allPassed);
        }

        public String render() {
            StringBuilder sb = new StringBuilder();
            int passed = 0;
            for (Verifier.LoopResult r : results) {
                sb.append(r.render());
                if (r.allPassed()) {
                    passed++;
                }
            }
            sb.append(String.format("%n%d/%d scenarios passed%n", passed, results.size()));
            return sb.toString();
        }
    }

    /** Run every scenario against a single signal source (e.g. one produced log). */
    public static SuiteResult runAll(List<Scenario> scenarios, SignalSource source) {
        List<Verifier.LoopResult> results = new ArrayList<>();
        for (Scenario s : scenarios) {
            results.add(Verifier.verify(s, source));
        }
        return new SuiteResult(results);
    }

    private RegressionSuite() {}
}
