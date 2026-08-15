package org.mercsmavs.frccopilot.simreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The replay side of a loop config: {@code {inputLog}} substitution and its failure modes. */
class ReplayConfigTest {

    private static Path write(Path dir, String yaml) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(LoopConfig.FILE_NAME);
        Files.writeString(file, yaml);
        return file;
    }

    private static final String REPLAY = """
            name: demo-replay
            run: ["./replay", "{inputLog}", "{log}"]
            scenarioDir: scenarios
            inputLog: logs/match10.wpilog
            """;

    @Test
    void substitutesBothTheInputAndOutputLogs(@TempDir Path tmp) throws Exception {
        LoopConfig config = LoopConfig.load(write(tmp.resolve("robot"), REPLAY));
        assertTrue(config.consumesInputLog());
        assertTrue(config.producesLogPath(), "a replay pass still writes an output log");

        // Both substituted paths are absolutized, so build the expectations the same way — a
        // hardcoded POSIX path fails on Windows for reasons that have nothing to do with replay.
        Path out = Path.of("/logs/iteration-007.wpilog");
        List<String> argv = config.runCommand(out);
        assertEquals(
                List.of("./replay",
                        tmp.resolve("robot/logs/match10.wpilog").toAbsolutePath().toString(),
                        out.toAbsolutePath().toString()),
                argv);
    }

    @Test
    void aPerTurnInputLogOverridesTheConfiguredOne(@TempDir Path tmp) throws Exception {
        // This is what lets one config replay a season of matches instead of the one it names.
        LoopConfig config = LoopConfig.load(write(tmp.resolve("robot"), REPLAY));
        Path out = Path.of("/logs/out.wpilog");
        Path match = Path.of("/matches/qm42.wpilog");
        List<String> argv = config.runCommand(out, match);
        assertEquals(
                List.of("./replay", match.toAbsolutePath().toString(), out.toAbsolutePath().toString()),
                argv);
    }

    @Test
    void aSimConfigIsNotAReplayConfig(@TempDir Path tmp) throws Exception {
        LoopConfig config = LoopConfig.load(write(tmp.resolve("robot"), """
                name: demo
                run: ["./sim", "{log}"]
                scenarioDir: scenarios
                """));
        assertFalse(config.consumesInputLog());
        assertNull(config.inputLogPath());
    }

    @Test
    void rejectsAReplayConfigWithNoLogToReplay(@TempDir Path tmp) throws Exception {
        // Unchecked, the literal "{inputLog}" reaches the robot process, which fails to open it and
        // looks like a broken replay rather than a misconfigured one.
        Path file = write(tmp.resolve("robot"), """
                name: demo-replay
                run: ["./replay", "{inputLog}", "{log}"]
                scenarioDir: scenarios
                """);
        IOException e = assertThrows(IOException.class, () -> LoopConfig.load(file));
        assertTrue(e.getMessage().contains("inputLog"), e.getMessage());
    }

    @Test
    void reportsNoInputLogWhenTheLogToReplayIsMissing(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("robot");
        Path config = write(root, REPLAY); // inputLog names a file that does not exist
        Files.createDirectories(root.resolve("scenarios"));
        Scenario.load(writeScenario(root.resolve("scenarios/s.yaml")));

        LoopRunner.IterationReport report = LoopRunner.iterate(LoopConfig.load(config), null);
        assertEquals(LoopRunner.Outcome.NO_INPUT_LOG, report.outcome());
        assertFalse(report.passed());
        assertTrue(report.message().contains("match10.wpilog"), report.message());
        // A missing input is a setup problem, so no build should have been spent on it.
        assertTrue(report.message().contains("Nothing was built or run"), report.message());
    }

    @Test
    void aSimAndAReplayConfigDoNotShareOneIterationJournal(@TempDir Path tmp) throws Exception {
        // Their deltas ("how every checked value moved since the last iteration") are only
        // meaningful between comparable runs, so interleaving them would present a sim-vs-replay
        // diff of two different things as progress.
        Path root = tmp.resolve("robot");
        LoopConfig sim = LoopConfig.load(write(root, """
                name: demo-sim
                run: ["./sim", "{log}"]
                scenarioDir: scenarios
                """));
        Path replayFile = root.resolve("replay.yaml");
        Files.writeString(replayFile, REPLAY);
        LoopConfig replay = LoopConfig.load(replayFile);

        assertNotEquals(sim.sessionFile(), replay.sessionFile());
        assertEquals(sim.loopStateDir(), replay.loopStateDir(), "but they share the state directory");
    }

    @Test
    void anUnnamedConfigKeepsTheLegacyJournalName(@TempDir Path tmp) throws Exception {
        LoopConfig config = LoopConfig.load(write(tmp.resolve("robot"), """
                run: ["./sim", "{log}"]
                scenarioDir: scenarios
                """));
        assertEquals("session.json", config.sessionFile().getFileName().toString());
    }

    private static Path writeScenario(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                name: s
                assertions:
                - signal: /Autonomous/BallsScored
                  aggregation: MAX
                  op: GE
                  threshold: 5.0
                """);
        return file;
    }
}
