package org.mercsmavs.frccopilot.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a documentation source file into plain text plus the heading structure around it.
 *
 * <p>Supports the formats FRC documentation actually ships in: reStructuredText (WPILib frc-docs,
 * PhotonVision, CTRE Phoenix 6 all use Sphinx), Markdown, HTML, and plain text. This is
 * deliberately a pragmatic extractor, not a conforming parser — the goal is retrievable prose, so
 * markup is stripped and code blocks are kept (an API name in a snippet is often the whole answer).
 */
final class TextExtractor {

    /** A line of extracted text tagged with the heading in effect at that point. */
    record Line(String heading, String text) {}

    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*$");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern HTML_HEADING =
            Pattern.compile("<h([1-6])[^>]*>(.*?)</h\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_DROP =
            Pattern.compile("<(script|style)[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** reStructuredText underlines a heading with a run of punctuation the same width or wider. */
    private static final Pattern RST_UNDERLINE = Pattern.compile("^([=\\-~`:'\"^_*+#])\\1{2,}\\s*$");

    /** Sphinx/rst inline roles and directives that carry no prose, e.g. ``:ref:`x``` or ``.. note::``. */
    private static final Pattern RST_ROLE = Pattern.compile(":[a-z:]+:`([^`]*)`");
    private static final Pattern RST_DIRECTIVE = Pattern.compile("^\\s*\\.\\.\\s+[a-z-]+::.*$");

    static List<Line> extract(String fileName, String content) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return fromHtml(content);
        }
        if (lower.endsWith(".rst")) {
            return fromRst(content);
        }
        // Markdown rules are a superset of what we need for plain text too.
        return fromMarkdown(content);
    }

    /** True if this extractor knows how to read the file at all. */
    static boolean supports(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".md")
                || lower.endsWith(".markdown")
                || lower.endsWith(".rst")
                || lower.endsWith(".txt")
                || lower.endsWith(".html")
                || lower.endsWith(".htm");
    }

    private static List<Line> fromMarkdown(String content) {
        List<Line> out = new ArrayList<>();
        String heading = "";
        for (String raw : content.split("\r?\n")) {
            Matcher m = MD_HEADING.matcher(raw);
            if (m.matches()) {
                heading = m.group(2).trim();
                out.add(new Line(heading, heading));
                continue;
            }
            String text = raw.strip();
            if (!text.isEmpty()) {
                out.add(new Line(heading, text));
            }
        }
        return out;
    }

    private static List<Line> fromRst(String content) {
        String[] lines = content.split("\r?\n");
        List<Line> out = new ArrayList<>();
        String heading = "";
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];

            // frc-docs and the CTRE/PhotonVision Sphinx sites keep the .rst extension but write
            // MyST-flavored Markdown: `#` headings and ``` fences alongside `.. note::`
            // directives. So a .rst file may use either heading style, and we accept both.
            Matcher md = MD_HEADING.matcher(raw);
            if (md.matches()) {
                heading = cleanRst(md.group(2)).strip();
                out.add(new Line(heading, heading));
                continue;
            }

            // Classic reStructuredText: a line of text whose *next* line is a punctuation underline.
            if (i + 1 < lines.length
                    && RST_UNDERLINE.matcher(lines[i + 1]).matches()
                    && !raw.isBlank()
                    && lines[i + 1].strip().length() >= raw.strip().length() - 2) {
                heading = cleanRst(raw).strip();
                out.add(new Line(heading, heading));
                i++; // consume the underline
                continue;
            }
            if (RST_UNDERLINE.matcher(raw).matches() || RST_DIRECTIVE.matcher(raw).matches()) {
                continue;
            }
            String text = cleanRst(raw).strip();
            if (!text.isEmpty()) {
                out.add(new Line(heading, text));
            }
        }
        return out;
    }

    private static String cleanRst(String s) {
        String t = RST_ROLE.matcher(s).replaceAll("$1");
        return t.replace("``", "").replace("**", "").replace("|", "");
    }

    private static List<Line> fromHtml(String content) {
        String cleaned = HTML_DROP.matcher(content).replaceAll(" ");

        // Mark headings before stripping tags so the structure survives.
        Matcher m = HTML_HEADING.matcher(cleaned);
        StringBuilder marked = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(marked,
                    Matcher.quoteReplacement("\n\u0001" + unescape(HTML_TAG.matcher(m.group(2)).replaceAll("")).strip() + "\n"));
        }
        m.appendTail(marked);

        String text = unescape(HTML_TAG.matcher(marked).replaceAll("\n"));
        List<Line> out = new ArrayList<>();
        String heading = "";
        for (String raw : text.split("\r?\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("\u0001")) {
                heading = line.substring(1).strip();
                if (!heading.isEmpty()) {
                    out.add(new Line(heading, heading));
                }
                continue;
            }
            out.add(new Line(heading, line));
        }
        return out;
    }

    private static String unescape(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private TextExtractor() {}
}
