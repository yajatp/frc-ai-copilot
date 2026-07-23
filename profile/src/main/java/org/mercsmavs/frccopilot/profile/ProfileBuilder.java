package org.mercsmavs.frccopilot.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.mercsmavs.frccopilot.profile.RobotProfile.Device;
import org.mercsmavs.frccopilot.profile.RobotProfile.Drivetrain;
import org.mercsmavs.frccopilot.profile.RobotProfile.FieldProfile;
import org.mercsmavs.frccopilot.profile.parse.ConstantsScanner;
import org.mercsmavs.frccopilot.profile.parse.PathPlannerSettingsParser;
import org.mercsmavs.frccopilot.profile.parse.VendordepScanner;

/** Composes a {@link RobotProfile} by parsing an on-disk WPILib robot repository. */
public final class ProfileBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern APRILTAG = Pattern.compile("AprilTagFields\\.(\\w+)");

    /** Result of a build: the profile plus any advisory warnings for the reviewer. */
    public record Result(RobotProfile profile, List<String> warnings) {}

    public static Result fromRepo(Path repoRoot, Integer teamOverride, String robotOverride, String game)
            throws IOException {
        List<String> warnings = new ArrayList<>();

        Set<String> vendors = VendordepScanner.scan(repoRoot);
        String primaryVendor = VendordepScanner.primaryMotorVendor(vendors);

        Drivetrain drivetrain = null;
        Path settings = repoRoot.resolve("src/main/deploy/pathplanner/settings.json");
        if (Files.exists(settings)) {
            drivetrain = PathPlannerSettingsParser.parse(Files.readString(settings));
        } else {
            warnings.add("No pathplanner/settings.json found — drivetrain params unknown.");
        }

        List<Device> devices = ConstantsScanner.scanRepo(repoRoot, primaryVendor);
        long unverified = devices.stream().filter(d -> !d.accurate()).count();
        if (unverified > 0) {
            warnings.add(unverified + " CAN ID(s) flagged unverified in code (NOT ACCURATE/TODO) — review these.");
        }
        if (devices.isEmpty()) {
            warnings.add("No CAN IDs recovered from *Constants.java — check by hand.");
        }

        Integer team = teamOverride != null ? teamOverride : readTeamNumber(repoRoot);
        if (team == null) {
            warnings.add("Team number not found in .wpilib/wpilib_preferences.json.");
            team = 0;
        }
        String robot = robotOverride != null ? robotOverride : repoRoot.getFileName().toString();

        List<String> subsystems = listSubsystems(repoRoot);
        FieldProfile field = readField(repoRoot, game, warnings);

        RobotProfile profile =
                new RobotProfile(
                        team,
                        robot,
                        field != null ? field.season() : null,
                        game,
                        new ArrayList<>(vendors),
                        drivetrain,
                        devices,
                        subsystems,
                        field,
                        warnings.isEmpty() ? null : new ArrayList<>(warnings));
        return new Result(profile, warnings);
    }

    private static Integer readTeamNumber(Path repoRoot) throws IOException {
        Path prefs = repoRoot.resolve(".wpilib/wpilib_preferences.json");
        if (!Files.exists(prefs)) {
            return null;
        }
        JsonNode n = JSON.readTree(Files.readString(prefs));
        JsonNode team = n.get("teamNumber");
        return team == null || team.isNull() ? null : team.asInt();
    }

    private static List<String> listSubsystems(Path repoRoot) throws IOException {
        List<String> subsystems = new ArrayList<>();
        Path dir = repoRoot.resolve("src/main/java/frc/robot/subsystems");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> entries = Files.list(dir)) {
                entries.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .forEach(subsystems::add);
            }
        }
        return subsystems.isEmpty() ? null : subsystems;
    }

    private static FieldProfile readField(Path repoRoot, String game, List<String> warnings)
            throws IOException {
        Path constants = repoRoot.resolve("src/main/java/frc/robot/Constants.java");
        String aprilTagField = null;
        if (Files.exists(constants)) {
            Matcher m = APRILTAG.matcher(Files.readString(constants));
            if (m.find()) {
                aprilTagField = m.group(1);
                // 2026 is REBUILT; flag if the code still loads a prior-year field layout.
                if (game != null && game.equalsIgnoreCase("REBUILT")
                        && !aprilTagField.toLowerCase().contains("rebuilt")) {
                    warnings.add(
                            "AprilTag layout in code is '" + aprilTagField
                                    + "' but game is REBUILT — confirm the correct 2026 field data.");
                }
            }
        }
        Integer season = game != null && game.equalsIgnoreCase("REBUILT") ? 2026 : null;
        return new FieldProfile(game, season, aprilTagField, null, null);
    }

    private ProfileBuilder() {}
}
