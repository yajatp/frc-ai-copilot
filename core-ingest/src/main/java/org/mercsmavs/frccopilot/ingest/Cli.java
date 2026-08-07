package org.mercsmavs.frccopilot.ingest;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.mercsmavs.frccopilot.ingest.store.LogSummary;
import org.mercsmavs.frccopilot.ingest.store.TrendStore;

/**
 * Minimal command-line entry point for the ingestion core. This is a developer/verification
 * harness; the same primitives are what the MCP server will expose as tools.
 *
 * <pre>
 *   info    &lt;file.wpilog&gt;            log version, header, entry count, duration
 *   entries &lt;file.wpilog&gt; [substr]  list entries (optionally filtered by name substring)
 *   dump    &lt;file.wpilog&gt; &lt;entry&gt;    print decoded samples for one entry (timestamp value)
 * </pre>
 */
public final class Cli {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            System.exit(2);
            return;
        }

        String command = args[0];

        // Store-oriented commands take a db path as their first argument.
        switch (command) {
            case "gen" -> {
                generateSample(args[1]);
                System.out.println("Wrote synthetic log: " + args[1]);
                return;
            }
            case "ingest" -> {
                if (args.length < 3) {
                    System.err.println("ingest requires <db> <file.wpilog>");
                    System.exit(2);
                    return;
                }
                ingest(args[1], args[2]);
                return;
            }
            case "logs" -> {
                listStoredLogs(args[1]);
                return;
            }
            case "trend" -> {
                if (args.length < 3) {
                    System.err.println("trend requires <db> <metric>");
                    System.exit(2);
                    return;
                }
                trend(args[1], args[2]);
                return;
            }
            default -> {
                // fall through to log-file commands below
            }
        }

        // Log-file commands take a .wpilog path as their first argument.
        String file = args[1];
        WpilogReader reader = new WpilogReader(file);
        switch (command) {
            case "info" -> info(reader);
            case "entries" -> entries(reader, args.length > 2 ? args[2] : null);
            case "dump" -> {
                if (args.length < 3) {
                    System.err.println("dump requires an entry name");
                    System.exit(2);
                    return;
                }
                dump(reader, args[2]);
            }
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    private static void ingest(String db, String file) throws Exception {
        WpilogReader reader = new WpilogReader(file);
        Map<Integer, LogEntry> index = reader.index();
        LogSummary summary = LogSummary.from(reader, index);
        try (TrendStore store = new TrendStore(db)) {
            long id = store.ingest(summary, index.values());
            System.out.printf(
                    "Ingested log #%d: %d entries, %.2f s, git=%s%n",
                    id, index.size(), summary.durationSeconds(),
                    summary.gitSha() == null ? "(none)" : summary.gitSha());
        }
    }

    private static void listStoredLogs(String db) throws Exception {
        try (TrendStore store = new TrendStore(db)) {
            System.out.printf("%-5s %-11s %-9s  %s%n", "ID", "MATCH", "DUR(s)", "PATH");
            for (TrendStore.LogRow r : store.listLogs()) {
                System.out.printf(
                        "%-5d %-11s %-9.2f  %s%n",
                        r.id(), r.matchKey() == null ? "-" : r.matchKey(), r.durationSeconds(), r.path());
            }
        }
    }

    private static void trend(String db, String metric) throws Exception {
        try (TrendStore store = new TrendStore(db)) {
            List<TrendStore.MetricPoint> points = store.trend(metric);
            if (points.isEmpty()) {
                System.out.println("No data for metric: " + metric);
                return;
            }
            System.out.printf("%-6s %-11s %-10s %s%n", "LOG", "MATCH", "PHASE", "VALUE");
            for (TrendStore.MetricPoint p : points) {
                System.out.printf(
                        "%-6d %-11s %-10s %.4f %s%n",
                        p.logId(), p.matchKey() == null ? "-" : p.matchKey(),
                        p.phase() == null || p.phase().isEmpty() ? "-" : p.phase(),
                        p.value(), p.unit() == null ? "" : p.unit());
            }
        }
    }

    private static void info(WpilogReader reader) {
        Map<Integer, LogEntry> index = reader.index();
        long totalSamples = index.values().stream().mapToLong(LogEntry::count).sum();
        double duration = index.values().stream().mapToDouble(LogEntry::spanSeconds).max().orElse(0.0);

        System.out.printf("file:          %s%n", reader.path());
        System.out.printf("wpilog version: %d%n", reader.version());
        System.out.printf("extra header:  %s%n", reader.extraHeader());
        System.out.printf("entries:       %d%n", index.size());
        System.out.printf("total samples: %d%n", totalSamples);
        System.out.printf("duration:      %.2f s (longest signal span)%n", duration);
    }

    private static void entries(WpilogReader reader, String filter) {
        Map<Integer, LogEntry> index = reader.index();
        System.out.printf("%-8s %-10s %-9s  %s%n", "SAMPLES", "TYPE", "SPAN(s)", "NAME");
        index.values().stream()
                .filter(e -> filter == null || e.name.toLowerCase().contains(filter.toLowerCase()))
                .sorted((a, b) -> a.name.compareToIgnoreCase(b.name))
                .forEach(e -> System.out.printf(
                        "%-8d %-10s %-9.2f  %s%n", e.count(), e.type, e.spanSeconds(), e.name));
    }

    private static void dump(WpilogReader reader, String entryName) {
        List<WpilogReader.Sample> samples = reader.read(entryName);
        if (samples.isEmpty()) {
            System.err.println("No samples found for entry: " + entryName);
            return;
        }
        for (WpilogReader.Sample s : samples) {
            System.out.printf("%.4f\t%s%n", s.timestampSeconds(), render(s.value()));
        }
    }

    private static String render(Object value) {
        if (value instanceof byte[] bytes) {
            return "<raw " + bytes.length + " bytes>";
        }
        if (value instanceof double[] a) {
            return Arrays.toString(a);
        }
        if (value instanceof long[] a) {
            return Arrays.toString(a);
        }
        if (value instanceof float[] a) {
            return Arrays.toString(a);
        }
        if (value instanceof boolean[] a) {
            return Arrays.toString(a);
        }
        if (value instanceof Object[] a) {
            return Arrays.toString(a);
        }
        return String.valueOf(value);
    }

    /**
     * Write a full-match synthetic log with findable faults. See {@link SampleLogGenerator} — the
     * generator lives there so tests and the demo path share exactly one fixture definition.
     */
    private static void generateSample(String file) throws Exception {
        SampleLogGenerator.write(file);
    }

    private static void usage() {
        System.err.println("""
                frc-ai-copilot ingest
                usage:
                  info    <file.wpilog>
                  entries <file.wpilog> [name-substring]
                  dump    <file.wpilog> <entry-name>
                  gen     <file.wpilog>                 write a synthetic 150 s match log
                  ingest  <db.sqlite> <file.wpilog>     parse + persist a log summary
                  logs    <db.sqlite>                   list ingested logs
                  trend   <db.sqlite> <metric>          one metric across all logs
                """);
    }

    private Cli() {}
}
