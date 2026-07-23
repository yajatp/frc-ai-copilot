package org.mercsmavs.frccopilot.analysis;

import java.util.Map;
import java.util.Optional;
import org.mercsmavs.frccopilot.ingest.LogEntry;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

/**
 * Runs the Mode-A safety primitives (power/brownout + CAN health) over a single log and prints
 * hedged, pit-actionable findings. Read-only, pure Java (no natives).
 *
 * <pre>
 *   analyze &lt;file.wpilog&gt;
 * </pre>
 */
public final class AnalyzeCli {

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !args[0].equals("analyze")) {
            System.err.println("usage: analyze <file.wpilog>");
            System.exit(2);
            return;
        }
        WpilogReader reader = new WpilogReader(args[1]);
        Map<Integer, LogEntry> index = reader.index();

        System.out.println("== Power / brownout ==");
        Optional<String> voltage = SignalResolver.resolve(index, SignalResolver.VOLTAGE);
        if (voltage.isEmpty()) {
            System.out.println("  No battery-voltage signal found — cannot assess brownout risk.");
        } else {
            Series v = Series.fromSamples(reader.read(voltage.get()));
            PowerAnalysis.Result r = PowerAnalysis.analyze(v);
            System.out.println("  signal: " + voltage.get());
            System.out.println("  " + r.assessment());
            for (PowerAnalysis.BrownoutEvent e : r.events()) {
                System.out.printf(
                        "    - %.2fs–%.2fs  min %.2f V  (%.0f ms)%n",
                        e.startSeconds(), e.endSeconds(), e.minVolts(), e.durationMs());
            }
        }

        System.out.println("== CAN health ==");
        Optional<String> can = SignalResolver.resolve(index, SignalResolver.CAN_ERRORS);
        if (can.isEmpty()) {
            System.out.println("  No CAN error-count signal found.");
        } else {
            Series c = Series.fromSamples(reader.read(can.get()));
            CanHealth.Result r = CanHealth.analyze(c);
            System.out.println("  signal: " + can.get());
            System.out.println("  " + r.assessment());
        }
    }

    private AnalyzeCli() {}
}
