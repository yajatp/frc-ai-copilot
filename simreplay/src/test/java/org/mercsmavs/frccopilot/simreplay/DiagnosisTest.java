package org.mercsmavs.frccopilot.simreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
