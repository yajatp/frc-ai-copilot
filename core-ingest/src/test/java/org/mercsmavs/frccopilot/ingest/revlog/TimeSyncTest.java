package org.mercsmavs.frccopilot.ingest.revlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class TimeSyncTest {

    @Test
    void recoversKnownShift() {
        // a is a smooth-ish waveform; b is a shifted by 5 samples with mild noise.
        int n = 200;
        int shift = 5;
        double[] a = new double[n];
        double[] b = new double[n];
        Random rng = new Random(42);
        for (int i = 0; i < n; i++) {
            a[i] = Math.sin(i * 0.2) + 0.5 * Math.sin(i * 0.05);
        }
        for (int i = 0; i < n; i++) {
            int src = i - shift;
            b[i] = (src >= 0 && src < n ? a[src] : 0) + rng.nextGaussian() * 0.02;
        }
        // b[i] = a[i-shift], so a[i] aligns with b[i+shift] -> best lag = +shift.
        TimeSync.SyncResult r = TimeSync.crossCorrelate(a, b, 20.0, 400.0);
        assertEquals(shift * 20.0, r.offsetMs(), 20.0, "recovered offset should match the injected shift");
        assertTrue(r.confidence() == TimeSync.Confidence.HIGH || r.confidence() == TimeSync.Confidence.MEDIUM,
                () -> "expected decent confidence, got " + r.confidence() + " (r=" + r.peakCorrelation() + ")");
    }

    @Test
    void unrelatedSignalsFailToSync() {
        int n = 200;
        double[] a = new double[n];
        double[] b = new double[n];
        Random rng = new Random(1);
        for (int i = 0; i < n; i++) {
            a[i] = rng.nextGaussian();
            b[i] = rng.nextGaussian();
        }
        TimeSync.SyncResult r = TimeSync.crossCorrelate(a, b, 20.0, 400.0);
        assertTrue(r.confidence() == TimeSync.Confidence.LOW || r.confidence() == TimeSync.Confidence.FAILED,
                () -> "unrelated signals should not sync confidently, got " + r.confidence());
    }
}
