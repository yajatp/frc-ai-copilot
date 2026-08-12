package org.mercsmavs.frccopilot.simreplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

/**
 * One turn of the agentic loop: build the robot code, run it headless, verify the log it produced,
 * diagnose whatever failed, and record the attempt.
 *
 * <p>This is the step the harness was missing. {@code Verifier} could already answer "does this log
 * satisfy these criteria", but an agent iterating on robot code needs the whole turn behind a single
 * call — otherwise it has to orchestrate four tools, remember the previous run's numbers, and decide
 * for itself whether a build failure, a missing log, or a failed assertion is what it is looking at.
 * Those are different outcomes with different next actions, so each is reported distinctly:
 *
 * <ul>
 *   <li>build failed — the edit does not compile; the compiler output is the finding
 *   <li>run failed or produced no log — the robot code crashed or never logged
 *   <li>assertions failed — the robot ran and misbehaved; a {@link Diagnosis} says how
 *   <li>everything passed — bank a scenario and move on
 * </ul>
 */
public final class LoopRunner {

    /** What happened in one turn, in the order an agent needs to read it. */
    public record IterationReport(
            int number,
            Outcome outcome,
            int exitCode,
            Optional<Path> log,
            List<Verifier.LoopResult> results,
            Diagnosis diagnosis,
            Optional<LogDiff.Result> baselineDiff,
            List<String> changedFiles,
            List<String> deltas,
            String message,
            String outputTail) {

        public boolean passed() {
            return outcome == Outcome.PASSED;
        }

        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("iteration #").append(number).append(": ").append(outcome).append('\n');
            if (!changedFiles.isEmpty()) {
                sb.append("changed since last iteration: ").append(String.join(", ", changedFiles)).append('\n');
            }
            log.ifPresent(p -> sb.append("log: ").append(p).append('\n'));
            if (message != null) {
                sb.append(message).append('\n');
            }
            if (outcome == Outcome.BUILD_FAILED || outcome == Outcome.RUN_FAILED) {
                sb.append("--- output tail ---\n").append(outputTail).append('\n');
            }
            for (Verifier.LoopResult r : results) {
                sb.append(r.render());
            }
            sb.append(diagnosis.render());
            baselineDiff.ifPresent(d -> sb.append("baseline comparison:\n").append(d.render(8)));
            if (!deltas.isEmpty()) {
                sb.append("since last iteration:\n");
                for (String d : deltas) {
                    sb.append("  ").append(d).append('\n');
                }
            }
            return sb.toString();
        }
    }

    public enum Outcome {
        PASSED,
        CHECKS_FAILED,
        BUILD_FAILED,
        RUN_FAILED,
        NO_LOG,
        NO_SCENARIOS,
        /** A replay config's input log is missing — a setup problem, not a robot-code problem. */
        NO_INPUT_LOG
    }

    /**
     * Run one full turn against a project's {@code loop.yaml}.
     *
     * @param config the project's loop declaration
     * @param scenarioOverride a single scenario file to check, or {@code null} for the whole suite
     */
    public static IterationReport iterate(LoopConfig config, Path scenarioOverride) throws Exception {
        return iterate(config, scenarioOverride, null);
    }

    /**
     * Run one full turn, optionally replaying a specific input log.
     *
     * @param inputLogOverride the log to replay this turn, substituted for {@code {inputLog}} in the
     *     run command; {@code null} uses the config's own {@code inputLog}. Overriding it is how one
     *     replay config is pointed at each of a season's match logs in turn.
     */
    public static IterationReport iterate(
            LoopConfig config, Path scenarioOverride, Path inputLogOverride) throws Exception {
        Path sessionFile = config.sessionFile();
        LoopSession session = LoopSession.load(sessionFile);
        session.project = config.name;
        Map<String, String> fingerprint =
                LoopSession.fingerprintSources(config.sourcePaths(), config.baseDir());
        int number = session.iterations.size() + 1;

        List<Scenario> scenarios = loadScenarios(config, scenarioOverride);
        if (scenarios.isEmpty()) {
            return finish(session, sessionFile, fingerprint, number, Outcome.NO_SCENARIOS, 0,
                    Optional.empty(), List.of(), Diagnosis.empty(), Optional.empty(), scenarios,
                    "No scenarios found — nothing to verify. Generate one from a known-good run"
                            + " (`loop generate`) or write one by hand.",
                    "");
        }

        // A replay turn must fail loudly if the log it is meant to replay is not there, and before
        // spending a build on it. Otherwise the robot process fails to open the log and reports as a
        // generic RUN_FAILED, which points the agent at its own code instead of at the missing input.
        Path inputLog = inputLogOverride != null ? inputLogOverride : config.inputLogPath();
        if (config.consumesInputLog() && (inputLog == null || !Files.isRegularFile(inputLog))) {
            return finish(session, sessionFile, fingerprint, number, Outcome.NO_INPUT_LOG, 0,
                    Optional.empty(), List.of(), Diagnosis.empty(), Optional.empty(), scenarios,
                    "This is a replay config (its run command uses " + LoopConfig.INPUT_LOG_PLACEHOLDER
                            + ") but the log to replay is missing: "
                            + (inputLog == null ? "none given" : inputLog.toString())
                            + ". Nothing was built or run.",
                    "");
        }

        // 1. Build. A failing build is the most common outcome mid-iteration and the cheapest to
        //    report precisely, so it short-circuits before anything touches the simulator.
        if (config.build != null && !config.build.isEmpty()) {
            SimRunner.ExecResult build = SimRunner.exec(
                    config.workDirPath(), config.build, config.timeoutSeconds, 60, config.resolvedEnv());
            if (!build.ok()) {
                String why = build.timedOut()
                        ? "Build timed out after " + config.timeoutSeconds + "s."
                        : "Build failed (exit " + build.exitCode() + "). The code does not compile;"
                                + " fix that before reading any robot behaviour into this.";
                return finish(session, sessionFile, fingerprint, number, Outcome.BUILD_FAILED,
                        build.exitCode(), Optional.empty(), List.of(), Diagnosis.empty(), Optional.empty(), scenarios,
                        why, build.tail());
            }
        }

        // 2. Run.
        Path logDir = config.logDirPath();
        Files.createDirectories(logDir);
        Path expectedLog = logDir.resolve(String.format("iteration-%03d.wpilog", number));
        if (config.producesLogPath()) {
            Files.deleteIfExists(expectedLog); // a stale file would be mistaken for this run's output
        }
        List<String> command = config.runCommand(expectedLog, inputLog);
        SimRunner.RunResult run = SimRunner.run(
                config.workDirPath(), command, logDir, config.timeoutSeconds, config.resolvedEnv());

        Optional<Path> log = config.producesLogPath() && Files.isRegularFile(expectedLog)
                ? Optional.of(expectedLog)
                : run.log();

        if (run.exitCode() != 0 && log.isEmpty()) {
            String why = run.exitCode() == SimRunner.TIMEOUT_EXIT
                    ? "Run timed out after " + config.timeoutSeconds + "s and produced no log."
                    : "Run failed (exit " + run.exitCode() + ") and produced no log.";
            return finish(session, sessionFile, fingerprint, number, Outcome.RUN_FAILED,
                    run.exitCode(), Optional.empty(), List.of(), Diagnosis.empty(), Optional.empty(), scenarios, why, run.tail());
        }
        if (log.isEmpty()) {
            return finish(session, sessionFile, fingerprint, number, Outcome.NO_LOG, run.exitCode(),
                    Optional.empty(), List.of(), Diagnosis.empty(), Optional.empty(), scenarios,
                    "The command succeeded but wrote no .wpilog under " + logDir
                            + ". Check that data logging is started, or point 'logDir' at where the"
                            + " log actually lands.",
                    run.tail());
        }

        // 3. Verify, and 4. diagnose whatever failed.
        WpilogReader reader = new WpilogReader(log.get().toString());
        List<Verifier.LoopResult> results = new ArrayList<>();
        List<Diagnosis.Finding> findings = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        boolean all = true;
        for (Scenario scenario : scenarios) {
            Verifier.LoopResult result = Verifier.verify(scenario, reader::read);
            results.add(result);
            all &= result.allPassed();
            if (result.allPassed()) {
                continue;
            }
            // Scenarios overlap on the same signals by design, so merge their findings rather than
            // repeating one defect once per scenario that noticed it.
            for (Diagnosis.Finding f : Diagnosis.of(scenario, result, reader).findings()) {
                if (seen.add(f.kind() + "|" + f.signal())) {
                    findings.add(f);
                }
            }
        }

        // The baseline diff describes the run, not any one scenario — compute it once.
        Optional<LogDiff.Result> baselineDiff = Optional.empty();
        if (!all) {
            WpilogReader baseline = openBaseline(config, log.get());
            if (baseline != null) {
                baselineDiff = Optional.of(LogDiff.compare(baseline, reader));
            }
        }

        return finish(session, sessionFile, fingerprint, number,
                all ? Outcome.PASSED : Outcome.CHECKS_FAILED, run.exitCode(), log, results,
                new Diagnosis(findings), baselineDiff, scenarios, null, run.tail());
    }

    /**
     * Open the configured baseline, unless it is the log we just produced — diffing a run against
     * itself would report no divergence and quietly waste the most useful diagnostic there is.
     */
    private static WpilogReader openBaseline(LoopConfig config, Path produced) {
        Path baseline = config.baselinePath();
        if (baseline == null || !Files.isRegularFile(baseline)) {
            return null;
        }
        try {
            if (Files.isSameFile(baseline, produced)) {
                return null;
            }
            return new WpilogReader(baseline.toString());
        } catch (IOException e) {
            return null; // an unreadable baseline degrades the diagnosis; it must not fail the loop
        }
    }

    private static List<Scenario> loadScenarios(LoopConfig config, Path override) throws IOException {
        if (override != null) {
            return List.of(Scenario.load(override));
        }
        Path dir = config.scenarioDirPath();
        return dir == null ? List.of() : RegressionSuite.load(dir);
    }

    /** Record the attempt in the journal and assemble the report. */
    private static IterationReport finish(
            LoopSession session,
            Path sessionFile,
            Map<String, String> fingerprint,
            int number,
            Outcome outcome,
            int exitCode,
            Optional<Path> log,
            List<Verifier.LoopResult> results,
            Diagnosis diagnosis,
            Optional<LogDiff.Result> baselineDiff,
            List<Scenario> scenarios,
            String message,
            String outputTail)
            throws IOException {
        // Track every check in the suite, not just the scenario being worked on — a fix commonly
        // moves a value in a scenario nobody was looking at, and that is exactly what wants noticing.
        List<LoopSession.Check> checks = new ArrayList<>();
        for (int i = 0; i < results.size() && i < scenarios.size(); i++) {
            checks.addAll(LoopSession.Check.of(scenarios.get(i), results.get(i)));
        }
        String scenarioLabel = scenarios.isEmpty()
                ? null
                : scenarios.size() == 1
                        ? scenarios.get(0).name()
                        : scenarios.size() + " scenarios";

        LoopSession.Iteration recorded = session.record(
                scenarioLabel, outcome == Outcome.PASSED, exitCode,
                log.map(Path::toString).orElse(null), checks, fingerprint,
                outcome == Outcome.PASSED || outcome == Outcome.CHECKS_FAILED ? null : outcome.name());
        session.save(sessionFile);

        return new IterationReport(number, outcome, exitCode, log, results, diagnosis, baselineDiff,
                recorded.changedFiles, recorded.deltas, message, outputTail);
    }

    private LoopRunner() {}
}
