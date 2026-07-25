package org.mercsmavs.frccopilot.examplerobot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.hal.HAL;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import org.ironmaple.simulation.SimulatedArena;
import org.ironmaple.simulation.seasonspecific.rebuilt2026.Arena2026Rebuilt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DriveTest {

    @BeforeAll
    static void initHal() {
        HAL.initialize(500, 0);
    }

    @AfterAll
    static void shutdownHal() {
        HAL.shutdown();
    }

    private static double runAuto(boolean broken) {
        SimulatedArena.overrideInstance(new Arena2026Rebuilt()); // fresh world per run, no stale robots
        DriveSubsystem drive = new DriveSubsystem(new Pose2d(1.0, 1.0, new Rotation2d()));
        DriveAuto auto = new DriveAuto(drive, broken);
        auto.initialize();
        for (int i = 0; i < 75; i++) {
            auto.execute();
            SimulatedArena.getInstance().simulationPeriodic();
        }
        auto.end(false);
        return DriveAuto.distanceToTarget(drive.getPose());
    }

    @Test
    void fixedDriveReachesTargetBrokenDriveDoesNot() {
        double fixedDistance = runAuto(false);
        double brokenDistance = runAuto(true);
        assertTrue(fixedDistance < 1.0, "fixed auto should end within 1 m of the target, was " + fixedDistance);
        assertTrue(brokenDistance > fixedDistance,
                "inverted-axis auto should end farther from the target than the fixed auto");
    }
}
