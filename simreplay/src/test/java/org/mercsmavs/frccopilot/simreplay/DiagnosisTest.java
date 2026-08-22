package org.mercsmavs.frccopilot.simreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

class DiagnosisTest {

    private static Scenario scenario(Assertion... assertions) {
        return new Scenario("s", "d", List.of(assertions));
    }

    private static Diagnosis diagnose(WpilogReader log, Scenario scenario) {
        return Diagnosis.of(scenario, Verifier.verify(scenario, log::read), log);
    }

    @Test
    void distinguishesAMechanismThatNeverRanFromOneThatFellShort(@TempDir Path tmp) throws Exception {
        // Nothing scored at all: the signal never moved.
        WpilogReader dead = new WpilogReader(
                TestLogs.write(tmp.resolve("dead.wpilog"), 0, i -> 12.5, i -> 1.0).toString());
        Diagnosis constant = diagnose(dead, scenario(
                new Assertion("/Autonomous/BallsScored", Assertion.Aggregation.MAX,
                        Assertion.Op.GE, 5, null, null, "scores 5")));
        assertEquals(Diagnosis.Kind.SIGNAL_CONSTANT, constant.findings().get(0).kind());

        // Scored, but not enough: the same check fails for an entirely different reason.
        WpilogReader weak = new WpilogReader(
                TestLogs.write(tmp.resolve("weak.wpilog"), 2, i -> 12.5, i -> 1.0).toString());
        Diagnosis shortfall = diagnose(weak, scenario(
                new Assertion("/Autonomous/BallsScored", Assertion.Aggregation.MAX,
                        Assertion.Op.GE, 5, null, null, "scores 5")));
        assertEquals(Diagnosis.Kind.SHORTFALL, shortfall.findings().get(0).kind());
        assertTrue(shortfall.findings().get(0).detail().contains("short of"));
    }

    @Test
    void treatsAMissingSignalAsARenameAndPointsAtTheClosestName(@TempDir Path tmp) throws Exception {
        WpilogReader log = new WpilogReader(TestLogs.good(tmp.resolve("good.wpilog")).toString());
        // The log key the team actually writes is /Autonomous/BallsScored.
        Diagnosis d = diagnose(log, scenario(
                new Assertion("/Auto/BallsScored", Assertion.Aggregation.MAX,
                        Assertion.Op.GT, 0, null, null, "scores")));

        Diagnosis.Finding finding = d.findings().get(0);
        assertEquals(Diagnosis.Kind.SIGNAL_ABSENT, finding.kind());
        assertTrue(String.join(" ", finding.suggestions()).contains("/Autonomous/BallsScored"),
                () -> "should suggest the real key, got: " + finding.suggestions());
    }

    @Test
    void reportsAPhaseThatNeverOccurred(@TempDir Path tmp) throws Exception {
        WpilogReader log = new WpilogReader(TestLogs.good(tmp.resolve("good.wpilog")).toString());
        Diagnosis d = diagnose(log, scenario(
                new Assertion("/Autonomous/BallsScored", Assertion.Aggregation.MAX,
                        Assertion.Op.GT, 0, "/Robot/State", "ENDGAME", "scores in endgame")));

        Diagnosis.Finding finding = d.findings().get(0);
        assertEquals(Diagnosis.Kind.NO_SAMPLES_IN_PHASE, finding.kind());
        assertTrue(finding.detail().contains("ENDGAME"));
    }

    @Test
    void statesEachDistinctProblemOnce(@TempDir Path tmp) throws Exception {
        // Two checks on the same dead signal are one defect, not two.
        WpilogReader dead = new WpilogReader(
                TestLogs.write(tmp.resolve("dead.wpilog"), 0, i -> 12.5, i -> 1.0).toString());
        Diagnosis d = diagnose(dead, scenario(
                new Assertion("/Autonomous/BallsScored", Assertion.Aggregation.MAX,
                        Assertion.Op.GT, 0, null, null, "non-zero"),
                new Assertion("/Autonomous/BallsScored", Assertion.Aggregation.MAX,
                        Assertion.Op.GE, 5, null, null, "target")));
        assertEquals(1, d.findings().size(), () -> "expected one finding, got " + d.render());
    }

    @Test
    void saysNothingWhenEverythingPassed(@TempDir Path tmp) throws Exception {
        WpilogReader log = new WpilogReader(TestLogs.good(tmp.resolve("good.wpilog")).toString());
        Diagnosis d = diagnose(log, scenario(
                new Assertion("/Autonomous/BallsScored", Assertion.Aggregation.MAX,
                        Assertion.Op.GT, 0, null, null, "scores")));
        assertTrue(d.isEmpty());
        assertEquals("", d.render());
    }

    @Test
    void callsOutASignErrorRatherThanCallingItAShortfall(@TempDir Path tmp) throws Exception {
        // An inverted output moves the mechanism just as hard, the other way. Read as a number it
        // is simply short of target, and the shortfall advice ("gains, timing") sends the agent to
        // retune a controller that is working correctly. Against a baseline the shape is obvious.
        WpilogReader baseline = new WpilogReader(
                TestLogs.write(tmp.resolve("base.wpilog"), 5, i -> 12.5, i -> 4.0 - i * 0.05)
                        .toString());
        WpilogReader inverted = new WpilogReader(
                TestLogs.write(tmp.resolve("inv.wpilog"), 5, i -> 12.5, i -> -4.0 + i * 0.05)
                        .toString());

        Scenario scenario = new Scenario("auto", "", java.util.List.of(
                new Assertion("/Drivetrain/DistanceToTarget", Assertion.Aggregation.MEAN,
                        Assertion.Op.GE, 1.0, null, null, "drives toward the target")));
        Verifier.LoopResult result = Verifier.verify(scenario, inverted::read);

        Diagnosis withBaseline =
                Diagnosis.of(scenario, result, inverted, baseline.numericSummaries());
        assertEquals(Diagnosis.Kind.POLARITY_REVERSED, withBaseline.findings().get(0).kind());

        // Without a baseline there is genuinely not enough to tell, and it must not pretend there is.
        Diagnosis withoutBaseline = Diagnosis.of(scenario, result, inverted);
        assertEquals(Diagnosis.Kind.SHORTFALL, withoutBaseline.findings().get(0).kind());
    }

    @Test
    void saysOvershotRatherThanShortForAnUpperBound(@TempDir Path tmp) throws Exception {
        // Endpoint checks are two-sided, so half of every failure is an overshoot. Reporting a
        // robot that finished a metre too far as having "fallen short" points at the wrong defect.
        WpilogReader run = new WpilogReader(
                TestLogs.write(tmp.resolve("run.wpilog"), 5, i -> 12.5, i -> 4.0 - i * 0.05)
                        .toString());
        Scenario scenario = new Scenario("auto", "", java.util.List.of(
                new Assertion("/Autonomous/BallsScored", Assertion.Aggregation.LAST,
                        Assertion.Op.LE, 2.0, null, null, "scores at most 2")));

        Diagnosis d = Diagnosis.of(scenario, Verifier.verify(scenario, run::read), run);
        String rendered = d.render();
        assertTrue(rendered.contains("over 2"), () -> rendered);
        assertFalse(rendered.contains("short of"), () -> rendered);
    }

    @Test
    void onlySuggestsAdoptingABaselineWhenThereIsNone(@TempDir Path tmp) throws Exception {
        WpilogReader run = new WpilogReader(
                TestLogs.write(tmp.resolve("run.wpilog"), 1, i -> 12.5, i -> 4.0 - i * 0.05)
                        .toString());
        Scenario scenario = new Scenario("auto", "", java.util.List.of(
                new Assertion("/Autonomous/BallsScored", Assertion.Aggregation.LAST,
                        Assertion.Op.GE, 5.0, null, null, "scores five")));
        Verifier.LoopResult result = Verifier.verify(scenario, run::read);

        assertTrue(Diagnosis.of(scenario, result, run).render().contains("no baseline adopted"));
        assertFalse(
                Diagnosis.of(scenario, result, run, run.numericSummaries())
                        .render().contains("no baseline adopted"),
                "advice must not tell someone to adopt a baseline they already have");
    }

    @Test
    void findsTheSignErrorUpstreamOfTheSignalThatFailed(@TempDir Path tmp) throws Exception {
        // The realistic shape: a negated command shows up as an outcome that ended somewhere wrong,
        // and no assertion names the value that actually flipped. The scan has to reach it anyway.
        WpilogReader baseline = new WpilogReader(
                TestLogs.withReversibleChain(tmp.resolve("base.wpilog"), 1.0).toString());
        WpilogReader inverted = new WpilogReader(
                TestLogs.withReversibleChain(tmp.resolve("inv.wpilog"), -1.0).toString());

        // The failing check names the outcome; the command that flipped is never mentioned.
        Scenario scenario = new Scenario("auto", "", java.util.List.of(
                new Assertion("/Drivetrain/DistanceToTarget", Assertion.Aggregation.LAST,
                        Assertion.Op.GE, 1.0, null, null, "ends downfield")));
        Diagnosis d = Diagnosis.of(scenario, Verifier.verify(scenario, inverted::read), inverted,
                baseline.numericSummaries());

        assertTrue(d.findings().stream().anyMatch(f -> f.kind() == Diagnosis.Kind.POLARITY_REVERSED),
                () -> "expected the reversed chain to be surfaced:\n" + d.render());
        assertTrue(d.render().contains("/Drive/CommandedVx"), () -> d.render());
    }

    @Test
    void needsMoreThanOneReversedSignalBeforeCallingItAnInversion(@TempDir Path tmp)
            throws Exception {
        // A single signal averaging the other way is what a robot that drove a different path looks
        // like — measured on a real detuned-gains build, which is not a wiring problem and must not
        // be reported as one.
        WpilogReader baseline = new WpilogReader(
                TestLogs.write(tmp.resolve("base.wpilog"), 5, i -> 12.5, i -> 4.0 - i * 0.05)
                        .toString());
        WpilogReader wandered = new WpilogReader(
                TestLogs.write(tmp.resolve("run.wpilog"), 5, i -> 12.5, i -> -4.0 + i * 0.05)
                        .toString());

        Scenario scenario = new Scenario("auto", "", java.util.List.of(
                new Assertion("/Autonomous/BallsScored", Assertion.Aggregation.LAST,
                        Assertion.Op.GE, 99.0, null, null, "scores 99")));
        Diagnosis d = Diagnosis.of(scenario, Verifier.verify(scenario, wandered::read), wandered,
                baseline.numericSummaries());

        assertFalse(d.findings().stream().anyMatch(f -> f.kind() == Diagnosis.Kind.POLARITY_REVERSED),
                () -> "one lopsided signal is not an inversion:\n" + d.render());
    }

    @Test
    void neverCallsAWrappingHeadingAPolarityErrorEvenWhenItIsTheFailingSignal(@TempDir Path tmp)
            throws Exception {
        // The per-assertion check and the cross-signal scan have to agree: a heading's mean is
        // unsound as a direction either way, so a run that merely ended past 180 must not be
        // reported as an inverted output.
        WpilogReader baseline = new WpilogReader(
                TestLogs.withPoseAndClock(tmp.resolve("base.wpilog"), 4.0).toString());
        WpilogReader other = new WpilogReader(
                TestLogs.withPoseAndClock(tmp.resolve("other.wpilog"), -4.0).toString());

        Scenario scenario = new Scenario("auto", "", java.util.List.of(
                new Assertion("/Odometry/Robot/RotationDeg", Assertion.Aggregation.LAST,
                        Assertion.Op.GE, 500.0, null, null, "heading check that cannot pass")));
        Diagnosis d = Diagnosis.of(scenario, Verifier.verify(scenario, other::read), other,
                baseline.numericSummaries());

        assertFalse(
                d.findings().stream().anyMatch(f ->
                        f.kind() == Diagnosis.Kind.POLARITY_REVERSED
                                && f.signal().equals("/Odometry/Robot/RotationDeg")),
                () -> "a wrapping heading is not evidence of polarity:\n" + d.render());
    }

    @Test
    void doesNotCallAWrappingHeadingAPolarityError(@TempDir Path tmp) throws Exception {
        // A heading that wraps has no meaningful mean, so its sign carries no direction. Treating
        // it as one would report a sign error on every run that happened to end past 180 degrees.
        WpilogReader baseline = new WpilogReader(
                TestLogs.withPoseAndClock(tmp.resolve("base.wpilog"), 4.0).toString());
        WpilogReader other = new WpilogReader(
                TestLogs.withPoseAndClock(tmp.resolve("other.wpilog"), -4.0).toString());

        Scenario scenario = new Scenario("auto", "", java.util.List.of(
                new Assertion("/Odometry/Robot/X", Assertion.Aggregation.LAST,
                        Assertion.Op.GE, 3.5, null, null, "ends downfield")));
        Diagnosis d = Diagnosis.of(scenario, Verifier.verify(scenario, other::read), other,
                baseline.numericSummaries());

        assertFalse(d.render().contains("RotationDeg  mean"),
                () -> "a wrapping angle must not be listed as reversed:\n" + d.render());
    }
}
