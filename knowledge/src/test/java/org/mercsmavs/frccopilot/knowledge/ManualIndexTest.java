package org.mercsmavs.frccopilot.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Game manuals are the one corpus where a wrong answer gets a team penalized on the field, so the
 * page number a result cites has to be right. These tests build a small PDF whose rules are on
 * known pages and check that retrieval reports those exact pages.
 */
class ManualIndexTest {

    /** Rule text per page, mimicking the manual's numbering style. */
    private static final String[][] PAGES = {
        {"G410 Robots may not intentionally detach or leave parts on the FIELD.",
         "Violation: FOUL. If repeated, YELLOW CARD."},
        {"G411 A ROBOT may not extend more than 12 in. beyond its FRAME PERIMETER.",
         "Violation: MINOR FOUL per instance of extension beyond the limit."},
        {"H201 DRIVE TEAMS may not enter the FIELD without a FIELD STAFF escort.",
         "Violation: verbal warning, then YELLOW CARD for the ALLIANCE."},
    };

    private Path writeManual(Path tmp) throws Exception {
        Path pdf = tmp.resolve("game-manual.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (String[] lines : PAGES) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.setLeading(16f);
                    cs.newLineAtOffset(60, 700);
                    for (String line : lines) {
                        cs.showText(line);
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    @Test
    void everyPageBecomesAChunkTaggedWithItsPageNumber(@TempDir Path tmp) throws Exception {
        List<Chunk> chunks = Indexer.fromPdf(writeManual(tmp), "manual");
        assertEquals(3, chunks.size(), "one chunk per page");
        assertEquals(1, chunks.get(0).page());
        assertEquals(3, chunks.get(2).page());
        // Rule codes are promoted to the heading so a code query hits the weighted column.
        assertTrue(chunks.get(0).heading().contains("G410"), () -> "heading was: " + chunks.get(0).heading());
    }

    @Test
    void lookingUpARuleCodeReturnsItsPage(@TempDir Path tmp) throws Exception {
        Path pdf = writeManual(tmp);
        try (KnowledgeIndex index = new KnowledgeIndex(tmp.resolve("k.db").toString())) {
            List<Chunk> chunks = Indexer.fromPdf(pdf, "manual");
            index.add(chunks);
            index.recordSource("manual", chunks.size());

            List<SearchHit> hits = index.search("G411", "manual", 3);
            assertFalse(hits.isEmpty(), "expected to find rule G411");
            assertEquals(2, hits.get(0).page(), "G411 is on page 2 of the fixture");
            assertEquals("page 2", hits.get(0).citation());
        }
    }

    @Test
    void aQuestionAboutARuleFindsItWithoutKnowingTheCode(@TempDir Path tmp) throws Exception {
        Path pdf = writeManual(tmp);
        try (KnowledgeIndex index = new KnowledgeIndex(tmp.resolve("k.db").toString())) {
            index.add(Indexer.fromPdf(pdf, "manual"));

            List<SearchHit> hits = index.search(
                    "how far can a robot extend beyond its frame perimeter?", "manual", 3);
            assertFalse(hits.isEmpty());
            assertEquals(2, hits.get(0).page(), "the extension rule is on page 2");
        }
    }
}
