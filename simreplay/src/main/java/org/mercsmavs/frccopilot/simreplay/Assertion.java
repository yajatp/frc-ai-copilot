package org.mercsmavs.frccopilot.simreplay;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

/**
 * One success criterion checked against a log: aggregate a signal (optionally within a phase
 * window) and compare it to a threshold.
 *
 * <p>Chosen over a full DSL (per the plan): lightweight, auditable, and enough to express the
 * 254-style checks — "auto scores non-zero" ({@code MAX /Auto/BallsScored > 0}), "arm settles"
 * ({@code LAST error < 0.05}), "no tip" ({@code MAX pitch < threshold}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Assertion(
        String signal,
        Aggregation aggregation,
        Op op,
        double threshold,
        String phaseSignal,
        String phaseEquals,
        String description) {

    public enum Aggregation {
        MAX,
        MIN,
        MEAN,
        LAST,
        FIRST,
        COUNT
    }

    public enum Op {
        GT(">"),
        GE(">="),
        LT("<"),
        LE("<="),
        EQ("=="),
        NE("!=");

        public final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        boolean test(double actual, double threshold) {
            return switch (this) {
                case GT -> actual > threshold;
                case GE -> actual >= threshold;
                case LT -> actual < threshold;
                case LE -> actual <= threshold;
                case EQ -> actual == threshold;
                case NE -> actual != threshold;
            };
        }
    }

    public record Result(boolean passed, double actual, int sampleCount, String message) {}

    /** Evaluate against a signal source. */
    public Result evaluate(SignalSource source) {
        List<WpilogReader.Sample> samples = source.read(signal);
        List<Double> values = numericValuesInPhase(source, samples);
        if (values.isEmpty()) {
            return new Result(false, Double.NaN, 0,
                    "no samples for '" + signal + "'"
                            + (phaseSignal != null ? " in phase " + phaseSignal + "==" + phaseEquals : ""));
        }
        double actual = aggregate(values);
        boolean passed = op.test(actual, threshold);
        String label = description != null ? description
                : aggregation + " " + signal + " " + op.symbol + " " + threshold;
        String message = String.format(
                "%s  [%s(%s)=%.4f %s %.4f over %d samples]",
                passed ? "PASS" : "FAIL", aggregation, signal, actual, op.symbol, threshold, values.size());
        return new Result(passed, actual, values.size(), label + " -> " + message);
    }

    private List<Double> numericValuesInPhase(SignalSource source, List<WpilogReader.Sample> samples) {
        PhaseWindows windows = phaseSignal == null ? null : PhaseWindows.of(source, phaseSignal, phaseEquals);
        List<Double> out = new ArrayList<>();
        for (WpilogReader.Sample s : samples) {
            if (windows != null && !windows.contains(s.timestampUs())) {
                continue;
            }
            Double v = numeric(s.value());
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    private double aggregate(List<Double> values) {
        return switch (aggregation) {
            case MAX -> values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
            case MIN -> values.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
            case MEAN -> values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            case FIRST -> values.get(0);
            case LAST -> values.get(values.size() - 1);
            case COUNT -> values.size();
        };
    }

    private static Double numeric(Object value) {
        if (value instanceof Double d) return d;
        if (value instanceof Float f) return (double) f;
        if (value instanceof Long l) return (double) l;
        if (value instanceof Integer i) return (double) i;
        if (value instanceof Boolean b) return b ? 1.0 : 0.0;
        return null;
    }
}
