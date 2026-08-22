package org.mercsmavs.frccopilot.ingest.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.mercsmavs.frccopilot.ingest.LogEntry;

/**
 * Persistent structured store for parsed-log summaries (SQLite).
 *
 * <p>The point of persisting rather than re-parsing on each query: we keep
 * per-log <em>summaries</em> and per-match <em>metrics/events</em> so a season-spanning trend
 * query is a SQL read, not a re-parse of every raw .wpilog. Raw samples stay in the log files
 * and are re-opened lazily only when needed.
 */
public final class TrendStore implements AutoCloseable {

    private final Connection conn;

    public TrendStore(String dbPath) throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
            s.execute("PRAGMA journal_mode = WAL");
        }
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS logs (
                      id              INTEGER PRIMARY KEY AUTOINCREMENT,
                      path            TEXT NOT NULL,
                      sha             TEXT NOT NULL UNIQUE,
                      team            INTEGER,
                      match_key       TEXT,
                      robot_profile   TEXT,
                      start_utc_us    INTEGER,
                      duration_s      REAL NOT NULL,
                      wpilib_version  INTEGER NOT NULL,
                      git_sha         TEXT,
                      ingested_at     INTEGER NOT NULL
                    )""");
            s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS entries (
                      log_id    INTEGER NOT NULL REFERENCES logs(id) ON DELETE CASCADE,
                      name      TEXT NOT NULL,
                      type      TEXT NOT NULL,
                      count     INTEGER NOT NULL,
                      first_ts  INTEGER NOT NULL,
                      last_ts   INTEGER NOT NULL,
                      PRIMARY KEY (log_id, name)
                    )""");
            s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS metrics (
                      log_id      INTEGER NOT NULL REFERENCES logs(id) ON DELETE CASCADE,
                      metric      TEXT NOT NULL,
                      phase       TEXT,
                      value       REAL,
                      unit        TEXT,
                      confidence  TEXT,
                      PRIMARY KEY (log_id, metric, phase)
                    )""");
            s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS events (
                      log_id    INTEGER NOT NULL REFERENCES logs(id) ON DELETE CASCADE,
                      ts_us     INTEGER NOT NULL,
                      kind      TEXT NOT NULL,
                      severity  TEXT NOT NULL,
                      detail    TEXT
                    )""");
            s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS revlog_sync (
                      log_id       INTEGER NOT NULL REFERENCES logs(id) ON DELETE CASCADE,
                      revlog_path  TEXT NOT NULL,
                      offset_ms    REAL,
                      drift        REAL,
                      confidence   TEXT,
                      PRIMARY KEY (log_id, revlog_path)
                    )""");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_metrics_metric ON metrics(metric)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_events_kind ON events(kind)");
        }
    }

    /**
     * Insert or update (keyed by file SHA) a log summary plus its entry index. Returns the log's
     * row id, which is stable across re-ingests of the same file — anything already recorded
     * against that log (Mode A metrics, events, revlog sync) survives and stays attached.
     */
    public long ingest(LogSummary summary, Collection<LogEntry> entries) throws SQLException {
        boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            // Re-ingesting a log must not lose what has already been concluded about it. Deleting
            // the row cascades into `metrics`, which is where Mode A's findings live — so a team
            // that ran a pit check and then re-ingested the same file silently lost that match from
            // every season trend. Reuse the existing row instead, and replace only the entry index,
            // which is the part a re-parse actually regenerates.
            Long existingId = null;
            try (PreparedStatement find =
                    conn.prepareStatement("SELECT id FROM logs WHERE sha = ?")) {
                find.setString(1, summary.sha());
                try (ResultSet rs = find.executeQuery()) {
                    if (rs.next()) {
                        existingId = rs.getLong(1);
                    }
                }
            }
            if (existingId != null) {
                try (PreparedStatement del =
                        conn.prepareStatement("DELETE FROM entries WHERE log_id = ?")) {
                    del.setLong(1, existingId);
                    del.executeUpdate();
                }
                try (PreparedStatement upd =
                        conn.prepareStatement(
                                """
                                UPDATE logs SET
                                  path = ?, team = ?, match_key = ?, robot_profile = ?,
                                  start_utc_us = ?, duration_s = ?, wpilib_version = ?,
                                  git_sha = ?, ingested_at = ?
                                WHERE id = ?""")) {
                    upd.setString(1, summary.path());
                    setNullableInt(upd, 2, summary.team());
                    upd.setString(3, summary.matchKey());
                    upd.setString(4, summary.robotProfile());
                    setNullableLong(upd, 5, summary.startUtcMicros());
                    upd.setDouble(6, summary.durationSeconds());
                    upd.setInt(7, summary.wpilibVersion());
                    upd.setString(8, summary.gitSha());
                    upd.setLong(9, System.currentTimeMillis());
                    upd.setLong(10, existingId);
                    upd.executeUpdate();
                }
            }

            long logId;
            if (existingId != null) {
                logId = existingId;
            } else {
                try (PreparedStatement ins =
                        conn.prepareStatement(
                                """
                                INSERT INTO logs
                                  (path, sha, team, match_key, robot_profile, start_utc_us,
                                   duration_s, wpilib_version, git_sha, ingested_at)
                                VALUES (?,?,?,?,?,?,?,?,?,?)""",
                                Statement.RETURN_GENERATED_KEYS)) {
                    ins.setString(1, summary.path());
                    ins.setString(2, summary.sha());
                    setNullableInt(ins, 3, summary.team());
                    ins.setString(4, summary.matchKey());
                    ins.setString(5, summary.robotProfile());
                    setNullableLong(ins, 6, summary.startUtcMicros());
                    ins.setDouble(7, summary.durationSeconds());
                    ins.setInt(8, summary.wpilibVersion());
                    ins.setString(9, summary.gitSha());
                    ins.setLong(10, System.currentTimeMillis());
                    ins.executeUpdate();
                    try (ResultSet keys = ins.getGeneratedKeys()) {
                        keys.next();
                        logId = keys.getLong(1);
                    }
                }
            }

            try (PreparedStatement ins =
                    conn.prepareStatement(
                            "INSERT INTO entries (log_id, name, type, count, first_ts, last_ts)"
                                    + " VALUES (?,?,?,?,?,?)")) {
                for (LogEntry e : entries) {
                    ins.setLong(1, logId);
                    ins.setString(2, e.name);
                    ins.setString(3, e.type);
                    ins.setLong(4, e.count());
                    ins.setLong(5, e.firstTimestampUs());
                    ins.setLong(6, e.lastTimestampUs());
                    ins.addBatch();
                }
                ins.executeBatch();
            }

            conn.commit();
            return logId;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(prevAutoCommit);
        }
    }

    /** Record a computed per-match metric (called by Module 3 analysis). */
    public void recordMetric(
            long logId, String metric, String phase, double value, String unit, String confidence)
            throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT OR REPLACE INTO metrics (log_id, metric, phase, value, unit, confidence)"
                                + " VALUES (?,?,?,?,?,?)")) {
            ps.setLong(1, logId);
            ps.setString(2, metric);
            ps.setString(3, phase == null ? "" : phase);
            ps.setDouble(4, value);
            ps.setString(5, unit);
            ps.setString(6, confidence);
            ps.executeUpdate();
        }
    }

    /** Record a flagged event (brownout, CAN fault, comms drop, ...). */
    public void recordEvent(long logId, long tsUs, String kind, String severity, String detail)
            throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT INTO events (log_id, ts_us, kind, severity, detail) VALUES (?,?,?,?,?)")) {
            ps.setLong(1, logId);
            ps.setLong(2, tsUs);
            ps.setString(3, kind);
            ps.setString(4, severity);
            ps.setString(5, detail);
            ps.executeUpdate();
        }
    }

    public record LogRow(long id, String path, String matchKey, double durationSeconds, String gitSha) {}

    public List<LogRow> listLogs() throws SQLException {
        List<LogRow> rows = new ArrayList<>();
        try (Statement s = conn.createStatement();
                ResultSet rs =
                        s.executeQuery(
                                "SELECT id, path, match_key, duration_s, git_sha FROM logs ORDER BY ingested_at")) {
            while (rs.next()) {
                rows.add(
                        new LogRow(
                                rs.getLong("id"),
                                rs.getString("path"),
                                rs.getString("match_key"),
                                rs.getDouble("duration_s"),
                                rs.getString("git_sha")));
            }
        }
        return rows;
    }

    public record MetricPoint(long logId, String matchKey, String phase, double value, String unit) {}

    /** Trend query: one metric across every ingested log (no raw-log re-parsing). */
    public List<MetricPoint> trend(String metric) throws SQLException {
        List<MetricPoint> points = new ArrayList<>();
        try (PreparedStatement ps =
                conn.prepareStatement(
                        """
                        SELECT m.log_id, l.match_key, m.phase, m.value, m.unit
                        FROM metrics m JOIN logs l ON l.id = m.log_id
                        WHERE m.metric = ?
                        ORDER BY l.ingested_at""")) {
            ps.setString(1, metric);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    points.add(
                            new MetricPoint(
                                    rs.getLong("log_id"),
                                    rs.getString("match_key"),
                                    rs.getString("phase"),
                                    rs.getDouble("value"),
                                    rs.getString("unit")));
                }
            }
        }
        return points;
    }

    private static void setNullableInt(PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.INTEGER);
        } else {
            ps.setInt(idx, v);
        }
    }

    private static void setNullableLong(PreparedStatement ps, int idx, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.INTEGER);
        } else {
            ps.setLong(idx, v);
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
