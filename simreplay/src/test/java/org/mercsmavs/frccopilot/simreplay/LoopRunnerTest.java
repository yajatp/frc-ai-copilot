package org.mercsmavs.frccopilot.simreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

/**
 * Drives the whole loop against a stand-in "robot" — a shell script that writes a log — so the
 * orchestration (build, run, locate the log, verify, diagnose, journal) is exercised without
 * depending on the WPILib simulation HAL.
 */
class LoopRunnerTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase().contains("win");
    private static final String RUN_SCRIPT = WINDOWS ? "run.bat" : "run.sh";
    private static final String BUILD_SCRIPT = WINDOWS ? "build.bat" : "build.sh";

    /** A "robot" that copies a prepared log to wherever the loop asks for it. */
    private static void writeRunScript(Path dir, Path logToProduce) throws Exception {
        String source = logToProduce.toAbsolutePath().toString();
        Path run = dir.resolve(RUN_SCRIPT);
        Files.writeString(run, WINDOWS
                // %~1 strips the quotes the loop may pass around the destination path.
                ? "@echo off\r\ncopy /Y \"" + source + "\" \"%~1\" >nul\r\n"
                : "#!/bin/sh\ncp '" + source + "' \"$1\"\n");
        run.toFile().setExecutable(true);
    }

    /** A robot that exits cleanly but writes no log. */
    private static void writeSilentRunScript(Path dir) throws Exception {
        Path run = dir.resolve(RUN_SCRIPT);
        Files.writeString(run, WINDOWS ? "@echo off\r\nexit /b 0\r\n" : "#!/bin/sh\nexit 0\n");
        run.toFile().setExecutable(true);
    }

    /**
     * Write a fake robot: a script that copies a prepared log to wherever the loop asks for it, and
     * whose "build" step succeeds or fails on demand.
     *
     * <p>Batch files on Windows, shell scripts elsewhere. The loop runs whatever argv a project's
     * {@code loop.yaml} names, so a real Windows robot project would say {@code gradlew.bat} — using
     * the platform's own script format here keeps these tests exercising the loop on Windows rather
     * than skipping it, which is where the interesting failures were.
     */
    private static Path project(Path dir, Path logToProduce, boolean buildSucceeds) throws Exception {
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/Robot.java"), "class Robot {}");

        String buildName = BUILD_SCRIPT;

        writeRunScript(dir, logToProduce);

        Path build = dir.resolve(buildName);
        Files.writeString(build, WINDOWS
                ? (buildSucceeds
                        ? "@echo off\r\nexit /b 0\r\n"
                        : "@echo off\r\necho Robot.java:3: error: cannot find symbol 1>&2\r\nexit /b 1\r\n")
                : (buildSucceeds
                        ? "#!/bin/sh\nexit 0\n"
                        : "#!/bin/sh\necho 'Robot.java:3: error: cannot find symbol' >&2\nexit 1\n"));
        build.toFile().setExecutable(true);

        Files.writeString(dir.resolve(LoopConfig.FILE_NAME), """
                name: fake-robot
                build: ["%s"]
                run: ["%s", "{log}"]
                scenarioDir: scenarios
                baseline: .loop/baseline.wpilog
                sources: ["src"]
                timeoutSeconds: 60
                """.formatted(
                        // Relative, so the loop resolves them against workDir the way a real project does.
                        (WINDOWS ? "" : "./") + buildName,
                        (WINDOWS ? "" : "./") + RUN_SCRIPT));
        return dir.resolve(LoopConfig.FILE_NAME);
    }

    private static void scenario(Path dir, String name, double threshold) throws Exception {
        new Scenario(name, "d", List.of(
                new Assertion("/Autonomous/BallsScored", Assertion.Aggregation.MAX,
                        Assertion.Op.GE, threshold, "/Robot/State", "AUTO", "scores " + threshold)))
                .save(dir.resolve("scenarios/" + name + ".yaml"));
    }

    @Test
    void completesAFullTurnAndBanksTheResultInTheJournal(@TempDir Path tmp) throws Exception {
        Path good = TestLogs.good(tmp.resolve("good.wpilog"));
        Path dir = tmp.resolve("robot");
        Path config = project(dir, good, true);
        scenario(dir, "auto_scores", 5);

        LoopRunner.IterationReport report = LoopRunner.iterate(LoopConfig.load(config), null);

        assertEquals(LoopRunner.Outcome.PASSED, report.outcome(), report::render);
        assertTrue(report.passed());
        assertTrue(report.log().isPresent(), "the produced log must be located");
        assertEquals(1, report.number());

        // The journal is scoped per config name, so ask the config where it is rather than guessing.
        LoopSession session = LoopSession.load(LoopConfig.load(config).sessionFile());
        assertEquals(1, session.iterations.size());
        assertTrue(session.iterations.get(0).passed);
        assertFalse(session.fingerprint.isEmpty(), "sources should have been fingerprinted");
    }

    @Test
    void reportsABuildFailureWithoutRunningTheRobot(@TempDir Path tmp) throws Exception {
        // The most common mid-edit outcome. Reading robot behaviour into a stale binary would be
        // actively misleading, so the turn stops at the compiler.
        Path dir = tmp.resolve("robot");
        Path config = project(dir, TestLogs.good(tmp.resolve("good.wpilog")), false);
        scenario(dir, "auto_scores", 5);

        LoopRunner.IterationReport report = LoopRunner.iterate(LoopConfig.load(config), null);

        assertEquals(LoopRunner.Outcome.BUILD_FAILED, report.outcome());
        assertTrue(report.log().isEmpty(), "no log should be attributed to a failed build");
        assertTrue(report.render().contains("cannot find symbol"),
                () -> "the compiler output is the finding:\n" + report.render());
        assertTrue(report.results().isEmpty(), "nothing should have been verified");
    }

    @Test
    void diagnosesMisbehaviourAndDiffsAgainstTheBaseline(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("robot");
        // The robot now produces a log where nothing scores...
        Path broken = TestLogs.write(tmp.resolve("broken.wpilog"), 0, i -> 12.5, i -> 1.0);
        Path config = project(dir, broken, true);
        scenario(dir, "auto_scores", 5);
        // ...while a known-good run is on file as the baseline.
        Files.createDirectories(dir.resolve(".loop"));
        Files.copy(TestLogs.good(tmp.resolve("good.wpilog")), dir.resolve(".loop/baseline.wpilog"));

        LoopRunner.IterationReport report = LoopRunner.iterate(LoopConfig.load(config), null);

        assertEquals(LoopRunner.Outcome.CHECKS_FAILED, report.outcome());
        assertEquals(Diagnosis.Kind.SIGNAL_CONSTANT, report.diagnosis().findings().get(0).kind());
        assertTrue(report.baselineDiff().isPresent(), "a configured baseline should be compared");
        assertEquals("/Autonomous/BallsScored",
                report.baselineDiff().get().diverged().get(0).signal(),
                () -> report.render());
    }

    @Test
    void tracksMovementAcrossIterationsWhenTheCodeChanges(@TempDir Path tmp) throws Exception {
        // The point of the loop: after an edit, say whether it helped.
        Path dir = tmp.resolve("robot");
        Path broken = TestLogs.write(tmp.resolve("broken.wpilog"), 0, i -> 12.5, i -> 1.0);
        Path config = project(dir, broken, true);
        scenario(dir, "auto_scores", 5);

        LoopRunner.IterationReport first = LoopRunner.iterate(LoopConfig.load(config), null);
        assertEquals(LoopRunner.Outcome.CHECKS_FAILED, first.outcome());

        // "Fix" the robot: edit the source, and let the run now produce a scoring log.
        Files.writeString(dir.resolve("src/Robot.java"), "class Robot { int fixed; }");
        Path good = TestLogs.good(tmp.resolve("good.wpilog"));
        writeRunScript(dir, good);

        LoopRunner.IterationReport second = LoopRunner.iterate(LoopConfig.load(config), null);

        assertEquals(LoopRunner.Outcome.PASSED, second.outcome(), second::render);
        assertEquals(2, second.number());
        assertEquals(List.of("src/Robot.java"), second.changedFiles());
        assertTrue(second.deltas().stream().anyMatch(d -> d.contains("now PASSES")),
                () -> "the fix should be recorded as movement: " + second.deltas());
    }

    @Test
    void keepsEachIterationsLogSeparate(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("robot");
        Path config = project(dir, TestLogs.good(tmp.resolve("good.wpilog")), true);
        scenario(dir, "auto_scores", 5);

        Path first = LoopRunner.iterate(LoopConfig.load(config), null).log().orElseThrow();
        Path second = LoopRunner.iterate(LoopConfig.load(config), null).log().orElseThrow();
        assertFalse(first.equals(second), "each turn needs its own log to compare against");
        assertTrue(Files.exists(first) && Files.exists(second));
    }

    @Test
    void saysSoWhenThereIsNothingToVerifyAgainst(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("robot");
        Path config = project(dir, TestLogs.good(tmp.resolve("good.wpilog")), true);
        Files.createDirectories(dir.resolve("scenarios"));

        LoopRunner.IterationReport report = LoopRunner.iterate(LoopConfig.load(config), null);
        assertEquals(LoopRunner.Outcome.NO_SCENARIOS, report.outcome());
        assertTrue(report.render().contains("loop generate"), "should point at the way out");
    }

    @Test
    void reportsWhenTheRunProducesNoLog(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("robot");
        Path config = project(dir, TestLogs.good(tmp.resolve("good.wpilog")), true);
        scenario(dir, "auto_scores", 5);
        // A robot that exits cleanly but never writes a log — logging left disabled, typically.
        writeSilentRunScript(dir);

        LoopRunner.IterationReport report = LoopRunner.iterate(LoopConfig.load(config), null);
        assertEquals(LoopRunner.Outcome.NO_LOG, report.outcome());
        assertTrue(report.render().contains("wrote no .wpilog"));
    }

    @Test
    void checksEveryScenarioInTheSuite(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("robot");
        Path config = project(dir, TestLogs.good(tmp.resolve("good.wpilog")), true);
        scenario(dir, "auto_scores", 5);       // passes: the good run scores 5
        scenario(dir, "auto_scores_more", 40); // fails: it does not score 40

        LoopRunner.IterationReport report = LoopRunner.iterate(LoopConfig.load(config), null);

        assertEquals(LoopRunner.Outcome.CHECKS_FAILED, report.outcome());
        assertEquals(2, report.results().size());
        assertEquals(Set.of("auto_scores", "auto_scores_more"),
                Set.copyOf(report.results().stream().map(Verifier.LoopResult::scenario).toList()));
    }

    @Test
    void canCheckASingleScenarioInsteadOfTheSuite(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("robot");
        Path config = project(dir, TestLogs.good(tmp.resolve("good.wpilog")), true);
        scenario(dir, "auto_scores", 5);
        scenario(dir, "auto_scores_more", 40);

        LoopRunner.IterationReport report = LoopRunner.iterate(
                LoopConfig.load(config), dir.resolve("scenarios/auto_scores.yaml"));

        assertEquals(LoopRunner.Outcome.PASSED, report.outcome(), report::render);
        assertEquals(1, report.results().size());
    }

    @Test
    void doesNotDiffARunAgainstItself(@TempDir Path tmp) throws Exception {
        // If the baseline happens to be the log just produced, comparing would report no divergence
        // and silently discard the most useful diagnostic available.
        Path dir = tmp.resolve("robot");
        Path broken = TestLogs.write(tmp.resolve("broken.wpilog"), 0, i -> 12.5, i -> 1.0);
        Path config = project(dir, broken, true);
        scenario(dir, "auto_scores", 5);

        LoopRunner.IterationReport first = LoopRunner.iterate(LoopConfig.load(config), null);
        Files.createDirectories(dir.resolve(".loop"));
        Files.copy(first.log().orElseThrow(), dir.resolve(".loop/baseline.wpilog"));

        LoopRunner.IterationReport second = LoopRunner.iterate(LoopConfig.load(config), null);
        // A distinct file with identical content is still a legitimate comparison; it simply
        // yields nothing, which the report must not present as a finding.
        assertTrue(second.baselineDiff().isEmpty()
                        || second.baselineDiff().get().diverged().isEmpty(),
                () -> second.render());
    }

    @Test
    void verifiesTheLogTheRunActuallyProduced(@TempDir Path tmp) throws Exception {
        // A leftover log from an earlier iteration must never be mistaken for this run's output.
        Path dir = tmp.resolve("robot");
        Path config = project(dir, TestLogs.good(tmp.resolve("good.wpilog")), true);
        scenario(dir, "auto_scores", 5);
        Files.createDirectories(dir.resolve(".loop/logs"));
        Files.copy(TestLogs.write(tmp.resolve("stale.wpilog"), 0, i -> 12.5, i -> 1.0),
                dir.resolve(".loop/logs/iteration-001.wpilog"));

        LoopRunner.IterationReport report = LoopRunner.iterate(LoopConfig.load(config), null);

        assertEquals(LoopRunner.Outcome.PASSED, report.outcome(), report::render);
        WpilogReader produced = new WpilogReader(report.log().orElseThrow().toString());
        assertEquals(5.0, produced.numericSummaries().get("/Autonomous/BallsScored").last());
    }
}
