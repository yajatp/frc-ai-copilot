package org.mercsmavs.frccopilot.modes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mercsmavs.frccopilot.analysis.Series;
import org.mercsmavs.frccopilot.analysis.SignalResolver;
import org.mercsmavs.frccopilot.ingest.LogEntry;
import org.mercsmavs.frccopilot.ingest.WpilogReader;
import org.mercsmavs.frccopilot.ingest.store.LogSummary;
import org.mercsmavs.frccopilot.ingest.store.TrendStore;

/**
 * CLI for the two operating modes.
 *
 * <pre>
 *   mode-a &lt;db.sqlite&gt; &lt;log.wpilog&gt;   between-match safety pass (ingest + flags + persist metrics)
 *   trends &lt;db.sqlite&gt; &lt;metric&gt;       one metric across every ingested log (season view)
 * </pre>
 */
public final class ModeCli {

    private static final List<String> LOOP_CANDIDATES =
            List.of("loopTimeMs", "LoopTime", "codeRuntime", "PeriodicMs", "loopPeriod");

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("""
                    usage:
                      mode-a <db.sqlite> <log.wpilog>
                      trends <db.sqlite> <metric>
                    """);
            System.exit(2);
            return;
        }
        switch (args[0]) {
            case "mode-a" -> modeA(args[1], args[2]);
            case "trends" -> trends(args[1], args[2]);
            default -> {
                System.err.println("unknown command: " + args[0]);
                System.exit(2);
            }
        }
    }

    private static void modeA(String db, String logPath) throws Exception {
        WpilogReader reader = new WpilogReader(logPath);
        Map<Integer, LogEntry> index = reader.index();
        try (TrendStore store = new TrendStore(db)) {
            long logId = store.ingest(LogSummary.from(reader, index), index.values());
            Series voltage = seriesFor(reader, index, SignalResolver.VOLTAGE);
            Series current = seriesFor(reader, index, SignalResolver.TOTAL_CURRENT);
            Series can = seriesFor(reader, index, SignalResolver.CAN_ERRORS);
            Series loop = seriesFor(reader, index, LOOP_CANDIDATES);
            ModeA.Result result = ModeA.analyze(store, logId, voltage, current, can, loop);
            System.out.print(result.report());
            System.out.println("Overall: " + result.worst() + "  (log #" + logId + ", metrics persisted)");
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

    private static Series seriesFor(WpilogReader reader, Map<Integer, LogEntry> index, List<String> candidates) {
        Optional<String> name = SignalResolver.resolve(index, candidates);
        return name.map(s -> Series.fromSamples(reader.read(s))).orElse(new Series(new double[0], new long[0]));
    }

    private ModeCli() {}
}
