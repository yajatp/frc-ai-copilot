package org.mercsmavs.frccopilot.simreplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Runs a log-producing command inside a robot project (headless sim, an AdvantageKit REPLAY pass,
 * or any process that writes a .wpilog) and locates the log it produced. This is the "run" step of
 * the closed loop; assertion checking is done by {@link Verifier}.
 *
 * <p>Kept generic on purpose: the harness does not care whether the command is
 * {@code ./gradlew simulateJava}, a replay task, or a custom script — only that it yields a log.
 */
public final class SimRunner {

    public record RunResult(int exitCode, Optional<Path> log, String tail) {}

    /**
     * Execute {@code command} (argv) in {@code workingDir}, then return the newest {@code .wpilog}
     * found under {@code logSearchDir} that was modified at/after the run started.
     */
    public static RunResult run(Path workingDir, List<String> command, Path logSearchDir, long timeoutSeconds)
            throws IOException, InterruptedException {
        long startedAt = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        boolean finished = p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
        }
        int exit = finished ? p.exitValue() : -1;
        Optional<Path> newest = newestLogSince(logSearchDir, startedAt);
        return new RunResult(exit, newest, tail(output, 40));
    }

    /** Find the newest .wpilog under a directory modified at/after the given epoch millis. */
    public static Optional<Path> newestLogSince(Path dir, long sinceMillis) throws IOException {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".wpilog"))
                    .filter(p -> lastModified(p) >= sinceMillis - 1000)
                    .max(Comparator.comparingLong(SimRunner::lastModified));
        }
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String tail(String text, int lines) {
        String[] arr = text.split("\n");
        int from = Math.max(0, arr.length - lines);
        return String.join("\n", java.util.Arrays.copyOfRange(arr, from, arr.length));
    }

    private SimRunner() {}
}
