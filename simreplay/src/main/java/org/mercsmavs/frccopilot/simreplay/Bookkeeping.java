package org.mercsmavs.frccopilot.simreplay;

import java.util.List;

/**
 * Signals that describe the logging run rather than the robot.
 *
 * <p>Wall clocks and monotonic counters are the two shapes most likely to look interesting to
 * anything that ranks by change or by "still reaches": {@code /SystemStats/EpochTimeMicros} always
 * increases, always by a lot, and always differs between two runs — so it wins a divergence ranking
 * outright and satisfies a generated "counter" check trivially. Neither says anything about the
 * robot, and both crowd out the signal that does.
 *
 * <p>Matching is on the whole entry name, lower-cased, by substring. Kept deliberately narrow:
 * excluding a real signal is worse than tolerating a boring one, so this lists clocks and log
 * plumbing only, never anything a mechanism could move.
 */
final class Bookkeeping {

    private static final List<String> PATTERNS = List.of(
            "/timestamp",
            "timestamps",
            "epochtime",
            "fpgatimestamp",
            "systemtime",
            "uptime",
            "/serialnumber",
            "/buildconstants");

    /** True when this signal describes the run's bookkeeping rather than the robot's behaviour. */
    static boolean isBookkeeping(String signalName) {
        if (signalName == null) {
            return false;
        }
        String lower = signalName.toLowerCase();
        return PATTERNS.stream().anyMatch(lower::contains);
    }

    private Bookkeeping() {}
}
