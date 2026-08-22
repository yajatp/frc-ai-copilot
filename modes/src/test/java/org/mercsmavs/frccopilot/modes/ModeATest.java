package org.mercsmavs.frccopilot.modes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mercsmavs.frccopilot.analysis.Series;
import org.mercsmavs.frccopilot.ingest.LogEntry;
import org.mercsmavs.frccopilot.ingest.store.LogSummary;
import org.mercsmavs.frccopilot.ingest.store.TrendStore;

class ModeATest {

    private static Series series(double periodMs, double... values) {
        long[] ts = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            ts[i] = (long) (i * periodMs * 1000);
        }
        return new Series(values, ts);
    }

    @Test
    void flagsBrownoutAndCanAndPersistsMetrics(@TempDir Path tmp) throws Exception {
        String db = tmp.resolve("t.sqlite").toString();
        try (TrendStore store = new TrendStore(db)) {
            LogSummary summary = new LogSummary(
                    "/logs/q10.wpilog", "sha", 6369, "2026dal_qm10", "6369-echo",
                    1_700_000_000_000_000L, 150.0, 256, "abc");
            long logId = store.ingest(summary, List.of(new LogEntry(1, "/x", "double", "")));

            // Voltage with a sustained sub-6.8V brownout dip.
            double[] volts = new double[120];
            for (int i = 0; i < volts.length; i++) volts[i] = 12.0;
            for (int i = 50; i < 58; i++) volts[i] = 6.2;
            // Rising CAN errors.
            double[] can = new double[120];
            for (int i = 0; i < can.length; i++) can[i] = i < 100 ? 0 : (i - 100);
            // A couple of loop overruns.
            double[] loop = new double[120];
            java.util.Arrays.fill(loop, 6.0);
            loop[30] = 40;

            ModeA.Result r = ModeA.analyze(store, logId,
                    series(20, volts), null, series(20, can), series(20, loop));

            assertTrue(r.flags().stream().anyMatch(f -> f.severity() == ModeA.Severity.CRITICAL),
                    () -> "expected a CRITICAL brownout flag:\n" + r.report());
            assertTrue(r.flags().stream().anyMatch(f -> f.area().contains("CAN")));
            assertTrue(r.flags().stream().anyMatch(f -> f.area().contains("loop")));
            assertEquals(ModeA.Severity.CRITICAL, r.worst());

            // Metrics were persisted (season trend has data now).
            List<TrendStore.MetricPoint> minV = store.trend("min_voltage");
            assertEquals(1, minV.size());
            assertEquals(6.2, minV.get(0).value(), 1e-9);
            assertFalse(store.trend("can_error_increase").isEmpty());
            assertFalse(store.trend("loop_overruns").isEmpty());
        }
    }

    @Test
    void cleanMatchHasNoFlags(@TempDir Path tmp) throws Exception {
        String db = tmp.resolve("t2.sqlite").toString();
        try (TrendStore store = new TrendStore(db)) {
            long logId = store.ingest(new LogSummary("/l.wpilog", "s", 6369, null, null, 0L, 100, 256, null),
                    List.of(new LogEntry(1, "/x", "double", "")));
            double[] volts = new double[120];
            java.util.Arrays.fill(volts, 12.4);
            ModeA.Result r = ModeA.analyze(store, logId, series(20, volts), null, null, null);
            assertTrue(r.flags().isEmpty(), () -> "clean match should have no flags:\n" + r.report());
            assertEquals(ModeA.Severity.OK, r.worst());
        }
    }

    @Test
    void doesNotReportAnUnpoweredVoltageChannelAsABatteryProblem(@TempDir Path tmp)
            throws Exception {
        // A simulated or unplugged PDP reads 0 V. That is not a brownout — the robot was not
        // running — and flagging it sends the pit crew to swap a healthy battery between matches.
        String db = tmp.resolve("t.sqlite").toString();
        try (TrendStore store = new TrendStore(db)) {
            LogSummary summary = new LogSummary(
                    "/logs/sim.wpilog", "sha-sim", 6369, null, "6369-echo",
                    1_700_000_000_000_000L, 15.0, 256, "abc");
            long logId = store.ingest(summary, List.of(new LogEntry(1, "/x", "double", "")));

            double[] volts = new double[60];
            ModeA.Result result =
                    ModeA.analyze(store, logId, series(20, volts), null, null, null);

            assertTrue(result.flags().stream()
                            .anyMatch(f -> f.message().contains("missing measurement")),
                    () -> "expected the reading to be called out as unusable: " + result.report());
            assertFalse(result.flags().stream().anyMatch(f -> f.message().contains("Brownout risk")),
                    "0 V is not a brownout");
        }
    }

    @Test
    void carriesTheConfidenceCaveatOntoTheFlag(@TempDir Path tmp) throws Exception {
        // The flag line is the only thing anyone reads between matches. The primitives hedge their
        // findings; dropping that hedge here turns "two samples, treat as weak" into an instruction.
        String db = tmp.resolve("t.sqlite").toString();
        try (TrendStore store = new TrendStore(db)) {
            LogSummary summary = new LogSummary(
                    "/logs/q10.wpilog", "sha-q", 6369, null, "6369-echo",
                    1_700_000_000_000_000L, 150.0, 256, "abc");
            long logId = store.ingest(summary, List.of(new LogEntry(1, "/x", "double", "")));

            // A thin, irregular series: real values, but nowhere near enough of them to conclude.
            ModeA.Result result = ModeA.analyze(
                    store, logId, series(900, 12.0, 7.4), null, null, null);

            assertTrue(result.flags().stream().anyMatch(f -> f.message().contains("samples")),
                    () -> "expected a data-quality caveat on the flag: " + result.report());
        }
    }
}
