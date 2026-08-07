package org.mercsmavs.frccopilot.ingest;

import edu.wpi.first.util.datalog.DataLogWriter;
import java.io.IOException;

/**
 * Writes a synthetic but realistic match log so the analysis tools can be demoed and smoke-tested
 * without a real robot. Real logs are gitignored (they are data, not source), so this is how a new
 * contributor gets something to point {@code analyze} at.
 *
 * <p>The generated match deliberately contains findable problems: a sustained brownout during a
 * heavy-current scoring push, a CAN error burst, a few loop overruns, and intermittent vision
 * dropouts — so every primitive has something to report.
 *
 * <pre>
 *   sample-log &lt;out.wpilog&gt;
 * </pre>
 */
public final class SampleLogGenerator {

    private static final double PERIOD_S = 0.02;
    private static final int SAMPLES = (int) (150 / PERIOD_S); // a 150 s match

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: sample-log <out.wpilog>");
            System.exit(2);
            return;
        }
        write(args[0]);
        System.out.println("Wrote a 150 s synthetic match log to " + args[0]);
        System.out.println("Try: analyze full " + args[0]);
    }

    /** Generates the log at {@code path}. Package-visible so tests can reuse the same fixture. */
    public static void write(String path) throws IOException {
        try (DataLogWriter log = new DataLogWriter(path)) {
            int voltage = log.start("/PowerDistribution/Voltage", "double");
            int current = log.start("/PowerDistribution/TotalCurrent", "double");
            int canErrors = log.start("/CAN/ReceiveErrorCount", "int64");
            int loopMs = log.start("/Robot/loopTimeMs", "double");
            int hasTarget = log.start("/Vision/hasTarget", "boolean");
            int driveVel = log.start("/Drive/Module0/DriveVelocity", "double");
            int cycles = log.start("/Superstructure/CycleCount", "int64");
            int enabled = log.start("/DS/Enabled", "boolean");

            long canCount = 0;
            long cycleCount = 0;
            long nextCycleSample = 240; // first score ~4.8 s in

            for (int i = 0; i < SAMPLES; i++) {
                long ts = 1_000_000L + (long) (i * PERIOD_S * 1_000_000L);
                double t = i * PERIOD_S;

                // Baseline drivetrain draw plus a heavy scoring push from 60–75 s.
                boolean pushing = t >= 60 && t < 75;
                double amps = (pushing ? 210 : 55) + 12 * Math.sin(t * 3.1);

                // Voltage sags with current; the push drives a sustained brownout below 6.8 V.
                double volts = 12.8 - amps * 0.021 - 0.004 * t;
                if (pushing && t > 64 && t < 66.5) {
                    volts = 6.5 + 0.2 * Math.sin(t * 9);
                }

                // CAN errors: clean until a burst at ~90 s, then a plateau (a loose wire, not noise).
                if (t > 90 && t < 93 && i % 5 == 0) {
                    canCount += 2;
                }

                // Loop timing: nominal 20 ms with periodic overruns during vision processing.
                double loop = 19.4 + 0.6 * Math.sin(t * 7);
                if (i % 250 == 0) {
                    loop = 34.0; // an overrun every 5 s
                }

                // Vision: mostly locked on, with dropouts while the robot is spinning fast.
                double omega = Math.sin(t * 0.8);
                boolean target = Math.abs(omega) < 0.85;

                // Swerve module velocity: smooth, except a ringing period after 100 s.
                double vel = 2.6 * Math.sin(t * 1.4);
                if (t > 100 && t < 110) {
                    vel += 0.9 * Math.sin(t * 31); // underdamped oscillation
                }

                // Scoring cycles roughly every 6 s while enabled and not brownout-limited.
                if (i >= nextCycleSample && !(t > 64 && t < 66.5)) {
                    cycleCount++;
                    nextCycleSample = i + 300;
                }

                log.appendDouble(voltage, round(volts), ts);
                log.appendDouble(current, round(amps), ts);
                log.appendInteger(canErrors, canCount, ts);
                log.appendDouble(loopMs, round(loop), ts);
                log.appendBoolean(hasTarget, target, ts);
                log.appendDouble(driveVel, round(vel), ts);
                log.appendInteger(cycles, cycleCount, ts);
                log.appendBoolean(enabled, t > 2, ts);
            }

            for (int handle : new int[] {voltage, current, canErrors, loopMs, hasTarget, driveVel, cycles, enabled}) {
                log.finish(handle);
            }
            log.flush();
        }
    }

    private static double round(double x) {
        return Math.round(x * 1000) / 1000.0;
    }

    private SampleLogGenerator() {}
}
