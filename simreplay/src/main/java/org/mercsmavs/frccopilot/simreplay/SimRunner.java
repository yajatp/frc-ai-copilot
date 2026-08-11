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

    /** Outcome of a command run for its exit status alone (a build step, say). */
    public record ExecResult(int exitCode, String tail) {
        public boolean ok() {
            return exitCode == 0;
        }

        /** True when the process was killed for exceeding its timeout. */
        public boolean timedOut() {
            return exitCode == TIMEOUT_EXIT;
        }
    }

    /** Exit code reported when a command had to be killed for running past its timeout. */
    public static final int TIMEOUT_EXIT = -1;

    /**
     * Execute {@code command} (argv) in {@code workingDir}, then return the newest {@code .wpilog}
     * found under {@code logSearchDir} that was modified at/after the run started.
     */
    public static RunResult run(Path workingDir, List<String> command, Path logSearchDir, long timeoutSeconds)
            throws IOException, InterruptedException {
        return run(workingDir, command, logSearchDir, timeoutSeconds, java.util.Map.of());
    }

    public static RunResult run(
            Path workingDir,
            List<String> command,
            Path logSearchDir,
            long timeoutSeconds,
            java.util.Map<String, String> env)
            throws IOException, InterruptedException {
        long startedAt = System.currentTimeMillis();
        ExecResult exec = exec(workingDir, command, timeoutSeconds, 40, env);
        Optional<Path> newest = newestLogSince(logSearchDir, startedAt);
        return new RunResult(exec.exitCode(), newest, exec.tail());
    }

    /**
     * Run a command to completion, capturing the tail of its combined output. Used for steps whose
     * product is a status rather than a log — notably the build, where a non-zero exit and its
     * compiler output are the most useful thing the loop can hand back.
     */
    public static ExecResult exec(Path workingDir, List<String> command, long timeoutSeconds, int tailLines)
            throws IOException, InterruptedException {
        return exec(workingDir, command, timeoutSeconds, tailLines, java.util.Map.of());
    }

    public static ExecResult exec(
            Path workingDir,
            List<String> command,
            long timeoutSeconds,
            int tailLines,
            java.util.Map<String, String> env)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.environment().putAll(env);
        // Gradle wrappers and Java launcher scripts need a JDK on JAVA_HOME. The loop is itself
        // running on one, so hand that down rather than depending on the caller's shell profile —
        // an MCP server started by an editor typically inherits none.
        pb.environment().computeIfAbsent("JAVA_HOME", k -> System.getProperty("java.home"));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // Drain before waiting: a process that fills the pipe buffer blocks forever otherwise.
        String output = new String(p.getInputStream().readAllBytes());
        boolean finished = p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
        }
        return new ExecResult(finished ? p.exitValue() : TIMEOUT_EXIT, tail(output, tailLines));
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
