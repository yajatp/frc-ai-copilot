package org.mercsmavs.frccopilot.dashboard;

import edu.wpi.first.networktables.NetworkTableInstance;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A NetworkTables server that publishes plausible robot telemetry, so the dashboard can be
 * developed and demonstrated without a roboRIO on the bench.
 *
 * <p>It is not a physics model — it exists to exercise the real pipeline end to end (topic
 * discovery, intent resolution, rolling windows, the analysis primitives, and the health tiles)
 * with data that actually trips those checks. Drive current is bursty, battery voltage sags under
 * load through a modelled internal resistance, the loop occasionally overruns, and CAN errors creep
 * up — so WATCH and CRITICAL verdicts appear on their own rather than only OK.
 */
public final class SimRobot implements AutoCloseable {

    /** Publish rate, matching a normal 20 ms robot loop. */
    private static final int HZ = 50;

    private static final double NOMINAL_VOLTS = 12.7;

    /** Modelled battery internal resistance — a healthy-ish pack. */
    private static final double INTERNAL_RESISTANCE = 0.018;

    private final NetworkTableInstance inst = NetworkTableInstance.create();
    private final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sim-robot");
                t.setDaemon(true);
                return t;
            });
    private final Random random = new Random(6369);

    private double stateOfCharge = 1.0;
    private double driveCurrent = 8.0;
    private double canErrors = 0;
    private int tick;

    /** Starts an NT4 server on {@code port} and begins publishing. */
    public void start(int port) {
        inst.startServer("", "127.0.0.1", 1735, port);

        // Match context, as the Driver Station would publish it.
        inst.getEntry("/FMSInfo/EventName").setString("Bench Simulation");
        inst.getEntry("/FMSInfo/MatchNumber").setInteger(7);
        inst.getEntry("/FMSInfo/MatchType").setInteger(2);
        inst.getEntry("/FMSInfo/StationNumber").setInteger(2);
        inst.getEntry("/FMSInfo/IsRedAlliance").setBoolean(true);

        ticker.scheduleAtFixedRate(this::publish, 0, 1000 / HZ, TimeUnit.MILLISECONDS);
    }

    private void publish() {
        tick++;
        double seconds = tick / (double) HZ;

        // Bursty drive load: a hard acceleration every few seconds on top of a baseline.
        boolean sprinting = (tick / (HZ * 3)) % 2 == 0;
        double target = sprinting ? 145 : 22;
        driveCurrent += (target - driveCurrent) * 0.08 + random.nextGaussian() * 3.0;
        driveCurrent = Math.max(2, driveCurrent);

        // Pack drains slowly; voltage is open-circuit minus the IR drop under load.
        stateOfCharge = Math.max(0.35, stateOfCharge - 0.000012);
        double openCircuit = 11.6 + 1.1 * stateOfCharge;
        double volts = openCircuit - driveCurrent * INTERNAL_RESISTANCE + random.nextGaussian() * 0.03;

        // Every ~25 s, a stall deep enough to trip the brownout check.
        if (seconds % 25 < 0.35) {
            volts -= 4.6;
        }
        volts = Math.max(4.5, Math.min(NOMINAL_VOLTS + 0.4, volts));

        // Loop time sits near 20 ms and occasionally overruns, as real code does.
        double loopMs = 19.4 + random.nextGaussian() * 0.7;
        if (random.nextDouble() < 0.01) {
            loopMs += 8 + random.nextDouble() * 14;
        }

        // CAN errors are a monotonic counter that creeps under electrical stress.
        if (driveCurrent > 120 && random.nextDouble() < 0.02) {
            canErrors += 1;
        }

        inst.getEntry("/PowerDistribution/Voltage").setDouble(volts);
        inst.getEntry("/PowerDistribution/TotalCurrent").setDouble(driveCurrent);
        inst.getEntry("/CANStatus/ReceiveErrorCount").setDouble(canErrors);
        inst.getEntry("/loopTimeMs").setDouble(loopMs);

        // A little subsystem telemetry so the topic browser has something to show.
        inst.getEntry("/Drive/LeftVelocity").setDouble(sprinting ? 3.6 + random.nextGaussian() * 0.2 : 0.1);
        inst.getEntry("/Drive/RightVelocity").setDouble(sprinting ? 3.5 + random.nextGaussian() * 0.2 : 0.1);
        inst.getEntry("/Drive/GyroYaw").setDouble((seconds * 12) % 360 - 180);
        inst.getEntry("/Vision/HasTarget").setBoolean(random.nextDouble() < 0.7);
        inst.getEntry("/Vision/TagCount").setDouble(random.nextInt(4));
        inst.getEntry("/Elevator/PositionMeters").setDouble(0.9 + Math.sin(seconds * 0.6) * 0.85);
    }

    @Override
    public void close() {
        ticker.shutdownNow();
        inst.close();
    }
}
