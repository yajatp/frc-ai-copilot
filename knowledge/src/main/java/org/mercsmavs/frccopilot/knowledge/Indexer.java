package org.mercsmavs.frccopilot.knowledge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/** Walks a corpus directory (or a single PDF) and turns it into indexable {@link Chunk}s. */
public final class Indexer {

    /** Directory names that hold build output or assets, never prose worth indexing. */
    private static final List<String> SKIP_DIRS =
            List.of("_build", "build", "node_modules", ".git", "_static", "_images", "images", "assets");

    /**
     * Index every supported file under {@code root}.
     *
     * @param source corpus label recorded on every chunk ("wpilib", "ctre", …)
     * @param urlBase optional prefix used to reconstruct a docs URL from the relative file path;
     *     pass null when the corpus has no canonical web home
     */
    public static List<Chunk> fromDirectory(Path root, String source, String urlBase) throws IOException {
        List<Chunk> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isSkipped(root, p))
                    .forEach(p -> {
                        try {
                            String name = p.getFileName().toString();
                            if (name.toLowerCase().endsWith(".pdf")) {
                                out.addAll(fromPdf(p, source));
                            } else if (TextExtractor.supports(name)) {
                                out.addAll(fromTextFile(root, p, source, urlBase));
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException("failed reading " + p, e);
                        }
                    });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return out;
    }

    private static boolean isSkipped(Path root, Path file) {
        Path rel = root.relativize(file);
        for (Path part : rel) {
            String name = part.toString();
            if (SKIP_DIRS.contains(name) || (name.startsWith(".") && name.length() > 1)) {
                return true;
            }
        }
        return false;
    }

    private static List<Chunk> fromTextFile(Path root, Path file, String source, String urlBase)
            throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        List<TextExtractor.Line> lines = TextExtractor.extract(file.getFileName().toString(), content);
        if (lines.isEmpty()) {
            return List.of();
        }
        // The first heading is the document title; fall back to the filename when there is none.
        String title = lines.stream()
                .map(TextExtractor.Line::heading)
                .filter(h -> !h.isEmpty())
                .findFirst()
                .orElse(stripExtension(file.getFileName().toString()));
        return Chunker.chunk(source, title, url(root, file, urlBase), lines);
    }

    /** Reconstruct a docs URL from the file's path within the corpus, e.g. .../docs/swerve.html. */
    private static String url(Path root, Path file, String urlBase) {
        if (urlBase == null || urlBase.isEmpty()) {
            return "";
        }
        String rel = root.relativize(file).toString().replace('\\', '/');
        rel = stripExtension(rel) + ".html";
        return urlBase.endsWith("/") ? urlBase + rel : urlBase + "/" + rel;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /**
     * Extract a PDF one page at a time so every chunk carries a real page number. For a game
     * manual that is the whole point: "rule G410, page 78" is checkable, "somewhere in the manual"
     * is not.
     */
    public static List<Chunk> fromPdf(Path file, String source) throws IOException {
        List<Chunk> out = new ArrayList<>();
        String title = stripExtension(file.getFileName().toString());
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pages = doc.getNumberOfPages();
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(doc).strip();
                if (text.isEmpty()) {
                    continue;
                }
                out.add(new Chunk(source, title, headingFor(text), "", page, text));
            }
        }
        return out;
    }

    /**
     * Game manuals label rules with codes like G410 or H201. Promoting the first one on a page to
     * the heading makes results scannable and lets a rule-code query hit the weighted heading
     * column rather than only the body.
     */
    private static String headingFor(String pageText) {
        var matcher = java.util.regex.Pattern.compile("\\b([A-Z]{1,2}\\d{3})\\b").matcher(pageText);
        List<String> codes = new ArrayList<>();
        while (matcher.find() && codes.size() < 6) {
            if (!codes.contains(matcher.group(1))) {
                codes.add(matcher.group(1));
            }
        }
        return String.join(", ", codes);
    }

    private Indexer() {}
}
