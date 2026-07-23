package org.mercsmavs.frccopilot.ingest;

import edu.wpi.first.util.datalog.DataLogReader;
import edu.wpi.first.util.datalog.DataLogRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around WPILib's official {@link DataLogReader}.
 *
 * <p>We deliberately do NOT reimplement the .wpilog binary format — using WPILib's own reader
 * guarantees compatibility with whatever the robot wrote (this mirrors wpilog-mcp's correct call).
 * A {@code DataLogReader} is {@link Iterable}; iterating produces a fresh cursor each time, so the
 * multi-pass approach here (index pass, then decode pass) is safe.
 */
public final class WpilogReader {
    private final DataLogReader reader;
    private final String path;

    public WpilogReader(String path) throws IOException {
        this.path = path;
        this.reader = new DataLogReader(path);
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
