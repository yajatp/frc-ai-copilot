package org.mercsmavs.frccopilot.write;

import java.nio.file.Path;
import java.util.List;

/**
 * CLI for the PathPlanner write layer. Edits are DRY-RUN by default (print the diff); a change is
 * only written when an explicit output file is given — never overwriting the original in place.
 *
 * <pre>
 *   show  &lt;file.path&gt;
 *   fudge &lt;file.path&gt; &lt;waypointIndex&gt; &lt;dxMeters&gt; &lt;dyMeters&gt; [out.path]
 *   speed &lt;file.path&gt; &lt;maxVel&gt; &lt;maxAccel&gt; [out.path]
 * </pre>
 */
public final class PathCli {

    public static void main(String[] args) throws Exception {
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
            default -> {
                usage();
                System.exit(2);
            }
        }
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
                usage:
                  show  <file.path>
                  fudge <file.path> <waypointIndex> <dxMeters> <dyMeters> [out.path]
                  speed <file.path> <maxVel> <maxAccel> [out.path]
                """);
    }

    private PathCli() {}
}
