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

    /**
     * A run shaped like a real AdvantageKit log: a pose logged as a struct, a wall clock that only
     * ever increases, and a signal with a handful of samples because it was logged on change.
     * These are the three shapes that made generated suites useless on real robot logs.
     */
    static Path withPoseAndClock(Path file, double endX) throws IOException {
        try (DataLogWriter log = new DataLogWriter(file.toString())) {
            int state = log.start("/Robot/State", "string");
            int pose = log.start("/Odometry/Robot", "struct:Pose2d");
            int clock = log.start("/SystemStats/EpochTimeMicros", "int64");
            int thin = log.start("/Intake/StatorCurrentAmps", "double");

            java.nio.ByteBuffer buf =
                    java.nio.ByteBuffer.allocate(edu.wpi.first.math.geometry.Pose2d.struct.getSize())
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < CYCLES; i++) {
                long ts = 1_000_000L + i * 20_000L;
                boolean auto = i < AUTO_CYCLES;
                log.appendString(state, auto ? "AUTO" : "TELEOP", ts);

                // Drive from the origin to endX over autonomous, easing to a stop, then hold.
                // The deceleration matters: a real auto comes to rest, and a check on where it
                // finished is only meaningful for a value that has actually settled.
                double t = auto ? i / (double) (AUTO_CYCLES - 1) : 1.0;
                double progress = 1.0 - Math.pow(1.0 - t, 3);
                buf.clear();
                edu.wpi.first.math.geometry.Pose2d.struct.pack(
                        buf,
                        new edu.wpi.first.math.geometry.Pose2d(
                                endX * progress,
                                1.0 * progress,
                                edu.wpi.first.math.geometry.Rotation2d.fromDegrees(90.0 * progress)));
                log.appendRaw(pose, buf.array(), ts);

                log.appendInteger(clock, 1_700_000_000_000_000L + ts, ts);

                // Logged on change: three samples, rising, across the whole match.
                if (i == 0 || i == 10 || i == 40) {
                    log.appendDouble(thin, 20.0 + i, ts);
                }
            }
            log.flush();
        }
        return file;
    }

    /**
     * A run holding two pose-shaped signals that behave differently at the window edge: the robot's
     * own pose settles, while a commanded setpoint is still travelling when autonomous ends. Plus a
     * swerve module azimuth, which is an angle but not a position.
     */
    static Path stillMovingAtWindowClose(Path file) throws IOException {
        try (DataLogWriter log = new DataLogWriter(file.toString())) {
            int state = log.start("/Robot/State", "string");
            int setpoint = log.start("/Odometry/Setpoint", "struct:Pose2d");
            int azimuth = log.start("/Drive/Module0/TurnPosition", "struct:Rotation2d");

            java.nio.ByteBuffer poseBuf =
                    java.nio.ByteBuffer.allocate(edu.wpi.first.math.geometry.Pose2d.struct.getSize())
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            java.nio.ByteBuffer rotBuf =
                    java.nio.ByteBuffer.allocate(
                                    edu.wpi.first.math.geometry.Rotation2d.struct.getSize())
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN);

            for (int i = 0; i < CYCLES; i++) {
                long ts = 1_000_000L + i * 20_000L;
                boolean auto = i < AUTO_CYCLES;
                log.appendString(state, auto ? "AUTO" : "TELEOP", ts);

                // Constant velocity: never decelerates, so the last AUTO sample is arbitrary.
                double travelled = 0.06 * i;
                poseBuf.clear();
                edu.wpi.first.math.geometry.Pose2d.struct.pack(
                        poseBuf,
                        new edu.wpi.first.math.geometry.Pose2d(
                                travelled, travelled / 2.0,
                                edu.wpi.first.math.geometry.Rotation2d.fromDegrees(i)));
                log.appendRaw(setpoint, poseBuf.array(), ts);

                rotBuf.clear();
                edu.wpi.first.math.geometry.Rotation2d.struct.pack(
                        rotBuf, edu.wpi.first.math.geometry.Rotation2d.fromDegrees(40.0 + i * 0.5));
                log.appendRaw(azimuth, rotBuf.array(), ts);
            }
            log.flush();
        }
        return file;
    }

    /** A run whose loop period is comfortably inside the deadline, logged in milliseconds. */
    static Path withLoopPeriod(Path file) throws IOException {
        try (DataLogWriter log = new DataLogWriter(file.toString())) {
            int state = log.start("/Robot/State", "string");
            int period = log.start("/RealOutputs/LoggedRobot/LoopPeriodMS", "double");
            for (int i = 0; i < CYCLES; i++) {
                long ts = 1_000_000L + i * 20_000L;
                log.appendString(state, i < AUTO_CYCLES ? "AUTO" : "TELEOP", ts);
                log.appendDouble(period, 2.5 + (i % 7) * 0.05, ts);
            }
            log.flush();
        }
        return file;
    }

    /**
     * A run with a monotonically accumulating odometry signal. {@code ratePerCycle} is how fast it
     * accumulates (the part an edit can change); {@code offset} shifts where it starts, standing in
     * for the window landing at a different point — which must not read as divergence.
     */
    static Path withAccumulator(Path file, double ratePerCycle, double offset) throws IOException {
        try (DataLogWriter log = new DataLogWriter(file.toString())) {
            int state = log.start("/Robot/State", "string");
            int position = log.start("/Drive/Module0/DrivePositionRad", "double");
            for (int i = 0; i < CYCLES; i++) {
                long ts = 1_000_000L + i * 20_000L;
                log.appendString(state, i < AUTO_CYCLES ? "AUTO" : "TELEOP", ts);
                log.appendDouble(position, offset + ratePerCycle * i, ts);
            }
            log.flush();
        }
        return file;
    }

/**
     * A run with a short chain of signals that all take their sign from {@code direction} — a
     * command, the velocity it produces, and the position that integrates. Inverting one output on
     * a real robot moves the whole chain, which is the corroboration the polarity scan requires
     * before it will call a sign difference an inversion.
     */
    static Path withReversibleChain(Path file, double direction) throws IOException {
        try (DataLogWriter log = new DataLogWriter(file.toString())) {
            int state = log.start("/Robot/State", "string");
            int command = log.start("/Drive/CommandedVx", "double");
            int velocity = log.start("/Drive/MeasuredVx", "double");
            int position = log.start("/Drivetrain/DistanceToTarget", "double");
            for (int i = 0; i < CYCLES; i++) {
                long ts = 1_000_000L + i * 20_000L;
                log.appendString(state, i < AUTO_CYCLES ? "AUTO" : "TELEOP", ts);
                log.appendDouble(command, direction * 2.0, ts);
                log.appendDouble(velocity, direction * 1.8, ts);
                log.appendDouble(position, direction * (4.0 - i * 0.02), ts);
            }
            log.flush();
        }
        return file;
    }

    private TestLogs() {}
}
