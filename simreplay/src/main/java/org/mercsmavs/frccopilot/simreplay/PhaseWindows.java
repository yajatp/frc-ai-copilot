package org.mercsmavs.frccopilot.simreplay;

import java.util.ArrayList;
import java.util.List;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

/**
 * The time windows during which a phase signal held a given value — "while {@code /Robot/State} was
 * {@code AUTO}", say.
 *
 * <p>Shared by {@link Assertion} (which evaluates inside the window) and {@link ScenarioGenerator}
 * (which derives thresholds from it). They must agree: if the generator measured a signal over the
 * whole log while the check only looks at autonomous, it can emit a threshold the very log it was
 * generated from does not satisfy.
 */
public record PhaseWindows(List<long[]> windows) {

    /** Windows where {@code phaseSignal} equalled {@code phaseEquals}, by string comparison. */
    public static PhaseWindows of(SignalSource source, String phaseSignal, String phaseEquals) {
        List<WpilogReader.Sample> phase = source.read(phaseSignal);
        List<long[]> windows = new ArrayList<>();
        long start = -1;
        for (WpilogReader.Sample sample : phase) {
            boolean match = String.valueOf(sample.value()).equals(phaseEquals);
            if (match && start < 0) {
                start = sample.timestampUs();
            } else if (!match && start >= 0) {
                windows.add(new long[] {start, sample.timestampUs()});
                start = -1;
            }
        }
        if (start >= 0) {
            windows.add(new long[] {start, Long.MAX_VALUE});
        }
        return new PhaseWindows(windows);
    }

    public boolean contains(long timestampUs) {
        for (long[] w : windows) {
            if (timestampUs >= w[0] && timestampUs < w[1]) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return windows.isEmpty();
    }
}
