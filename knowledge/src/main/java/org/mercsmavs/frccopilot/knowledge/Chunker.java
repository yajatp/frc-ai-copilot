package org.mercsmavs.frccopilot.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups extracted lines into retrievable passages.
 *
 * <p>Chunks break on heading boundaries first — a section is the natural unit of an answer — and
 * are then split by size so a single long section cannot dominate a result list. Every chunk
 * carries its heading, so a retrieved passage still says what it is about even when read alone.
 */
final class Chunker {

    /** Roughly a screenful of prose: big enough to hold a full explanation, small enough to skim. */
    private static final int TARGET_CHARS = 1200;

    /** Below this, a trailing fragment is folded back into the previous chunk instead of standing alone. */
    private static final int MIN_CHARS = 120;

    static List<Chunk> chunk(String source, String title, String url, List<TextExtractor.Line> lines) {
        List<Chunk> out = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String currentHeading = "";

        for (TextExtractor.Line line : lines) {
            boolean headingChanged = !line.heading().equals(currentHeading);
            if (headingChanged || body.length() >= TARGET_CHARS) {
                flush(out, source, title, currentHeading, url, body);
                currentHeading = line.heading();
            }
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(line.text());
        }
        flush(out, source, title, currentHeading, url, body);
        return out;
    }

    private static void flush(
            List<Chunk> out, String source, String title, String heading, String url, StringBuilder body) {
        String text = body.toString().strip();
        body.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        // A scrap too small to answer anything is more useful appended to what came before it.
        if (text.length() < MIN_CHARS && !out.isEmpty()) {
            Chunk prev = out.remove(out.size() - 1);
            out.add(new Chunk(prev.source(), prev.title(), prev.heading(), prev.url(), prev.page(),
                    prev.body() + "\n" + text));
            return;
        }
        out.add(new Chunk(source, title, heading, url, 0, text));
    }

    private Chunker() {}
}
