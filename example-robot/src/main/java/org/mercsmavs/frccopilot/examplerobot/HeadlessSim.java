package org.mercsmavs.frccopilot.examplerobot;

import edu.wpi.first.hal.HAL;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.util.datalog.DataLogWriter;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.simulation.DriverStationSim;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import org.ironmaple.simulation.SimulatedArena;

/**
 * Runs {@link ScoreAuto} through the real WPILib command scheduler in the simulation HAL — with no
 * GUI and no user input — and writes a real {@code .wpilog}. This is the "run" step of the agentic
 * loop executed against actual robot code; the produced log is then verified by the simreplay harness.
 *
 * <pre>
 *   HeadlessSim &lt;out.wpilog&gt;        # -Dexample.broken=true reproduces the scoring bug
 * </pre>
 */
public final class HeadlessSim {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: HeadlessSim <out.wpilog> [broken]");
            System.exit(2);
            return;
        }
        if (!HAL.initialize(500, 0)) {
            System.err.println("HAL failed to initialize (simulation)");
            System.exit(1);
            return;
        }

        boolean broken = args.length > 1 && args[1].equalsIgnoreCase("broken");
        ScoringSubsystem scoring = new ScoringSubsystem();
        DriveSubsystem drive = new DriveSubsystem(new Pose2d(1.0, 1.0, new Rotation2d()));
        CommandScheduler scheduler = CommandScheduler.getInstance();
        Command scoreAuto = new ScoreAuto(scoring, broken);
        Command driveAuto = new DriveAuto(drive, broken);

        // Enable the robot in autonomous so the command scheduler actually runs commands
        // (WPILib will not execute a command while the robot is disabled).
        DriverStationSim.setDsAttached(true);
        DriverStationSim.setAutonomous(true);
        DriverStationSim.setEnabled(true);
        DriverStationSim.notifyNewData();
        DriverStation.refreshData();
        scheduler.schedule(scoreAuto);
        scheduler.schedule(driveAuto);

        try (DataLogWriter log = new DataLogWriter(args[0])) {
            int state = log.start("/Robot/State", "string");
            int balls = log.start("/Autonomous/BallsScored", "int64");
            int voltage = log.start("/PowerDistribution/Voltage", "double");
            int poseX = log.start("/Drivetrain/Pose/X", "double");
            int poseY = log.start("/Drivetrain/Pose/Y", "double");
            int distToTarget = log.start("/Drivetrain/DistanceToTarget", "double");

            // 150 cycles @ 20 ms = 3 s: 1.5 s autonomous (run the auto commands), then teleop.
            for (int cycle = 0; cycle < 150; cycle++) {
                long ts = 1_000_000L + cycle * 20_000L;
                boolean autonomous = cycle < 75;
                if (autonomous) {
                    DriverStation.refreshData();
                    scheduler.run(); // real command-based execution (robot enabled in auto)
                    SimulatedArena.getInstance().simulationPeriodic(); // step the physics engine
                } else {
                    if (scoreAuto.isScheduled()) {
                        scoreAuto.cancel();
                    }
                    if (driveAuto.isScheduled()) {
                        driveAuto.cancel();
                    }
                    DriverStationSim.setEnabled(false);
                    DriverStationSim.setAutonomous(false);
                    DriverStationSim.notifyNewData();
                }
                Pose2d pose = drive.getPose();
                log.appendString(state, autonomous ? "AUTO" : "TELEOP", ts);
                log.appendInteger(balls, scoring.ballsScored(), ts);
                log.appendDouble(voltage, Math.round((12.6 - cycle * 0.002) * 100) / 100.0, ts);
                log.appendDouble(poseX, pose.getX(), ts);
                log.appendDouble(poseY, pose.getY(), ts);
                log.appendDouble(distToTarget, DriveAuto.distanceToTarget(pose), ts);
            }
            log.flush();
        }

        Pose2d finalPose = drive.getPose();
        System.out.println("HeadlessSim wrote " + args[0] + " (broken=" + broken
                + ", ballsScored=" + scoring.ballsScored()
                + ", finalPose=" + finalPose
                + ", distanceToTarget=" + DriveAuto.distanceToTarget(finalPose) + ")");
        HAL.shutdown();
        System.exit(0);
    }

    private HeadlessSim() {}
}
