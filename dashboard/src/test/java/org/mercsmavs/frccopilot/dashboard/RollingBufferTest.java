package org.mercsmavs.frccopilot.dashboard;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.mercsmavs.frccopilot.analysis.Series;

/**
 * The ring buffer feeds every live verdict, so an ordering bug here would not throw — it would
 * quietly hand the analysis primitives a time-scrambled window and produce confident nonsense.
 */
class RollingBufferTest {

    @Test
    void emptyBufferHasNoSamples() {
        RollingBuffer buffer = new RollingBuffer(4);
        assertTrue(buffer.isEmpty());
        assertEquals(0, buffer.size());
        assertTrue(Double.isNaN(buffer.latest()));
        assertEquals(0, buffer.toSeries().size());
    }

    @Test
    void keepsSamplesInOrderBeforeWrapping() {
        RollingBuffer buffer = new RollingBuffer(8);
        for (int i = 0; i < 3; i++) {
            buffer.add(i * 1000L, i * 1.5);
        }
        Series series = buffer.toSeries();
        assertEquals(3, series.size());
        assertArrayEquals(new long[] {0, 1000, 2000}, series.timestampsUs());
        assertArrayEquals(new double[] {0, 1.5, 3.0}, series.values(), 1e-9);
    }

    @Test
    void evictsOldestAndStaysChronologicalAfterWrapping() {
        RollingBuffer buffer = new RollingBuffer(3);
        for (int i = 0; i < 5; i++) {
            buffer.add(i * 1000L, i);
        }
        // Capacity 3 after five writes: the two oldest are gone, order is still oldest-first.
        Series series = buffer.toSeries();
        assertEquals(3, series.size());
        assertArrayEquals(new long[] {2000, 3000, 4000}, series.timestampsUs());
        assertArrayEquals(new double[] {2, 3, 4}, series.values(), 1e-9);
    }

    @Test
    void latestReflectsTheMostRecentWriteAcrossTheWrap() {
        RollingBuffer buffer = new RollingBuffer(2);
        buffer.add(10, 1.0);
        buffer.add(20, 2.0);
        assertEquals(2.0, buffer.latest(), 1e-9);
        assertEquals(20, buffer.latestTimestampUs());

        buffer.add(30, 3.0); // wraps back to index 0
        assertEquals(3.0, buffer.latest(), 1e-9);
        assertEquals(30, buffer.latestTimestampUs());
    }

    @Test
    void decimationCapsPointCountAndPreservesChronology() {
        RollingBuffer buffer = new RollingBuffer(1000);
        for (int i = 0; i < 1000; i++) {
            buffer.add(i * 1000L, i);
        }
        double[][] points = buffer.decimated(100);
        assertTrue(points.length <= 100, "expected at most 100 points, got " + points.length);
        assertTrue(points.length > 1);
        for (int i = 1; i < points.length; i++) {
            assertTrue(points[i][0] > points[i - 1][0], "timestamps must increase");
        }
        // Timestamps are handed to the browser in milliseconds.
        assertEquals(0.0, points[0][0], 1e-9);
    }

    @Test
    void decimationOfSparseDataReturnsEveryPoint() {
        RollingBuffer buffer = new RollingBuffer(16);
        buffer.add(1000, 5.0);
        buffer.add(2000, 6.0);
        double[][] points = buffer.decimated(100);
        assertEquals(2, points.length);
        assertEquals(1.0, points[0][0], 1e-9);
        assertEquals(5.0, points[0][1], 1e-9);
    }
}
