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
 * change, so everything we don't model (event markers, constraint zones, point-towards zones, etc.)
 * is preserved byte-for-byte in meaning. This is Mode A's actual deliverable: teams don't recompile
 * at events, they fudge waypoints and timing — so we propose exactly those edits.
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

    JsonNode root() {
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
