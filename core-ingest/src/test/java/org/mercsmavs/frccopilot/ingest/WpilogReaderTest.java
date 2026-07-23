package org.mercsmavs.frccopilot.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.util.datalog.DataLogWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Round-trips a synthetic .wpilog (written with WPILib's DataLogWriter) back through
 * {@link WpilogReader}. Doubles as the log-fixture generator we reuse elsewhere.
 */
class WpilogReaderTest {

    @Test
    void indexesAndDecodesRealWpilog(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("fixture.wpilog");

        // Write a small log resembling real robot telemetry: a battery voltage signal (double),
        // a CAN error counter (int64), an enabled flag (boolean), and a state label (string).
        try (DataLogWriter log = new DataLogWriter(file.toString())) {
            int voltage = log.start("/PowerDistribution/Voltage", "double");
            int canErrors = log.start("/CAN/ReceiveErrorCount", "int64");
            int enabled = log.start("/DS/Enabled", "boolean");
            int state = log.start("/Robot/State", "string");

            // 5 samples, 20 ms apart, starting at t = 1_000_000 us.
            double[] voltages = {12.6, 12.4, 11.9, 11.2, 12.1};
            long[] errors = {0, 0, 1, 3, 3};
            for (int i = 0; i < 5; i++) {
                long ts = 1_000_000L + i * 20_000L;
                log.appendDouble(voltage, voltages[i], ts);
                log.appendInteger(canErrors, errors[i], ts);
                log.appendBoolean(enabled, i > 0, ts);
                log.appendString(state, i < 3 ? "AUTO" : "TELEOP", ts);
            }
            log.finish(voltage);
            log.finish(canErrors);
            log.finish(enabled);
            log.finish(state);
            log.flush();
        }

        WpilogReader reader = new WpilogReader(file.toString());

        // --- index pass ---
        Map<Integer, LogEntry> index = reader.index();
        assertEquals(4, index.size(), "expected four entries");

        LogEntry v = byName(index, "/PowerDistribution/Voltage");
        assertEquals("double", v.type);
        assertEquals(5, v.count());
        assertEquals(0.08, v.spanSeconds(), 1e-9, "5 samples 20ms apart span 80ms");

        LogEntry can = byName(index, "/CAN/ReceiveErrorCount");
        assertEquals("int64", can.type);
        assertEquals(5, can.count());

        // --- decode pass ---
        List<WpilogReader.Sample> voltageSamples = reader.read("/PowerDistribution/Voltage");
        assertEquals(5, voltageSamples.size());
        assertEquals(12.6, (double) voltageSamples.get(0).value(), 1e-9);
        assertEquals(11.2, (double) voltageSamples.get(3).value(), 1e-9);
        assertEquals(1.0, voltageSamples.get(0).timestampSeconds(), 1e-9);

        List<WpilogReader.Sample> errorSamples = reader.read("/CAN/ReceiveErrorCount");
        assertEquals(3L, (long) errorSamples.get(3).value());

        List<WpilogReader.Sample> stateSamples = reader.read("/Robot/State");
        assertEquals("AUTO", stateSamples.get(0).value());
        assertEquals("TELEOP", stateSamples.get(4).value());

        assertTrue(reader.read("/does/not/exist").isEmpty());
    }

    private static LogEntry byName(Map<Integer, LogEntry> index, String name) {
        return index.values().stream()
                .filter(e -> e.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing entry: " + name));
    }
}
