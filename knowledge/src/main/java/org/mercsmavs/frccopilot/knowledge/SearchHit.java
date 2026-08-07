package org.mercsmavs.frccopilot.knowledge;

/**
 * One search result.
 *
 * @param score FTS5 bm25 score — negative, with more-negative meaning more relevant
 */
public record SearchHit(
        String source, String title, String heading, String url, int page, String snippet, double score) {

    /** "WPILib Swerve Drive Kinematics › Converting chassis speeds" */
    public String label() {
        String base = heading == null || heading.isEmpty() || heading.equals(title) ? title : title + " › " + heading;
        return base;
    }

    /** A citation a human can follow: a page number for the manual, a URL for web docs. */
    public String citation() {
        if (page > 0) {
            return "page " + page;
        }
        return url == null || url.isEmpty() ? "" : url;
    }

    /** One-line rendering used by both the CLI and the MCP tools so output stays consistent. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(source).append("] ").append(label());
        String cite = citation();
        if (!cite.isEmpty()) {
            sb.append("  (").append(cite).append(')');
        }
        sb.append('\n').append("    ").append(snippet.replace("\n", " ").strip());
        return sb.toString();
    }
}
