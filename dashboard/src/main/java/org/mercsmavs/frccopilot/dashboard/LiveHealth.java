package org.mercsmavs.frccopilot.dashboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.mercsmavs.frccopilot.analysis.BatteryHealth;
import org.mercsmavs.frccopilot.analysis.CanHealth;
import org.mercsmavs.frccopilot.analysis.DataQuality;
import org.mercsmavs.frccopilot.analysis.LoopTiming;
import org.mercsmavs.frccopilot.analysis.PowerAnalysis;
import org.mercsmavs.frccopilot.analysis.Series;
import org.mercsmavs.frccopilot.modes.ModeA;

/**
 * Runs the existing analysis primitives against the live rolling telemetry window.
 *
 * <p>Nothing here is a new analysis. {@code PowerAnalysis}, {@code BatteryHealth}, {@code CanHealth}
 * and {@code LoopTiming} accept a {@link Series} and do not care whether its arrays came from a
 * {@code .wpilog} or from the last forty seconds of NetworkTables — so the dashboard gets the same
 * hedged, quality-scored verdicts Mode A produces after a match, except continuously and without
 * anyone having to ask an AI to run a tool.
 *
 * <p>Severity deliberately reuses {@link ModeA.Severity} so live tiles and post-match reports speak
 * one vocabulary. Unlike {@link ModeA}, this path is pure: it persists nothing to the trend store,
 * because a verdict recomputed several times a second is not a match record.
 */
final class LiveHealth {

    /**
     * One health tile.
     *
     * @param role intent key from {@link TelemetryHub.Resolved} (e.g. {@code battery_voltage})
     * @param label human title for the tile
     * @param severity OK / WATCH / CRITICAL, shared with Mode A
     * @param value the headline number, or NaN when the signal is missing
     * @param unit unit for {@code value}
     * @param assessment the primitive's own hedged prose
     * @param confidence data-quality confidence backing the verdict
     * @param signal the NT topic this resolved to, or null when unpublished
     */
    record Verdict(
            String role,
            String label,
            ModeA.Severity severity,
            double value,
            String unit,
            String assessment,
            String confidence,
            String signal) {}

    /** Builds the current tile set from whatever the robot is publishing right now. */
    static List<Verdict> assess(TelemetryHub hub) {
        Map<String, TelemetryHub.Resolved> resolved = hub.resolved();
        List<Verdict> out = new ArrayList<>();

        Series voltage = seriesFor(hub, resolved, "battery_voltage");
        Series current = seriesFor(hub, resolved, "total_current");
        Series canErrors = seriesFor(hub, resolved, "can_errors");
        Series loopMs = seriesFor(hub, resolved, "loop_ms");

        out.add(power(voltage, signalOf(resolved, "battery_voltage")));
        out.add(battery(voltage, current, signalOf(resolved, "battery_voltage")));
        out.add(can(canErrors, signalOf(resolved, "can_errors")));
        out.add(loop(loopMs, signalOf(resolved, "loop_ms")));
        return out;
    }

    private static Verdict power(Series voltage, String signal) {
        if (voltage == null || voltage.isEmpty()) {
            return missing("battery_voltage", "Brownout risk", "V", signal);
        }
        PowerAnalysis.Result r = PowerAnalysis.analyze(voltage);
        ModeA.Severity severity;
        if (r.brownoutRisk()) {
            severity = ModeA.Severity.CRITICAL;
        } else if (r.minVolts() < PowerAnalysis.DEFAULT_BROWNOUT_VOLTS + 1.0) {
            severity = ModeA.Severity.WATCH;
        } else {
            severity = ModeA.Severity.OK;
        }
        return new Verdict("battery_voltage", "Brownout risk", severity, r.minVolts(), "V",
                r.assessment(), confidence(r.quality()), signal);
    }

    private static Verdict battery(Series voltage, Series current, String signal) {
        if (voltage == null || voltage.isEmpty()) {
            return missing("battery_droop", "Battery droop", "V", signal);
        }
        BatteryHealth.Result r = BatteryHealth.analyze(voltage, current);
        boolean steep = !Double.isNaN(r.projectedEndVolts())
                && r.projectedEndVolts() < PowerAnalysis.DEFAULT_BROWNOUT_VOLTS + 0.5;
        return new Verdict("battery_droop", "Battery droop",
                steep ? ModeA.Severity.WATCH : ModeA.Severity.OK,
                r.droopVolts(), "V", r.assessment(), confidence(r.quality()), signal);
    }

    private static Verdict can(Series canErrors, String signal) {
        if (canErrors == null || canErrors.isEmpty()) {
            return missing("can_errors", "CAN bus", "count", signal);
        }
        CanHealth.Result r = CanHealth.analyze(canErrors);
        return new Verdict("can_errors", "CAN bus",
                r.risingErrors() ? ModeA.Severity.WATCH : ModeA.Severity.OK,
                r.totalIncrease(), "errors", r.assessment(), confidence(r.quality()), signal);
    }

    private static Verdict loop(Series loopMs, String signal) {
        if (loopMs == null || loopMs.isEmpty()) {
            return missing("loop_ms", "Loop timing", "ms", signal);
        }
        LoopTiming.Result r = LoopTiming.analyze(loopMs);
        return new Verdict("loop_ms", "Loop timing",
                r.overruns() > 0 ? ModeA.Severity.WATCH : ModeA.Severity.OK,
                r.p95Ms(), "ms", r.assessment(), confidence(r.quality()), signal);
    }

    /**
     * A tile for a check the robot isn't publishing the inputs for. This is reported rather than
     * hidden — "we cannot see this" is different from "this is fine", and the Signal Coverage view
     * turns these into the exact telemetry the robot code is missing.
     */
    private static Verdict missing(String role, String label, String unit, String signal) {
        return new Verdict(role, label, ModeA.Severity.OK, Double.NaN, unit,
                "No matching signal is being published, so this check cannot run.", "NONE", signal);
    }

    private static Series seriesFor(
            TelemetryHub hub, Map<String, TelemetryHub.Resolved> resolved, String role) {
        TelemetryHub.Resolved r = resolved.get(role);
        if (r == null) {
            return null;
        }
        RollingBuffer buf = hub.buffer(r.key());
        return buf == null ? null : buf.toSeries();
    }

    private static String signalOf(Map<String, TelemetryHub.Resolved> resolved, String role) {
        TelemetryHub.Resolved r = resolved.get(role);
        return r == null ? null : r.key();
    }

    private static String confidence(DataQuality quality) {
        return quality.confidence().name();
    }

    private LiveHealth() {}
}
