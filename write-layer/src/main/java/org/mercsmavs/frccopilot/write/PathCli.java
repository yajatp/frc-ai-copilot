package org.mercsmavs.frccopilot.write;

import java.nio.file.Path;
import java.util.List;

/**
 * CLI for the PathPlanner write layer. Edits are DRY-RUN by default (print the diff); a change is
 * only written when an explicit output file is given — never overwriting the original in place.
 *
 * <pre>
 *   show   &lt;file.path&gt;
 *   fudge  &lt;file.path&gt; &lt;waypointIndex&gt; &lt;dxMeters&gt; &lt;dyMeters&gt; [out.path]
 *   speed  &lt;file.path&gt; &lt;maxVel&gt; &lt;maxAccel&gt; [out.path]
 *   marker &lt;file.path&gt; &lt;markerIndex&gt; &lt;pos|+delta&gt; [endPos|none] [out.path]
 *   zone   &lt;file.path&gt; &lt;zoneIndex&gt; range &lt;minPos&gt; &lt;maxPos&gt; [out.path]
 *   zone   &lt;file.path&gt; &lt;zoneIndex&gt; set &lt;key&gt; &lt;value&gt; [out.path]
 * </pre>
 */
public final class PathCli {

    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException e) {
            // A refused edit is a normal outcome here (locked waypoint, marker off the end of the
            // path), so it reads as one line, not a stack trace. This runs on a pit laptop.
            System.err.println("Refused: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            System.exit(2);
            return;
        }
        String cmd = args[0];
        PathFile path = PathFile.load(Path.of(args[1]));

        switch (cmd) {
            case "show" -> show(path);
            case "fudge" -> {
                int idx = Integer.parseInt(args[2]);
                double dx = Double.parseDouble(args[3]);
                double dy = Double.parseDouble(args[4]);
                PathFile edited = path.copy();
                edited.translateWaypoint(idx, dx, dy);
                applyOrDryRun(path, edited, args.length > 5 ? args[5] : null,
                        String.format("Fudge waypoint %d by (%+.3f, %+.3f) m", idx, dx, dy));
            }
            case "speed" -> {
                double maxVel = Double.parseDouble(args[2]);
                double maxAccel = Double.parseDouble(args[3]);
                PathFile edited = path.copy();
                edited.setGlobalConstraint("maxVelocity", maxVel);
                edited.setGlobalConstraint("maxAcceleration", maxAccel);
                applyOrDryRun(path, edited, args.length > 4 ? args[4] : null,
                        String.format("Set maxVelocity=%.2f, maxAcceleration=%.2f", maxVel, maxAccel));
            }
            case "marker" -> marker(path, args);
            case "zone" -> zone(path, args);
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    /**
     * Retime an event marker. A leading {@code +} or {@code -} on the position makes it a delta,
     * which is the form a between-match tweak usually takes ("start the flywheels a bit earlier").
     */
    private static void marker(PathFile path, String[] args) throws Exception {
        int idx = Integer.parseInt(args[2]);
        String posArg = args[3];
        PathFile edited = path.copy();
        String title;

        // Trailing args: an optional end position (or "none"), then an optional out file.
        String endArg = args.length > 4 && !looksLikeFile(args[4]) ? args[4] : null;
        String out = lastFileArg(args, endArg == null ? 4 : 5);

        if (endArg != null) {
            double pos = absolutePos(path, idx, posArg);
            Double end = "none".equals(endArg) ? null : Double.parseDouble(endArg);
            edited.setEventMarkerRange(idx, pos, end);
            title = String.format("Set marker %d (%s) range to %.4f..%s",
                    idx, path.eventMarker(idx).name(), pos, end == null ? "point" : String.format("%.4f", end));
        } else if (posArg.startsWith("+") || posArg.startsWith("-")) {
            double delta = Double.parseDouble(posArg);
            edited.shiftEventMarker(idx, delta);
            title = String.format("Shift marker %d (%s) by %+.4f",
                    idx, path.eventMarker(idx).name(), delta);
        } else {
            double pos = Double.parseDouble(posArg);
            edited.moveEventMarker(idx, pos);
            title = String.format("Move marker %d (%s) to %.4f",
                    idx, path.eventMarker(idx).name(), pos);
        }
        applyOrDryRun(path, edited, out, title);
    }

    private static void zone(PathFile path, String[] args) throws Exception {
        int idx = Integer.parseInt(args[2]);
        String op = args[3];
        PathFile edited = path.copy();
        String title;
        String out;

        switch (op) {
            case "range" -> {
                double min = Double.parseDouble(args[4]);
                double max = Double.parseDouble(args[5]);
                edited.setConstraintZoneRange(idx, min, max);
                title = String.format("Set zone %d range to %.4f..%.4f", idx, min, max);
                out = lastFileArg(args, 6);
            }
            case "set" -> {
                String key = args[4];
                double value = Double.parseDouble(args[5]);
                edited.setZoneConstraint(idx, key, value);
                title = String.format("Set zone %d %s=%.3f (was %s)",
                        idx, key, value, path.zoneConstraint(idx, key));
                out = lastFileArg(args, 6);
            }
            default -> {
                System.err.println("zone: expected 'range' or 'set', got: " + op);
                System.exit(2);
                return;
            }
        }
        applyOrDryRun(path, edited, out, title);
    }

    /** Resolve a possibly-relative ({@code +0.1}) position against the marker's current one. */
    private static double absolutePos(PathFile path, int idx, String arg) {
        double v = Double.parseDouble(arg);
        return (arg.startsWith("+") || arg.startsWith("-")) ? path.eventMarker(idx).pos() + v : v;
    }

    private static boolean looksLikeFile(String arg) {
        if ("none".equals(arg)) {
            return false;
        }
        try {
            Double.parseDouble(arg);
            return false;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static String lastFileArg(String[] args, int index) {
        return args.length > index ? args[index] : null;
    }

    private static void show(PathFile path) {
        System.out.println("version:    " + path.version());
        System.out.println("waypoints:  " + path.waypointCount());
        for (int i = 0; i < path.waypointCount(); i++) {
            double[] a = path.anchor(i);
            System.out.printf("  [%d] anchor (%.3f, %.3f)%n", i, a[0], a[1]);
        }
        System.out.printf(
                "constraints: maxVel=%s maxAccel=%s%n",
                path.globalConstraint("maxVelocity"), path.globalConstraint("maxAcceleration"));

        // Positions are waypoint-relative, not seconds — see PathFile's class doc.
        System.out.println("event markers: " + path.eventMarkerCount()
                + "  (positions are waypoint-relative, range 0.." + (path.waypointCount() - 1) + ")");
        for (int i = 0; i < path.eventMarkerCount(); i++) {
            PathFile.EventMarker m = path.eventMarker(i);
            System.out.printf("  [%d] %-18s at %.4f%s%n", i, m.name(), m.pos(),
                    m.ranged() ? String.format(" .. %.4f", m.endPos()) : "");
        }
        System.out.println("constraint zones: " + path.constraintZoneCount());
        for (int i = 0; i < path.constraintZoneCount(); i++) {
            PathFile.ConstraintZone z = path.constraintZone(i);
            System.out.printf("  [%d] %-18s %.4f .. %.4f  maxVel=%s maxAccel=%s%n",
                    i, z.name(), z.minPos(), z.maxPos(),
                    path.zoneConstraint(i, "maxVelocity"), path.zoneConstraint(i, "maxAcceleration"));
        }
    }

    private static void applyOrDryRun(PathFile before, PathFile after, String out, String title)
            throws Exception {
        List<PathDiff.Change> changes = PathDiff.diff(before.root(), after.root());
        System.out.println("Proposed: " + title);
        if (changes.isEmpty()) {
            System.out.println("  (no change)");
            return;
        }
        for (PathDiff.Change c : changes) {
            System.out.println("  " + c);
        }
        if (out == null) {
            System.out.println("\nDry run (no output file given). Re-run with an out path to write a copy.");
        } else {
            after.save(Path.of(out));
            System.out.println("\nWrote edited path to: " + out);
        }
    }

    private static void usage() {
        System.err.println(
                """
                frc-ai-copilot pathplanner writer
                Edits are dry-run by default; give an out path to write a copy.

                usage:
                  show   <file.path>
                  fudge  <file.path> <waypointIndex> <dxMeters> <dyMeters> [out.path]
                  speed  <file.path> <maxVel> <maxAccel> [out.path]
                  marker <file.path> <markerIndex> <pos|+delta> [endPos|none] [out.path]
                  zone   <file.path> <zoneIndex> range <minPos> <maxPos> [out.path]
                  zone   <file.path> <zoneIndex> set <key> <value> [out.path]

                Marker and zone positions are waypoint-relative (0..waypointCount-1), not seconds:
                the integer part is a waypoint index, the fraction is progress along the next
                segment. A leading + or - on <pos> makes it a delta from where the marker is now.

                  marker auto.path 3 -0.15                # fire marker 3 earlier (dry run)
                  marker auto.path 3 -0.15 out.path       # ...and write the result to a copy
                  zone   auto.path 0 set maxVelocity 1.8  # slow down through zone 0 only
                """);
    }

    private PathCli() {}
}
