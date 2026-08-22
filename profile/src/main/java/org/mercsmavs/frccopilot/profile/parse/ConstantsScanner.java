package org.mercsmavs.frccopilot.profile.parse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.mercsmavs.frccopilot.profile.RobotProfile.Device;

/**
 * Best-effort scanner that pulls CAN IDs out of a team's Java constants files.
 *
 * <p>We deliberately do NOT attempt full Java parsing — teams write these many ways. We match the
 * two overwhelmingly common shapes and lean on the trailing comment as the human-readable label:
 *
 * <pre>
 *   51, // left motor CAN ID
 *   public static final int kIntakeId = 12; // intake roller
 * </pre>
 *
 * When a comment says "NOT ACCURATE" (real thing seen in 6369's code) the device is flagged
 * {@code accurate=false} so a reviewer knows exactly what to verify.
 */
public final class ConstantsScanner {

    // "  51, // left motor CAN ID"  → id, label
    private static final Pattern POSITIONAL =
            Pattern.compile("^\\s*(\\d{1,3})\\s*,\\s*//\\s*(.+?)\\s*$");
    // "... = 12; // intake roller"  → id, label
    private static final Pattern ASSIGNMENT =
            Pattern.compile("=\\s*(\\d{1,3})\\s*;\\s*//\\s*(.+?)\\s*$");
    // "private static final int kFrontLeftDriveMotorId = 18;"  → identifier, id
    //
    // Both patterns above need a trailing comment, and the file that holds a swerve robot's most
    // safety-relevant IDs has none: Tuner X generates TunerConstants.java without them. Recovering
    // nothing from the single most standardised file in FRC is worse than the odd false positive,
    // so an int constant whose *name* ends in "Id" is taken at its word.
    private static final Pattern IDENTIFIER =
            Pattern.compile("\\bint\\s+(\\w*[Ii][Dd])\\s*=\\s*(\\d{1,3})\\s*;");

    public static List<Device> scanRepo(Path repoRoot, String vendor) throws IOException {
        List<Device> devices = new ArrayList<>();
        Path javaRoot = repoRoot.resolve("src/main/java");
        Path scanRoot = Files.isDirectory(javaRoot) ? javaRoot : repoRoot;
        try (Stream<Path> files = Files.walk(scanRoot)) {
            for (Path f :
                    files.filter(p -> p.getFileName().toString().endsWith("Constants.java"))
                            .toList()) {
                devices.addAll(scanFile(f, vendor));
            }
        }
        return devices;
    }

    public static List<Device> scanFile(Path file, String vendor) throws IOException {
        String subsystem = subsystemOf(file.getFileName().toString());
        String source = file.getFileName().toString();
        List<Device> devices = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            Device d = matchLine(line, subsystem, source, vendor);
            if (d != null) {
                devices.add(d);
            }
        }
        return devices;
    }

    public static Device matchLine(String line, String subsystem, String source, String vendor) {
        Matcher m = POSITIONAL.matcher(line);
        if (!m.find()) {
            m = ASSIGNMENT.matcher(line);
            if (!m.find()) {
                return matchIdentifier(line, subsystem, source, vendor);
            }
        }
        String label = m.group(2).trim();
        // Require the comment to actually be about a device id to cut false positives.
        String lower = label.toLowerCase();
        if (!(lower.contains("id") || lower.contains("can") || lower.contains("motor")
                || lower.contains("encoder"))) {
            return null;
        }
        // Exclude gearing/ratio comments (e.g. "12:20 gearing from motor to flywheel"), which
        // otherwise masquerade as a CAN id because they mention "motor".
        if (lower.contains("gear") || lower.contains("ratio") || label.matches(".*\\d+\\s*:\\s*\\d+.*")) {
            return null;
        }
        int id = Integer.parseInt(m.group(1));
        boolean accurate = !lower.contains("not accurate") && !lower.contains("todo") && !lower.contains("guess");
        return new Device(id, label, subsystem, vendor, source, accurate);
    }

    /**
     * A device id declared as a named int constant with no explanatory comment — the Tuner X
     * house style. The label is read off the identifier, since that is the only description there
     * is; the value stands unless a trailing comment disowns it.
     */
    private static Device matchIdentifier(
            String line, String subsystem, String source, String vendor) {
        Matcher m = IDENTIFIER.matcher(line);
        if (!m.find()) {
            return null;
        }
        String identifier = m.group(1);
        int id = Integer.parseInt(m.group(2));
        String label = humanize(identifier);
        String lower = line.toLowerCase();
        boolean accurate = !lower.contains("not accurate") && !lower.contains("todo")
                && !lower.contains("guess");
        return new Device(id, label, subsystem, vendor, source, accurate);
    }

    /** "kFrontLeftDriveMotorId" → "front left drive motor id". */
    static String humanize(String identifier) {
        String base = identifier.startsWith("k") && identifier.length() > 1
                        && Character.isUpperCase(identifier.charAt(1))
                ? identifier.substring(1)
                : identifier;
        String spaced = base.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
        return spaced.toLowerCase();
    }

    private static String subsystemOf(String fileName) {
        String base = fileName.replace("Constants.java", "").replace(".java", "");
        return base.isEmpty() ? "misc" : base.toLowerCase();
    }

    private ConstantsScanner() {}
}
