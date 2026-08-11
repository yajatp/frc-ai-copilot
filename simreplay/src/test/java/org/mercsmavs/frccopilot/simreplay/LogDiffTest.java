package org.mercsmavs.frccopilot.simreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

class LogDiffTest {

    @Test
    void ranksTheSignalThatActuallyBrokeFirst(@TempDir Path tmp) throws Exception {
        WpilogReader baseline = new WpilogReader(TestLogs.good(tmp.resolve("base.wpilog")).toString());
        // Identical run except the scoring counter collapsed to zero.
        WpilogReader broken = new WpilogReader(
                TestLogs.write(tmp.resolve("broken.wpilog"), 0, i -> 12.5 - i * 0.002,
                        i -> 4.0 * Math.exp(-i / 25.0)).toString());

        LogDiff.Result result = LogDiff.compare(baseline, broken);
        assertFalse(result.diverged().isEmpty());
        assertEquals("/Autonomous/BallsScored", result.diverged().get(0).signal(),
                () -> "expected the counter to rank first:\n" + result.render(10));
    }

    @Test
    void reportsNoDivergenceBetweenIdenticalRuns(@TempDir Path tmp) throws Exception {
        WpilogReader a = new WpilogReader(TestLogs.good(tmp.resolve("a.wpilog")).toString());
        WpilogReader b = new WpilogReader(TestLogs.good(tmp.resolve("b.wpilog")).toString());
        LogDiff.Result result = LogDiff.compare(a, b);
        assertTrue(result.diverged().isEmpty(), () -> "identical runs must not diverge:\n" + result.render(10));
        assertTrue(result.onlyInBaseline().isEmpty());
        assertTrue(result.onlyInRun().isEmpty());
    }

    @Test
    void callsOutASignalThatStoppedBeingLogged(@TempDir Path tmp) throws Exception {
        // A renamed or dropped log key is a strong hint on its own, so it is reported separately
        // rather than being scored as a divergence of zero.
        WpilogReader baseline = new WpilogReader(TestLogs.good(tmp.resolve("base.wpilog")).toString());
        Path partial = tmp.resolve("partial.wpilog");
        try (edu.wpi.first.util.datalog.DataLogWriter log =
                new edu.wpi.first.util.datalog.DataLogWriter(partial.toString())) {
            int volts = log.start("/PowerDistribution/Voltage", "double");
            for (int i = 0; i < TestLogs.CYCLES; i++) {
                log.appendDouble(volts, 12.5 - i * 0.002, 1_000_000L + i * 20_000L);
            }
            log.flush();
        }

        LogDiff.Result result = LogDiff.compare(baseline, new WpilogReader(partial.toString()));
        assertTrue(result.onlyInBaseline().contains("/Autonomous/BallsScored"),
                () -> "should flag the missing counter:\n" + result.render(10));
    }

    @Test
    void scoresSignalsInDifferentUnitsComparably(@TempDir Path tmp) throws Exception {
        // Voltage moves by ~0.3 V and the error by ~4 m; normalizing by each signal's own range is
        // what keeps the larger-numbered signal from always winning.
        WpilogReader baseline = new WpilogReader(TestLogs.good(tmp.resolve("base.wpilog")).toString());
        WpilogReader sagging = new WpilogReader(
                TestLogs.write(tmp.resolve("sag.wpilog"), 5, i -> 12.5 - i * 0.02,
                        i -> 4.0 * Math.exp(-i / 25.0)).toString());

        LogDiff.Result result = LogDiff.compare(baseline, sagging);
        assertEquals("/PowerDistribution/Voltage", result.diverged().get(0).signal(),
                () -> "the sagging battery is the story here:\n" + result.render(10));
    }
}
