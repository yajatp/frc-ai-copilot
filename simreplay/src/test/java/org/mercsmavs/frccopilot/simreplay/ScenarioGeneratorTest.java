package org.mercsmavs.frccopilot.simreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

class ScenarioGeneratorTest {

    @Test
    void generatedScenarioPassesOnTheLogItCameFrom(@TempDir Path tmp) throws Exception {
        // The property that makes generation usable at all. It is easy to violate: measuring a
        // signal over the whole log while scoping the check to autonomous produces thresholds the
        // source log itself fails, since battery voltage keeps sagging after auto ends.
        WpilogReader good = new WpilogReader(TestLogs.good(tmp.resolve("good.wpilog")).toString());
        Scenario scenario = ScenarioGenerator.generate(
                good, "auto", "/Robot/State", "AUTO", ScenarioGenerator.DEFAULT_TOLERANCE);

        assertFalse(scenario.assertions().isEmpty(), "expected the good run to justify some checks");
        Verifier.LoopResult result = Verifier.verify(scenario, good::read);
        assertTrue(result.allPassed(), () -> "generated scenario must pass its own log:\n" + result.render());
    }

    @Test
    void catchesTheRegressionItWasGeneratedToCatch(@TempDir Path tmp) throws Exception {
        WpilogReader good = new WpilogReader(TestLogs.good(tmp.resolve("good.wpilog")).toString());
        Scenario scenario = ScenarioGenerator.generate(
                good, "auto", "/Robot/State", "AUTO", ScenarioGenerator.DEFAULT_TOLERANCE);

        // Same run, except nothing scores — the 254 defect.
        WpilogReader broken = new WpilogReader(
                TestLogs.write(tmp.resolve("broken.wpilog"), 0, i -> 12.5 - i * 0.002,
                        i -> 4.0 * Math.exp(-i / 25.0)).toString());
        assertFalse(Verifier.verify(scenario, broken::read).allPassed(),
                "a run that scores nothing must fail a scenario generated from one that scored");
    }

    @Test
    void proposesWholeNumbersForCountersAndDecimalsForContinuousSignals(@TempDir Path tmp)
            throws Exception {
        WpilogReader good = new WpilogReader(TestLogs.good(tmp.resolve("good.wpilog")).toString());
        Scenario scenario = ScenarioGenerator.generate(good, "auto", null, null, 0.10);

        Assertion counter = find(scenario, "/Autonomous/BallsScored");
        assertEquals(Math.rint(counter.threshold()), counter.threshold(),
                "a game-piece count threshold must be a whole number");
        assertEquals(Assertion.Aggregation.LAST, counter.aggregation());
        assertEquals(Assertion.Op.GE, counter.op());

        Assertion battery = find(scenario, "/PowerDistribution/Voltage");
        assertEquals(Assertion.Aggregation.MIN, battery.aggregation());
        assertEquals(Assertion.Op.GE, battery.op(), "battery check must be a floor, not a ceiling");
        assertTrue(battery.threshold() < 12.5, "threshold must sit below the observed minimum");
    }

    @Test
    void requiresAConvergingErrorToSettle(@TempDir Path tmp) throws Exception {
        WpilogReader good = new WpilogReader(TestLogs.good(tmp.resolve("good.wpilog")).toString());
        Assertion settle = find(
                ScenarioGenerator.generate(good, "auto", null, null, 0.10),
                "/Drivetrain/DistanceToTarget");
        assertEquals(Assertion.Aggregation.LAST, settle.aggregation());
        assertEquals(Assertion.Op.LE, settle.op());
    }

    @Test
    void ignoresSignalsThatNeverMoved(@TempDir Path tmp) throws Exception {
        // A constant signal defends nothing and would only add noise to the suite.
        WpilogReader flat = new WpilogReader(
                TestLogs.write(tmp.resolve("flat.wpilog"), 0, i -> 12.0, i -> 1.0).toString());
        Scenario scenario = ScenarioGenerator.generate(flat, "flat", null, null, 0.10);
        assertTrue(scenario.assertions().stream()
                        .noneMatch(a -> a.signal().equals("/PowerDistribution/Voltage")),
                "a signal that held constant should not become a check");
    }

    private static Assertion find(Scenario scenario, String signal) {
        return scenario.assertions().stream()
                .filter(a -> a.signal().equals(signal))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no generated check for " + signal
                        + " in " + scenario.assertions().stream().map(Assertion::signal).toList()));
    }
}
