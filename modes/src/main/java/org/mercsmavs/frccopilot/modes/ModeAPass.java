package org.mercsmavs.frccopilot.modes;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mercsmavs.frccopilot.analysis.Series;
import org.mercsmavs.frccopilot.analysis.SignalResolver;
import org.mercsmavs.frccopilot.ingest.LogEntry;
import org.mercsmavs.frccopilot.ingest.WpilogReader;
import org.mercsmavs.frccopilot.ingest.store.LogSummary;
import org.mercsmavs.frccopilot.ingest.store.TrendStore;

/**
 * The one implementation of "open a .wpilog, resolve the Mode A signals, run the pass".
 *
 * <p>Both the {@code mode-a} CLI subcommand and the {@link LogWatcher} daemon go through here.
 * They used to be two call sites away from each other; a daemon that resolved signals slightly
 * differently from the CLI would report different flags for the same log, which is the kind of
 * divergence nobody notices until it matters in a pit.
 */
public final class ModeAPass {

    /** A completed pass: the log's store id plus the Mode A verdict. */
    public record Outcome(long logId, ModeA.Result result) {}

    /**
     * Ingest {@code logPath} into {@code store} and run the Mode A checks over it. The log is
     * re-opened and re-indexed here rather than passed in, because that is exactly the work the
     * daemon needs done per file and the CLI needs done once.
     */
    public static Outcome run(TrendStore store, String logPath) throws IOException, SQLException {
        WpilogReader reader = new WpilogReader(logPath);
        Map<Integer, LogEntry> index = reader.index();
        long logId = store.ingest(LogSummary.from(reader, index), index.values());
        ModeA.Result result =
                ModeA.analyze(
                        store,
                        logId,
                        seriesFor(reader, index, SignalResolver.VOLTAGE),
                        seriesFor(reader, index, SignalResolver.TOTAL_CURRENT),
                        seriesFor(reader, index, SignalResolver.CAN_ERRORS),
                        seriesFor(reader, index, SignalResolver.LOOP_PERIOD));
        return new Outcome(logId, result);
    }

    private static Series seriesFor(
            WpilogReader reader, Map<Integer, LogEntry> index, List<String> candidates) {
        Optional<String> name = SignalResolver.resolve(index, candidates);
        return name.map(s -> Series.fromSamples(reader.read(s)))
                .orElse(new Series(new double[0], new long[0]));
    }

    private ModeAPass() {}
}
