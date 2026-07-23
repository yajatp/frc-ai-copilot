package org.mercsmavs.frccopilot.profile.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.mercsmavs.frccopilot.profile.RobotProfile.Drivetrain;
import org.mercsmavs.frccopilot.profile.RobotProfile.ModuleOffset;

/**
 * Parses PathPlanner's {@code settings.json} — the single richest source of drivetrain physical
 * parameters that already exists in a team's repo (mass, MOI, kinematics, motor, current limit).
 */
public final class PathPlannerSettingsParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static Drivetrain parse(String json) throws IOException {
        JsonNode n = JSON.readTree(json);

        List<ModuleOffset> modules = new ArrayList<>();
        addModule(modules, n, "fl", "flModuleX", "flModuleY");
        addModule(modules, n, "fr", "frModuleX", "frModuleY");
        addModule(modules, n, "bl", "blModuleX", "blModuleY");
        addModule(modules, n, "br", "brModuleX", "brModuleY");

        return new Drivetrain(
                dbl(n, "robotMass"),
                dbl(n, "robotMOI"),
                dbl(n, "robotTrackwidth"),
                dbl(n, "driveWheelRadius"),
                dbl(n, "driveGearing"),
                firstNonNull(dbl(n, "maxDriveSpeed"), dbl(n, "defaultMaxVel")),
                str(n, "driveMotorType"),
                dbl(n, "driveCurrentLimit"),
                dbl(n, "wheelCOF"),
                dbl(n, "robotWidth"),
                dbl(n, "robotLength"),
                modules.isEmpty() ? null : modules);
    }

    private static void addModule(List<ModuleOffset> out, JsonNode n, String name, String xk, String yk) {
        Double x = dbl(n, xk);
        Double y = dbl(n, yk);
        if (x != null && y != null) {
            out.add(new ModuleOffset(name, x, y));
        }
    }

    private static Double dbl(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asDouble();
    }

    private static String str(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Double firstNonNull(Double a, Double b) {
        return a != null ? a : b;
    }

    private PathPlannerSettingsParser() {}
}
