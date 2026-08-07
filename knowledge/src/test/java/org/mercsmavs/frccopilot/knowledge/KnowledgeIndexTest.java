package org.mercsmavs.frccopilot.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeIndexTest {

    private static final String RST_DOC =
            """
            Swerve Drive Kinematics
            =======================

            The :ref:`SwerveDriveKinematics <docs/kinematics>` class converts between a
            ``ChassisSpeeds`` object and several ``SwerveModuleState`` objects.

            Converting chassis speeds
            -------------------------

            The ``toSwerveModuleStates`` method converts a ChassisSpeeds object into an array of
            SwerveModuleState objects, one for each module on the drivetrain.

            .. note:: Module order must match the order the kinematics object was constructed with.

            Desaturating wheel speeds
            -------------------------

            When a commanded velocity exceeds what the modules can achieve, call
            desaturateWheelSpeeds to scale every module down proportionally.
            """;

    private static final String MD_DOC =
            """
            # PID Control

            ## Tuning a position controller

            Increase kP until the mechanism oscillates, then back it off. A brownout during a
            heavy current draw will look like a tuning problem but is an electrical problem.
            """;

    private KnowledgeIndex indexOf(Path tmp) throws Exception {
        KnowledgeIndex index = new KnowledgeIndex(tmp.resolve("k.db").toString());
        Path corpus = Files.createDirectories(tmp.resolve("corpus"));
        Files.writeString(corpus.resolve("swerve.rst"), RST_DOC);
        Files.writeString(corpus.resolve("pid.md"), MD_DOC);

        List<Chunk> chunks = Indexer.fromDirectory(corpus, "wpilib", "https://docs.wpilib.org/en/stable");
        index.add(chunks);
        index.recordSource("wpilib", chunks.size());
        return index;
    }

    @Test
    void findsThePassageThatAnswersANaturalLanguageQuestion(@TempDir Path tmp) throws Exception {
        try (KnowledgeIndex index = indexOf(tmp)) {
            List<SearchHit> hits = index.search("How do I convert chassis speeds to module states?", null, 5);
            assertFalse(hits.isEmpty(), "expected a match");
            // The section actually about the conversion must outrank the rest of the document.
            assertTrue(hits.get(0).label().contains("Converting chassis speeds"),
                    () -> "top hit was: " + hits.get(0).label());
        }
    }

    @Test
    void questionPunctuationDoesNotBreakFts5(@TempDir Path tmp) throws Exception {
        try (KnowledgeIndex index = indexOf(tmp)) {
            // Quotes, parens and operators are FTS5 syntax; unescaped they are a SQL error.
            for (String q : List.of(
                    "what is \"desaturateWheelSpeeds\"?",
                    "kinematics AND (module OR state)",
                    "NEAR/2 swerve*",
                    "-----",
                    "how do I ???")) {
                assertTrue(index.search(q, null, 3) != null, "query must not throw: " + q);
            }
        }
    }

    @Test
    void strictAllTermMatchesOutrankLooseOnes(@TempDir Path tmp) throws Exception {
        try (KnowledgeIndex index = indexOf(tmp)) {
            List<SearchHit> hits = index.search("desaturate wheel speeds", null, 5);
            assertTrue(hits.get(0).snippet().toLowerCase().contains("desaturate")
                            || hits.get(0).label().contains("Desaturating"),
                    () -> "top hit was: " + hits.get(0).label());
        }
    }

    @Test
    void sourceFilterRestrictsResults(@TempDir Path tmp) throws Exception {
        try (KnowledgeIndex index = indexOf(tmp)) {
            assertFalse(index.search("kinematics", "wpilib", 5).isEmpty());
            assertTrue(index.search("kinematics", "manual", 5).isEmpty(), "no manual corpus is indexed");
        }
    }

    @Test
    void reindexingASourceReplacesRatherThanDuplicates(@TempDir Path tmp) throws Exception {
        try (KnowledgeIndex index = indexOf(tmp)) {
            int before = index.search("kinematics", null, 50).size();

            Path corpus = tmp.resolve("corpus");
            List<Chunk> again = Indexer.fromDirectory(corpus, "wpilib", null);
            index.clearSource("wpilib");
            index.add(again);

            assertEquals(before, index.search("kinematics", null, 50).size(),
                    "re-indexing the same corpus must not double the hits");
        }
    }

    @Test
    void rstHeadingsAndRolesAreCleanedUp(@TempDir Path tmp) throws Exception {
        try (KnowledgeIndex index = indexOf(tmp)) {
            SearchHit hit = index.search("SwerveDriveKinematics converts ChassisSpeeds", null, 1).get(0);
            assertEquals("wpilib", hit.source());
            assertTrue(hit.title().contains("Swerve Drive Kinematics"), () -> "title was: " + hit.title());
            // The :ref:`text <target>` role should have been reduced to its display text.
            assertFalse(hit.snippet().contains(":ref:"), () -> "snippet leaked rst markup: " + hit.snippet());
        }
    }

    @Test
    void urlsAreReconstructedFromTheCorpusLayout(@TempDir Path tmp) throws Exception {
        try (KnowledgeIndex index = indexOf(tmp)) {
            SearchHit hit = index.search("desaturateWheelSpeeds", null, 1).get(0);
            assertEquals("https://docs.wpilib.org/en/stable/swerve.html", hit.url());
        }
    }

    /**
     * frc-docs keeps the .rst extension but writes MyST-flavored Markdown: `#` headings and ```
     * fences mixed with `.. note::` directives. Verbatim excerpt from the real corpus — an
     * extractor that only understands classic RST underlines silently produces titleless chunks.
     */
    private static final String MYST_RST_DOC =
            """
            # Swerve Drive Kinematics
            The ``SwerveDriveKinematics`` class is a useful tool that converts between a
            ``ChassisSpeeds`` object and several ``SwerveModuleState`` objects.

            .. note:: Swerve drive kinematics uses a common coordinate system. See
               :doc:`/docs/software/basic-programming/coordinate-system` for details.

            ## Converting chassis speeds to module states
            The toSwerveModuleStates(ChassisSpeeds speeds) method should be used to convert a
            ChassisSpeeds object to an array of SwerveModuleState objects.
            """;

    @Test
    void parsesTheMystFlavoredRstThatFrcDocsActuallyUses(@TempDir Path tmp) throws Exception {
        Path corpus = Files.createDirectories(tmp.resolve("myst"));
        Files.writeString(corpus.resolve("swerve-drive-kinematics.rst"), MYST_RST_DOC);
        List<Chunk> chunks = Indexer.fromDirectory(corpus, "wpilib", null);

        // The title must come from the `#` heading, not from the filename.
        assertEquals("Swerve Drive Kinematics", chunks.get(0).title());
        assertTrue(chunks.stream().anyMatch(c -> c.heading().equals("Converting chassis speeds to module states")),
                () -> "headings were: " + chunks.stream().map(Chunk::heading).toList());
        // `#` markers must not survive into indexed prose.
        assertTrue(chunks.stream().noneMatch(c -> c.body().contains("## ")),
                "markdown heading markers leaked into the body");
    }

    @Test
    void emptyIndexIsReportedRatherThanCrashing(@TempDir Path tmp) throws Exception {
        try (KnowledgeIndex index = new KnowledgeIndex(tmp.resolve("empty.db").toString())) {
            assertTrue(index.isEmpty());
            assertTrue(index.search("anything", null, 5).isEmpty());
        }
    }
}
