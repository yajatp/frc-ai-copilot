package org.mercsmavs.frccopilot.ingest.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mercsmavs.frccopilot.ingest.LogEntry;

class TrendStoreTest {

    @Test
    void ingestsSummaryEntriesMetricsAndTrends(@TempDir Path tmp) throws Exception {
        String db = tmp.resolve("trends.sqlite").toString();

        LogEntry voltage = new LogEntry(1, "/PowerDistribution/Voltage", "double", "");
        voltage.record(1_000_000L);
        voltage.record(1_020_000L);
        LogEntry state = new LogEntry(2, "/Robot/State", "string", "");
        state.record(1_000_000L);

        try (TrendStore store = new TrendStore(db)) {
            // --- match 1 ---
            LogSummary m1 =
                    new LogSummary("/logs/q10.wpilog", "sha-q10", 6369, "2026dal_qm10", "6369-echo",
                            1_700_000_000_000_000L, 152.4, 256, "abc123");
            long id1 = store.ingest(m1, List.of(voltage, state));
            store.recordMetric(id1, "min_voltage", "MATCH", 8.9, "V", "MEDIUM");

            // --- match 2 (same metric, later) ---
            LogSummary m2 =
                    new LogSummary("/logs/q11.wpilog", "sha-q11", 6369, "2026dal_qm11", "6369-echo",
                            1_700_000_300_000_000L, 151.9, 256, "abc123");
            long id2 = store.ingest(m2, List.of(voltage));
            store.recordMetric(id2, "min_voltage", "MATCH", 7.2, "V", "HIGH");
            store.recordEvent(id2, 95_000_000L, "brownout", "CRITICAL", "voltage < 6.8V for 40ms");

            // --- entries persisted ---
            assertEquals(2, store.listLogs().size());

            // --- trend query returns both points in order, no raw-log re-parse ---
            List<TrendStore.MetricPoint> trend = store.trend("min_voltage");
            assertEquals(2, trend.size());
            assertEquals(8.9, trend.get(0).value(), 1e-9);
            assertEquals(7.2, trend.get(1).value(), 1e-9);
            assertEquals("2026dal_qm11", trend.get(1).matchKey());

            // --- re-ingesting the same file (same sha) replaces, does not duplicate ---
            long id1b = store.ingest(m1, List.of(voltage, state));
            assertTrue(id1b >= id1);
            assertEquals(2, store.listLogs().size(), "re-ingest must not duplicate");
        }
    }
}
