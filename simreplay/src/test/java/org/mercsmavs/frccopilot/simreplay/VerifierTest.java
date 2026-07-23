package org.mercsmavs.frccopilot.simreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

class VerifierTest {

    /** In-memory signal source so verification is testable without a real log or natives. */
    private static final class FakeSource implements SignalSource {
        final Map<String, List<WpilogReader.Sample>> signals = new HashMap<>();

        FakeSource put(String name, List<WpilogReader.Sample> samples) {
            signals.put(name, samples);
            return this;
        }

        @Override
        public List<WpilogReader.Sample> read(String name) {
            return signals.getOrDefault(name, List.of());
        }
    }

    private static List<WpilogReader.Sample> state() {
        // AUTO for the first 1.5 s, then TELEOP.
        List<WpilogReader.Sample> s = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            s.add(new WpilogReader.Sample(1_000_000L + i * 20_000L, i < 75 ? "AUTO" : "TELEOP"));
        }
        return s;
    }

    private static List<WpilogReader.Sample> balls(long autoValue, long teleopValue) {
        List<WpilogReader.Sample> s = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            s.add(new WpilogReader.Sample(1_000_000L + i * 20_000L, i < 75 ? autoValue : teleopValue));
        }
        return s;
    }

    private static Scenario autoScoresScenario() {
        return new Scenario(
                "auto_scores_balls",
                "auto must score non-zero and hit target",
                List.of(
                        new Assertion("/Autonomous/BallsScored", Assertion.Aggregation.MAX,
                                Assertion.Op.GT, 0, "/Robot/State", "AUTO", "non-zero"),
                        new Assertion("/Autonomous/BallsScored", Assertion.Aggregation.MAX,
                                Assertion.Op.GE, 40, "/Robot/State", "AUTO", "target")));
    }

    @Test
    void brokenAutoFailsBothChecks() {
        // The bug: 0 balls during AUTO (even if TELEOP later shows 99, phase filter must ignore it).
        FakeSource src = new FakeSource()
                .put("/Robot/State", state())
                .put("/Autonomous/BallsScored", balls(0, 99));
        Verifier.LoopResult r = Verifier.verify(autoScoresScenario(), src);
        assertFalse(r.allPassed(), "broken auto must fail");
        assertEquals(2, r.failed());
        assertTrue(r.render().contains("FAIL"));
    }

    @Test
    void fixedAutoPassesBothChecks() {
        FakeSource src = new FakeSource()
                .put("/Robot/State", state())
                .put("/Autonomous/BallsScored", balls(50, 50));
        Verifier.LoopResult r = Verifier.verify(autoScoresScenario(), src);
        assertTrue(r.allPassed(), () -> "fixed auto should pass:\n" + r.render());
    }

    @Test
    void phaseFilterIgnoresOutOfPhaseSamples() {
        // 99 balls only in TELEOP; MAX within AUTO must still be 0 -> non-zero check fails.
        FakeSource src = new FakeSource()
                .put("/Robot/State", state())
                .put("/Autonomous/BallsScored", balls(0, 99));
        Assertion inAuto = new Assertion("/Autonomous/BallsScored", Assertion.Aggregation.MAX,
                Assertion.Op.GT, 0, "/Robot/State", "AUTO", null);
        assertFalse(inAuto.evaluate(src).passed());
        // Without the phase filter, the TELEOP 99 would make it pass.
        Assertion noPhase = new Assertion("/Autonomous/BallsScored", Assertion.Aggregation.MAX,
                Assertion.Op.GT, 0, null, null, null);
        assertTrue(noPhase.evaluate(src).passed());
    }

    @Test
    void scenarioYamlRoundTrips(@TempDir Path tmp) throws Exception {
        Scenario s = autoScoresScenario();
        Path f = tmp.resolve("s.yaml");
        s.save(f);
        Scenario back = Scenario.load(f);
        assertEquals("auto_scores_balls", back.name());
        assertEquals(2, back.assertions().size());
        assertEquals(Assertion.Op.GE, back.assertions().get(1).op());
    }
}
