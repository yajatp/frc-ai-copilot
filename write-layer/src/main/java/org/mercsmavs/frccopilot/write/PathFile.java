package org.mercsmavs.frccopilot.write;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A PathPlanner {@code .path} file, edited with full round-trip fidelity.
 *
 * <p>We keep the whole document as a Jackson tree and only mutate the specific fields we intend to
 * change, so everything we don't model (rotation targets, point-towards zones, command bodies, etc.)
 * is preserved byte-for-byte in meaning. This is Mode A's actual deliverable: teams don't recompile
 * at events, they fudge waypoints and timing — so we propose exactly those edits.
 *
 * <p><b>On "timing".</b> PathPlanner does not position event markers or constraint zones in
 * seconds; it positions them by <em>waypoint-relative position</em>, a float where the integer part
 * is a waypoint index and the fraction is progress along the following segment. A path with N
 * waypoints therefore spans {@code [0, N-1]}. Retiming "start the flywheels earlier" means moving a
 * marker to a smaller relative position — wall-clock time follows from the constraints, and is not
 * something this file stores.
 *
 * <p>Schema confirmed against real 6369 Echo paths (e.g. JAMES1.path): top-level {@code version},
 * {@code waypoints[{anchor{x,y}, prevControl, nextControl, isLocked, linkedName}]},
 * {@code rotationTargets[{waypointRelativePos, rotationDegrees}]}, {@code globalConstraints},
 * {@code goalEndState}, {@code idealStartingState}, {@code constraintZones}, {@code eventMarkers}, …
 */
public final class PathFile {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ObjectNode root;

    public PathFile(ObjectNode root) {
        this.root = root;
    }

    public static PathFile parse(String json) throws IOException {
        JsonNode node = JSON.readTree(json);
        if (!(node instanceof ObjectNode obj)) {
            throw new IOException("Not a PathPlanner path object");
        }
        return new PathFile(obj);
    }

    public static PathFile load(Path file) throws IOException {
        return parse(Files.readString(file));
    }

    /** Pretty-printed JSON (2-space indent, matching PathPlanner's own output style). */
    public String toJson() throws IOException {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
    }

    public void save(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, toJson());
    }

    /** A deep copy, so callers can diff before/after. */
    public PathFile copy() {
        return new PathFile(root.deepCopy());
    }

    /** The underlying JSON tree (read view), e.g. for diffing before/after an edit. */
    public JsonNode root() {
        return root;
    }

    // --- read ---

    public String version() {
        return root.path("version").asText(null);
    }

    public int waypointCount() {
        return waypoints().size();
    }

    public double[] anchor(int i) {
        ObjectNode a = (ObjectNode) waypoints().get(i).get("anchor");
        return new double[] {a.get("x").asDouble(), a.get("y").asDouble()};
    }

    // --- edit: the "fudge a waypoint" operation (most common between-match change) ---

    /**
     * Translate a waypoint by (dx, dy) meters — moving its anchor AND its control handles together
     * so the surrounding spline shape is preserved (a true positional fudge, not a shape change).
     * Refuses to move a locked waypoint.
     */
    public void translateWaypoint(int index, double dx, double dy) {
        ObjectNode wp = (ObjectNode) waypoints().get(index);
        if (wp.path("isLocked").asBoolean(false)) {
            throw new IllegalStateException("waypoint " + index + " is locked; not moving it");
        }
        shift((ObjectNode) wp.get("anchor"), dx, dy);
        shiftIfPresent(wp, "prevControl", dx, dy);
        shiftIfPresent(wp, "nextControl", dx, dy);
    }

    // --- edit: timing/kinematics (the other common tweak) ---

    /** Set a field on globalConstraints, e.g. maxVelocity / maxAcceleration. */
    public void setGlobalConstraint(String key, double value) {
        ObjectNode gc = (ObjectNode) root.get("globalConstraints");
        if (gc == null) {
            throw new IllegalStateException("path has no globalConstraints");
        }
        gc.put(key, value);
    }

    public Double globalConstraint(String key) {
        JsonNode gc = root.get("globalConstraints");
        return gc == null || gc.get(key) == null ? null : gc.get(key).asDouble();
    }

    // --- read: event markers and constraint zones ---

    /**
     * An event marker's timing. {@code endPos} is null for a point marker and set for a ranged one
     * (PathPlanner's "zoned" marker, which runs its command across an interval).
     */
    public record EventMarker(int index, String name, double pos, Double endPos) {
        public boolean ranged() {
            return endPos != null;
        }
    }

    /** The interval a constraint zone covers. Its overrides are read via {@link #zoneConstraint}. */
    public record ConstraintZone(int index, String name, double minPos, double maxPos) {}

    public int eventMarkerCount() {
        return array("eventMarkers").size();
    }

    public EventMarker eventMarker(int index) {
        ObjectNode m = (ObjectNode) element("eventMarkers", index);
        JsonNode end = m.get("endWaypointRelativePos");
        return new EventMarker(
                index,
                m.path("name").asText(null),
                m.path("waypointRelativePos").asDouble(),
                end == null || end.isNull() ? null : end.asDouble());
    }

    public int constraintZoneCount() {
        return array("constraintZones").size();
    }

    public ConstraintZone constraintZone(int index) {
        ObjectNode z = (ObjectNode) element("constraintZones", index);
        return new ConstraintZone(
                index,
                z.path("name").asText(null),
                z.path("minWaypointRelativePos").asDouble(),
                z.path("maxWaypointRelativePos").asDouble());
    }

    /** A constraint override inside a zone, or null if the zone does not set that key. */
    public Double zoneConstraint(int index, String key) {
        JsonNode c = element("constraintZones", index).get("constraints");
        return c == null || c.get(key) == null || c.get(key).isNull() ? null : c.get(key).asDouble();
    }

    // --- edit: event-marker timing ---

    /**
     * Move an event marker to an absolute waypoint-relative position. A ranged marker is
     * <em>translated</em> — its end moves with its start, preserving the interval's length, because
     * "fire the intake earlier" should not also change how long it runs. Use
     * {@link #setEventMarkerRange} to change the length itself.
     */
    public void moveEventMarker(int index, double pos) {
        ObjectNode m = (ObjectNode) element("eventMarkers", index);
        double from = m.path("waypointRelativePos").asDouble();
        JsonNode end = m.get("endWaypointRelativePos");
        if (end != null && !end.isNull()) {
            double length = end.asDouble() - from;
            checkPos(pos, "start");
            checkPos(pos + length, "end (start + preserved length)");
            m.put("endWaypointRelativePos", round(pos + length));
        } else {
            checkPos(pos, "position");
        }
        m.put("waypointRelativePos", round(pos));
    }

    /** Retime a marker by a delta, the shape most between-match tweaks actually take. */
    public void shiftEventMarker(int index, double delta) {
        moveEventMarker(index, eventMarker(index).pos() + delta);
    }

    /**
     * Set both ends of a ranged marker. Passing a null {@code endPos} converts a ranged marker back
     * to a point marker, which is how PathPlanner itself represents "not zoned".
     */
    public void setEventMarkerRange(int index, double pos, Double endPos) {
        ObjectNode m = (ObjectNode) element("eventMarkers", index);
        checkPos(pos, "start");
        if (endPos == null) {
            m.putNull("endWaypointRelativePos");
        } else {
            checkPos(endPos, "end");
            if (endPos < pos) {
                throw new IllegalArgumentException(
                        "marker end (" + endPos + ") is before its start (" + pos + ")");
            }
            m.put("endWaypointRelativePos", round(endPos));
        }
        m.put("waypointRelativePos", round(pos));
    }

    // --- edit: constraint zones ---

    /** Move or resize a constraint zone's interval. */
    public void setConstraintZoneRange(int index, double minPos, double maxPos) {
        ObjectNode z = (ObjectNode) element("constraintZones", index);
        checkPos(minPos, "zone min");
        checkPos(maxPos, "zone max");
        if (maxPos < minPos) {
            throw new IllegalArgumentException(
                    "zone max (" + maxPos + ") is before its min (" + minPos + ")");
        }
        z.put("minWaypointRelativePos", round(minPos));
        z.put("maxWaypointRelativePos", round(maxPos));
    }

    /**
     * Set one constraint override inside a zone, e.g. {@code maxVelocity} to slow the robot through
     * a tight passage without touching the path's global speed.
     */
    public void setZoneConstraint(int index, String key, double value) {
        ObjectNode z = (ObjectNode) element("constraintZones", index);
        JsonNode c = z.get("constraints");
        if (!(c instanceof ObjectNode constraints)) {
            throw new IllegalStateException("constraint zone " + index + " has no constraints object");
        }
        constraints.put(key, value);
    }

    /**
     * Reject a position outside {@code [0, waypointCount-1]}. PathPlanner silently ignores a marker
     * placed off the end of the path, so an out-of-range retime would look applied and do nothing.
     */
    private void checkPos(double pos, String what) {
        double max = waypointCount() - 1;
        if (Double.isNaN(pos) || pos < 0 || pos > max) {
            throw new IllegalArgumentException(
                    what + " " + pos + " is outside this path's range [0, " + max + "]"
                            + " (" + waypointCount() + " waypoints)");
        }
    }

    private ArrayNode array(String field) {
        JsonNode n = root.get(field);
        return n instanceof ArrayNode arr ? arr : JSON.createArrayNode();
    }

    private JsonNode element(String field, int index) {
        ArrayNode arr = array(field);
        if (index < 0 || index >= arr.size()) {
            throw new IndexOutOfBoundsException(
                    field + " index " + index + " out of range (" + arr.size() + " present)");
        }
        JsonNode n = arr.get(index);
        if (!n.isObject()) {
            throw new IllegalStateException(field + "[" + index + "] is not an object");
        }
        return n;
    }

    private ArrayNode waypoints() {
        JsonNode wp = root.get("waypoints");
        if (!(wp instanceof ArrayNode arr)) {
            throw new IllegalStateException("path has no waypoints array");
        }
        return arr;
    }

    private static void shiftIfPresent(ObjectNode wp, String field, double dx, double dy) {
        JsonNode n = wp.get(field);
        if (n != null && n.isObject()) {
            shift((ObjectNode) n, dx, dy);
        }
    }

    private static void shift(ObjectNode point, double dx, double dy) {
        point.put("x", round(point.get("x").asDouble() + dx));
        point.put("y", round(point.get("y").asDouble() + dy));
    }

    private static double round(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }
}
