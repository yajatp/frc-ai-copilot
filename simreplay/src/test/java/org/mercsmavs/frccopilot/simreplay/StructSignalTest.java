package org.mercsmavs.frccopilot.simreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

/**
 * Where the robot ended up is the thing an autonomous routine exists to control, and it is logged
 * as a {@code Pose2d}. These cover the path from that struct to a number a check can compare.
 */
class StructSignalTest {

    @Test
    void poseFieldsAreReadableAsSignals(@TempDir Path tmp) throws Exception {
        WpilogReader run = new WpilogReader(
                TestLogs.withPoseAndClock(tmp.resolve("run.wpilog"), 4.0).toString());

        List<WpilogReader.Sample> x = run.read("/Odometry/Robot/X");
        assertFalse(x.isEmpty(), "a pose entry must expose its X field as a signal");
        assertEquals(4.0, (Double) x.get(x.size() - 1).value(), 1e-6);

        // Angles come out in both units, because a hand-written threshold is nearly always degrees.
        List<WpilogReader.Sample> deg = run.read("/Odometry/Robot/RotationDeg");
        assertEquals(90.0, (Double) deg.get(deg.size() - 1).value(), 1e-6);
    }

    @Test
    void anAssertionCanBeWrittenAgainstAPose(@TempDir Path tmp) throws Exception {
        WpilogReader run = new WpilogReader(
                TestLogs.withPoseAndClock(tmp.resolve("run.wpilog"), 4.0).toString());
        Assertion endsDownfield = new Assertion(
                "/Odometry/Robot/X", Assertion.Aggregation.LAST, Assertion.Op.GE, 3.5,
                null, null, "auto ends past x=3.5m");

        assertTrue(endsDownfield.evaluate(run::read).passed());
    }

    @Test
    void namingTheStructItselfSaysWhatToDoInstead(@TempDir Path tmp) throws Exception {
        // The failure a user hits first. "No samples" alone is indistinguishable from a typo, so
        // the message has to name the actual problem: a pose is not a number, pick a field.
        WpilogReader run = new WpilogReader(
                TestLogs.withPoseAndClock(tmp.resolve("run.wpilog"), 4.0).toString());
        Assertion onTheStruct = new Assertion(
                "/Odometry/Robot", Assertion.Aggregation.LAST, Assertion.Op.GE, 3.5,
                null, null, null);

        Assertion.Result result = onTheStruct.evaluate(run::read);
        assertFalse(result.passed());
        assertTrue(result.message().contains("/Odometry/Robot/X"),
                () -> "expected a pointer at the field form, got: " + result.message());
    }

    @Test
    void poseFieldsAreSummarizedForDiffAndGeneration(@TempDir Path tmp) throws Exception {
        WpilogReader run = new WpilogReader(
                TestLogs.withPoseAndClock(tmp.resolve("run.wpilog"), 4.0).toString());
        assertTrue(run.numericSummaries().containsKey("/Odometry/Robot/X"),
                "baseline divergence and scenario generation both read the bulk summaries");
    }

    @Test
    void baselineDivergenceSeesAPoseThatMoved(@TempDir Path tmp) throws Exception {
        WpilogReader baseline = new WpilogReader(
                TestLogs.withPoseAndClock(tmp.resolve("base.wpilog"), 4.0).toString());
        WpilogReader drifted = new WpilogReader(
                TestLogs.withPoseAndClock(tmp.resolve("drift.wpilog"), 3.6).toString());

        LogDiff.Result diff = LogDiff.compare(baseline, drifted);
        assertTrue(diff.diverged().stream().anyMatch(d -> d.signal().equals("/Odometry/Robot/X")),
                "a robot that finished 40cm short must show up as divergence");
    }

    @Test
    void accumulatorsAreRankedByHowMuchTheyAccumulated(@TempDir Path tmp) throws Exception {
        // A wheel's total travel is a running integral: two honest runs of identical code differ by
        // however much of the window elapsed, which used to put odometry at the top of every
        // divergence ranking and push the signal the edit actually moved off the list.
        WpilogReader baseline = new WpilogReader(
                TestLogs.withAccumulator(tmp.resolve("base.wpilog"), 1.0, 5.0).toString());
        // Same travel rate, different end value; plus a genuinely changed signal.
        WpilogReader run = new WpilogReader(
                TestLogs.withAccumulator(tmp.resolve("run.wpilog"), 1.0, 9.0).toString());

        LogDiff.Result diff = LogDiff.compare(baseline, run);
        assertTrue(diff.diverged().isEmpty()
                        || !diff.diverged().get(0).signal().equals("/Drive/Module0/DrivePositionRad"),
                () -> "an accumulator that accumulated the same amount must not top the ranking: "
                        + diff.render(5));
    }
}
