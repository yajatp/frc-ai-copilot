package org.mercsmavs.frccopilot.simreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoopSessionTest {

    private static final Scenario SCENARIO = new Scenario("auto", "d", List.of(
            new Assertion("/Autonomous/BallsScored", Assertion.Aggregation.MAX,
                    Assertion.Op.GE, 5, null, null, "scores 5")));

    /** A verification outcome with a chosen measured value, without needing a log. */
    private static List<LoopSession.Check> checks(double actual, boolean passed) {
        return LoopSession.Check.of(SCENARIO, new Verifier.LoopResult("auto", passed,
                List.of(new Assertion.Result(passed, actual, 75, "msg"))));
    }

    @Test
    void reportsHowEachValueMovedBetweenIterations(@TempDir Path tmp) throws Exception {
        LoopSession session = new LoopSession();
        session.record("auto", false, 0, "a.wpilog", checks(0, false), Map.of("Sub.java", "aaa"), null);
        LoopSession.Iteration second = session.record(
                "auto", true, 0, "b.wpilog", checks(5, true), Map.of("Sub.java", "bbb"), null);

        assertEquals(1, second.deltas.size());
        assertTrue(second.deltas.get(0).contains("0.0000 -> 5.0000"), second.deltas.get(0));
        assertTrue(second.deltas.get(0).contains("now PASSES"), second.deltas.get(0));
    }

    @Test
    void attributesTheChangeToTheFilesThatWereEdited(@TempDir Path tmp) throws Exception {
        LoopSession session = new LoopSession();
        session.record("auto", false, 0, "a.wpilog", checks(0, false),
                Map.of("Scoring.java", "aaa", "Drive.java", "ddd"), null);
        LoopSession.Iteration second = session.record("auto", true, 0, "b.wpilog", checks(5, true),
                Map.of("Scoring.java", "zzz", "Drive.java", "ddd"), null);

        assertEquals(List.of("Scoring.java"), second.changedFiles,
                "only the edited file should be listed");
    }

    @Test
    void reportsNoChangeWhenTheCodeWasNotTouched(@TempDir Path tmp) throws Exception {
        LoopSession session = new LoopSession();
        Map<String, String> same = Map.of("Scoring.java", "aaa");
        session.record("auto", false, 0, "a.wpilog", checks(0, false), same, null);
        LoopSession.Iteration second =
                session.record("auto", false, 0, "b.wpilog", checks(0, false), same, null);

        assertTrue(second.changedFiles.isEmpty());
        assertTrue(second.deltas.isEmpty(), "an unchanged value is not a delta");
    }

    @Test
    void survivesARestart(@TempDir Path tmp) throws Exception {
        // The journal exists precisely so a later session can pick up the history.
        Path file = tmp.resolve(".loop/session.json");
        LoopSession session = new LoopSession();
        session.project = "demo";
        session.record("auto", false, 0, "a.wpilog", checks(0, false), Map.of("Sub.java", "aaa"), null);
        session.save(file);

        LoopSession reloaded = LoopSession.load(file);
        assertEquals("demo", reloaded.project);
        assertEquals(1, reloaded.iterations.size());
        assertEquals(Map.of("Sub.java", "aaa"), reloaded.fingerprint);

        // A later iteration still sees the earlier fingerprint, so the edit is attributed.
        LoopSession.Iteration second = reloaded.record(
                "auto", true, 0, "b.wpilog", checks(5, true), Map.of("Sub.java", "bbb"), null);
        assertEquals(2, second.number);
        assertEquals(List.of("Sub.java"), second.changedFiles);
    }

    @Test
    void fingerprintsSourceContentRatherThanTimestamps(@TempDir Path tmp) throws Exception {
        // A rebuild touches files without changing them; reporting that as an edit would be noise.
        Path src = tmp.resolve("src");
        Files.createDirectories(src);
        Path source = src.resolve("Robot.java");
        Files.writeString(source, "class Robot {}");

        Map<String, String> before = LoopSession.fingerprintSources(List.of(src), tmp);
        Files.setLastModifiedTime(source, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() + 60_000));
        assertEquals(before, LoopSession.fingerprintSources(List.of(src), tmp));

        Files.writeString(source, "class Robot { int x; }");
        assertTrue(!before.equals(LoopSession.fingerprintSources(List.of(src), tmp)),
                "a content change must be detected");
    }

    @Test
    void ignoresFilesThatAreNotSource(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Robot.java"), "class Robot {}");
        Files.writeString(src.resolve("notes.txt"), "scratch");
        Map<String, String> fingerprint = LoopSession.fingerprintSources(List.of(src), tmp);
        assertEquals(List.of("src/Robot.java"), List.copyOf(fingerprint.keySet()));
    }
}
