package org.mercsmavs.frccopilot.ingest;

import edu.wpi.first.util.datalog.DataLogReader;
import edu.wpi.first.util.datalog.DataLogRecord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around WPILib's official {@link DataLogReader}.
 *
 * <p>We deliberately do NOT reimplement the .wpilog binary format — using WPILib's own reader
 * guarantees compatibility with whatever the robot actually wrote.
 * A {@code DataLogReader} is {@link Iterable}; iterating produces a fresh cursor each time, so the
 * multi-pass approach here (index pass, then decode pass) is safe.
 */
public final class WpilogReader {
    private final DataLogReader reader;
    private final String path;

    public WpilogReader(String path) throws IOException {
        this.path = path;
        // Read the bytes rather than using DataLogReader(String), which memory-maps the file and
        // offers no way to unmap it. On Windows a mapped file cannot be deleted, moved or replaced
        // while the mapping is alive, and nothing here ever releases it — so ingesting a log would
        // silently lock it for the rest of the process. Java has no public unmap API, so the fix is
        // to not map in the first place: DataLogReader also accepts a ByteBuffer.
        //
        // The cost is holding the log in heap instead of paging it. That is the right trade here —
        // logs are matches, not archives, and every caller already re-reads the buffer several times
        // (index pass, then one decode pass per signal), which is exactly the access pattern where
        // an in-memory buffer is no worse than a mapping.
        this.reader = new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(Path.of(path))));
        if (!reader.isValid()) {
            throw new IOException("Not a valid WPILOG file: " + path);
        }
    }

    public String path() {
        return path;
    }

    public int version() {
        return reader.getVersion();
    }

    public String extraHeader() {
        return reader.getExtraHeader();
    }

    /**
     * Scan the whole log once, building an entry index (id &rarr; {@link LogEntry}) with sample
     * counts and time bounds. Control records (start/finish/set-metadata) update the index;
     * data records bump the corresponding entry's stats.
     */
    public Map<Integer, LogEntry> index() {
        Map<Integer, LogEntry> entries = new LinkedHashMap<>();
        for (DataLogRecord record : reader) {
            if (record.isStart()) {
                DataLogRecord.StartRecordData start = record.getStartData();
                entries.put(
                        start.entry,
                        new LogEntry(start.entry, start.name, start.type, start.metadata));
            } else if (record.isSetMetadata()) {
                DataLogRecord.MetadataRecordData meta = record.getSetMetadataData();
                LogEntry entry = entries.get(meta.entry);
                if (entry != null) {
                    entry.metadata = meta.metadata;
                }
            } else if (!record.isControl()) {
                LogEntry entry = entries.get(record.getEntry());
                if (entry != null) {
                    entry.record(record.getTimestamp());
                }
            }
        }
        return entries;
    }

    /** A single decoded sample: timestamp (microseconds) + decoded value. */
    public record Sample(long timestampUs, Object value) {
        public double timestampSeconds() {
            return timestampUs / 1_000_000.0;
        }
    }

    /**
     * Decode every sample for the named entry. Returns an empty list if the name is not present.
     * Values are decoded per the entry's declared WPILOG type; struct / raw / msgpack payloads are
     * returned as a {@code byte[]} for downstream decoders to interpret.
     */
    public List<Sample> read(String entryName) {
        int targetId = -1;
        String targetType = null;
        List<Sample> samples = new ArrayList<>();

        for (DataLogRecord record : reader) {
            if (record.isStart()) {
                DataLogRecord.StartRecordData start = record.getStartData();
                if (start.name.equals(entryName)) {
                    targetId = start.entry;
                    targetType = start.type;
                }
            } else if (!record.isControl() && record.getEntry() == targetId && targetType != null) {
                samples.add(new Sample(record.getTimestamp(), decode(record, targetType)));
            }
        }
        return samples;
    }

    /** Aggregate shape of one numeric signal, accumulated without retaining its samples. */
    public static final class NumericSummary {
        public final String name;
        public final String type;
        private long count;
        private double min = Double.POSITIVE_INFINITY;
        private double max = Double.NEGATIVE_INFINITY;
        private double sum;
        private double first;
        private double last;
        private boolean nonDecreasing = true;
        private boolean nonIncreasing = true;

        NumericSummary(String name, String type) {
            this.name = name;
            this.type = type;
        }

        void add(double v) {
            if (count == 0) {
                first = v;
            } else {
                nonDecreasing &= v >= last;
                nonIncreasing &= v <= last;
            }
            last = v;
            count++;
            sum += v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }

        public long count() {
            return count;
        }

        public double min() {
            return count == 0 ? Double.NaN : min;
        }

        public double max() {
            return count == 0 ? Double.NaN : max;
        }

        public double mean() {
            return count == 0 ? Double.NaN : sum / count;
        }

        public double first() {
            return count == 0 ? Double.NaN : first;
        }

        public double last() {
            return count == 0 ? Double.NaN : last;
        }

        /** True for a signal that never decreases — the shape of a score/cycle counter. */
        public boolean monotonicIncreasing() {
            return count > 1 && nonDecreasing && max > min;
        }

        public boolean monotonicDecreasing() {
            return count > 1 && nonIncreasing && max > min;
        }

        /** True when every sample held the same value (a signal that never moved). */
        public boolean constant() {
            return count > 0 && min == max;
        }
    }

    /**
     * Summarize every numeric signal in the log in a <em>single</em> pass, retaining only aggregate
     * statistics. Comparing two runs means touching all signals at once, where the per-name
     * {@link #read(String)} would rescan the file once per signal.
     */
    public Map<String, NumericSummary> numericSummaries() {
        return numericSummaries(ts -> true);
    }

    /**
     * As {@link #numericSummaries()}, but counting only samples whose timestamp passes the filter —
     * used to summarize a single match phase (autonomous, say) rather than the whole log.
     */
    public Map<String, NumericSummary> numericSummaries(java.util.function.LongPredicate timestampFilter) {
        Map<Integer, NumericSummary> byId = new LinkedHashMap<>();
        Map<Integer, String> typeById = new LinkedHashMap<>();
        Map<String, NumericSummary> byName = new LinkedHashMap<>();
        for (DataLogRecord record : reader) {
            if (record.isStart()) {
                DataLogRecord.StartRecordData start = record.getStartData();
                if (isNumericType(start.type)) {
                    NumericSummary summary =
                            byName.computeIfAbsent(start.name, n -> new NumericSummary(n, start.type));
                    byId.put(start.entry, summary);
                    typeById.put(start.entry, start.type);
                }
            } else if (!record.isControl()) {
                NumericSummary summary = byId.get(record.getEntry());
                if (summary != null && timestampFilter.test(record.getTimestamp())) {
                    Double v = asDouble(decode(record, typeById.get(record.getEntry())));
                    if (v != null) {
                        summary.add(v);
                    }
                }
            }
        }
        return byName;
    }

    private static boolean isNumericType(String type) {
        return switch (type) {
            case "double", "float", "int64", "boolean" -> true;
            default -> false;
        };
    }

    private static Double asDouble(Object value) {
        if (value instanceof Double d) return d;
        if (value instanceof Float f) return (double) f;
        if (value instanceof Long l) return (double) l;
        if (value instanceof Integer i) return (double) i;
        if (value instanceof Boolean b) return b ? 1.0 : 0.0;
        return null;
    }

    /** Decode a data record according to its declared WPILOG type string. */
    static Object decode(DataLogRecord record, String type) {
        try {
            switch (type) {
                case "boolean":
                    return record.getBoolean();
                case "int64":
                    return record.getInteger();
                case "float":
                    return record.getFloat();
                case "double":
                    return record.getDouble();
                case "string":
                case "json":
                    return record.getString();
                case "boolean[]":
                    return record.getBooleanArray();
                case "int64[]":
                    return record.getIntegerArray();
                case "float[]":
                    return record.getFloatArray();
                case "double[]":
                    return record.getDoubleArray();
                case "string[]":
                    return record.getStringArray();
                default:
                    // struct:* geometry/kinematics types get decoded to real objects; everything
                    // else (structschema, msgpack, custom structs, raw) falls back to raw bytes.
                    if (StructDecoder.isStructType(type)) {
                        Object decoded = StructDecoder.decode(type, record.getRaw());
                        if (decoded != null) {
                            return decoded;
                        }
                    }
                    return record.getRaw();
            }
        } catch (Exception e) {
            // Type mismatch or truncated record; surface as raw bytes rather than throwing mid-scan.
            return record.getRaw();
        }
    }
}
