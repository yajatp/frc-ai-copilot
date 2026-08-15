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
        ProcessBuilder pb = new ProcessBuilder(resolveExecutable(workingDir, command));
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

    /**
     * Make a relative command work the same way on every platform, by absolutizing it against the
     * working directory when it names a file that is actually there.
     *
     * <p>{@code ProcessBuilder.directory()} sets the child's working directory, but it does not
     * affect how the executable itself is located. On POSIX the JVM changes directory before exec,
     * so {@code ./gradlew} resolves against the new one. Windows {@code CreateProcess} resolves the
     * program name against the <em>calling</em> process's directory and PATH instead — so the same
     * config fails with "The system cannot find the file specified" even though the script is
     * sitting in {@code workDir}.
     *
     * <p>Every {@code loop.yaml} in this repo, and the documented example, uses a relative command,
     * which is the natural thing to write when the config already declares a {@code workDir}. So
     * this resolves it rather than asking Windows users to write absolute paths that could not then
     * be committed.
     *
     * <p>Left untouched when the command is absolute, or when nothing by that name exists in
     * {@code workDir} — a bare {@code gradle} or {@code npm} is meant to come off PATH.
     */
    static List<String> resolveExecutable(Path workingDir, List<String> command) {
        if (command.isEmpty()) {
            return command;
        }
        String program = command.get(0);
        Path candidate = Path.of(program);
        if (candidate.isAbsolute()) {
            return command;
        }
        Path resolved = workingDir.resolve(candidate).normalize();
        if (!Files.isRegularFile(resolved)) {
            return command; // not a script in the project — let the OS search PATH
        }
        List<String> out = new java.util.ArrayList<>(command);
        out.set(0, resolved.toAbsolutePath().toString());
        return out;
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
