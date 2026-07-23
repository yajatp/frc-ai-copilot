package org.mercsmavs.frccopilot.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PathFileTest {

    // Mirrors the real structure of 6369 Echo's JAMES1.path (trimmed, with an event marker so we
    // can prove non-targeted fields are preserved).
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
                  "nextControl": null, "isLocked": true, "linkedName": null }
              ],
              "rotationTargets": [ {"waypointRelativePos": 0.5, "rotationDegrees": -90.0} ],
              "constraintZones": [],
              "eventMarkers": [ {"name": "shoot", "waypointRelativePos": 1.0} ],
              "globalConstraints": {"maxVelocity": 4.5, "maxAcceleration": 4.0,
                "maxAngularVelocity": 540.0, "maxAngularAcceleration": 720.0, "nominalVoltage": 12.0},
              "goalEndState": {"velocity": 0.0, "rotation": 180.0},
              "reversed": false, "folder": "JamesAuto",
              "idealStartingState": {"velocity": 0.0, "rotation": 0.0},
              "useDefaultConstraints": true
            }
            """;

    @Test
    void translateMovesAnchorAndControlsPreservesEverythingElse() throws Exception {
        PathFile path = PathFile.parse(PATH_JSON);
        PathFile edited = path.copy();
        edited.translateWaypoint(0, 0.10, -0.05);

        List<PathDiff.Change> changes = PathDiff.diff(path.root(), edited.root());
        // anchor.x, anchor.y, nextControl.x, nextControl.y — 4 leaf changes on waypoint 0 only.
        assertEquals(4, changes.size(), () -> "unexpected changes: " + changes);
        assertTrue(changes.stream().allMatch(c -> c.path().startsWith("waypoints[0]")),
                "only waypoint 0 should change");

        double[] a = edited.anchor(0);
        assertEquals(4.54, a[0], 1e-6);
        assertEquals(7.35, a[1], 1e-6);

        // Waypoint 1 and the event marker are untouched.
        assertEquals(7.65, edited.anchor(1)[0], 1e-6);
        assertTrue(edited.toJson().contains("\"shoot\""), "event marker must be preserved");
    }

    @Test
    void refusesToMoveLockedWaypoint() throws Exception {
        PathFile path = PathFile.parse(PATH_JSON);
        assertThrows(IllegalStateException.class, () -> path.translateWaypoint(2, 0.1, 0.1));
    }

    @Test
    void setsGlobalConstraintOnly() throws Exception {
        PathFile path = PathFile.parse(PATH_JSON);
        PathFile edited = path.copy();
        edited.setGlobalConstraint("maxVelocity", 3.0);

        List<PathDiff.Change> changes = PathDiff.diff(path.root(), edited.root());
        assertEquals(1, changes.size());
        assertEquals("globalConstraints.maxVelocity", changes.get(0).path());
        assertEquals(3.0, edited.globalConstraint("maxVelocity"), 1e-9);
        assertEquals(4.0, edited.globalConstraint("maxAcceleration"), 1e-9);
    }
}
