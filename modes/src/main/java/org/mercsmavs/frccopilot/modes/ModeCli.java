package org.mercsmavs.frccopilot.modes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.mercsmavs.frccopilot.ingest.store.TrendStore;

/**
 * CLI for the two operating modes.
 *
 * <pre>
 *   mode-a &lt;db.sqlite&gt; &lt;log.wpilog&gt;   between-match safety pass (ingest + flags + persist metrics)
 *   watch  &lt;db.sqlite&gt; &lt;dir&gt;...       daemon: run the Mode A pass on each new log that appears
 *   trends &lt;db.sqlite&gt; &lt;metric&gt;       one metric across every ingested log (season view)
 * </pre>
 */
public final class ModeCli {

    /** How often the watcher rescans, and how many same-size polls mean "done being written". */
    private static final long DEFAULT_POLL_MS = 2_000;
    private static final int DEFAULT_STABLE_CHECKS = 2;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("""
                    usage:
                      mode-a <db.sqlite> <log.wpilog>
                      watch  <db.sqlite> <dir>... [--poll-ms N] [--stable-checks N] [--once]
                      trends <db.sqlite> <metric>
                    """);
            System.exit(2);
            return;
        }
        switch (args[0]) {
            case "mode-a" -> modeA(args[1], args[2]);
            case "watch" -> watch(args);
            case "trends" -> trends(args[1], args[2]);
            default -> {
                System.err.println("unknown command: " + args[0]);
                System.exit(2);
            }
        }
    }

    private static void modeA(String db, String logPath) throws Exception {
        try (TrendStore store = new TrendStore(db)) {
            ModeAPass.Outcome outcome = ModeAPass.run(store, logPath);
            System.out.print(outcome.result().report());
            System.out.println(
                    "Overall: " + outcome.result().worst()
                            + "  (log #" + outcome.logId() + ", metrics persisted)");
        }
    }

    /**
     * Mode A's automatic trigger. Blocks, scanning the given directories until interrupted; the
     * pit-crew workflow is to leave this running in a terminal with the dashboard open on the same
     * database.
     */
    private static void watch(String[] args) throws Exception {
        String db = args[1];
        List<Path> roots = new ArrayList<>();
        long pollMs = DEFAULT_POLL_MS;
        int stableChecks = DEFAULT_STABLE_CHECKS;
        boolean once = false;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--poll-ms" -> pollMs = Long.parseLong(args[++i]);
                case "--stable-checks" -> stableChecks = Integer.parseInt(args[++i]);
                case "--once" -> once = true;
                default -> roots.add(Path.of(args[i]));
            }
        }
        if (roots.isEmpty()) {
            System.err.println("watch: give at least one directory to watch");
            System.exit(2);
            return;
        }

        try (TrendStore store = new TrendStore(db)) {
            LogWatcher watcher =
                    new LogWatcher(store, roots, stableChecks, event -> {
                        System.out.println();
                        System.out.println("=== new log: " + LogWatcher.describe(event));
                        if (event instanceof LogWatcher.Event.Analyzed a) {
                            System.out.print(a.outcome().result().report());
                        }
                        System.out.flush();
                    });
            System.out.println("Watching " + roots + " (poll " + pollMs + " ms), "
                    + watcher.ingestedPaths().size() + " log(s) already ingested.");
            System.out.println("Metrics land in " + db + " — point the dashboard at the same file.");

            // `--once` still needs `stableChecks` passes to satisfy the size-stability rule — one
            // poll can only ever establish a file's first size reading, never confirm it settled.
            int remaining = once ? stableChecks : -1;
            while (remaining != 0) {
                watcher.poll();
                if (remaining > 0 && --remaining == 0) {
                    break;
                }
                Thread.sleep(pollMs);
            }
        }
    }

    private static void trends(String db, String metric) throws Exception {
        try (TrendStore store = new TrendStore(db)) {
            List<TrendStore.MetricPoint> points = store.trend(metric);
            if (points.isEmpty()) {
                System.out.println("No data for metric: " + metric);
                return;
            }
            System.out.printf("%-6s %-12s %s%n", "LOG", "MATCH", "VALUE");
            for (TrendStore.MetricPoint p : points) {
                System.out.printf("%-6d %-12s %.4f %s%n", p.logId(),
                        p.matchKey() == null ? "-" : p.matchKey(), p.value(), p.unit() == null ? "" : p.unit());
            }
        }
    }

    private ModeCli() {}
}
