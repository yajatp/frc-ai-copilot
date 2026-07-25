package org.mercsmavs.frccopilot.examplerobot;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import org.ironmaple.simulation.SimulatedArena;
import org.ironmaple.simulation.drivesims.SwerveDriveSimulation;
import org.ironmaple.simulation.drivesims.configs.DriveTrainSimulationConfig;

/**
 * A swerve drivetrain backed by maple-sim's physics simulation (dyn4j rigid-body dynamics: real
 * mass, momentum, and wheel friction) rather than a kinematic integrator — so driving bugs (wrong
 * axis sign, bad gains, wheel slip) surface in the produced {@code .wpilog} the way they would on
 * an actual chassis, instead of only in mechanism-level counters like {@link ScoringSubsystem}.
 */
public class DriveSubsystem extends SubsystemBase {

    private final SwerveDriveSimulation drivetrain;

    public DriveSubsystem(Pose2d startPose) {
        drivetrain = new SwerveDriveSimulation(DriveTrainSimulationConfig.Default(), startPose);
        SimulatedArena.getInstance().addDriveTrainSimulation(drivetrain);
    }

    /** Command a field-relative chassis velocity for the current control cycle. */
    public void drive(ChassisSpeeds fieldRelativeSpeeds) {
        drivetrain.setRobotSpeeds(fieldRelativeSpeeds);
    }

    public void stop() {
        drive(new ChassisSpeeds());
    }

    public Pose2d getPose() {
        return drivetrain.getSimulatedDriveTrainPose();
    }
}
