package org.mercsmavs.frccopilot.simreplay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A robot project's declaration of how to close the loop on itself: what to build, what to run,
 * where the produced log lands, which scenarios must pass, and which sources count as "the code".
 *
 * <p>Without this, every {@code loop} invocation has to be handed the build command, the run
 * command, the log directory and the scenario path — four arguments an agent has to keep straight
 * across iterations and cannot discover from the repository. With it, {@code loop iterate} takes no
 * arguments, which is what makes the cycle something an agent can drive unattended.
 *
 * <p>Paths are resolved relative to the config file's own directory, so a checked-in
 * {@code loop.yaml} works from any working directory.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class LoopConfig {

    /** Placeholder in a run command, substituted with a fresh log path for each iteration. */
    public static final String LOG_PLACEHOLDER = "{log}";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** Conventional file name, looked up by {@link #discover(Path)}. */
    public static final String FILE_NAME = "loop.yaml";

    public String name;
    public String description;
    /** Directory commands run in, relative to the config file. Defaults to the config's directory. */
    public String workDir;
    /** Optional build step; skipped when absent. Run before every iteration. */
    public List<String> build;
    /** Required run step. Include {@link #LOG_PLACEHOLDER} to be handed an output path. */
    public List<String> run;
    /** Where produced .wpilog files are searched for when {@code run} has no {@code {log}}. */
    public String logDir;
    /** Directory of scenario .yaml files that must pass. */
    public String scenarioDir;
    /** Optional known-good log that failures are diffed against. */
    public String baseline;
    /** Source trees fingerprinted so an iteration can report which files changed. */
    public List<String> sources;
    /** Per-step timeout. */
    public long timeoutSeconds = 600;
    /** Extra arguments appended to the run command (e.g. a mode switch). */
    public List<String> runArgs;
    /**
     * Environment variables for the build and run steps, layered over the inherited environment.
     * Values may use {@code {baseDir}} (this file's directory) and {@code {workDir}}, which is how
     * a committed config can point at a path inside the checkout without hardcoding a machine.
     */
    public java.util.Map<String, String> env;

    private transient Path baseDir = Path.of(".");

    /** Load a config and remember its directory for path resolution. */
    public static LoopConfig load(Path file) throws IOException {
        LoopConfig config = YAML.readValue(Files.readString(file), LoopConfig.class);
        config.baseDir = file.toAbsolutePath().getParent();
        config.validate(file);
        return config;
    }

    /**
     * Find the nearest {@code loop.yaml} at or above {@code start} — so the loop can be driven from
     * anywhere inside the robot project, the way {@code git} finds its root.
     */
    public static Path discover(Path start) throws IOException {
        Path dir = start.toAbsolutePath();
        if (Files.isRegularFile(dir)) {
            return dir;
        }
        while (dir != null) {
            Path candidate = dir.resolve(FILE_NAME);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IOException("No " + FILE_NAME + " found at or above " + start.toAbsolutePath());
    }

    private void validate(Path file) throws IOException {
        if (run == null || run.isEmpty()) {
            throw new IOException(file + ": 'run' is required (the command that produces a .wpilog)");
        }
        if (scenarioDir == null && baseline == null) {
            throw new IOException(file + ": set 'scenarioDir' (scenarios to verify) — nothing to check against");
        }
        if (!producesLogPath() && logDir == null) {
            throw new IOException(
                    file + ": set 'logDir', or put " + LOG_PLACEHOLDER + " in 'run' — otherwise the"
                            + " produced log cannot be located");
        }
    }

    /** True when the run command asks to be handed the output path rather than choosing its own. */
    public boolean producesLogPath() {
        return run.stream().anyMatch(a -> a.contains(LOG_PLACEHOLDER));
    }

    /** Build the argv for one iteration, substituting {@code {log}} with the given output path. */
    public List<String> runCommand(Path logFile) {
        List<String> argv = new ArrayList<>(run.size());
        for (String arg : run) {
            argv.add(arg.replace(LOG_PLACEHOLDER, logFile.toAbsolutePath().toString()));
        }
        if (runArgs != null) {
            argv.addAll(runArgs);
        }
        return argv;
    }

    public Path workDirPath() {
        return workDir == null ? baseDir : resolve(workDir);
    }

    public Path logDirPath() {
        return logDir == null ? loopStateDir().resolve("logs") : resolve(logDir);
    }

    public Path scenarioDirPath() {
        return scenarioDir == null ? null : resolve(scenarioDir);
    }

    public Path baselinePath() {
        return baseline == null ? null : resolve(baseline);
    }

    /** Where the iteration journal and generated logs live. */
    public Path loopStateDir() {
        return baseDir.resolve(".loop");
    }

    public List<Path> sourcePaths() {
        List<Path> paths = new ArrayList<>();
        if (sources != null) {
            for (String s : sources) {
                paths.add(resolve(s));
            }
        }
        return paths;
    }

    public Path baseDir() {
        return baseDir;
    }

    /** Environment overrides with {@code {baseDir}} / {@code {workDir}} expanded to real paths. */
    public java.util.Map<String, String> resolvedEnv() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (env == null) {
            return out;
        }
        String base = baseDir.toAbsolutePath().toString();
        String work = workDirPath().toAbsolutePath().toString();
        for (java.util.Map.Entry<String, String> e : env.entrySet()) {
            out.put(e.getKey(), e.getValue().replace("{baseDir}", base).replace("{workDir}", work));
        }
        return out;
    }

    /** Package-visible so tests can build a config without a file on disk. */
    void setBaseDir(Path dir) {
        this.baseDir = dir;
    }

    private Path resolve(String p) {
        Path path = Path.of(p);
        return path.isAbsolute() ? path : baseDir.resolve(path).normalize();
    }
}
