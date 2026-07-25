package org.mercsmavs.frccopilot.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mercsmavs.frccopilot.profile.RobotProfile.Device;
import org.mercsmavs.frccopilot.profile.RobotProfile.Drivetrain;
import org.mercsmavs.frccopilot.profile.RobotProfile.FieldProfile;
import org.mercsmavs.frccopilot.profile.parse.ConstantsScanner;
import org.mercsmavs.frccopilot.profile.parse.PathPlannerSettingsParser;

class ProfileTest {

    // Real fields from 6369 Echo's pathplanner/settings.json.
    private static final String ECHO_SETTINGS =
            """
            {
              "robotMass": 54.4, "robotMOI": 6.883, "robotTrackwidth": 0.546,
              "driveWheelRadius": 0.0508, "driveGearing": 6.026785714285714,
              "maxDriveSpeed": 5.24, "driveMotorType": "krakenX60FOC",
              "driveCurrentLimit": 60.0, "wheelCOF": 1.2,
              "robotWidth": 0.9, "robotLength": 0.9,
              "flModuleX": 0.276, "flModuleY": 0.276,
              "frModuleX": 0.276, "frModuleY": -0.276,
              "blModuleX": -0.276, "blModuleY": 0.276,
              "brModuleX": -0.276, "brModuleY": -0.276
            }
            """;

    @Test
    void parsesPathPlannerSettings() throws Exception {
        Drivetrain d = PathPlannerSettingsParser.parse(ECHO_SETTINGS);
        assertEquals(54.4, d.massKg(), 1e-9);
        assertEquals(6.883, d.moiKgM2(), 1e-9);
        assertEquals(0.0508, d.wheelRadiusM(), 1e-9);
        assertEquals("krakenX60FOC", d.driveMotor());
        assertEquals(60.0, d.driveCurrentLimitA(), 1e-9);
        assertEquals(4, d.moduleOffsets().size());
    }

    @Test
    void scansCanIdsAndFlagsUnverified() {
        // The exact shape from 6369's ShooterConstants.java (positional args with trailing comments).
        String label = "flywheel";
        Device good = ConstantsScanner.matchLine("        51, // left motor CAN ID", label, "ShooterConstants.java", "CTRE");
        assertEquals(51, good.canId());
        assertTrue(good.accurate());

        Device flagged =
                ConstantsScanner.matchLine("        2, // right two motor CAN ID NOT ACCURATE", label, "ShooterConstants.java", "CTRE");
        assertEquals(2, flagged.canId());
        assertFalse(flagged.accurate(), "NOT ACCURATE must be flagged for review");

        // Assignment form.
        Device assign =
                ConstantsScanner.matchLine("  public static final int kIntakeId = 12; // intake roller motor", "intake", "IntakeConstants.java", "CTRE");
        assertEquals(12, assign.canId());

        // A comment with no id-ish words is ignored.
        assertEquals(null, ConstantsScanner.matchLine("  42, // arbitrary tuning value", label, "X.java", "CTRE"));
    }

    @Test
    void yamlRoundTrips(@TempDir Path tmp) throws Exception {
        RobotProfile p =
                new RobotProfile(
                        6369,
                        "Echo",
                        2026,
                        "REBUILT",
                        List.of("CTRE", "AdvantageKit", "PathPlanner"),
                        PathPlannerSettingsParser.parse(ECHO_SETTINGS),
                        List.of(new Device(51, "left motor CAN ID", "flywheel", "CTRE", "ShooterConstants.java", true)),
                        List.of("shooter", "intake", "drive"),
                        new FieldProfile("REBUILT", 2026, "k2025ReefscapeWelded", null, null),
                        List.of("test note"),
                        List.of(new RobotProfile.Mechanism("hopper", "roller", 1, 0.9)));

        Path file = tmp.resolve("6369.yaml");
        ProfileMapper.write(p, file, "header line");
        RobotProfile back = ProfileMapper.read(file);

        assertEquals(0.9, back.mechanisms().get(0).maxHeightMeters(), 1e-9);
        assertEquals(6369, back.team());
        assertEquals("krakenX60FOC", back.drivetrain().driveMotor());
        assertEquals(1, back.devices().size());
        assertEquals("k2025ReefscapeWelded", back.field().aprilTagField());
    }

    @Test
    void gameDataHasSaneValues() {
        GameProfile rebuilt = GameData.forSeason(2026).orElseThrow();
        assertEquals(2026, rebuilt.season());
        assertTrue(rebuilt.fieldLengthM() > 0 && rebuilt.fieldWidthM() > 0);
        assertEquals(150.0, rebuilt.matchSeconds(), 1e-9);
        assertTrue(rebuilt.obstacles().stream().anyMatch(o -> o.name().equals("trench")));
        // 2025 also present; an unknown season returns empty.
        assertTrue(GameData.forSeason(2025).isPresent());
        assertTrue(GameData.forSeason(1999).isEmpty());
    }

    @Test
    void profileWithoutMechanismsStillLoads(@TempDir Path tmp) throws Exception {
        // Backward compatibility: YAML generated before the mechanisms field must still parse.
        Path f = tmp.resolve("old.yaml");
        java.nio.file.Files.writeString(f, "team: 6369\nrobot: Old\nseason: 2026\ngame: REBUILT\n");
        RobotProfile back = ProfileMapper.read(f);
        assertEquals(6369, back.team());
        assertEquals(null, back.mechanisms());
    }
}
