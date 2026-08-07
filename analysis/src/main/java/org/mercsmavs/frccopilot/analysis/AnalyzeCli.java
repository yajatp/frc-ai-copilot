package org.mercsmavs.frccopilot.analysis;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mercsmavs.frccopilot.ingest.LogEntry;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

/**
 * Command-line front end to the analysis primitives. Every primitive the MCP server exposes is
 * reachable here too, so a human in the pit can get the same answers without an agent in the loop.
 * Read-only, pure Java (no natives).
 *
 * <pre>
 *   analyze  &lt;file.wpilog&gt;              Mode-A safety sweep (power, battery, CAN, loop timing)
 *   full     &lt;file.wpilog&gt;              safety sweep plus swerve/vision/cycles where resolvable
 *   entries  &lt;file.wpilog&gt; [filter]     list signals, optionally substring-filtered
 *   stats    &lt;file.wpilog&gt; &lt;entry&gt;      min/max/mean/median/stdDev/p95
 *   quality  &lt;file.wpilog&gt; &lt;entry&gt;      sampling density/regularity and confidence
 *   anomaly  &lt;file.wpilog&gt; &lt;entry&gt;      robust (MAD) outlier detection
 *   peaks    &lt;file.wpilog&gt; &lt;entry&gt; [minProminence]
 *   roc      &lt;file.wpilog&gt; &lt;entry&gt;      rate of change (units/second)
 *   cycles   &lt;file.wpilog&gt; [entry]      scoring cycle time from a counter
 *   swerve   &lt;file.wpilog&gt; [entry]      closed-loop oscillation check
 *   vision   &lt;file.wpilog&gt;              detection rate / dropouts
 *   loop     &lt;file.wpilog&gt;              loop overruns vs 20 ms
 *   correlate &lt;file.wpilog&gt; &lt;entryA&gt; &lt;entryB&gt;
 *   compare  &lt;fileA&gt; &lt;entryA&gt; &lt;fileB&gt; &lt;entryB&gt;
 * </pre>
 */
public final class AnalyzeCli {

    private static final String USAGE =
            """
            usage: analyze <command> <args>

              analyze   <file.wpilog>                     Mode-A safety sweep
              full      <file.wpilog>                     safety sweep + swerve/vision/cycles
              entries   <file.wpilog> [filter]            list signals
              stats     <file.wpilog> <entry>             summary statistics
              quality   <file.wpilog> <entry>             sampling quality / confidence
              anomaly   <file.wpilog> <entry>             robust outlier detection
              peaks     <file.wpilog> <entry> [minProm]   local maxima (default prominence 1.0)
              roc       <file.wpilog> <entry>             rate of change (units/s)
              cycles    <file.wpilog> [entry]             scoring cycle time
              swerve    <file.wpilog> [entry]             closed-loop oscillation
              vision    <file.wpilog>                     detection rate / dropouts
              loop      <file.wpilog>                     loop overruns vs 20 ms
              correlate <file.wpilog> <entryA> <entryB>   Pearson correlation
              compare   <fileA> <entryA> <fileB> <entryB> compare two signals

            Every result carries a data-quality caveat. A single match is rarely conclusive.
            """;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.print(USAGE);
            System.exit(2);
            return;
        }
        String command = args[0];
        try {
            switch (command) {
                case "analyze" -> safetySweep(reader(args[1]));
                case "full" -> full(reader(args[1]));
                case "entries" -> entries(reader(args[1]), args.length > 2 ? args[2] : null);
                case "stats" -> stats(reader(args[1]), require(args, 2, "entry"));
                case "quality" -> quality(reader(args[1]), require(args, 2, "entry"));
                case "anomaly" -> anomaly(reader(args[1]), require(args, 2, "entry"));
                case "peaks" -> peaks(reader(args[1]), require(args, 2, "entry"),
                        args.length > 3 ? Double.parseDouble(args[3]) : 1.0);
                case "roc" -> rateOfChange(reader(args[1]), require(args, 2, "entry"));
                case "cycles" -> cycles(reader(args[1]), args.length > 2 ? args[2] : null);
                case "swerve" -> swerve(reader(args[1]), args.length > 2 ? args[2] : null);
                case "vision" -> vision(reader(args[1]));
                case "loop" -> loopTiming(reader(args[1]));
                case "correlate" -> correlate(reader(args[1]), require(args, 2, "entryA"), require(args, 3, "entryB"));
                case "compare" -> compare(args);
                default -> {
                    System.err.println("unknown command: " + command);
                    System.err.print(USAGE);
                    System.exit(2);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(2);
        }
    }

    // --- commands ---

    /** The Mode-A pit sweep: the four things that decide whether the robot survives the next match. */
    private static void safetySweep(WpilogReader r) {
        Map<Integer, LogEntry> index = r.index();

        System.out.println("== Power / brownout ==");
        Optional<String> voltage = SignalResolver.resolve(index, SignalResolver.VOLTAGE);
        if (voltage.isEmpty()) {
            System.out.println("  No battery-voltage signal found — cannot assess brownout risk.");
        } else {
            Series v = series(r, voltage.get());
            PowerAnalysis.Result res = PowerAnalysis.analyze(v);
            System.out.println("  signal: " + voltage.get());
            System.out.println("  " + res.assessment());
            for (PowerAnalysis.BrownoutEvent e : res.events()) {
                System.out.printf(
                        "    - %.2fs–%.2fs  min %.2f V  (%.0f ms)%n",
                        e.startSeconds(), e.endSeconds(), e.minVolts(), e.durationMs());
            }

            System.out.println("== Battery health ==");
            Series current = seriesOrEmpty(r, index, SignalResolver.TOTAL_CURRENT);
            System.out.println("  " + BatteryHealth.analyze(v, current.isEmpty() ? null : current).assessment());
        }

        System.out.println("== CAN health ==");
        Optional<String> can = SignalResolver.resolve(index, SignalResolver.CAN_ERRORS);
        if (can.isEmpty()) {
            System.out.println("  No CAN error-count signal found.");
        } else {
            System.out.println("  signal: " + can.get());
            System.out.println("  " + CanHealth.analyze(series(r, can.get())).assessment());
        }

        System.out.println("== Loop timing ==");
        Optional<String> loop = SignalResolver.resolve(index, SignalResolver.LOOP_PERIOD);
        if (loop.isEmpty()) {
            System.out.println("  No loop-timing signal found.");
        } else {
            System.out.println("  signal: " + loop.get());
            System.out.println("  " + LoopTiming.analyze(series(r, loop.get())).assessment());
        }
    }

    /** Everything the safety sweep covers, plus the Mode-B primitives that can auto-resolve. */
    private static void full(WpilogReader r) {
        safetySweep(r);
        Map<Integer, LogEntry> index = r.index();

        System.out.println("== Swerve ==");
        Optional<String> sw = SignalResolver.resolve(index, SignalResolver.SWERVE);
        if (sw.isEmpty()) {
            System.out.println("  No swerve module signal found.");
        } else {
            System.out.println("  signal: " + sw.get());
            System.out.println("  " + SwerveAnalysis.analyze(series(r, sw.get())).assessment());
        }

        System.out.println("== Vision ==");
        Optional<String> vi = SignalResolver.resolve(index, SignalResolver.VISION);
        if (vi.isEmpty()) {
            System.out.println("  No vision detection signal found.");
        } else {
            System.out.println("  signal: " + vi.get());
            System.out.println("  " + VisionAnalysis.analyze(series(r, vi.get()), null).assessment());
        }

        System.out.println("== Scoring cycles ==");
        Optional<String> cy = SignalResolver.resolve(index, SignalResolver.CYCLE_COUNTER);
        if (cy.isEmpty()) {
            System.out.println("  No cycle/score counter signal found.");
        } else {
            System.out.println("  signal: " + cy.get());
            System.out.println("  " + CycleTime.analyze(series(r, cy.get())).assessment());
        }
    }

    private static void entries(WpilogReader r, String filter) {
        String needle = filter == null ? null : filter.toLowerCase();
        r.index().values().stream()
                .filter(e -> needle == null || e.name.toLowerCase().contains(needle))
                .sorted((x, y) -> x.name.compareToIgnoreCase(y.name))
                .forEach(e -> System.out.printf("%-8d %-8s %s%n", e.count(), e.type, e.name));
    }

    private static void stats(WpilogReader r, String entry) {
        Series s = requireSeries(r, entry);
        Statistics.Result st = Statistics.of(s);
        System.out.printf("signal: %s%n", entry);
        System.out.printf("  min=%.4f max=%.4f mean=%.4f%n", st.min(), st.max(), st.mean());
        System.out.printf("  median=%.4f stdDev=%.4f p95=%.4f%n", st.median(), st.stdDev(), st.p95());
        System.out.println("  " + st.quality().caveat());
    }

    private static void quality(WpilogReader r, String entry) {
        DataQuality q = DataQuality.of(series(r, entry).timestampsUs());
        System.out.printf("signal: %s%n", entry);
        System.out.printf("  samples=%d span=%.2fs medianPeriod=%.2fms maxGap=%.2fms%n",
                q.sampleCount(), q.spanSeconds(), q.medianPeriodMs(), q.maxGapMs());
        System.out.println("  confidence=" + q.confidence());
        System.out.println("  " + q.caveat());
    }

    private static void anomaly(WpilogReader r, String entry) {
        System.out.println("signal: " + entry);
        System.out.println("  " + AnomalyDetection.detect(requireSeries(r, entry)).assessment());
    }

    private static void peaks(WpilogReader r, String entry, double minProminence) {
        PeakFinder.Result res = PeakFinder.find(requireSeries(r, entry), minProminence);
        System.out.println("signal: " + entry);
        System.out.println("  " + res.assessment());
        for (PeakFinder.Peak p : res.peaks()) {
            System.out.printf("    - %.3fs  %.4f%n", p.timeSeconds(), p.value());
        }
    }

    private static void rateOfChange(WpilogReader r, String entry) {
        Series s = requireSeries(r, entry);
        if (s.size() < 2) {
            System.out.println("Fewer than two numeric samples for '" + entry + "'; no slope to report.");
            return;
        }
        RateOfChange.Result res = RateOfChange.of(s);
        System.out.println("signal: " + entry);
        System.out.printf("  maxSlope=%.4f/s (at %.3fs) minSlope=%.4f/s meanAbsSlope=%.4f/s%n",
                res.maxSlope(), res.maxSlopeTimeSeconds(), res.minSlope(), res.meanAbsSlope());
        System.out.println("  " + res.quality().caveat());
    }

    private static void cycles(WpilogReader r, String entry) {
        String signal = resolveOrGiven(r, entry, SignalResolver.CYCLE_COUNTER,
                "No cycle/score counter found; pass an entry name explicitly.");
        System.out.println("signal: " + signal);
        System.out.println("  " + CycleTime.analyze(series(r, signal)).assessment());
    }

    private static void swerve(WpilogReader r, String entry) {
        String signal = resolveOrGiven(r, entry, SignalResolver.SWERVE,
                "No swerve module signal found; pass a velocity/voltage entry name explicitly.");
        System.out.println("signal: " + signal);
        System.out.println("  " + SwerveAnalysis.analyze(series(r, signal)).assessment());
    }

    private static void vision(WpilogReader r) {
        String signal = resolveOrGiven(r, null, SignalResolver.VISION, "No vision detection signal found.");
        System.out.println("signal: " + signal);
        System.out.println("  " + VisionAnalysis.analyze(series(r, signal), null).assessment());
    }

    private static void loopTiming(WpilogReader r) {
        String signal = resolveOrGiven(r, null, SignalResolver.LOOP_PERIOD, "No loop-timing signal found.");
        System.out.println("signal: " + signal);
        System.out.println("  " + LoopTiming.analyze(series(r, signal)).assessment());
    }

    private static void correlate(WpilogReader r, String entryA, String entryB) {
        Correlation.Result res = Correlation.of(requireSeries(r, entryA), requireSeries(r, entryB));
        System.out.println("a: " + entryA);
        System.out.println("b: " + entryB);
        System.out.println("  " + res.assessment());
    }

    private static void compare(String[] args) throws Exception {
        String fileA = args[1];
        String entryA = require(args, 2, "entryA");
        String fileB = require(args, 3, "fileB");
        String entryB = require(args, 4, "entryB");
        Series a = requireSeries(reader(fileA), entryA);
        Series b = requireSeries(reader(fileB), entryB);
        System.out.println("a: " + entryA + "  (" + fileA + ")");
        System.out.println("b: " + entryB + "  (" + fileB + ")");
        System.out.println("  " + Compare.of(a, b).assessment());
    }

    // --- helpers ---

    private static WpilogReader reader(String file) throws Exception {
        return new WpilogReader(file);
    }

    private static Series series(WpilogReader r, String entry) {
        return Series.fromSamples(r.read(entry));
    }

    /** Like {@link #series} but refuses to print statistics about nothing. */
    private static Series requireSeries(WpilogReader r, String entry) {
        Series s = series(r, entry);
        if (s.isEmpty()) {
            throw new IllegalArgumentException("no numeric samples for '" + entry + "' (check the name with 'entries')");
        }
        return s;
    }

    private static Series seriesOrEmpty(WpilogReader r, Map<Integer, LogEntry> index, List<String> candidates) {
        return SignalResolver.resolve(index, candidates)
                .map(name -> series(r, name))
                .orElse(new Series(new double[0], new long[0]));
    }

    private static String resolveOrGiven(WpilogReader r, String given, List<String> candidates, String failure) {
        if (given != null) {
            return given;
        }
        return SignalResolver.resolve(r.index(), candidates)
                .orElseThrow(() -> new IllegalArgumentException(failure));
    }

    private static String require(String[] args, int i, String name) {
        if (args.length <= i) {
            throw new IllegalArgumentException("missing required argument: " + name);
        }
        return args[i];
    }

    private AnalyzeCli() {}
}
