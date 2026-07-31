package org.mercsmavs.frccopilot.dashboard;

import org.mercsmavs.frccopilot.analysis.Series;

/**
 * A fixed-capacity ring buffer of timestamped numeric samples, holding the most recent window of
 * one live signal.
 *
 * <p>This is the bridge that lets the live dashboard reuse the whole offline analysis layer: {@link
 * #toSeries()} hands back exactly the {@link Series} the primitives already accept, so {@code
 * PowerAnalysis}, {@code BatteryHealth}, {@code CanHealth} and {@code LoopTiming} run against a
 * rolling NetworkTables window with no changes — a log and a live feed are the same shape to them.
 *
 * <p>Written from the NetworkTables listener thread and read from HTTP/SSE threads, so every method
 * is synchronized. Contention is negligible: writes are a couple of array stores, and reads happen
 * at the broadcast tick rate rather than per sample.
 */
final class RollingBuffer {

    private final long[] timestampsUs;
    private final double[] values;

    /** Index where the next sample will be written. */
    private int head;

    /** Number of valid samples, saturating at capacity once the buffer has wrapped. */
    private int count;

    RollingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.timestampsUs = new long[capacity];
        this.values = new double[capacity];
    }

    /** Appends a sample, evicting the oldest once capacity is reached. */
    synchronized void add(long timestampUs, double value) {
        timestampsUs[head] = timestampUs;
        values[head] = value;
        head = (head + 1) % timestampsUs.length;
        if (count < timestampsUs.length) {
            count++;
        }
    }

    synchronized int size() {
        return count;
    }

    synchronized boolean isEmpty() {
        return count == 0;
    }

    /** The most recently added value, or {@code Double.NaN} if nothing has arrived yet. */
    synchronized double latest() {
        return count == 0 ? Double.NaN : values[Math.floorMod(head - 1, values.length)];
    }

    /** The timestamp of the most recent sample, or 0 if nothing has arrived yet. */
    synchronized long latestTimestampUs() {
        return count == 0 ? 0L : timestampsUs[Math.floorMod(head - 1, timestampsUs.length)];
    }

    /**
     * Copies the window out in chronological (oldest-first) order as a {@link Series}, ready to hand
     * straight to any analysis primitive.
     */
    synchronized Series toSeries() {
        double[] v = new double[count];
        long[] t = new long[count];
        int start = count < values.length ? 0 : head; // unwrapped buffers start at 0
        for (int i = 0; i < count; i++) {
            int idx = (start + i) % values.length;
            v[i] = values[idx];
            t[i] = timestampsUs[idx];
        }
        return new Series(v, t);
    }

    /**
     * Chronological samples decimated to at most {@code maxPoints}, for sending an initial chart
     * history to a browser without shipping the entire window.
     */
    synchronized double[][] decimated(int maxPoints) {
        Series s = toSeries();
        int n = s.size();
        if (n == 0) {
            return new double[0][];
        }
        int stride = Math.max(1, (int) Math.ceil((double) n / maxPoints));
        int out = (n + stride - 1) / stride;
        double[][] points = new double[out][2];
        for (int i = 0, j = 0; i < n && j < out; i += stride, j++) {
            points[j][0] = s.timestampsUs()[i] / 1000.0; // milliseconds, what the browser charts on
            points[j][1] = s.values()[i];
        }
        return points;
    }
}
