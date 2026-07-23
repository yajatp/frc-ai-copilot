package org.mercsmavs.frccopilot.ingest;

/**
 * Metadata + running statistics for a single logged entry (signal) within a .wpilog file.
 *
 * <p>Built during a scan pass so we can present an entry index (name, type, sample count,
 * time bounds) without holding every raw sample in memory.
 */
public final class LogEntry {
    public final int id;
    public final String name;
    public final String type;
    public String metadata;

    private long count = 0;
    private long firstTimestampUs = Long.MAX_VALUE;
    private long lastTimestampUs = Long.MIN_VALUE;

    public LogEntry(int id, String name, String type, String metadata) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.metadata = metadata == null ? "" : metadata;
    }

    /** Record that a sample for this entry was observed at the given timestamp (microseconds). */
    public void record(long timestampUs) {
        count++;
        if (timestampUs < firstTimestampUs) {
            firstTimestampUs = timestampUs;
        }
        if (timestampUs > lastTimestampUs) {
            lastTimestampUs = timestampUs;
        }
    }

    public long count() {
        return count;
    }

    public long firstTimestampUs() {
        return count == 0 ? 0 : firstTimestampUs;
    }

    public long lastTimestampUs() {
        return count == 0 ? 0 : lastTimestampUs;
    }

    /** Timestamps are stored in microseconds in a WPILOG; this returns the span in seconds. */
    public double spanSeconds() {
        if (count < 2) {
            return 0.0;
        }
        return (lastTimestampUs - firstTimestampUs) / 1_000_000.0;
    }
}
