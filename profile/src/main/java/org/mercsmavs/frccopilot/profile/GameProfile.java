package org.mercsmavs.frccopilot.profile;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Field/game data, kept separate from the robot profile so it can be shared and updated
 * independently. Values that could not be verified are flagged in {@code notes} rather than
 * presented as precise fact.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GameProfile(
        String game,
        int season,
        double fieldLengthM,
        double fieldWidthM,
        double autoSeconds,
        double teleopSeconds,
        double endgameSeconds,
        List<ScoringZone> scoringZones,
        List<FieldObstacle> obstacles,
        List<String> notes) {

    /** A named region of the field, as an axis-aligned rectangle (meters, field coordinates). */
    public record ScoringZone(String name, double x, double y, double widthM, double lengthM) {}

    /**
     * A field obstacle relevant to robot geometry/clearance (e.g. a trench a tall mechanism can't
     * fit under, or a bump). {@code maxClearanceM} is null when not applicable/known.
     */
    public record FieldObstacle(String name, String description, Double maxClearanceM) {}

    public double matchSeconds() {
        return autoSeconds + teleopSeconds;
    }
}
