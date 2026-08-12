package org.mercsmavs.frccopilot.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Event-marker timing and constraint-zone edits.
 *
 * <p>The fixture mirrors the real structure of 6369 Echo's JAMES1.path more completely than
 * {@link PathFileTest}'s does — four waypoints, a point marker, a ranged marker, and two constraint
 * zones with the full nested {@code constraints} object — because the fields most likely to be
 * clobbered by a careless edit are exactly the ones a trimmed fixture leaves out.
 */
class PathTimingTest {

    /** Gradle runs tests with the module dir as the working dir, so this is repo-root-relative. */
    private static final Path ECHO_PATHS =
            Path.of("../reference/echo/src/main/deploy/pathplanner/paths");

    private static final String PATH_JSON =
            """
            {
              "version": "2025.0",
              "waypoints": [
                { "anchor": {"x": 4.44, "y": 7.40}, "prevControl": null,
                  "nextControl": {"x": 8.30, "y": 7.51}, "isLocked": false, "linkedName": null },
                { "anchor": {"x": 7.65, "y": 4.77}, "prevControl": {"x": 7.69, "y": 6.06},
                  "nextControl": {"x": 7.62, "y": 3.58}, "isLocked": false, "linkedName": null },
                { "anchor": {"x": 3.22, "y": 5.67}, "prevControl": {"x": 4.16, "y": 5.45},
                  "nextControl": {"x": 2.80, "y": 5.80}, "isLocked": false, "linkedName": null },
                { "anchor": {"x": 1.90, "y": 6.10}, "prevControl": {"x": 2.40, "y": 6.00},
                  "nextControl": null, "isLocked": false, "linkedName": null }
              ],
              "rotationTargets": [ {"waypointRelativePos": 0.5, "rotationDegrees": -90.0} ],
              "constraintZones": [
                { "name": "Tight passage", "minWaypointRelativePos": 0.5804274465691804,
                  "maxWaypointRelativePos": 1.0731158605174465,
                  "constraints": {"maxVelocity": 2.0, "maxAcceleration": 4.0,
                    "maxAngularVelocity": 540.0, "maxAngularAcceleration": 720.0,
                    "nominalVoltage": 12.0, "unlimited": false} },
                { "name": "Approach", "minWaypointRelativePos": 2.1074204946996646,
                  "maxWaypointRelativePos": 2.8791519434629116,
                  "constraints": {"maxVelocity": 3.2, "maxAcceleration": 4.0,
                    "maxAngularVelocity": 540.0, "maxAngularAcceleration": 720.0,
                    "nominalVoltage": 12.0, "unlimited": false} }
              ],
              "pointTowardsZones": [],
              "eventMarkers": [
                { "name": "StartFlywheels", "waypointRelativePos": 0.29770776854610875,
                  "endWaypointRelativePos": null,
                  "command": {"type": "named", "data": {"name": "StartFlywheels"}} },
                { "name": "IntakeRun", "waypointRelativePos": 1.20,
                  "endWaypointRelativePos": 1.80,
                  "command": {"type": "named", "data": {"name": "IntakeRun"}} }
              ],
              "globalConstraints": {"maxVelocity": 4.5, "maxAcceleration": 4.0,
                "maxAngularVelocity": 540.0, "maxAngularAcceleration": 720.0, "nominalVoltage": 12.0},
              "goalEndState": {"velocity": 0.0, "rotation": 180.0},
              "reversed": false, "folder": "JamesAuto",
              "idealStartingState": {"velocity": 0.0, "rotation": 0.0},
              "useDefaultConstraints": true
            }
            """;

    private static PathFile path() throws Exception {
        return PathFile.parse(PATH_JSON);
    }

    // --- read ---

    @Test
    void readsMarkersAndZones() throws Exception {
        PathFile p = path();
        assertEquals(2, p.eventMarkerCount());
        assertEquals(2, p.constraintZoneCount());

        PathFile.EventMarker point = p.eventMarker(0);
        assertEquals("StartFlywheels", point.name());
        assertEquals(0.29770776854610875, point.pos(), 1e-12);
        assertNull(point.endPos());
        assertTrue(!point.ranged());

        PathFile.EventMarker ranged = p.eventMarker(1);
        assertEquals(1.20, ranged.pos(), 1e-9);
        assertEquals(1.80, ranged.endPos(), 1e-9);
        assertTrue(ranged.ranged());

        assertEquals("Tight passage", p.constraintZone(0).name());
        assertEquals(2.0, p.zoneConstraint(0, "maxVelocity"), 1e-9);
        assertNull(p.zoneConstraint(0, "nonexistentKey"));
    }

    // --- marker timing ---

    @Test
    void movingAPointMarkerChangesOnlyItsPosition() throws Exception {
        PathFile before = path();
        PathFile after = before.copy();
        after.moveEventMarker(0, 0.15);

        List<PathDiff.Change> changes = PathDiff.diff(before.root(), after.root());
        assertEquals(1, changes.size(), () -> "unexpected changes: " + changes);
        assertEquals("eventMarkers[0].waypointRelativePos", changes.get(0).path());
        assertEquals(0.15, after.eventMarker(0).pos(), 1e-9);
        // The command body is what makes the marker do anything — it must survive untouched.
        assertEquals("StartFlywheels",
                after.root().get("eventMarkers").get(0).get("command").get("data").get("name").asText());
    }

    @Test
    void shiftingAMarkerIsRelativeToWhereItIs() throws Exception {
        PathFile p = path();
        double was = p.eventMarker(0).pos();
        p.shiftEventMarker(0, -0.10);
        assertEquals(was - 0.10, p.eventMarker(0).pos(), 1e-6);
    }

    @Test
    void movingARangedMarkerPreservesItsLength() throws Exception {
        // "Fire the intake earlier" must not also change how long it runs.
        PathFile p = path();
        double length = p.eventMarker(1).endPos() - p.eventMarker(1).pos();
        p.moveEventMarker(1, 0.90);

        assertEquals(0.90, p.eventMarker(1).pos(), 1e-9);
        assertEquals(0.90 + length, p.eventMarker(1).endPos(), 1e-9);
        assertEquals(length, p.eventMarker(1).endPos() - p.eventMarker(1).pos(), 1e-9);
    }

    @Test
    void setRangeChangesTheLengthAndCanConvertToAPointMarker() throws Exception {
        PathFile p = path();
        p.setEventMarkerRange(1, 1.00, 2.50);
        assertEquals(1.00, p.eventMarker(1).pos(), 1e-9);
        assertEquals(2.50, p.eventMarker(1).endPos(), 1e-9);

        p.setEventMarkerRange(1, 1.00, null);
        assertNull(p.eventMarker(1).endPos(), "null end means a point marker");
        assertTrue(p.root().get("eventMarkers").get(1).get("endWaypointRelativePos").isNull(),
                "and it is written as JSON null, the way PathPlanner represents it");
    }

    @Test
    void rejectsPositionsOffTheEndOfThePath() throws Exception {
        PathFile p = path();
        // 4 waypoints => valid range is [0, 3]. PathPlanner silently ignores a marker past the end,
        // so an out-of-range retime would look applied and do nothing.
        assertThrows(IllegalArgumentException.class, () -> p.moveEventMarker(0, 3.5));
        assertThrows(IllegalArgumentException.class, () -> p.moveEventMarker(0, -0.1));
        assertThrows(IllegalArgumentException.class, () -> p.moveEventMarker(0, Double.NaN));
        // A ranged marker whose preserved end would fall off the path is rejected too.
        assertThrows(IllegalArgumentException.class, () -> p.moveEventMarker(1, 2.9));
        assertThrows(IllegalArgumentException.class, () -> p.setEventMarkerRange(1, 2.0, 1.0));
        assertThrows(IndexOutOfBoundsException.class, () -> p.moveEventMarker(9, 1.0));
    }

    @Test
    void aRejectedEditLeavesTheMarkerAlone() throws Exception {
        PathFile p = path();
        double was = p.eventMarker(1).pos();
        Double wasEnd = p.eventMarker(1).endPos();
        assertThrows(IllegalArgumentException.class, () -> p.moveEventMarker(1, 2.9));
        assertEquals(was, p.eventMarker(1).pos(), 1e-12, "start must not move on a rejected edit");
        assertEquals(wasEnd, p.eventMarker(1).endPos(), 1e-12, "nor the end");
    }

    // --- constraint zones ---

    @Test
    void movingAZoneChangesOnlyItsBounds() throws Exception {
        PathFile before = path();
        PathFile after = before.copy();
        after.setConstraintZoneRange(0, 0.70, 1.20);

        List<PathDiff.Change> changes = PathDiff.diff(before.root(), after.root());
        assertEquals(2, changes.size(), () -> "unexpected changes: " + changes);
        assertTrue(changes.stream().allMatch(c -> c.path().startsWith("constraintZones[0].")
                        && c.path().endsWith("WaypointRelativePos")),
                () -> "only the zone's bounds should move: " + changes);
        // The zone's constraints are untouched by a move.
        assertEquals(2.0, after.zoneConstraint(0, "maxVelocity"), 1e-9);
    }

    @Test
    void slowingOneZoneDoesNotTouchGlobalSpeedOrTheOtherZone() throws Exception {
        PathFile before = path();
        PathFile after = before.copy();
        after.setZoneConstraint(0, "maxVelocity", 1.8);

        List<PathDiff.Change> changes = PathDiff.diff(before.root(), after.root());
        assertEquals(1, changes.size(), () -> "unexpected changes: " + changes);
        assertEquals("constraintZones[0].constraints.maxVelocity", changes.get(0).path());
        assertEquals(4.5, after.globalConstraint("maxVelocity"), 1e-9, "global speed unchanged");
        assertEquals(3.2, after.zoneConstraint(1, "maxVelocity"), 1e-9, "zone 1 unchanged");
        // Sibling keys inside the same constraints object survive.
        assertEquals(false, after.root().get("constraintZones").get(0)
                .get("constraints").get("unlimited").asBoolean());
    }

    @Test
    void rejectsAnInvertedOrOffPathZone() throws Exception {
        PathFile p = path();
        assertThrows(IllegalArgumentException.class, () -> p.setConstraintZoneRange(0, 1.5, 0.5));
        assertThrows(IllegalArgumentException.class, () -> p.setConstraintZoneRange(0, 0.5, 3.5));
        assertThrows(IndexOutOfBoundsException.class, () -> p.setConstraintZoneRange(7, 0.5, 1.5));
    }

    // --- round-trip fidelity against real paths on disk ---

    @Test
    void everyRealReferencePathRoundTripsUnchangedWhenNothingIsEdited() throws Exception {
        // The strongest available check that we do not model the schema wrongly: parse each real
        // 6369 Echo path and re-serialize it with no edit. Any field we mishandle shows up as a diff.
        Path dir = ECHO_PATHS;
        // `/reference/` is a gitignored read-only clone of the team robot repo, so this can be
        // absent. Skip visibly rather than returning — a test that silently passes when its data is
        // missing reports green on a fresh checkout while having checked nothing.
        assumeTrue(Files.isDirectory(dir), "reference/echo not present in this working tree");
        try (var files = Files.list(dir)) {
            List<Path> paths = files.filter(f -> f.toString().endsWith(".path")).sorted().toList();
            assertTrue(paths.size() > 5, "expected the reference paths to be present");
            for (Path file : paths) {
                PathFile p = PathFile.load(file);
                PathFile reparsed = PathFile.parse(p.toJson());
                assertEquals(List.of(), PathDiff.diff(p.root(), reparsed.root()),
                        () -> "re-serializing " + file.getFileName() + " changed it");
            }
        }
    }

    @Test
    void retimingARealPathChangesExactlyOneLeaf(@TempDir Path tmp) throws Exception {
        Path file = ECHO_PATHS.resolve("JAMES1.path");
        assumeTrue(Files.exists(file), "reference/echo not present in this working tree");
        PathFile before = PathFile.load(file);
        assertEquals(5, before.eventMarkerCount(), "JAMES1 has five markers");
        assertEquals(2, before.constraintZoneCount(), "and two constraint zones");

        PathFile after = before.copy();
        after.shiftEventMarker(3, -0.05); // StartFlywheels, slightly earlier

        List<PathDiff.Change> changes = PathDiff.diff(before.root(), after.root());
        assertEquals(1, changes.size(), () -> "unexpected changes: " + changes);
        assertEquals("eventMarkers[3].waypointRelativePos", changes.get(0).path());

        // And writing it out is a real file the original is not clobbered by.
        Path out = tmp.resolve("JAMES1-retimed.path");
        after.save(out);
        assertEquals(1, PathDiff.diff(PathFile.load(file).root(), PathFile.load(out).root()).size());
    }
}
