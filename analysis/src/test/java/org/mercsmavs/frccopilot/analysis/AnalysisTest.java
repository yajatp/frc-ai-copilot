package org.mercsmavs.frccopilot.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnalysisTest {

    /** Build a series sampled every {@code periodMs} starting at t=0. */
    private static Series series(double periodMs, double... values) {
        long[] ts = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            ts[i] = (long) (i * periodMs * 1000);
        }
        return new Series(values, ts);
    }

    @Test
    void statisticsAreCorrect() {
        Statistics.Result r = Statistics.of(series(20, 1, 2, 3, 4, 5));
        assertEquals(1, r.min(), 1e-9);
        assertEquals(5, r.max(), 1e-9);
        assertEquals(3, r.mean(), 1e-9);
        assertEquals(3, r.median(), 1e-9);
    }

    @Test
    void brownoutFiresOnSustainedDipNotOnCleanData() {
        // Clean: never near the floor.
        double[] clean = new double[120];
        for (int i = 0; i < clean.length; i++) clean[i] = 12.4;
        PowerAnalysis.Result cleanR = PowerAnalysis.analyze(series(20, clean));
        assertFalse(cleanR.brownoutRisk(), "clean 12.4V data must not flag a brownout");

        // A sustained dip below 6.8V for ~100ms (5 samples @ 20ms) in the middle.
        double[] dip = new double[120];
        for (int i = 0; i < dip.length; i++) dip[i] = 12.0;
        for (int i = 50; i < 56; i++) dip[i] = 6.2;
        PowerAnalysis.Result dipR = PowerAnalysis.analyze(series(20, dip));
        assertTrue(dipR.brownoutRisk(), "sustained sub-6.8V dip must flag");
        assertEquals(1, dipR.events().size());
        assertEquals(6.2, dipR.minVolts(), 1e-9);
        assertTrue(dipR.assessment().toLowerCase().contains("may indicate"), "must hedge");
    }

    @Test
    void confidenceDowngradesOnSparseData() {
        // 120 dense regular samples → HIGH.
        double[] many = new double[120];
        for (int i = 0; i < many.length; i++) many[i] = 12.0;
        assertEquals(DataQuality.Confidence.HIGH, PowerAnalysis.analyze(series(20, many)).quality().confidence());

        // A handful of samples → LOW.
        PowerAnalysis.Result few = PowerAnalysis.analyze(series(20, 12.0, 12.0, 11.9));
        assertEquals(DataQuality.Confidence.LOW, few.quality().confidence());
        assertTrue(few.assessment().contains("weak signal"), "low-confidence caveat expected");
    }

    @Test
    void canHealthDetectsRisingErrors() {
        CanHealth.Result flat = CanHealth.analyze(series(20, 0, 0, 0, 0, 0));
        assertFalse(flat.risingErrors());

        CanHealth.Result rising = CanHealth.analyze(series(20, 0, 0, 1, 3, 7));
        assertTrue(rising.risingErrors());
        assertEquals(7, rising.totalIncrease(), 1e-9);
        assertTrue(rising.assessment().toLowerCase().contains("may indicate"));
    }
}
