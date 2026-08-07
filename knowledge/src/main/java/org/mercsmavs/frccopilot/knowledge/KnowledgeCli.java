package org.mercsmavs.frccopilot.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Builds and queries the offline knowledge index.
 *
 * <pre>
 *   fetch  &lt;corpus&gt; &lt;dir&gt;              shallow-clone a known docs repo
 *   index  &lt;db&gt; &lt;source&gt; &lt;dir&gt; [urlBase]   index a local directory (or PDF) under a source label
 *   sync   &lt;db&gt; &lt;dir&gt;                 fetch + index every known corpus in one step
 *   manual &lt;db&gt; &lt;manual.pdf&gt;          index a game manual PDF as source "manual"
 *   search &lt;db&gt; &lt;query...&gt;            search everything
 *   ask    &lt;db&gt; &lt;source&gt; &lt;query...&gt;   search one corpus
 *   status &lt;db&gt;                       what is indexed
 * </pre>
 */
public final class KnowledgeCli {

    private static final String USAGE =
            """
            usage: knowledge <command> <args>

              sync   <db> <checkout-dir>              fetch + index every known corpus
              fetch  <corpus> <dir>                   shallow-clone one docs repo
              index  <db> <source> <dir> [urlBase]    index a local directory or PDF
              manual <db> <manual.pdf>                index a game manual PDF
              search <db> <query...>                  search all sources
              ask    <db> <source> <query...>         search one source
              status <db>                             show what is indexed

            Known corpora: %s
            REV publishes no public docs repo — download its pages and use `index` on the folder.
            """;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.printf(USAGE, Corpus.names());
            System.exit(2);
            return;
        }
        try {
            switch (args[0]) {
                case "sync" -> sync(args[1], Path.of(require(args, 2, "checkout-dir")));
                case "fetch" -> {
                    Corpus c = Corpus.byName(args[1]);
                    Path dest = Path.of(require(args, 2, "dir")).resolve(c.name());
                    fetch(c, dest);
                }
                case "index" -> index(args[1], require(args, 2, "source"), Path.of(require(args, 3, "dir")),
                        args.length > 4 ? args[4] : null);
                case "manual" -> indexManual(args[1], Path.of(require(args, 2, "manual.pdf")));
                case "search" -> search(args[1], joinFrom(args, 2), null);
                case "ask" -> search(args[1], joinFrom(args, 3), require(args, 2, "source"));
                case "status" -> status(args[1]);
                default -> {
                    System.err.println("unknown command: " + args[0]);
                    System.err.printf(USAGE, Corpus.names());
                    System.exit(2);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(2);
        }
    }

    /** The one-command path: clone everything we know about, then index all of it. */
    private static void sync(String db, Path checkoutDir) throws Exception {
        Files.createDirectories(checkoutDir);
        for (Corpus c : Corpus.KNOWN) {
            Path dest = checkoutDir.resolve(c.name());
            try {
                fetch(c, dest);
            } catch (IOException | InterruptedException e) {
                // One unreachable repo must not abort the other three.
                System.err.println("  ! skipping " + c.name() + ": " + e.getMessage());
                continue;
            }
            Path docs = c.subdir().isEmpty() ? dest : dest.resolve(c.subdir());
            if (!Files.isDirectory(docs)) {
                System.err.println("  ! " + c.name() + ": expected docs at " + docs + " — skipping");
                continue;
            }
            index(db, c.name(), docs, c.urlBase());
        }
        status(db);
    }

    private static void fetch(Corpus c, Path dest) throws IOException, InterruptedException {
        if (Files.isDirectory(dest.resolve(".git"))) {
            System.out.println("Updating " + c.name() + " in " + dest);
            run(dest, "git", "pull", "--ff-only", "--quiet");
            return;
        }
        Files.createDirectories(dest.getParent());
        System.out.println("Cloning " + c.name() + " (" + c.description() + ")");

        // Some corpora live inside a full product repo — PhotonVision's docs are ~9 MB inside a
        // ~500 MB source tree. Depth 1 alone still pulls all of it, which is a bad trade on a pit
        // laptop, so when the docs are in a subdirectory we sparse-checkout just that path.
        if (!c.subdir().isEmpty()) {
            run(null, "git", "clone", "--depth", "1", "--filter=blob:none", "--sparse", "--quiet",
                    c.repo(), dest.toString());
            run(dest, "git", "sparse-checkout", "set", c.subdir());
        } else {
            // Depth 1: we want the current docs, not years of history.
            run(null, "git", "clone", "--depth", "1", "--quiet", c.repo(), dest.toString());
        }
    }

    private static void run(Path workingDir, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).inheritIO();
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        Process p = pb.start();
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("timed out: " + String.join(" ", command));
        }
        if (p.exitValue() != 0) {
            throw new IOException("command failed (" + p.exitValue() + "): " + String.join(" ", command));
        }
    }

    private static void index(String db, String source, Path dir, String urlBase) throws Exception {
        if (!Files.exists(dir)) {
            throw new IllegalArgumentException("no such directory: " + dir);
        }
        System.out.println("Indexing " + source + " from " + dir);
        List<Chunk> chunks = Files.isRegularFile(dir)
                ? Indexer.fromPdf(dir, source)
                : Indexer.fromDirectory(dir, source, urlBase);
        try (KnowledgeIndex index = new KnowledgeIndex(db)) {
            index.clearSource(source); // re-indexing replaces, never duplicates
            index.add(chunks);
            index.recordSource(source, chunks.size());
        }
        System.out.println("  indexed " + chunks.size() + " chunk(s)");
    }

    private static void indexManual(String db, Path pdf) throws Exception {
        if (!Files.isRegularFile(pdf)) {
            throw new IllegalArgumentException("no such file: " + pdf);
        }
        index(db, "manual", pdf, null);
    }

    private static void search(String db, String query, String source) throws Exception {
        if (query.isBlank()) {
            throw new IllegalArgumentException("empty query");
        }
        try (KnowledgeIndex index = new KnowledgeIndex(db)) {
            if (index.isEmpty()) {
                System.out.println("The index is empty. Run: knowledge sync " + db + " <checkout-dir>");
                return;
            }
            List<SearchHit> hits = index.search(query, source, 8);
            if (hits.isEmpty()) {
                System.out.println("No matches for: " + query);
                return;
            }
            for (SearchHit h : hits) {
                System.out.println(h.render());
                System.out.println();
            }
        }
    }

    private static void status(String db) throws Exception {
        try (KnowledgeIndex index = new KnowledgeIndex(db)) {
            Map<String, Integer> sources = index.sources();
            if (sources.isEmpty()) {
                System.out.println("Nothing indexed yet in " + db);
                return;
            }
            System.out.printf("%-16s %s%n", "SOURCE", "CHUNKS");
            sources.forEach((name, count) -> System.out.printf("%-16s %d%n", name, count));
        }
    }

    private static String joinFrom(String[] args, int start) {
        if (args.length <= start) {
            throw new IllegalArgumentException("missing query");
        }
        return String.join(" ", java.util.Arrays.copyOfRange(args, start, args.length));
    }

    private static String require(String[] args, int i, String name) {
        if (args.length <= i) {
            throw new IllegalArgumentException("missing required argument: " + name);
        }
        return args[i];
    }

    private KnowledgeCli() {}
}
