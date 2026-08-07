package org.mercsmavs.frccopilot.knowledge;

/**
 * One indexed passage. Chunks — not whole files — are the unit of retrieval: a WPILib article can
 * run thousands of words, and handing an agent the whole thing wastes its context and buries the
 * one paragraph that answered the question.
 *
 * @param source corpus this came from ("wpilib", "ctre", "manual", …)
 * @param title document title (usually the file's top-level heading)
 * @param heading the nearest enclosing section heading, or "" at the top of a document
 * @param url a best-effort link back to the canonical docs page, or "" if unknown
 * @param page 1-based page number for PDF sources; 0 for text sources
 * @param body the passage text
 */
public record Chunk(String source, String title, String heading, String url, int page, String body) {

    /** The heading path shown to a reader: "Title › Section", collapsing empties. */
    public String label() {
        if (heading.isEmpty() || heading.equals(title)) {
            return title;
        }
        return title + " › " + heading;
    }
}
