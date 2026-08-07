package org.mercsmavs.frccopilot.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns a natural-language question into a safe FTS5 MATCH expression.
 *
 * <p>This exists because agents and humans both ask questions, not queries: "How do I stop my
 * swerve modules from oscillating?" Handed straight to FTS5 that is a syntax error (unquoted
 * punctuation) and, once escaped, a bad query (every stopword weighted like every real term). So
 * we tokenize, drop stopwords, quote every term, and let the caller try a strict AND before
 * falling back to OR.
 */
final class QueryParser {

    /**
     * Words that appear in nearly every FRC doc page or carry no retrieval signal. Kept short on
     * purpose — over-aggressive stopword lists silently delete meaningful terms ("can" is a CAN
     * bus, "state" is a swerve module state), so anything ambiguous stays in.
     */
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "if", "then", "than", "that", "this", "these",
            "those", "is", "are", "was", "were", "be", "been", "being", "to", "of", "in", "on",
            "for", "with", "at", "by", "from", "as", "it", "its", "i", "my", "we", "our", "you",
            "your", "do", "does", "did", "how", "what", "why", "when", "where", "which", "who",
            "should", "would", "could", "will", "about", "into", "there", "their", "them");

    /** Single characters and pure noise never help; two-letter terms sometimes do (PID, NT, DS). */
    private static final int MIN_TERM_LENGTH = 2;

    /** Extract the meaningful search terms from a free-form question. */
    static List<String> terms(String query) {
        List<String> terms = new ArrayList<>();
        for (String raw : query.toLowerCase().split("[^\\p{Alnum}_]+")) {
            if (raw.length() < MIN_TERM_LENGTH || STOPWORDS.contains(raw)) {
                continue;
            }
            if (!terms.contains(raw)) {
                terms.add(raw);
            }
        }
        // A question made entirely of stopwords still deserves an attempt.
        if (terms.isEmpty()) {
            for (String raw : query.toLowerCase().split("[^\\p{Alnum}_]+")) {
                if (raw.length() >= MIN_TERM_LENGTH && !terms.contains(raw)) {
                    terms.add(raw);
                }
            }
        }
        return terms;
    }

    /** All terms required. High precision — use this first. */
    static String and(List<String> terms) {
        return join(terms, " AND ");
    }

    /** Any term matches; bm25 still ranks passages containing more of them higher. */
    static String or(List<String> terms) {
        return join(terms, " OR ");
    }

    private static String join(List<String> terms, String op) {
        List<String> quoted = new ArrayList<>(terms.size());
        for (String t : terms) {
            // Double-quoting makes each term a literal FTS5 string, so no user input can ever be
            // read as operator syntax. Internal quotes are escaped by doubling, per SQL rules.
            quoted.add('"' + t.replace("\"", "\"\"") + '"');
        }
        return String.join(op, quoted);
    }

    private QueryParser() {}
}
