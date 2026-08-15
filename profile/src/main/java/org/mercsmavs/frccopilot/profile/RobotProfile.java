package org.mercsmavs.frccopilot.profile;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A team-agnostic description of one robot. Designed so a different team can drop in their own
 * profile and get analysis specific to their machine with no code changes — the context that
 * generic log analysis has no way to know.
 *
 * <p>Most of this is <em>bootstrapped</em> from the team's existing repo (see {@link ProfileBuilder})
 * rather than hand-authored, then human-reviewed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RobotProfile(
        int team,
        String robot,
        Integer season,
        String game,
        List<String> vendors,
        Drivetrain drivetrain,
        List<Device> devices,
        List<String> subsystems,
        FieldProfile field,
        List<String> notes,
        List<Mechanism> mechanisms) {

    /**
     * A robot mechanism relevant to field-clearance reasoning (e.g. a hopper whose height must fit
     * under a trench). Heights are left null for a human to fill in — we don't guess them.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Mechanism(String name, String type, Integer degreesOfFreedom, Double maxHeightMeters) {}

    /** Swerve drivetrain physical parameters (primarily sourced from pathplanner/settings.json). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Drivetrain(
            Double massKg,
            Double moiKgM2,
            Double trackwidthM,
            Double wheelRadiusM,
            Double gearing,
            Double maxSpeedMps,
            String driveMotor,
            Double driveCurrentLimitA,
            Double wheelCof,
            Double robotWidthM,
            Double robotLengthM,
            List<ModuleOffset> moduleOffsets) {}

    public record ModuleOffset(String name, double xM, double yM) {}

    /**
     * A CAN (or other) device pulled from the robot code.
     *
     * @param accurate false when the source comment flagged the ID as unverified (e.g. "NOT
     *     ACCURATE") — surfaced so a human reviewer knows exactly what to double-check.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Device(
            Integer canId, String label, String subsystem, String vendor, String source, boolean accurate) {}

    /** Field/game geometry, kept separate from the robot-specific profile. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldProfile(String game, Integer season, String aprilTagField, Double lengthM, Double widthM) {}
}
