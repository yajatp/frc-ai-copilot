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
    /** The season year WPILib embeds in a field-layout constant, e.g. {@code k2025ReefscapeWelded}. */
    private static final Pattern LAYOUT_YEAR = Pattern.compile("(20\\d{2})");

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
        // Seed mechanism entries from subsystem names; heights left null for a human to fill in.
        List<RobotProfile.Mechanism> mechanisms = subsystems == null ? null
                : subsystems.stream()
                        .map(s -> new RobotProfile.Mechanism(s, null, null, null))
                        .toList();
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
                        warnings.isEmpty() ? null : new ArrayList<>(warnings),
                        mechanisms);
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
        Integer season = GameData.seasonForGame(game).orElse(null);
        if (game != null && season == null) {
            warnings.add(
                    "Unknown game '" + game + "' — no bundled field data, so the profile has no season."
                            + " Known: " + String.join(", ", GameData.knownGames()) + ".");
        }

        Path constants = repoRoot.resolve("src/main/java/frc/robot/Constants.java");
        String aprilTagField = null;
        if (Files.exists(constants)) {
            Matcher m = APRILTAG.matcher(Files.readString(constants));
            if (m.find()) {
                aprilTagField = m.group(1);
                warnStaleFieldLayout(aprilTagField, game, season, warnings);
            }
        }
        return new FieldProfile(game, season, aprilTagField, null, null);
    }

    /**
     * Flag robot code still loading a prior season's AprilTag layout — a common and expensive
     * carry-over at the start of a build season, since every vision pose lands on last year's field.
     *
     * <p>Detected by comparing the year embedded in the layout name (WPILib names them like
     * {@code k2025ReefscapeWelded}) against the profile's season, rather than by matching this
     * season's game name. The year comparison keeps working next season with no code change; the
     * name match had to be edited every year, which is exactly the kind of thing that gets forgotten.
     */
    private static void warnStaleFieldLayout(
            String aprilTagField, String game, Integer season, List<String> warnings) {
        if (season == null) {
            return;
        }
        Matcher year = LAYOUT_YEAR.matcher(aprilTagField);
        if (!year.find()) {
            return; // no year in the name; nothing reliable to compare
        }
        int layoutSeason = Integer.parseInt(year.group(1));
        if (layoutSeason != season) {
            warnings.add(
                    "AprilTag layout in code is '" + aprilTagField + "' (" + layoutSeason
                            + ") but this profile is " + season + " (" + game + ") — vision poses"
                            + " would be resolved against the wrong field. Confirm the layout.");
        }
    }

    private ProfileBuilder() {}
}
