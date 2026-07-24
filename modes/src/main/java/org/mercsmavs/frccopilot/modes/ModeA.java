package org.mercsmavs.frccopilot.modes;

import java.util.ArrayList;
import java.util.List;
import org.mercsmavs.frccopilot.analysis.BatteryHealth;
import org.mercsmavs.frccopilot.analysis.CanHealth;
import org.mercsmavs.frccopilot.analysis.LoopTiming;
import org.mercsmavs.frccopilot.analysis.PowerAnalysis;
import org.mercsmavs.frccopilot.analysis.Series;
import org.mercsmavs.frccopilot.ingest.store.TrendStore;

/**
 * Mode A (live/competition): the fast, narrow, between-match safety pass. It runs only the
 * pit-actionable checks — brownout / battery / CAN / loop timing — persists their results to the
 * trend store (so season queries have data), and emits a plain-language flag report. It deliberately
 * does NOT run sim sweeps or deep audits (that's Mode B).
 */
public final class ModeA {

    public enum Severity {
        OK,
        WATCH,
        CRITICAL
    }

    public record Flag(Severity severity, String area, String message) {}

    public record Result(long logId, List<Flag> flags, String report) {
        public Severity worst() {
            return flags.stream().map(Flag::severity).max(java.util.Comparator.naturalOrder()).orElse(Severity.OK);
        }
    }

    /**
     * Run the Mode A checks against already-resolved signals, persisting metrics/events under
     * {@code logId}. Any signal may be null/empty (that check is skipped).
     */
    public static Result analyze(
            TrendStore store, long logId, Series voltage, Series current, Series can, Series loopMs)
            throws java.sql.SQLException {
        List<Flag> flags = new ArrayList<>();

        if (voltage != null && !voltage.isEmpty()) {
            PowerAnalysis.Result power = PowerAnalysis.analyze(voltage);
            store.recordMetric(logId, "min_voltage", "MATCH", power.minVolts(), "V", power.quality().confidence().name());
            if (power.brownoutRisk()) {
                flags.add(new Flag(Severity.CRITICAL, "battery/power",
                        "Brownout risk: " + power.assessment()));
                for (PowerAnalysis.BrownoutEvent e : power.events()) {
                    store.recordEvent(logId, (long) (e.startSeconds() * 1_000_000), "brownout", "CRITICAL",
                            String.format("min %.2fV for %.0fms", e.minVolts(), e.durationMs()));
                }
            } else if (power.minVolts() < PowerAnalysis.DEFAULT_BROWNOUT_VOLTS + 1.0) {
                flags.add(new Flag(Severity.WATCH, "battery/power",
                        "Voltage approached the brownout floor (min " + power.minVolts() + " V)."));
            }

            BatteryHealth.Result battery = BatteryHealth.analyze(voltage, current);
            store.recordMetric(logId, "voltage_droop", "MATCH", battery.droopVolts(), "V", battery.quality().confidence().name());
            if (!Double.isNaN(battery.projectedEndVolts()) && battery.projectedEndVolts() < PowerAnalysis.DEFAULT_BROWNOUT_VOLTS + 0.5) {
                flags.add(new Flag(Severity.WATCH, "battery",
                        "Battery droop trend is steep — " + battery.assessment()));
            }
        }

        if (can != null && !can.isEmpty()) {
            CanHealth.Result canHealth = CanHealth.analyze(can);
            store.recordMetric(logId, "can_error_increase", "MATCH", canHealth.totalIncrease(), "count", canHealth.quality().confidence().name());
            if (canHealth.risingErrors()) {
                flags.add(new Flag(Severity.WATCH, "CAN bus",
                        "CAN errors rose during the match — " + canHealth.assessment()));
                store.recordEvent(logId, 0, "can_errors", "WATCH", canHealth.assessment());
            }
        }

        if (loopMs != null && !loopMs.isEmpty()) {
            LoopTiming.Result loop = LoopTiming.analyze(loopMs);
            store.recordMetric(logId, "loop_p95_ms", "MATCH", loop.p95Ms(), "ms", loop.quality().confidence().name());
            store.recordMetric(logId, "loop_overruns", "MATCH", loop.overruns(), "count", loop.quality().confidence().name());
            if (loop.overruns() > 0) {
                flags.add(new Flag(Severity.WATCH, "loop timing",
                        loop.overruns() + " loop overrun(s) — " + loop.assessment()));
            }
        }

        return new Result(logId, flags, buildReport(flags));
    }

    private static String buildReport(List<Flag> flags) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Mode A — between-match check ===\n");
        if (flags.isEmpty()) {
            sb.append("No safety flags. (Nothing pit-actionable found; note a single match is limited data.)\n");
        } else {
            for (Flag f : flags) {
                sb.append("[").append(f.severity()).append("] ").append(f.area()).append(": ")
                        .append(f.message()).append('\n');
            }
        }
        sb.append("Note: these are electrical/mechanical flags for the pit crew — not code changes."
                + " Autonomous tweaks go through the PathPlanner tools.\n");
        return sb.toString();
    }

    private ModeA() {}
}
