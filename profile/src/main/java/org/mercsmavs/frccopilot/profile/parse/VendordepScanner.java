package org.mercsmavs.frccopilot.profile.parse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

/** Maps the presence of {@code vendordeps/*.json} files to a set of vendor tags. */
public final class VendordepScanner {

    public static Set<String> scan(Path repoRoot) throws IOException {
        Set<String> vendors = new LinkedHashSet<>();
        Path dir = repoRoot.resolve("vendordeps");
        if (!Files.isDirectory(dir)) {
            return vendors;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString().toLowerCase();
                if (name.contains("phoenix")) vendors.add("CTRE");
                if (name.contains("revlib") || name.startsWith("rev")) vendors.add("REV");
                if (name.contains("advantagekit")) vendors.add("AdvantageKit");
                if (name.contains("pathplanner")) vendors.add("PathPlanner");
                if (name.contains("photon")) vendors.add("PhotonVision");
                if (name.contains("yagsl")) vendors.add("YAGSL");
                if (name.contains("studica") || name.contains("navx")) vendors.add("Studica/navX");
            }
        }
        return vendors;
    }

    /** The primary motor-controller vendor to tag scanned CAN devices with. */
    public static String primaryMotorVendor(Set<String> vendors) {
        if (vendors.contains("CTRE")) return "CTRE";
        if (vendors.contains("REV")) return "REV";
        return "unknown";
    }

    private VendordepScanner() {}
}
