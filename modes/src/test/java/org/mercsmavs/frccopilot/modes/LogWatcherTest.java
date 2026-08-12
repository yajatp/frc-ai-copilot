package org.mercsmavs.frccopilot.modes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mercsmavs.frccopilot.ingest.SampleLogGenerator;
import org.mercsmavs.frccopilot.ingest.store.TrendStore;

class LogWatcherTest {

    /** The synthetic 150 s match — it contains a real brownout, so Mode A has something to flag. */
    private static Path sampleLog(Path dir, String name) throws Exception {
        Path log = Files.createDirectories(dir).resolve(name);
        SampleLogGenerator.write(log.toString());
        return log;
    }

    private static LogWatcher watcher(TrendStore store, Path root, int stableChecks, List<LogWatcher.Event> sink)
            throws Exception {
        return new LogWatcher(store, List.of(root), stableChecks, sink::add);
    }

    @Test
    void analyzesANewLogAndPersistsItsMetrics(@TempDir Path tmp) throws Exception {
        Path watched = Files.createDirectories(tmp.resolve("usb"));
        sampleLog(watched, "q10.wpilog");

        List<LogWatcher.Event> events = new ArrayList<>();
        try (TrendStore store = new TrendStore(tmp.resolve("t.sqlite").toString())) {
            LogWatcher w = watcher(store, watched, 1, events);
            List<LogWatcher.Event> pass = w.poll();

            assertEquals(1, pass.size(), () -> "expected one analyzed log, got " + pass);
            LogWatcher.Event.Analyzed a = assertInstanceOf(LogWatcher.Event.Analyzed.class, pass.get(0));
            assertEquals(ModeA.Severity.CRITICAL, a.outcome().result().worst(),
                    () -> "sample log has a brownout:\n" + a.outcome().result().report());
            assertEquals(events, pass, "listener should see exactly the events poll() returns");

            // Metrics reached the store, which is how the dashboard sees this.
            assertTrue(store.trend("min_voltage").size() == 1);
            assertEquals(1, store.listLogs().size());
        }
    }

    @Test
    void doesNotAnalyzeTheSameLogTwiceWithinASession(@TempDir Path tmp) throws Exception {
        Path watched = Files.createDirectories(tmp.resolve("usb"));
        sampleLog(watched, "q10.wpilog");

        List<LogWatcher.Event> events = new ArrayList<>();
        try (TrendStore store = new TrendStore(tmp.resolve("t.sqlite").toString())) {
            LogWatcher w = watcher(store, watched, 1, events);
            assertEquals(1, w.poll().size());
            assertEquals(0, w.poll().size(), "second poll must find nothing new");
            assertEquals(1, events.size());
        }
    }

    @Test
    void doesNotReanalyzeAcrossARestart(@TempDir Path tmp) throws Exception {
        Path watched = Files.createDirectories(tmp.resolve("usb"));
        sampleLog(watched, "q10.wpilog");
        String db = tmp.resolve("t.sqlite").toString();

        try (TrendStore store = new TrendStore(db)) {
            assertEquals(1, watcher(store, watched, 1, new ArrayList<>()).poll().size());
        }
        // A fresh watcher over a fresh store connection: dedupe has to come off disk, not memory.
        try (TrendStore store = new TrendStore(db)) {
            LogWatcher restarted = watcher(store, watched, 1, new ArrayList<>());
            assertTrue(restarted.ingestedPaths().contains(
                    watched.resolve("q10.wpilog").toAbsolutePath().normalize().toString()));
            assertEquals(0, restarted.poll().size(), "restart must not re-analyze a known log");
        }
    }

    @Test
    void waitsForTheFileSizeToStabilizeBeforeIngesting(@TempDir Path tmp) throws Exception {
        Path watched = Files.createDirectories(tmp.resolve("usb"));
        Path complete = sampleLog(tmp.resolve("staging"), "full.wpilog");
        Path growing = watched.resolve("q11.wpilog");

        // Simulate the Driver Station still flushing: the file exists but keeps getting longer.
        byte[] all = Files.readAllBytes(complete);
        Files.write(growing, java.util.Arrays.copyOf(all, all.length / 2));

        try (TrendStore store = new TrendStore(tmp.resolve("t.sqlite").toString())) {
            LogWatcher w = watcher(store, watched, 2, new ArrayList<>());
            assertEquals(0, w.poll().size(), "first sighting only records a size");
            Files.write(growing, all); // grew between polls
            assertEquals(0, w.poll().size(), "size changed, so the streak restarts at one");
            assertEquals(1, w.poll().size(), "two consecutive polls at the same size, so ingest");
            assertEquals(0, w.poll().size(), "and it is not analyzed again");
        }
    }

    @Test
    void aCorruptLogIsReportedAndDoesNotStopTheWatcher(@TempDir Path tmp) throws Exception {
        Path watched = Files.createDirectories(tmp.resolve("usb"));
        Files.writeString(watched.resolve("garbage.wpilog"), "this is not a wpilog");
        Path good = sampleLog(tmp.resolve("staging"), "full.wpilog");
        Files.copy(good, watched.resolve("q12.wpilog"), StandardCopyOption.REPLACE_EXISTING);

        try (TrendStore store = new TrendStore(tmp.resolve("t.sqlite").toString())) {
            List<LogWatcher.Event> pass = watcher(store, watched, 1, new ArrayList<>()).poll();
            assertEquals(2, pass.size());
            assertTrue(pass.stream().anyMatch(e -> e instanceof LogWatcher.Event.Failed),
                    () -> "expected the garbage file to fail: " + pass);
            assertTrue(pass.stream().anyMatch(e -> e instanceof LogWatcher.Event.Analyzed),
                    () -> "the valid log must still be analyzed: " + pass);
        }
    }

    @Test
    void aMissingWatchRootIsSkippedRatherThanFatal(@TempDir Path tmp) throws Exception {
        // The normal case for a USB mount point that has nothing plugged into it.
        try (TrendStore store = new TrendStore(tmp.resolve("t.sqlite").toString())) {
            LogWatcher w = new LogWatcher(
                    store, List.of(tmp.resolve("nope"), tmp.resolve("also-nope")), 1, e -> {});
            assertEquals(0, w.poll().size());
        }
    }
}
