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
            if (!isPlausibleBusVoltage(power.minVolts())) {
                // A robot that reached 0 V did not brown out, it was not running. This is what an
                // unpowered PDP or a simulated one that never publishes looks like, and reporting
                // it as a battery flag sends the pit crew to swap a perfectly good battery.
                String unpowered = String.format(
                        "Voltage channel read %.2f V, which is not a running robot — treat this as a"
                                + " missing measurement, not a brownout. Check that the PDP/PDH is on"
                                + " the bus and being logged.",
                        power.minVolts());
                flags.add(new Flag(Severity.WATCH, "battery/power",
                        unpowered + caveat(unpowered, power.quality())));
            } else if (power.brownoutRisk()) {
                flags.add(new Flag(Severity.CRITICAL, "battery/power",
                        withCaveat("Brownout risk: " + power.assessment(), power.quality())));
                for (PowerAnalysis.BrownoutEvent e : power.events()) {
                    store.recordEvent(logId, (long) (e.startSeconds() * 1_000_000), "brownout", "CRITICAL",
                            String.format("min %.2fV for %.0fms", e.minVolts(), e.durationMs()));
                }
            } else if (power.minVolts() < PowerAnalysis.DEFAULT_BROWNOUT_VOLTS + 1.0) {
                flags.add(new Flag(Severity.WATCH, "battery/power",
                        withCaveat("Voltage approached the brownout floor (min " + power.minVolts() + " V).",
                                power.quality())));
            }

            BatteryHealth.Result battery = BatteryHealth.analyze(voltage, current);
            store.recordMetric(logId, "voltage_droop", "MATCH", battery.droopVolts(), "V", battery.quality().confidence().name());
            if (!Double.isNaN(battery.projectedEndVolts()) && battery.projectedEndVolts() < PowerAnalysis.DEFAULT_BROWNOUT_VOLTS + 0.5) {
                flags.add(new Flag(Severity.WATCH, "battery",
                        withCaveat("Battery droop trend is steep — " + battery.assessment(),
                                battery.quality())));
            }
        }

        if (can != null && !can.isEmpty()) {
            CanHealth.Result canHealth = CanHealth.analyze(can);
            store.recordMetric(logId, "can_error_increase", "MATCH", canHealth.totalIncrease(), "count", canHealth.quality().confidence().name());
            if (canHealth.risingErrors()) {
                flags.add(new Flag(Severity.WATCH, "CAN bus",
                        withCaveat("CAN errors rose during the match — " + canHealth.assessment(),
                                canHealth.quality())));
                store.recordEvent(logId, 0, "can_errors", "WATCH", canHealth.assessment());
            }
        }

        if (loopMs != null && !loopMs.isEmpty()) {
            LoopTiming.Result loop = LoopTiming.analyze(loopMs);
            store.recordMetric(logId, "loop_p95_ms", "MATCH", loop.p95Ms(), "ms", loop.quality().confidence().name());
            store.recordMetric(logId, "loop_overruns", "MATCH", loop.overruns(), "count", loop.quality().confidence().name());
            if (loop.overruns() > 0) {
                flags.add(new Flag(Severity.WATCH, "loop timing",
                        withCaveat(loop.overruns() + " loop overrun(s) — " + loop.assessment(),
                                loop.quality())));
            }
        }

        return new Result(logId, flags, buildReport(flags));
    }

    /**
     * True when the lowest reading could have come from a robot that was actually running; see
     * {@link PowerAnalysis#MIN_PLAUSIBLE_BUS_VOLTS}.
     */
    private static boolean isPlausibleBusVoltage(double minVolts) {
        return !Double.isNaN(minVolts) && minVolts >= PowerAnalysis.MIN_PLAUSIBLE_BUS_VOLTS;
    }

    /**
     * The data-quality caveat, appended to the flag text itself.
     *
     * <p>Mode A summarises primitives that each hedge their own findings, and dropping that hedge
     * on the way out is how "only 2 samples, treat as weak" becomes a confident pit instruction.
     * The flag is the only thing anyone reads between matches, so the caveat has to ride on it.
     *
     * <p>The primitives currently all end their own {@code assessment()} with this same caveat, so
     * appending unconditionally printed it twice on every flag. Rather than trust that and drop the
     * append — which would silently lose the hedge the moment a primitive stopped including it —
     * this appends only when the text does not already carry it.
     *
     * @param text the flag text built so far, which may or may not already end with the caveat
     */
    /** {@code text} with the data-quality caveat appended, if it does not already end with it. */
    private static String withCaveat(String text, org.mercsmavs.frccopilot.analysis.DataQuality quality) {
        return text + caveat(text, quality);
    }

    private static String caveat(String text, org.mercsmavs.frccopilot.analysis.DataQuality quality) {
        if (quality == null) {
            return "";
        }
        String caveatText = quality.caveat();
        if (caveatText == null || caveatText.isBlank() || text.endsWith(caveatText)) {
            return "";
        }
        // caveat() already parenthesises itself; wrapping it again reads as a typo.
        return " " + caveatText;
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
