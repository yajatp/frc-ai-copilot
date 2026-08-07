package org.mercsmavs.frccopilot.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnalysisDepthTest {

    private static Series series(double periodMs, double... values) {
        long[] ts = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            ts[i] = (long) (i * periodMs * 1000);
        }
        return new Series(values, ts);
    }

    @Test
    void anomalyFiresOnOutlierNotOnClean() {
        double[] clean = new double[60];
        java.util.Arrays.fill(clean, 5.0);
        assertFalse(AnomalyDetection.detect(series(20, clean)).any());

        double[] spike = clean.clone();
        spike[30] = 50.0;
        AnomalyDetection.Result r = AnomalyDetection.detect(series(20, spike));
        assertTrue(r.any());
        assertEquals(50.0, r.anomalies().get(0).value(), 1e-9);
    }

    @Test
    void peakFinderFindsInjectedPeaks() {
        // two clear peaks
        double[] v = {0, 1, 0, 0, 3, 0, 0, 1, 0};
        PeakFinder.Result r = PeakFinder.find(series(20, v), 2.0);
        assertEquals(1, r.peaks().size(), "only the prominence-3 peak clears threshold 2");
        assertEquals(3.0, r.peaks().get(0).value(), 1e-9);
    }

    @Test
    void compareReportsMeanAndMaxDeltas() {
        Series before = series(20, 1, 2, 3, 4);
        Series after = series(20, 3, 4, 5, 6);
        Compare.Result r = Compare.of(before, after);
        assertEquals(2.0, r.meanDelta(), 1e-9);
        assertEquals(2.0, r.maxDelta(), 1e-9);

        // Identical series must not be described as a real change.
        assertTrue(Compare.of(before, series(20, 1, 2, 3, 4)).assessment().contains("No meaningful difference"));
    }

    @Test
    void rateOfChangeMeasuresSlopePerSecond() {
        // +1 unit every 100 ms == +10 units/s, with one flat step in the middle.
        RateOfChange.Result r = RateOfChange.of(series(100, 0, 1, 2, 2, 3));
        assertEquals(10.0, r.maxSlope(), 1e-9);
        assertEquals(0.0, r.minSlope(), 1e-9);
        assertEquals(0.1, r.maxSlopeTimeSeconds(), 1e-9, "ties report the first time the max slope occurred");
    }

    @Test
    void correlationSignsAreCorrect() {
        Series a = series(20, 1, 2, 3, 4, 5, 6);
        Series same = series(20, 1, 2, 3, 4, 5, 6);
        Series inv = series(20, 6, 5, 4, 3, 2, 1);
        assertTrue(Correlation.of(a, same).pearson() > 0.99);
        assertTrue(Correlation.of(a, inv).pearson() < -0.99);
    }

    @Test
    void swerveFlagsOscillationNotSmooth() {
        // smooth ramp -> not underdamped
        double[] smooth = new double[60];
        for (int i = 0; i < smooth.length; i++) smooth[i] = i * 0.1;
        assertFalse(SwerveAnalysis.analyze(series(20, smooth)).likelyUnderdamped());

        // sustained oscillation -> underdamped
        double[] osc = new double[60];
        for (int i = 0; i < osc.length; i++) osc[i] = Math.sin(i * 0.9) * 2.0;
        assertTrue(SwerveAnalysis.analyze(series(20, osc)).likelyUnderdamped());
    }

    @Test
    void loopTimingDetectsOverruns() {
        double[] good = new double[50];
        java.util.Arrays.fill(good, 5.0);
        assertEquals(0, LoopTiming.analyze(series(20, good)).overruns());

        double[] bad = good.clone();
        bad[10] = 45;
        bad[11] = 32;
        assertEquals(2, LoopTiming.analyze(series(20, bad)).overruns());
    }

    @Test
    void cycleTimeCountsIncrements() {
        // counter goes 0,0,1,1,1,2,2,3 -> 3 increments -> 2 measured cycle intervals
        CycleTime.Result r = CycleTime.analyze(series(1000, 0, 0, 1, 1, 1, 2, 2, 3));
        assertEquals(2, r.cycles());
    }

    @Test
    void visionReportsDetectionRate() {
        // target visible 3 of 6 frames, one dropout
        VisionAnalysis.Result r = VisionAnalysis.analyze(series(20, 1, 1, 1, 0, 0, 0), null);
        assertEquals(0.5, r.detectionRate(), 1e-9);
        assertEquals(1, r.dropouts());
    }

    @Test
    void batteryDroopIndicator() {
        double[] v = new double[100];
        for (int i = 0; i < v.length; i++) v[i] = 12.6 - i * 0.03; // clear droop
        BatteryHealth.Result r = BatteryHealth.analyze(series(20, v), null);
        assertTrue(r.droopVolts() > 0);
        assertTrue(r.assessment().toLowerCase().contains("single-match"));
    }
}
