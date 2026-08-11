package org.mercsmavs.frccopilot.simreplay;

import edu.wpi.first.util.datalog.DataLogWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.IntToDoubleFunction;

/**
 * Writes small but genuine {@code .wpilog} files for the loop tests.
 *
 * <p>Real logs rather than a stand-in source: {@link LogDiff}, {@link Diagnosis} and
 * {@link ScenarioGenerator} all read through {@code WpilogReader}'s single-pass summary, so testing
 * them against a fake would leave the decoding path — the part most likely to be wrong — unexercised.
 */
final class TestLogs {

    static final int CYCLES = 150;
    static final int AUTO_CYCLES = 75;

    /** A run with a phase signal, a scoring counter, battery voltage, and a converging error. */
    static Path write(
            Path file,
            long ballsDuringAuto,
            IntToDoubleFunction voltage,
            IntToDoubleFunction distanceToTarget)
            throws IOException {
        try (DataLogWriter log = new DataLogWriter(file.toString())) {
            int state = log.start("/Robot/State", "string");
            int balls = log.start("/Autonomous/BallsScored", "int64");
            int volts = log.start("/PowerDistribution/Voltage", "double");
            int distance = log.start("/Drivetrain/DistanceToTarget", "double");
            for (int i = 0; i < CYCLES; i++) {
                long ts = 1_000_000L + i * 20_000L;
                boolean auto = i < AUTO_CYCLES;
                log.appendString(state, auto ? "AUTO" : "TELEOP", ts);
                // The counter ramps to its total over autonomous, then holds.
                long scored = auto
                        ? Math.round(ballsDuringAuto * (i / (double) (AUTO_CYCLES - 1)))
                        : ballsDuringAuto;
                log.appendInteger(balls, scored, ts);
                log.appendDouble(volts, voltage.applyAsDouble(i), ts);
                log.appendDouble(distance, distanceToTarget.applyAsDouble(i), ts);
            }
            log.flush();
        }
        return file;
    }

    /** A healthy run: scores, holds voltage, and drives the error to near zero. */
    static Path good(Path file) throws IOException {
        return write(file, 5, i -> 12.5 - i * 0.002, i -> 4.0 * Math.exp(-i / 25.0));
    }

    private TestLogs() {}
}
