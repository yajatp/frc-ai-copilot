package org.mercsmavs.frccopilot.simreplay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The journal that turns repeated runs into an iteration history: what the code was, what the robot
 * then did, and how both changed between attempts.
 *
 * <p>An agent editing robot code needs to answer one question after every run — <em>did my last
 * edit help?</em> Answering it requires remembering the previous run's measured values and which
 * files changed since. Holding that in conversation context is exactly what degrades over a long
 * debugging session, so it is written to {@code .loop/session.json} instead: each iteration records
 * the files that changed and the movement in every checked value, which makes "BallsScored 0 -> 5
 * after editing ScoringSubsystem.java" a fact on disk rather than a recollection.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class LoopSession {

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * One recorded check within an iteration, kept flat so deltas are trivial to compute.
     *
     * <p>Labels are qualified by scenario, because the whole suite is tracked: the check that moves
     * when a fix lands is frequently in a different scenario from the one that was being worked on.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Check {
        public String label;
        public boolean passed;
        public double actual;

        public Check() {}

        Check(String label, boolean passed, double actual) {
            this.label = label;
            this.passed = passed;
            this.actual = actual;
        }

        /** Build the checks for one verified scenario, labelled {@code scenario / description}. */
        public static List<Check> of(Scenario scenario, Verifier.LoopResult result) {
            List<Check> checks = new ArrayList<>();
            List<Assertion> assertions = scenario.assertions();
            for (int i = 0; i < result.results().size(); i++) {
                String label = scenario.name() + " / "
                        + (i < assertions.size() ? labelOf(assertions.get(i)) : "check " + (i + 1));
                checks.add(new Check(label, result.results().get(i).passed(), result.results().get(i).actual()));
            }
            return checks;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Iteration {
        public int number;
        public String at;
        public String scenario;
        public boolean passed;
        public int exitCode;
        public String log;
        /** Files whose contents differ from the previous iteration. */
        public List<String> changedFiles = new ArrayList<>();
        public List<Check> checks = new ArrayList<>();
        /** Per-check movement since the previous iteration, as "label: from -> to". */
        public List<String> deltas = new ArrayList<>();
        /** Set when the run produced no log or the command failed. */
        public String error;

        public Iteration() {}
    }

    public String project;
    public List<Iteration> iterations = new ArrayList<>();
    /** Source fingerprint (relative path -> content hash) as of the most recent iteration. */
    public Map<String, String> fingerprint = new LinkedHashMap<>();

    public LoopSession() {}

    public static LoopSession load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new LoopSession();
        }
        return JSON.readValue(Files.readString(file), LoopSession.class);
    }

    public void save(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, JSON.writeValueAsString(this));
    }

    public Iteration latest() {
        return iterations.isEmpty() ? null : iterations.get(iterations.size() - 1);
    }

    /**
     * Append an iteration, computing which files changed and how each check moved relative to the
     * previous one, then advancing the stored fingerprint.
     */
    public Iteration record(
            String scenarioName,
            boolean passed,
            int exitCode,
            String logPath,
            List<Check> checks,
            Map<String, String> currentFingerprint,
            String error) {
        Iteration previous = latest();
        Iteration it = new Iteration();
        it.number = iterations.size() + 1;
        it.at = Instant.now().toString();
        it.scenario = scenarioName;
        it.passed = passed;
        it.exitCode = exitCode;
        it.log = logPath;
        it.error = error;
        it.changedFiles = changedFiles(fingerprint, currentFingerprint);
        it.checks.addAll(checks);

        if (previous != null) {
            it.deltas = deltas(previous.checks, it.checks);
        }

        iterations.add(it);
        fingerprint = new LinkedHashMap<>(currentFingerprint);
        return it;
    }

    private static String labelOf(Assertion a) {
        return a.description() != null
                ? a.description()
                : a.aggregation() + " " + a.signal() + " " + a.op().symbol + " " + a.threshold();
    }

    private static List<String> deltas(List<Check> before, List<Check> after) {
        Map<String, Check> byLabel = new LinkedHashMap<>();
        for (Check c : before) {
            byLabel.put(c.label, c);
        }
        List<String> out = new ArrayList<>();
        for (Check now : after) {
            Check then = byLabel.get(now.label);
            if (then == null || sameValue(then.actual, now.actual)) {
                continue;
            }
            out.add(String.format(
                    "%s: %.4f -> %.4f%s",
                    now.label, then.actual, now.actual,
                    then.passed == now.passed ? "" : (now.passed ? " (now PASSES)" : " (now FAILS)")));
        }
        return out;
    }

    private static boolean sameValue(double a, double b) {
        if (Double.isNaN(a) && Double.isNaN(b)) {
            return true;
        }
        return Math.abs(a - b) < 1e-9;
    }

    private static List<String> changedFiles(Map<String, String> before, Map<String, String> after) {
        if (before.isEmpty()) {
            return List.of(); // first iteration — everything is "new", which is not informative
        }
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : after.entrySet()) {
            String old = before.get(e.getKey());
            if (old == null) {
                changed.add(e.getKey() + " (added)");
            } else if (!old.equals(e.getValue())) {
                changed.add(e.getKey());
            }
        }
        for (String name : before.keySet()) {
            if (!after.containsKey(name)) {
                changed.add(name + " (removed)");
            }
        }
        return changed;
    }

    /**
     * Hash every source file under the given roots. Content hashes rather than timestamps, so a
     * rebuild or a touched-but-unmodified file is not misreported as an edit.
     */
    public static Map<String, String> fingerprintSources(List<Path> roots, Path relativeTo) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(LoopSession::isSource)
                        .sorted()
                        .forEach(p -> hashes.put(relativize(relativeTo, p), hashOf(p)));
            } catch (IOException e) {
                // A source tree we cannot read simply contributes nothing to the fingerprint;
                // failing the whole loop over it would be worse than reporting no change.
            }
        }
        return hashes;
    }

    private static boolean isSource(Path p) {
        String n = p.getFileName().toString();
        return n.endsWith(".java")
                || n.endsWith(".kt")
                || n.endsWith(".cpp")
                || n.endsWith(".h")
                || n.endsWith(".py")
                || n.endsWith(".json")
                || n.endsWith(".path")
                || n.endsWith(".auto");
    }

    /**
     * A source file's key in the journal: its path relative to the project, always with forward
     * slashes.
     *
     * <p>Normalized rather than using the platform separator because these keys are persisted. A
     * journal written on Windows would otherwise record {@code src\Robot.java} where one written on
     * macOS records {@code src/Robot.java}, so the same file would read as removed-and-added the
     * first time a teammate on the other platform ran an iteration — and "which files changed since
     * last time" is the whole point of the fingerprint.
     */
    private static String relativize(Path base, Path p) {
        try {
            Path relative = base.toAbsolutePath().relativize(p.toAbsolutePath());
            return toPosix(relative);
        } catch (IllegalArgumentException e) {
            return toPosix(p);
        }
    }

    private static String toPosix(Path p) {
        StringBuilder sb = new StringBuilder();
        for (Path part : p) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private static String hashOf(Path p) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Files.readAllBytes(p));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return "?";
        }
    }

    /** Compact history for an agent resuming a debugging session. */
    public String render() {
        if (iterations.isEmpty()) {
            return "no iterations recorded yet\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("loop history").append(project == null ? "" : " for " + project).append(":\n");
        for (Iteration it : iterations) {
            sb.append(String.format(
                    "  #%d %s  %s  %s%n",
                    it.number, it.passed ? "PASS" : "FAIL", it.at,
                    it.error != null ? "(" + it.error + ")" : it.scenario));
            if (!it.changedFiles.isEmpty()) {
                sb.append("      changed: ").append(String.join(", ", it.changedFiles)).append('\n');
            }
            for (String d : it.deltas) {
                sb.append("      ").append(d).append('\n');
            }
        }
        return sb.toString();
    }
}
