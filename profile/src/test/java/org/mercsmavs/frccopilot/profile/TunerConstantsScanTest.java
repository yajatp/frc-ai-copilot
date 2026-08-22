package org.mercsmavs.frccopilot.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mercsmavs.frccopilot.profile.RobotProfile.Device;
import org.mercsmavs.frccopilot.profile.parse.ConstantsScanner;

/**
 * The CAN ids in {@code TunerConstants.java} are the ones a pit crew is most likely to need and the
 * ones the scanner used to miss entirely: Tuner X generates them as bare named constants, and both
 * of the original patterns required an explanatory comment.
 */
class TunerConstantsScanTest {

    /** Trimmed from real Tuner X output — the declarations verbatim, comments and all. */
    private static final String TUNER_CONSTANTS =
            """
            package frc.robot.generated;

            public class TunerConstants {
                public static final CANBus kCANBus = new CANBus("canivore");
                private static final int kPigeonId = 16;

                private static final int kFrontLeftDriveMotorId = 18;
                private static final int kFrontLeftSteerMotorId = 16;
                private static final int kFrontLeftEncoderId = 11;

                private static final int kBackLeftDriveMotorId = 15;
                private static final int kBackLeftEncoderId = 13;

                private static final int kSteerCurrentLimit = 40;
                private static final double kFrontLeftXPos = 0.276;
            }
            """;

    @Test
    void recoversIdsDeclaredWithoutAComment(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("TunerConstants.java");
        Files.writeString(file, TUNER_CONSTANTS);

        List<Device> devices = ConstantsScanner.scanFile(file, "CTRE");
        List<Integer> ids = devices.stream().map(Device::canId).toList();

        assertTrue(ids.containsAll(List.of(16, 18, 11, 15, 13)),
                () -> "expected the drivetrain ids, got " + ids);
    }

    @Test
    void doesNotMistakeCurrentLimitsOrGeometryForDeviceIds(@TempDir Path tmp) throws Exception {
        // The reason the identifier has to end in "Id": a constants file is full of int tuning
        // values, and a current limit of 40 presented as a CAN id sends someone to a motor that
        // does not exist.
        Path file = tmp.resolve("TunerConstants.java");
        Files.writeString(file, TUNER_CONSTANTS);

        List<Device> devices = ConstantsScanner.scanFile(file, "CTRE");
        assertFalse(devices.stream().anyMatch(d -> d.label().contains("current limit")),
                "a current limit is not a device id");
        assertTrue(devices.stream().allMatch(d -> d.canId() <= 62),
                "CAN ids are 0..62; anything else came from the wrong line");
    }

    @Test
    void labelsReadLikeTheIdentifierTheyCameFrom(@TempDir Path tmp) throws Exception {
        // The identifier is the only description these declarations carry, so it has to survive
        // into the profile in a form a human can match against a wiring diagram.
        Path file = tmp.resolve("TunerConstants.java");
        Files.writeString(file, TUNER_CONSTANTS);

        List<Device> devices = ConstantsScanner.scanFile(file, "CTRE");
        assertEquals(
                "front left drive motor id",
                devices.stream().filter(d -> d.canId() == 18).findFirst().orElseThrow().label());
        assertEquals(
                "pigeon id",
                devices.stream().filter(d -> d.canId() == 16 && d.label().startsWith("pigeon"))
                        .findFirst().orElseThrow().label());
    }

    @Test
    void flagsAnIdClaimedByTwoSubsystems(@TempDir Path tmp) throws Exception {
        // Echo really does declare 13 twice — once as a swerve encoder, once as a transfer motor.
        // Legitimate across two CAN buses, a hunt for an intermittent if not, and worth saying so.
        Path repo = tmp.resolve("robot");
        Path java = repo.resolve("src/main/java/frc/robot");
        Files.createDirectories(java.resolve("generated"));
        Files.createDirectories(java.resolve("subsystems"));
        Files.writeString(java.resolve("generated/TunerConstants.java"), TUNER_CONSTANTS);
        Files.writeString(
                java.resolve("subsystems/TransferConstants.java"),
                """
                public class TransferConstants {
                    public static final int kTransferMotorId = 13;
                }
                """);

        ProfileBuilder.Result result = ProfileBuilder.fromRepo(repo, 6369, "echo", "REBUILT");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("CAN ID 13")),
                () -> "expected a duplicate-id warning, got " + result.warnings());
    }
}
