package org.mercsmavs.frccopilot.analysis;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mercsmavs.frccopilot.ingest.LogEntry;

/**
 * Finds signals by intent rather than exact path. Robots name telemetry differently, so primitives
 * should not hardcode a single entry name — resolve candidates against the log's actual index.
 */
public final class SignalResolver {

    /** Candidate substrings (case-insensitive) for common signals, best first. */
    public static final List<String> VOLTAGE =
            List.of("PowerDistribution/Voltage", "PDH/Voltage", "PDP/Voltage", "BatteryVoltage", "InputVoltage", "Voltage");
    public static final List<String> CAN_ERRORS =
            List.of("ReceiveErrorCount", "CANReceiveError", "BusOffCount", "CAN/Error", "CanBus", "Can");
    public static final List<String> TOTAL_CURRENT =
            List.of("PowerDistribution/TotalCurrent", "TotalCurrent", "Current");

    /**
     * Return the name of the entry that best matches the candidate list — the highest-priority
     * candidate that appears as a substring of some entry name, preferring numeric entries.
     */
    public static Optional<String> resolve(Map<Integer, LogEntry> index, List<String> candidates) {
        List<String> numericNames = index.values().stream()
                .filter(e -> isNumeric(e.type))
                .map(e -> e.name)
                .toList();
        return resolve(numericNames, candidates);
    }

    /**
     * Same intent-based resolution against a flat collection of signal names — the shape live
     * NetworkTables hands us, where topics are discovered by name rather than through a log index.
     * Callers are expected to have already filtered to numeric topics; unlike the log index, an NT
     * topic name carries no type information on its own.
     *
     * @param names candidate signal names to search (e.g. NT topic keys)
     * @param candidates intent substrings, best first (see {@link #VOLTAGE} and friends)
     */
    public static Optional<String> resolve(Collection<String> names, List<String> candidates) {
        for (String candidate : candidates) {
            String lower = candidate.toLowerCase();
            String best = null;
            for (String name : names) {
                if (name.toLowerCase().contains(lower)) {
                    // Prefer the shortest matching name (usually the most direct signal).
                    if (best == null || name.length() < best.length()) {
                        best = name;
                    }
                }
            }
            if (best != null) {
                return Optional.of(best);
            }
        }
        return Optional.empty();
    }

    private static boolean isNumeric(String type) {
        return switch (type) {
            case "double", "float", "int64", "boolean" -> true;
            default -> false;
        };
    }

    private SignalResolver() {}
}
