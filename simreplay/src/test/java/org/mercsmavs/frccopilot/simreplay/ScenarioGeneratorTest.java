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

    @Test
    void ignoresWallClocksAndThinlySampledSignals(@TempDir Path tmp) throws Exception {
        // Both shapes are trivially "monotonically increasing" and both used to be banked as
        // counters: a clock that always passes, and a current reading with three samples that
        // happened to rise. Together they made a generated suite on a real log entirely noise.
        WpilogReader run = new WpilogReader(
                TestLogs.withPoseAndClock(tmp.resolve("run.wpilog"), 4.0).toString());
        Scenario scenario = ScenarioGenerator.generate(
                run, "auto", "/Robot/State", "AUTO", ScenarioGenerator.DEFAULT_TOLERANCE);

        assertTrue(scenario.assertions().stream()
                        .noneMatch(a -> a.signal().contains("EpochTimeMicros")),
                "a wall clock defends nothing");
        assertTrue(scenario.assertions().stream()
                        .noneMatch(a -> a.signal().equals("/Intake/StatorCurrentAmps")),
                "three samples is not a shape");
    }

    @Test
    void boxesTheEndpointOfTheAutoOnBothSides(@TempDir Path tmp) throws Exception {
        // A one-sided "still reaches" bound cannot catch an auto that ends half a metre short,
        // because it did still move. The endpoint has to be constrained from both directions.
        WpilogReader run = new WpilogReader(
                TestLogs.withPoseAndClock(tmp.resolve("run.wpilog"), 4.0).toString());
        Scenario scenario = ScenarioGenerator.generate(
                run, "auto", "/Robot/State", "AUTO", ScenarioGenerator.DEFAULT_TOLERANCE);

        var onX = scenario.assertions().stream()
                .filter(a -> a.signal().equals("/Odometry/Robot/X"))
                .toList();
        assertEquals(2, onX.size(), "expected a lower and an upper bound on where the auto ended");
        assertTrue(onX.stream().anyMatch(a -> a.op() == Assertion.Op.GE));
        assertTrue(onX.stream().anyMatch(a -> a.op() == Assertion.Op.LE));
    }

    @Test
    void catchesAnAutoThatDriftedShortWithoutFailingToMove(@TempDir Path tmp) throws Exception {
        // The regression that used to pass silently: gains detuned, robot still drives, ends 40cm
        // off. Every "did it move" threshold is satisfied; only the endpoint box notices.
        WpilogReader good = new WpilogReader(
                TestLogs.withPoseAndClock(tmp.resolve("good.wpilog"), 4.0).toString());
        Scenario scenario = ScenarioGenerator.generate(
                good, "auto", "/Robot/State", "AUTO", ScenarioGenerator.DEFAULT_TOLERANCE);

        WpilogReader drifted = new WpilogReader(
                TestLogs.withPoseAndClock(tmp.resolve("drift.wpilog"), 3.5).toString());
        assertFalse(Verifier.verify(scenario, drifted::read).allPassed(),
                "an auto that finished half a metre short must not pass");
    }

    @Test
    void generatedEndpointChecksPassTheirOwnLog(@TempDir Path tmp) throws Exception {
        WpilogReader run = new WpilogReader(
                TestLogs.withPoseAndClock(tmp.resolve("run.wpilog"), 4.0).toString());
        Scenario scenario = ScenarioGenerator.generate(
                run, "auto", "/Robot/State", "AUTO", ScenarioGenerator.DEFAULT_TOLERANCE);
        Verifier.LoopResult result = Verifier.verify(scenario, run::read);
        assertTrue(result.allPassed(), () -> result.render());
    }

    @Test
    void doesNotBoxAValueThatWasStillMovingWhenTheWindowClosed(@TempDir Path tmp) throws Exception {
        // A commanded trajectory setpoint is a pose, but it is mid-travel when autonomous ends, so
        // its last value records where the window closed rather than where the robot got to. Boxing
        // it produces a check that fails on ordinary timing jitter — observed at 0.21 m on a real
        // run, against a 0.20 m budget.
        WpilogReader run = new WpilogReader(
                TestLogs.stillMovingAtWindowClose(tmp.resolve("moving.wpilog")).toString());
        Scenario scenario = ScenarioGenerator.generate(
                run, "auto", "/Robot/State", "AUTO", ScenarioGenerator.DEFAULT_TOLERANCE);

        assertTrue(scenario.assertions().stream()
                        .noneMatch(a -> a.signal().startsWith("/Odometry/Setpoint/")),
                () -> "a still-travelling pose must not get an endpoint box: "
                        + scenario.assertions().stream().map(Assertion::signal).toList());

        // ...and it must say so. A suite that looks like it defends where the auto finished, while
        // quietly omitting the axis the robot was moving along, is worse than one that offers no
        // endpoint checks at all.
        assertTrue(scenario.description().contains("/Odometry/Setpoint/X"),
                () -> "the omission must be stated, not silent: " + scenario.description());
        assertTrue(scenario.description().contains("still moving"),
                () -> scenario.description());
    }

    @Test
    void doesNotBoxSwerveModuleAzimuths(@TempDir Path tmp) throws Exception {
        // Every module publishes its azimuth as a Rotation2d. Keyed on "is this an angle" rather
        // than "is this a position", a generated suite picked up sixteen wheel angles pinned to
        // within 6 degrees at the end of autonomous — noise that defends nothing.
        WpilogReader run = new WpilogReader(
                TestLogs.stillMovingAtWindowClose(tmp.resolve("run.wpilog")).toString());
        Scenario scenario = ScenarioGenerator.generate(
                run, "auto", "/Robot/State", "AUTO", ScenarioGenerator.DEFAULT_TOLERANCE);

        assertTrue(scenario.assertions().stream()
                        .noneMatch(a -> a.signal().startsWith("/Drive/Module0/TurnPosition")),
                "a wheel angle is not where the robot ended up");
    }

    @Test
    void loopPeriodCheckIsAboutTheDeadlineNotTheBestRunEverSeen(@TempDir Path tmp) throws Exception {
        // Observed max was 2.94 ms on a quiet machine; a 10% band around that failed on identical
        // code the moment the laptop was busy, spiking to 5.7 ms. The deadline is 20 ms, and that
        // is what a loop-timing check is for.
        WpilogReader run = new WpilogReader(
                TestLogs.withLoopPeriod(tmp.resolve("run.wpilog")).toString());
        Scenario scenario = ScenarioGenerator.generate(
                run, "auto", "/Robot/State", "AUTO", ScenarioGenerator.DEFAULT_TOLERANCE);

        Assertion loop = scenario.assertions().stream()
                .filter(a -> a.signal().contains("LoopPeriodMS"))
                .findFirst()
                .orElseThrow();
        assertTrue(loop.threshold() >= 20.0,
                () -> "expected the 20 ms control deadline, got " + loop.threshold());
    }
}
