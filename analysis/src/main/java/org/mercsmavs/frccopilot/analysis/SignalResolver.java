package org.mercsmavs.frccopilot.analysis;

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
        for (String candidate : candidates) {
            String lower = candidate.toLowerCase();
            String best = null;
            for (LogEntry e : index.values()) {
                if (!isNumeric(e.type)) {
                    continue;
                }
                if (e.name.toLowerCase().contains(lower)) {
                    // Prefer the shortest matching name (usually the most direct signal).
                    if (best == null || e.name.length() < best.length()) {
                        best = e.name;
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
