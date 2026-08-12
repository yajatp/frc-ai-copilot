package org.mercsmavs.frccopilot.modes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.mercsmavs.frccopilot.ingest.store.TrendStore;

/**
 * Mode A's automatic trigger: watch one or more directories for new {@code .wpilog} files and run
 * the between-match pass on each as it lands.
 *
 * <p>Design decisions worth knowing before changing this:
 *
 * <ul>
 *   <li><b>Polling, not {@code WatchService}.</b> The directories being watched are USB drive mount
 *       points and the Driver Station log folder. {@code WatchService} on macOS falls back to
 *       polling anyway, and a watch registered on a path that does not exist yet (an unplugged USB
 *       drive) fails outright — whereas a poll simply finds the directory later. Polling also
 *       handles a drive being yanked and reinserted with no re-registration.
 *   <li><b>Size must stabilize before ingest.</b> A log being flushed by the Driver Station is a
 *       valid-but-truncated wpilog; analyzing it mid-write yields a real report about half a match.
 *       A file is only ingested once its size has been unchanged across {@code stableChecks}
 *       consecutive polls.
 *   <li><b>Dedupe lives in the {@link TrendStore}, not memory.</b> Restarting the daemon must not
 *       re-analyze the whole USB drive, so already-ingested paths come from {@code listLogs()}.
 *   <li><b>Mode A only.</b> The competition constraint is the gap between matches, so this stays on
 *       the fast pass. Deep (Mode B) analysis is deliberately not wired in here.
 * </ul>
 *
 * <p>Results reach the pit crew two ways, both of which fall out of work already done: the flag
 * report prints to the console the daemon runs in, and the metrics/events persisted to the
 * {@code TrendStore} are what the dashboard's Logs, Events and Trends endpoints already read. No
 * push channel is needed — pointing the daemon and the dashboard at the same SQLite file is the
 * whole integration.
 */
public final class LogWatcher {

    /** What the watcher did with one file. Reported per file so a caller can render or count them. */
    public sealed interface Event {
        Path file();

        record Analyzed(Path file, ModeAPass.Outcome outcome) implements Event {}

        /** Ingest or analysis threw — a corrupt or partial log should not kill the daemon. */
        record Failed(Path file, Exception cause) implements Event {}
    }

    public interface Listener {
        void onEvent(Event event);
    }

    private final TrendStore store;
    private final List<Path> roots;
    private final int stableChecks;
    private final Listener listener;

    /** Per-file size history: how many consecutive polls have seen the current size. */
    private final Map<Path, SizeStreak> pending = new HashMap<>();

    /** Paths already ingested, seeded from the store so restarts do not re-analyze. */
    private final Set<String> ingested = new HashSet<>();

    private record SizeStreak(long size, int stableFor) {}

    /**
     * @param roots directories to scan (recursively); missing ones are skipped each poll rather
     *     than being an error, since an unplugged USB drive is the normal case
     * @param stableChecks consecutive polls a file's size must hold before it is ingested
     */
    public LogWatcher(TrendStore store, List<Path> roots, int stableChecks, Listener listener)
            throws SQLException {
        this.store = store;
        this.roots = List.copyOf(roots);
        this.stableChecks = Math.max(1, stableChecks);
        this.listener = listener;
        for (TrendStore.LogRow row : store.listLogs()) {
            ingested.add(normalize(row.path()));
        }
    }

    /** Paths the watcher considers already handled (from the store plus this session's work). */
    public Set<String> ingestedPaths() {
        return Set.copyOf(ingested);
    }

    /**
     * Run one scan-and-ingest cycle. Returns the files analyzed (or failed) on this pass — a file
     * seen for the first time is usually returned empty here and analyzed a later pass, once its
     * size has stopped moving.
     */
    public List<Event> poll() {
        List<Path> found = scan();
        List<Event> events = new ArrayList<>();

        Set<Path> stillPresent = new HashSet<>(found);
        pending.keySet().removeIf(p -> !stillPresent.contains(p)); // drive unplugged mid-write

        for (Path file : found) {
            if (ingested.contains(normalize(file))) {
                continue;
            }
            long size;
            try {
                size = Files.size(file);
            } catch (IOException e) {
                continue; // vanished between listing and stat; it will reappear or it will not
            }
            SizeStreak prior = pending.get(file);
            int stableFor = (prior != null && prior.size() == size) ? prior.stableFor() + 1 : 1;
            if (stableFor < stableChecks) {
                pending.put(file, new SizeStreak(size, stableFor));
                continue;
            }
            pending.remove(file);
            events.add(analyze(file));
        }
        return events;
    }

    private Event analyze(Path file) {
        Event event;
        try {
            ModeAPass.Outcome outcome = ModeAPass.run(store, file.toString());
            event = new Event.Analyzed(file, outcome);
        } catch (IOException | SQLException | RuntimeException e) {
            event = new Event.Failed(file, e);
        }
        // Marked handled either way: a log that cannot be parsed will not parse next poll either,
        // and retrying it forever would burn the latency budget the pass exists to protect.
        ingested.add(normalize(file));
        listener.onEvent(event);
        return event;
    }

    /** Every {@code .wpilog} under the configured roots, oldest first so matches replay in order. */
    private List<Path> scan() {
        List<Path> out = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root, 4)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".wpilog"))
                        .forEach(out::add);
            } catch (IOException | RuntimeException e) {
                // A permission-denied subtree or a drive pulled mid-walk is not fatal.
            }
        }
        out.sort(Comparator.comparingLong(LogWatcher::lastModifiedQuietly).thenComparing(Path::toString));
        return out;
    }

    private static long lastModifiedQuietly(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Dedupe key. Absolute where possible: the store records the path the log was ingested from,
     * and a daemon started from a different working directory must still recognize it.
     */
    private static String normalize(String path) {
        return normalize(Path.of(path));
    }

    private static String normalize(Path path) {
        try {
            return path.toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return path.toString();
        }
    }

    /** Human-readable one-liner for an event, used by the CLI and safe to reuse elsewhere. */
    public static String describe(Event event) {
        if (event instanceof Event.Analyzed a) {
            return a.file()
                    + "  ->  "
                    + a.outcome().result().worst()
                    + " ("
                    + a.outcome().result().flags().size()
                    + " flag(s), log #"
                    + a.outcome().logId()
                    + ")";
        }
        Event.Failed f = (Event.Failed) event;
        return f.file() + "  ->  SKIPPED: " + f.cause().getMessage();
    }
}
