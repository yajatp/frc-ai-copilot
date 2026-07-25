package org.mercsmavs.frccopilot.examplerobot;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.wpilibj2.command.Command;

/**
 * Drives the physically-simulated chassis to a fixed scoring pose with simple proportional
 * control. {@code broken} negates the translation command — the classic "drive axis inverted"
 * wiring bug — so the robot accelerates away from the target instead of toward it; the physics
 * simulation (inertia, damping) then determines exactly how far off course it ends up, which a
 * kinematic-only stub could never reproduce.
 */
public class DriveAuto extends Command {

    static final Pose2d TARGET = new Pose2d(4.0, 2.0, new Rotation2d());
    private static final double KP_TRANSLATION = 2.5;

    private final DriveSubsystem drive;
    private final boolean broken;

    public DriveAuto(DriveSubsystem drive, boolean broken) {
        this.drive = drive;
        this.broken = broken;
        addRequirements(drive);
    }

    @Override
    public void execute() {
        Pose2d pose = drive.getPose();
        double sign = broken ? -1.0 : 1.0;
        double vx = sign * KP_TRANSLATION * (TARGET.getX() - pose.getX());
        double vy = sign * KP_TRANSLATION * (TARGET.getY() - pose.getY());
        drive.drive(new ChassisSpeeds(vx, vy, 0));
    }

    @Override
    public void end(boolean interrupted) {
        drive.stop();
    }

    @Override
    public boolean isFinished() {
        return false; // run for the fixed auto window; the caller cancels it
    }

    public static double distanceToTarget(Pose2d pose) {
        return TARGET.getTranslation().getDistance(pose.getTranslation());
    }
}
