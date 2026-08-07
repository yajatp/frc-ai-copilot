package org.mercsmavs.frccopilot.knowledge;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The offline documentation index: a SQLite FTS5 table ranked by bm25.
 *
 * <p>Why FTS5 rather than embeddings: a pit laptop at a competition has no reliable wifi and no
 * GPU, and the questions teams actually ask ("what units does setVoltage take?", "which method
 * resets the gyro?") are dominated by exact API names, where lexical search beats semantic search
 * anyway. The whole index is one portable file that can be built once and copied to every laptop.
 */
public final class KnowledgeIndex implements AutoCloseable {

    /**
     * Column weights for bm25: a hit in the title or heading is worth far more than one buried in
     * body prose, because doc titles in this corpus are almost always the API name being asked
     * about. (FTS5 bm25 returns *negative* scores, most-relevant first.)
     */
    private static final String BM25_WEIGHTS = "bm25(chunks, 10.0, 5.0, 1.0)";

    private final Connection conn;

    public KnowledgeIndex(String dbPath) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("sqlite-jdbc driver not on the classpath", e);
        }
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (Statement s = conn.createStatement()) {
            // title/heading/body are indexed; source/url/page ride along unindexed as metadata.
            s.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS chunks USING fts5(
                        title, heading, body,
                        source UNINDEXED, url UNINDEXED, page UNINDEXED,
                        tokenize = 'porter unicode61'
                    )
                    """);
            s.execute("CREATE TABLE IF NOT EXISTS sources (name TEXT PRIMARY KEY, chunks INTEGER, indexed_at TEXT)");
        }
    }

    /** Remove everything previously indexed under {@code source} so a re-index is not a duplicate. */
    public void clearSource(String source) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM chunks WHERE source = ?")) {
            ps.setString(1, source);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sources WHERE name = ?")) {
            ps.setString(1, source);
            ps.executeUpdate();
        }
    }

    /** Bulk-insert chunks. Batched in one transaction — row-at-a-time commits are ~100x slower. */
    public void add(List<Chunk> chunks) throws SQLException {
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO chunks (title, heading, body, source, url, page) VALUES (?,?,?,?,?,?)")) {
            for (Chunk c : chunks) {
                ps.setString(1, c.title());
                ps.setString(2, c.heading());
                ps.setString(3, c.body());
                ps.setString(4, c.source());
                ps.setString(5, c.url());
                ps.setInt(6, c.page());
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    /** Record that {@code source} is now indexed with {@code count} chunks. */
    public void recordSource(String source, int count) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO sources (name, chunks, indexed_at) VALUES (?,?,datetime('now'))")) {
            ps.setString(1, source);
            ps.setInt(2, count);
            ps.executeUpdate();
        }
    }

    /** What is in this index, and how much of it: {@code source -> chunk count}. */
    public Map<String, Integer> sources() throws SQLException {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (Statement s = conn.createStatement();
                ResultSet r = s.executeQuery("SELECT name, chunks FROM sources ORDER BY name")) {
            while (r.next()) {
                out.put(r.getString(1), r.getInt(2));
            }
        }
        return out;
    }

    public boolean isEmpty() throws SQLException {
        try (Statement s = conn.createStatement();
                ResultSet r = s.executeQuery("SELECT count(*) FROM chunks")) {
            return r.next() && r.getInt(1) == 0;
        }
    }

    /**
     * Search the index with a natural-language question.
     *
     * <p>Runs a strict all-terms query first; if that returns too little (the usual case for a
     * long question), it falls back to any-term matching and appends whatever the strict pass
     * missed. Strict hits therefore always outrank loose ones, which is the behavior a reader
     * expects and which pure bm25 over an OR query does not give you.
     *
     * @param source restrict to one corpus ("wpilib", "manual", …), or null for all
     */
    public List<SearchHit> search(String query, String source, int limit) throws SQLException {
        List<String> terms = QueryParser.terms(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        List<SearchHit> hits = new ArrayList<>(run(QueryParser.and(terms), source, limit));
        if (hits.size() < limit && terms.size() > 1) {
            for (SearchHit loose : run(QueryParser.or(terms), source, limit * 2)) {
                if (hits.size() >= limit) {
                    break;
                }
                boolean alreadyPresent = hits.stream()
                        .anyMatch(h -> h.label().equals(loose.label()) && h.snippet().equals(loose.snippet()));
                if (!alreadyPresent) {
                    hits.add(loose);
                }
            }
        }
        return hits;
    }

    private List<SearchHit> run(String matchExpr, String source, int limit) throws SQLException {
        String sql = "SELECT source, title, heading, url, page, "
                + "snippet(chunks, 2, '**', '**', ' … ', 32) AS snip, "
                + BM25_WEIGHTS + " AS score "
                + "FROM chunks WHERE chunks MATCH ? "
                + (source == null ? "" : "AND source = ? ")
                + "ORDER BY score LIMIT ?";

        List<SearchHit> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, matchExpr);
            if (source != null) {
                ps.setString(i++, source);
            }
            ps.setInt(i, limit);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    out.add(new SearchHit(
                            r.getString("source"),
                            r.getString("title"),
                            r.getString("heading"),
                            r.getString("url"),
                            r.getInt("page"),
                            r.getString("snip"),
                            r.getDouble("score")));
                }
            }
        } catch (SQLException e) {
            // A malformed MATCH expression is a bug in QueryParser, not a reason to kill the agent's
            // whole tool call — report nothing found rather than propagating SQL syntax noise.
            if (e.getMessage() != null && e.getMessage().contains("fts5: syntax error")) {
                return List.of();
            }
            throw e;
        }
        return out;
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
