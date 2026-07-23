package org.mercsmavs.frccopilot.ingest.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import org.mercsmavs.frccopilot.ingest.LogEntry;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

/**
 * Log-level summary persisted once per ingested .wpilog. This is the row that lets season/event
 * trend queries run without touching the raw log again.
 *
 * @param path absolute path the log was ingested from
 * @param sha SHA-256 of the file bytes (dedupe key; re-ingest replaces the same row)
 * @param team FRC team number, if discoverable from metadata (nullable)
 * @param matchKey TBA-style match key once enriched (nullable until Module 1's TBA step)
 * @param robotProfile profile name this log was analyzed under (nullable)
 * @param startUtcMicros wall-clock start from a {@code systemTime} entry, if present (nullable)
 * @param durationSeconds longest signal span in the log
 * @param wpilibVersion WPILOG format version
 * @param gitSha robot-code git SHA from AdvantageKit/DataLog metadata, if present (nullable)
 */
public record LogSummary(
        String path,
        String sha,
        Integer team,
        String matchKey,
        String robotProfile,
        Long startUtcMicros,
        double durationSeconds,
        int wpilibVersion,
        String gitSha) {

    /** Build a summary by scanning a reader + its computed entry index. */
    public static LogSummary from(WpilogReader reader, Map<Integer, LogEntry> index) throws IOException {
        double duration = index.values().stream().mapToDouble(LogEntry::spanSeconds).max().orElse(0.0);
        Long startUtc = firstLong(reader, "systemTime");
        String gitSha = firstMetadataString(reader, index, "GitSHA");
        Integer team = null; // filled in later by TBA enrichment / metadata
        return new LogSummary(
                reader.path(),
                sha256(Path.of(reader.path())),
                team,
                null,
                null,
                startUtc,
                duration,
                reader.version(),
                gitSha);
    }

    private static Long firstLong(WpilogReader reader, String entryName) {
        List<WpilogReader.Sample> samples = reader.read(entryName);
        if (!samples.isEmpty() && samples.get(0).value() instanceof Long l) {
            return l;
        }
        return null;
    }

    /**
     * AdvantageKit logs metadata under entries named like {@code /RealMetadata/GitSHA}; plain
     * DataLog puts them under other prefixes. Match on the trailing key.
     */
    private static String firstMetadataString(
            WpilogReader reader, Map<Integer, LogEntry> index, String key) {
        return index.values().stream()
                .filter(e -> e.name.endsWith("/" + key) || e.name.equals(key))
                .findFirst()
                .map(e -> reader.read(e.name))
                .filter(s -> !s.isEmpty())
                .map(s -> String.valueOf(s.get(0).value()))
                .orElse(null);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Files.readAllBytes(file));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
