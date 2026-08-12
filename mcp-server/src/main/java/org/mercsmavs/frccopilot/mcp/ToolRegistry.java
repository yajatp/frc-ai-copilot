package org.mercsmavs.frccopilot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mercsmavs.frccopilot.analysis.AnomalyDetection;
import org.mercsmavs.frccopilot.analysis.BatteryHealth;
import org.mercsmavs.frccopilot.analysis.CanHealth;
import org.mercsmavs.frccopilot.analysis.Compare;
import org.mercsmavs.frccopilot.analysis.Correlation;
import org.mercsmavs.frccopilot.analysis.CycleTime;
import org.mercsmavs.frccopilot.analysis.DataQuality;
import org.mercsmavs.frccopilot.analysis.LoopTiming;
import org.mercsmavs.frccopilot.analysis.PeakFinder;
import org.mercsmavs.frccopilot.analysis.PowerAnalysis;
import org.mercsmavs.frccopilot.analysis.RateOfChange;
import org.mercsmavs.frccopilot.analysis.Series;
import org.mercsmavs.frccopilot.analysis.SignalResolver;
import org.mercsmavs.frccopilot.analysis.Statistics;
import org.mercsmavs.frccopilot.analysis.SwerveAnalysis;
import org.mercsmavs.frccopilot.analysis.VisionAnalysis;
import org.mercsmavs.frccopilot.ingest.LogEntry;
import org.mercsmavs.frccopilot.ingest.WpilogReader;
import edu.wpi.first.networktables.NetworkTableInstance;
import org.mercsmavs.frccopilot.knowledge.KnowledgeIndex;
import org.mercsmavs.frccopilot.knowledge.SearchHit;
import org.mercsmavs.frccopilot.ingest.store.LogSummary;
import org.mercsmavs.frccopilot.ingest.store.TrendStore;
import org.mercsmavs.frccopilot.livent.NtClient;
import org.mercsmavs.frccopilot.modes.LogWatcher;
import org.mercsmavs.frccopilot.modes.ModeA;
import org.mercsmavs.frccopilot.modes.ModeAPass;
import org.mercsmavs.frccopilot.profile.ProfileMapper;
import org.mercsmavs.frccopilot.profile.RobotProfile;
import org.mercsmavs.frccopilot.simreplay.LogDiff;
import org.mercsmavs.frccopilot.simreplay.LoopConfig;
import org.mercsmavs.frccopilot.simreplay.LoopRunner;
import org.mercsmavs.frccopilot.simreplay.LoopSession;
import org.mercsmavs.frccopilot.simreplay.RegressionSuite;
import org.mercsmavs.frccopilot.simreplay.ScenarioGenerator;
import org.mercsmavs.frccopilot.simreplay.Scenario;
import org.mercsmavs.frccopilot.simreplay.Verifier;
import org.mercsmavs.frccopilot.write.AutoFile;
import org.mercsmavs.frccopilot.write.PathDiff;
import org.mercsmavs.frccopilot.write.PathFile;

/** Builds the set of MCP tools exposed by the server, wired to Modules 1–4. */
final class ToolRegistry {

    private record SimpleTool(String name, String description, ObjectNode inputSchema, Handler handler)
            implements Tool {
        @Override
        public String call(JsonNode arguments) throws Exception {
            return handler.call(arguments == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : arguments);
        }
    }

    @FunctionalInterface
    private interface Handler {
        String call(JsonNode args) throws Exception;
    }

    static Map<String, Tool> build() {
        Map<String, Tool> tools = new LinkedHashMap<>();
        add(tools, new SimpleTool("get_guide", "Read this FIRST. Describes the server, its epistemic"
                + " guardrails, and the intended workflow (load a robot profile, then analyze).",
                Schemas.empty(), ToolRegistry::guide));

        add(tools, new SimpleTool("log_info", "Summary of a .wpilog: version, entry count, duration.",
                Schemas.object(new Schemas.Prop("file", "string", "Path to a .wpilog file", true)),
                ToolRegistry::logInfo));

        add(tools, new SimpleTool("log_entries", "List entries (signals) in a .wpilog, optionally"
                + " filtered by a name substring.",
                Schemas.object(
                        new Schemas.Prop("file", "string", "Path to a .wpilog file", true),
                        new Schemas.Prop("filter", "string", "Case-insensitive name substring", false)),
                ToolRegistry::logEntries));

        add(tools, new SimpleTool("read_entry", "Read decoded samples for one entry (capped).",
                Schemas.object(
                        new Schemas.Prop("file", "string", "Path to a .wpilog file", true),
                        new Schemas.Prop("entry", "string", "Exact entry name", true),
                        new Schemas.Prop("limit", "integer", "Max samples to return (default 200)", false)),
                ToolRegistry::readEntry));

        add(tools, new SimpleTool("ingest_log", "Parse a .wpilog and persist its summary + entry"
                + " index into a SQLite trend store (so season queries don't re-parse).",
                Schemas.object(
                        new Schemas.Prop("db", "string", "Path to the SQLite store", true),
                        new Schemas.Prop("file", "string", "Path to a .wpilog file", true)),
                ToolRegistry::ingestLog));

        add(tools, new SimpleTool("power_analysis", "Brownout/battery analysis over a log's voltage"
                + " signal. Hedged, quality-scored. (The 2026 energy-management meta.)",
                Schemas.object(new Schemas.Prop("file", "string", "Path to a .wpilog file", true)),
                ToolRegistry::powerAnalysis));

        add(tools, new SimpleTool("can_health", "CAN error-count analysis over a log. Hedged.",
                Schemas.object(new Schemas.Prop("file", "string", "Path to a .wpilog file", true)),
                ToolRegistry::canHealth));

        add(tools, new SimpleTool("profile_show", "Show a robot profile (YAML) so analysis is"
                + " robot-specific (CAN map, drivetrain, subsystems).",
                Schemas.object(new Schemas.Prop("profile", "string", "Path to a profile .yaml", true)),
                ToolRegistry::profileShow));

        add(tools, new SimpleTool("pathplanner_show", "Summarize a PathPlanner .path (waypoints,"
                + " constraints).",
                Schemas.object(new Schemas.Prop("path", "string", "Path to a .path file", true)),
                ToolRegistry::pathShow));

        add(tools, new SimpleTool("pathplanner_fudge", "Propose translating a waypoint by (dx,dy)"
                + " meters. Returns a reviewable diff; writes only if 'out' is given.",
                Schemas.object(
                        new Schemas.Prop("path", "string", "Path to a .path file", true),
                        new Schemas.Prop("index", "integer", "Waypoint index (0-based)", true),
                        new Schemas.Prop("dx", "number", "Shift in +x meters", true),
                        new Schemas.Prop("dy", "number", "Shift in +y meters", true),
                        new Schemas.Prop("out", "string", "Output path (omit for dry-run)", false)),
                ToolRegistry::pathFudge));

        add(tools, new SimpleTool("pathplanner_set_speed", "Propose new global max velocity/accel"
                + " for a path. Reviewable diff; writes only if 'out' is given.",
                Schemas.object(
                        new Schemas.Prop("path", "string", "Path to a .path file", true),
                        new Schemas.Prop("maxVelocity", "number", "New max velocity (m/s)", true),
                        new Schemas.Prop("maxAcceleration", "number", "New max acceleration (m/s^2)", true),
                        new Schemas.Prop("out", "string", "Output path (omit for dry-run)", false)),
                ToolRegistry::pathSetSpeed));

        add(tools, new SimpleTool("loop_check", "Verify a scenario's success criteria against a"
                + " log (the closed-loop 'verify' step). Returns per-check PASS/FAIL.",
                Schemas.object(
                        new Schemas.Prop("log", "string", "Path to a .wpilog (sim/replay/real)", true),
                        new Schemas.Prop("scenario", "string", "Path to a scenario .yaml", true)),
                ToolRegistry::loopCheck));

        add(tools, new SimpleTool("loop_suite", "Run a whole regression suite (a directory of"
                + " scenarios) against a log — every banked fix re-checked at once.",
                Schemas.object(
                        new Schemas.Prop("log", "string", "Path to a .wpilog", true),
                        new Schemas.Prop("scenarioDir", "string", "Directory of scenario .yaml files", true)),
                ToolRegistry::loopSuite));

        add(tools, new SimpleTool("loop_iterate", "Run ONE full turn of the agentic closed loop:"
                + " rebuild the robot code, run it headless, verify every scenario against the log it"
                + " produced, and diagnose what failed. Use this after editing robot code — it reports"
                + " build errors, missing logs, and misbehaviour as distinct outcomes, and says which"
                + " checked values moved since your last edit. Requires a loop.yaml in the project.",
                Schemas.object(
                        new Schemas.Prop("config", "string",
                                "Path to loop.yaml or a directory inside the project (default: cwd)", false),
                        new Schemas.Prop("scenario", "string",
                                "Check a single scenario .yaml instead of the whole suite", false)),
                ToolRegistry::loopIterate));

        add(tools, new SimpleTool("loop_history", "The iteration journal for a project: every"
                + " previous turn, which source files changed, and how each checked value moved."
                + " Read this when resuming work to see what has already been tried.",
                Schemas.object(
                        new Schemas.Prop("config", "string",
                                "Path to loop.yaml or a directory inside the project (default: cwd)", false)),
                ToolRegistry::loopHistory));

        add(tools, new SimpleTool("loop_generate", "Derive a regression scenario from a run that is"
                + " known to be good, so a verified fix can be banked as a standing check. Proposes"
                + " thresholds for counters, battery voltage, loop period, and converging errors."
                + " Dry-run unless 'out' is given. Review the thresholds — they encode one run.",
                Schemas.object(
                        new Schemas.Prop("log", "string", "Path to a known-good .wpilog", true),
                        new Schemas.Prop("out", "string", "Where to write the scenario (omit for dry-run)", false),
                        new Schemas.Prop("name", "string", "Scenario name", false),
                        new Schemas.Prop("phaseSignal", "string",
                                "Scope every check to a phase, e.g. /Robot/State", false),
                        new Schemas.Prop("phaseEquals", "string", "The phase value, e.g. AUTO", false)),
                ToolRegistry::loopGenerate));

        add(tools, new SimpleTool("loop_diff", "Rank how far a run diverged from a known-good"
                + " baseline log, signal by signal. The largest divergence usually sits closest to the"
                + " cause; signals present in only one log point at a rename or a subsystem that"
                + " never initialized.",
                Schemas.object(
                        new Schemas.Prop("baseline", "string", "Path to the known-good .wpilog", true),
                        new Schemas.Prop("log", "string", "Path to the run being investigated", true)),
                ToolRegistry::loopDiff));

        add(tools, new SimpleTool("mode_a", "Mode A between-match pass: ingest a log, flag"
                + " brownout/battery/CAN/loop issues, and persist metrics to the trend store.",
                Schemas.object(
                        new Schemas.Prop("db", "string", "Path to the SQLite trend store", true),
                        new Schemas.Prop("file", "string", "Path to the match .wpilog", true)),
                ToolRegistry::modeA));

        add(tools, new SimpleTool("mode_a_scan", "Sweep one or more directories (USB mount point,"
                + " Driver Station log folder) for .wpilog files not yet in the trend store, and run"
                + " the Mode A pass on each. Use this to catch up after matches happened without the"
                + " watcher running; for continuous ingest between matches, run the daemon"
                + " (`modes watch <db> <dir>...`) instead, which cannot be a tool because it blocks.",
                Schemas.object(
                        new Schemas.Prop("db", "string", "Path to the SQLite trend store", true),
                        new Schemas.Prop("dirs", "array", "Directories to scan (recursively, 4 levels)",
                                true, "string")),
                ToolRegistry::modeAScan));

        add(tools, new SimpleTool("swerve_analysis", "Detect underdamped/oscillating closed-loop"
                + " behavior from a swerve module signal (hedged PID guidance).",
                Schemas.object(
                        new Schemas.Prop("file", "string", "Path to a .wpilog", true),
                        new Schemas.Prop("entry", "string", "Signal name (optional; auto-resolved if omitted)", false)),
                ToolRegistry::swerve));

        add(tools, new SimpleTool("vision_analysis", "Vision detection rate / dropouts from a"
                + " hasTarget or tag-count signal.",
                Schemas.object(new Schemas.Prop("file", "string", "Path to a .wpilog", true)),
                ToolRegistry::vision));

        add(tools, new SimpleTool("loop_timing", "Loop overrun analysis (vs 20 ms) from a"
                + " loop-period signal.",
                Schemas.object(new Schemas.Prop("file", "string", "Path to a .wpilog", true)),
                ToolRegistry::loopTiming));

        add(tools, new SimpleTool("battery_health", "Battery droop / internal-resistance indicator"
                + " with a hedged end-of-match sag projection.",
                Schemas.object(new Schemas.Prop("file", "string", "Path to a .wpilog", true)),
                ToolRegistry::batteryHealth));

        add(tools, new SimpleTool("anomaly", "Robust (MAD) outlier detection on one signal.",
                Schemas.object(
                        new Schemas.Prop("file", "string", "Path to a .wpilog", true),
                        new Schemas.Prop("entry", "string", "Signal name", true)),
                ToolRegistry::anomaly));

        add(tools, new SimpleTool("analyze_cycles", "Scoring cycle-time analysis from a monotonic"
                + " cycle/score counter: how many cycles, and how fast (mean/median/fastest).",
                Schemas.object(
                        new Schemas.Prop("file", "string", "Path to a .wpilog", true),
                        new Schemas.Prop("entry", "string", "Counter signal name (optional; auto-resolved if omitted)", false)),
                ToolRegistry::analyzeCycles));

        add(tools, new SimpleTool("signal_stats", "Summary statistics for one signal"
                + " (min/max/mean/median/stdDev/p95) with a data-quality block.",
                Schemas.object(
                        new Schemas.Prop("file", "string", "Path to a .wpilog", true),
                        new Schemas.Prop("entry", "string", "Signal name", true)),
                ToolRegistry::signalStats));

        add(tools, new SimpleTool("compare_signals", "Compare summary statistics of two signals —"
                + " the same signal across two matches, or two symmetric mechanisms (left vs right).",
                Schemas.object(
                        new Schemas.Prop("file", "string", "Path to a .wpilog", true),
                        new Schemas.Prop("entry", "string", "First signal name", true),
                        new Schemas.Prop("entryB", "string", "Second signal name (defaults to 'entry')", false),
                        new Schemas.Prop("fileB", "string", "Second .wpilog (defaults to 'file')", false)),
                ToolRegistry::compareSignals));

        add(tools, new SimpleTool("correlate", "Pearson correlation between two signals"
                + " (nearest-timestamp aligned). Suggests a relationship; never proves causation.",
                Schemas.object(
                        new Schemas.Prop("file", "string", "Path to a .wpilog", true),
                        new Schemas.Prop("entry", "string", "First signal name", true),
                        new Schemas.Prop("entryB", "string", "Second signal name", true),
                        new Schemas.Prop("fileB", "string", "Second .wpilog (defaults to 'file')", false)),
                ToolRegistry::correlate));

        add(tools, new SimpleTool("find_peaks", "Find local maxima in a signal whose prominence"
                + " exceeds a threshold (current spikes, impact events).",
                Schemas.object(
                        new Schemas.Prop("file", "string", "Path to a .wpilog", true),
                        new Schemas.Prop("entry", "string", "Signal name", true),
                        new Schemas.Prop("minProminence", "number", "Minimum peak prominence (default 1.0)", false)),
                ToolRegistry::findPeaks));

        add(tools, new SimpleTool("rate_of_change", "Derivative statistics for a signal (units per"
                + " second) — max/min slope and when the steepest change happened.",
                Schemas.object(
                        new Schemas.Prop("file", "string", "Path to a .wpilog", true),
                        new Schemas.Prop("entry", "string", "Signal name", true)),
                ToolRegistry::rateOfChange));

        add(tools, new SimpleTool("data_quality", "Assess how much a signal's sampling actually"
                + " supports a conclusion (sample count, span, period regularity, confidence).",
                Schemas.object(
                        new Schemas.Prop("file", "string", "Path to a .wpilog", true),
                        new Schemas.Prop("entry", "string", "Signal name", true)),
                ToolRegistry::dataQuality));

        add(tools, new SimpleTool("search_docs", "Search the offline documentation index (WPILib,"
                + " CTRE Phoenix 6, PhotonVision, PathPlanner) with a natural-language question."
                + " Use this before answering any API question from memory — the docs are"
                + " versioned and your recollection of them is not.",
                Schemas.object(
                        new Schemas.Prop("query", "string", "A natural-language question or API name", true),
                        new Schemas.Prop("source", "string", "Restrict to one corpus: wpilib | ctre | photonvision | pathplanner", false),
                        new Schemas.Prop("limit", "integer", "Max results (default 6)", false),
                        new Schemas.Prop("db", "string", "Index path (default .knowledge/frc.kdb)", false)),
                ToolRegistry::searchDocs));

        add(tools, new SimpleTool("search_manual", "Search the indexed game manual PDF for a rule."
                + " Returns the passage and its page number so the rule can be verified. Never"
                + " state a rule from memory — cite the page.",
                Schemas.object(
                        new Schemas.Prop("query", "string", "Rule code (e.g. G410) or a question", true),
                        new Schemas.Prop("limit", "integer", "Max results (default 6)", false),
                        new Schemas.Prop("db", "string", "Index path (default .knowledge/frc.kdb)", false)),
                ToolRegistry::searchManual));

        add(tools, new SimpleTool("knowledge_status", "Show which documentation corpora are indexed"
                + " and how large each is. Call this if a search returns nothing.",
                Schemas.object(new Schemas.Prop("db", "string", "Index path (default .knowledge/frc.kdb)", false)),
                ToolRegistry::knowledgeStatus));

        add(tools, new SimpleTool("nt_status", "Connect to a live robot's NetworkTables server and"
                + " report whether the connection is up (live/real-time; read-only).",
                Schemas.object(
                        new Schemas.Prop("host", "string", "Robot NT server host/IP (e.g. 10.TE.AM.2 or localhost)", true),
                        new Schemas.Prop("port", "integer", "NT4 port (default 5810)", false)),
                ToolRegistry::ntStatus));

        add(tools, new SimpleTool("nt_get", "Read a single live value from NetworkTables by key"
                + " (read-only; the copilot never writes actuator/enable values).",
                Schemas.object(
                        new Schemas.Prop("host", "string", "Robot NT server host/IP", true),
                        new Schemas.Prop("key", "string", "Topic key, e.g. /SmartDashboard/x", true),
                        new Schemas.Prop("port", "integer", "NT4 port (default 5810)", false)),
                ToolRegistry::ntGet));

        add(tools, new SimpleTool("nt_keys", "List live NetworkTables keys (optionally by prefix).",
                Schemas.object(
                        new Schemas.Prop("host", "string", "Robot NT server host/IP", true),
                        new Schemas.Prop("prefix", "string", "Key prefix filter (optional)", false),
                        new Schemas.Prop("port", "integer", "NT4 port (default 5810)", false)),
                ToolRegistry::ntKeys));

        add(tools, new SimpleTool("auto_show", "Summarize a PathPlanner .auto (path references).",
                Schemas.object(new Schemas.Prop("auto", "string", "Path to a .auto file", true)),
                ToolRegistry::autoShow));

        add(tools, new SimpleTool("auto_swap_path", "Propose swapping a path reference in a .auto."
                + " Reviewable diff; writes only if 'out' is given.",
                Schemas.object(
                        new Schemas.Prop("auto", "string", "Path to a .auto file", true),
                        new Schemas.Prop("oldName", "string", "Existing path name", true),
                        new Schemas.Prop("newName", "string", "Replacement path name", true),
                        new Schemas.Prop("out", "string", "Output path (omit for dry-run)", false)),
                ToolRegistry::autoSwap));

        return tools;
    }

    // --- handlers ---

    private static String guide(JsonNode a) {
        return """
                FRC AI Copilot — MCP server (Modules 1–6 + Mode A + live NT + docs; 34 tools).

                Workflow:
                  0) For any API or rules question, call search_docs / search_manual FIRST. The
                     index holds the actual WPILib, CTRE Phoenix 6, PhotonVision and PathPlanner
                     documentation, and the season game manual with page numbers. FRC APIs change
                     every season and rules change every year — answering from memory is how you
                     hand a team a method that no longer exists or a rule that was revised.
                  1) Call profile_show on the team's robot profile so analysis is robot-specific
                     (CAN map, drivetrain, current limit, subsystems).
                  2) Use log_info / log_entries / read_entry to explore a match log.
                  3) Use power_analysis, can_health, battery_health, and loop_timing for the Mode-A
                     safety picture (brownouts, battery, CAN, loop overruns) — the 2026
                     energy-management meta. mode_a runs this whole pass in one call and persists
                     metrics to a trend store.
                  4) Use swerve_analysis, vision_analysis, and anomaly for deeper Mode-B diagnosis
                     when there's time to chase a specific hypothesis. The general-purpose
                     primitives compose for anything not covered by a named tool: signal_stats
                     (summarize), rate_of_change (spikes/jerk), find_peaks (impact/current
                     events), compare_signals (match-over-match or left-vs-right), correlate
                     (does X move with Y?), data_quality (is this signal even worth trusting?),
                     and analyze_cycles (scoring throughput from a cycle counter).
                  5) Use pathplanner_show/fudge/set_speed and auto_show/auto_swap_path to propose
                     autonomous adjustments (the only thing teams change at competition).
                  6) Use loop_check/loop_suite to verify a robot-code fix against a written
                     success criterion (or a whole regression suite) before calling it done.
                  7) Use nt_status/nt_get/nt_keys to read a live, running robot's NetworkTables
                     (read-only — there is no NT write tool).

                Epistemic guardrails: every analysis result carries a data-quality/confidence block
                and hedged language. A single match is rarely conclusive — corroborate across matches.
                Write tools are dry-run by default and never overwrite a file in place.
                """;
    }

    private static String logInfo(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "file"));
        Map<Integer, LogEntry> index = r.index();
        long samples = index.values().stream().mapToLong(LogEntry::count).sum();
        double dur = index.values().stream().mapToDouble(LogEntry::spanSeconds).max().orElse(0);
        return "version=" + r.version() + "\nentries=" + index.size() + "\nsamples=" + samples
                + String.format("%nduration=%.2f s", dur);
    }

    private static String logEntries(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "file"));
        String filter = a.hasNonNull("filter") ? a.get("filter").asText().toLowerCase() : null;
        StringBuilder sb = new StringBuilder();
        r.index().values().stream()
                .filter(e -> filter == null || e.name.toLowerCase().contains(filter))
                .sorted((x, y) -> x.name.compareToIgnoreCase(y.name))
                .forEach(e -> sb.append(String.format("%-8d %-8s %s%n", e.count(), e.type, e.name)));
        return sb.isEmpty() ? "(no matching entries)" : sb.toString();
    }

    private static String readEntry(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "file"));
        int limit = a.hasNonNull("limit") ? a.get("limit").asInt() : 200;
        List<WpilogReader.Sample> samples = r.read(str(a, "entry"));
        if (samples.isEmpty()) {
            return "(no samples for '" + str(a, "entry") + "')";
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(limit, samples.size());
        for (int i = 0; i < n; i++) {
            WpilogReader.Sample s = samples.get(i);
            sb.append(String.format("%.4f\t%s%n", s.timestampSeconds(), render(s.value())));
        }
        if (samples.size() > n) {
            sb.append("... (").append(samples.size() - n).append(" more of ").append(samples.size())
                    .append(" total)\n");
        }
        return sb.toString();
    }

    private static String ingestLog(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "file"));
        Map<Integer, LogEntry> index = r.index();
        LogSummary summary = LogSummary.from(r, index);
        try (TrendStore store = new TrendStore(str(a, "db"))) {
            long id = store.ingest(summary, index.values());
            return "Ingested log #" + id + " (" + index.size() + " entries, "
                    + String.format("%.2f s", summary.durationSeconds()) + ").";
        }
    }

    private static String powerAnalysis(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "file"));
        Optional<String> sig = SignalResolver.resolve(r.index(), SignalResolver.VOLTAGE);
        if (sig.isEmpty()) {
            return "No battery-voltage signal found; cannot assess brownout risk.";
        }
        Series v = Series.fromSamples(r.read(sig.get()));
        PowerAnalysis.Result res = PowerAnalysis.analyze(v);
        StringBuilder sb = new StringBuilder("signal: " + sig.get() + "\n" + res.assessment() + "\n");
        for (PowerAnalysis.BrownoutEvent e : res.events()) {
            sb.append(String.format("  - %.2fs–%.2fs min %.2f V (%.0f ms)%n",
                    e.startSeconds(), e.endSeconds(), e.minVolts(), e.durationMs()));
        }
        return sb.toString();
    }

    private static String canHealth(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "file"));
        Optional<String> sig = SignalResolver.resolve(r.index(), SignalResolver.CAN_ERRORS);
        if (sig.isEmpty()) {
            return "No CAN error-count signal found.";
        }
        Series c = Series.fromSamples(r.read(sig.get()));
        return "signal: " + sig.get() + "\n" + CanHealth.analyze(c).assessment();
    }

    private static String profileShow(JsonNode a) throws Exception {
        RobotProfile p = ProfileMapper.read(Path.of(str(a, "profile")));
        return ProfileMapper.toYaml(p);
    }

    private static String pathShow(JsonNode a) throws Exception {
        PathFile p = PathFile.load(Path.of(str(a, "path")));
        StringBuilder sb = new StringBuilder("version=" + p.version() + " waypoints=" + p.waypointCount() + "\n");
        for (int i = 0; i < p.waypointCount(); i++) {
            double[] anchor = p.anchor(i);
            sb.append(String.format("  [%d] (%.3f, %.3f)%n", i, anchor[0], anchor[1]));
        }
        sb.append("maxVel=").append(p.globalConstraint("maxVelocity"))
                .append(" maxAccel=").append(p.globalConstraint("maxAcceleration"));
        return sb.toString();
    }

    private static String pathFudge(JsonNode a) throws Exception {
        PathFile before = PathFile.load(Path.of(str(a, "path")));
        PathFile after = before.copy();
        after.translateWaypoint(a.get("index").asInt(), a.get("dx").asDouble(), a.get("dy").asDouble());
        return applyOrDryRun(before, after, a.hasNonNull("out") ? a.get("out").asText() : null);
    }

    private static String pathSetSpeed(JsonNode a) throws Exception {
        PathFile before = PathFile.load(Path.of(str(a, "path")));
        PathFile after = before.copy();
        after.setGlobalConstraint("maxVelocity", a.get("maxVelocity").asDouble());
        after.setGlobalConstraint("maxAcceleration", a.get("maxAcceleration").asDouble());
        return applyOrDryRun(before, after, a.hasNonNull("out") ? a.get("out").asText() : null);
    }

    private static final List<String> SWERVE_CANDIDATES = SignalResolver.SWERVE;
    private static final List<String> VISION_CANDIDATES = SignalResolver.VISION;
    private static final List<String> LOOP_CANDIDATES = SignalResolver.LOOP_PERIOD;
    private static final List<String> CYCLE_CANDIDATES = SignalResolver.CYCLE_COUNTER;

    private static String modeA(JsonNode a) throws Exception {
        try (TrendStore store = new TrendStore(str(a, "db"))) {
            ModeAPass.Outcome outcome = ModeAPass.run(store, str(a, "file"));
            ModeA.Result res = outcome.result();
            return res.report()
                    + "Overall: " + res.worst() + " (log #" + outcome.logId() + ", metrics persisted)";
        }
    }

    /**
     * One-shot catch-up scan. The watcher daemon itself is deliberately not an MCP tool — a tool
     * call has to return, so a blocking daemon would either hang the agent or return immediately
     * having done nothing. This is the request/response-shaped half of it: sweep the directories
     * once, analyze whatever is new, and report.
     */
    private static String modeAScan(JsonNode a) throws Exception {
        List<Path> roots = new java.util.ArrayList<>();
        JsonNode dirs = a.get("dirs");
        if (dirs != null && dirs.isArray()) {
            dirs.forEach(d -> roots.add(Path.of(d.asText())));
        } else if (dirs != null && dirs.isTextual()) {
            roots.add(Path.of(dirs.asText()));
        }
        if (roots.isEmpty()) {
            return "Pass 'dirs' with at least one directory to scan.";
        }
        try (TrendStore store = new TrendStore(str(a, "db"))) {
            List<LogWatcher.Event> events =
                    new LogWatcher(store, roots, 1, e -> {}).poll();
            if (events.isEmpty()) {
                return "Scanned " + roots + " — no new .wpilog files. Everything present was already"
                        + " ingested (dedupe is by path, from the trend store).";
            }
            StringBuilder sb = new StringBuilder("Analyzed " + events.size() + " new log(s):\n\n");
            for (LogWatcher.Event e : events) {
                sb.append(LogWatcher.describe(e)).append('\n');
                if (e instanceof LogWatcher.Event.Analyzed an) {
                    sb.append(an.outcome().result().report()).append('\n');
                }
            }
            sb.append("For continuous between-match ingest, run the daemon instead:"
                    + " `modes watch <db> <dir>...`.");
            return sb.toString();
        }
    }

    private static String swerve(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "file"));
        String signal = a.hasNonNull("entry") ? a.get("entry").asText()
                : SignalResolver.resolve(r.index(), SWERVE_CANDIDATES).orElse(null);
        if (signal == null) {
            return "No swerve module signal found; pass 'entry' with a velocity/voltage signal name.";
        }
        return "signal: " + signal + "\n" + SwerveAnalysis.analyze(Series.fromSamples(r.read(signal))).assessment();
    }

    private static String vision(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "file"));
        Optional<String> sig = SignalResolver.resolve(r.index(), VISION_CANDIDATES);
        if (sig.isEmpty()) {
            return "No vision detection signal found.";
        }
        return "signal: " + sig.get() + "\n" + VisionAnalysis.analyze(Series.fromSamples(r.read(sig.get())), null).assessment();
    }

    private static String loopTiming(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "file"));
        Optional<String> sig = SignalResolver.resolve(r.index(), LOOP_CANDIDATES);
        if (sig.isEmpty()) {
            return "No loop-timing signal found.";
        }
        return "signal: " + sig.get() + "\n" + LoopTiming.analyze(Series.fromSamples(r.read(sig.get()))).assessment();
    }

    private static String batteryHealth(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "file"));
        Optional<String> volt = SignalResolver.resolve(r.index(), SignalResolver.VOLTAGE);
        if (volt.isEmpty()) {
            return "No battery-voltage signal found.";
        }
        Series current = seriesFor(r, r.index(), SignalResolver.TOTAL_CURRENT);
        return "signal: " + volt.get() + "\n"
                + BatteryHealth.analyze(Series.fromSamples(r.read(volt.get())), current.isEmpty() ? null : current).assessment();
    }

    private static String anomaly(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "file"));
        Series s = Series.fromSamples(r.read(str(a, "entry")));
        return AnomalyDetection.detect(s).assessment();
    }

    private static String analyzeCycles(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "file"));
        String signal = a.hasNonNull("entry") ? a.get("entry").asText()
                : SignalResolver.resolve(r.index(), CYCLE_CANDIDATES).orElse(null);
        if (signal == null) {
            return "No cycle/score counter signal found; pass 'entry' with a monotonic counter name.";
        }
        CycleTime.Result res = CycleTime.analyze(Series.fromSamples(r.read(signal)));
        return "signal: " + signal + "\n" + res.assessment();
    }

    private static String signalStats(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "file"));
        Series s = Series.fromSamples(r.read(str(a, "entry")));
        if (s.isEmpty()) {
            return "(no numeric samples for '" + str(a, "entry") + "')";
        }
        Statistics.Result st = Statistics.of(s);
        return String.format(
                "signal: %s%nmin=%.4f max=%.4f mean=%.4f median=%.4f stdDev=%.4f p95=%.4f%n%s",
                str(a, "entry"), st.min(), st.max(), st.mean(), st.median(), st.stdDev(), st.p95(),
                st.quality().caveat());
    }

    private static String compareSignals(JsonNode a) throws Exception {
        Series first = seriesOf(str(a, "file"), str(a, "entry"));
        String fileB = a.hasNonNull("fileB") ? a.get("fileB").asText() : str(a, "file");
        String entryB = a.hasNonNull("entryB") ? a.get("entryB").asText() : str(a, "entry");
        Series second = seriesOf(fileB, entryB);
        if (first.isEmpty() || second.isEmpty()) {
            return "One or both signals have no numeric samples; cannot compare.";
        }
        return "a: " + str(a, "entry") + "\nb: " + entryB + "\n" + Compare.of(first, second).assessment();
    }

    private static String correlate(JsonNode a) throws Exception {
        Series first = seriesOf(str(a, "file"), str(a, "entry"));
        String fileB = a.hasNonNull("fileB") ? a.get("fileB").asText() : str(a, "file");
        Series second = seriesOf(fileB, str(a, "entryB"));
        if (first.isEmpty() || second.isEmpty()) {
            return "One or both signals have no numeric samples; cannot correlate.";
        }
        return "a: " + str(a, "entry") + "\nb: " + str(a, "entryB") + "\n"
                + Correlation.of(first, second).assessment();
    }

    private static String findPeaks(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "file"));
        Series s = Series.fromSamples(r.read(str(a, "entry")));
        double minProminence = a.hasNonNull("minProminence") ? a.get("minProminence").asDouble() : 1.0;
        PeakFinder.Result res = PeakFinder.find(s, minProminence);
        StringBuilder sb = new StringBuilder("signal: " + str(a, "entry") + "\n" + res.assessment() + "\n");
        for (PeakFinder.Peak p : res.peaks()) {
            sb.append(String.format("  - %.3fs  %.4f%n", p.timeSeconds(), p.value()));
        }
        return sb.toString();
    }

    private static String rateOfChange(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "file"));
        Series s = Series.fromSamples(r.read(str(a, "entry")));
        if (s.size() < 2) {
            return "Fewer than two numeric samples for '" + str(a, "entry") + "'; no slope to report.";
        }
        RateOfChange.Result res = RateOfChange.of(s);
        return String.format(
                "signal: %s%nmaxSlope=%.4f/s (at %.3fs) minSlope=%.4f/s meanAbsSlope=%.4f/s%n%s",
                str(a, "entry"), res.maxSlope(), res.maxSlopeTimeSeconds(), res.minSlope(),
                res.meanAbsSlope(), res.quality().caveat());
    }

    private static String dataQuality(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "file"));
        Series s = Series.fromSamples(r.read(str(a, "entry")));
        DataQuality q = DataQuality.of(s.timestampsUs());
        return String.format(
                "signal: %s%nsamples=%d span=%.2fs medianPeriod=%.2fms maxGap=%.2fms confidence=%s%n%s",
                str(a, "entry"), q.sampleCount(), q.spanSeconds(), q.medianPeriodMs(), q.maxGapMs(),
                q.confidence(), q.caveat());
    }

    private static Series seriesOf(String file, String entry) throws Exception {
        return Series.fromSamples(new WpilogReader(file).read(entry));
    }

    /** Where the knowledge index lives unless a call overrides it. */
    private static final String DEFAULT_KNOWLEDGE_DB = ".knowledge/frc.kdb";

    private static String knowledgeDb(JsonNode a) {
        return a.hasNonNull("db") ? a.get("db").asText() : DEFAULT_KNOWLEDGE_DB;
    }

    private static String searchDocs(JsonNode a) throws Exception {
        String source = a.hasNonNull("source") ? a.get("source").asText() : null;
        return knowledgeSearch(knowledgeDb(a), str(a, "query"), source, limitOf(a));
    }

    private static String searchManual(JsonNode a) throws Exception {
        return knowledgeSearch(knowledgeDb(a), str(a, "query"), "manual", limitOf(a));
    }

    private static int limitOf(JsonNode a) {
        return a.hasNonNull("limit") ? Math.max(1, Math.min(20, a.get("limit").asInt())) : 6;
    }

    private static String knowledgeSearch(String db, String query, String source, int limit) throws Exception {
        if (!java.nio.file.Files.exists(Path.of(db))) {
            return "No knowledge index at " + db + ".\n"
                    + "Build one with:  knowledge sync " + db + " .knowledge\n"
                    + "For the game manual:  knowledge manual " + db + " <manual.pdf>";
        }
        try (KnowledgeIndex index = new KnowledgeIndex(db)) {
            List<SearchHit> hits = index.search(query, source, limit);
            if (hits.isEmpty()) {
                String scope = source == null ? "the indexed docs" : "the '" + source + "' corpus";
                return "No matches in " + scope + " for: " + query
                        + "\nCall knowledge_status to see what is actually indexed.";
            }
            StringBuilder sb = new StringBuilder();
            for (SearchHit h : hits) {
                sb.append(h.render()).append("\n\n");
            }
            sb.append("(Lexical search over the indexed docs — these are the passages that matched, ")
                    .append("not a verified answer. Read the passage before relying on it.)");
            return sb.toString();
        }
    }

    private static String knowledgeStatus(JsonNode a) throws Exception {
        String db = knowledgeDb(a);
        if (!java.nio.file.Files.exists(Path.of(db))) {
            return "No knowledge index at " + db + ". Build one with: knowledge sync " + db + " .knowledge";
        }
        try (KnowledgeIndex index = new KnowledgeIndex(db)) {
            Map<String, Integer> sources = index.sources();
            if (sources.isEmpty()) {
                return "Index exists at " + db + " but nothing is indexed yet.";
            }
            StringBuilder sb = new StringBuilder("Indexed corpora in " + db + ":\n");
            sources.forEach((name, count) -> sb.append(String.format("  %-14s %d chunks%n", name, count)));
            if (!sources.containsKey("manual")) {
                sb.append("  (no game manual indexed — run: knowledge manual ").append(db).append(" <manual.pdf>)\n");
            }
            return sb.toString();
        }
    }

    private static int ntPort(JsonNode a) {
        return a.hasNonNull("port") ? a.get("port").asInt() : NetworkTableInstance.kDefaultPort4;
    }

    private static String ntStatus(JsonNode a) {
        try (NtClient client = new NtClient()) {
            client.connect("frc-ai-copilot", str(a, "host"), ntPort(a));
            boolean up = client.waitForConnection(3.0);
            return up ? "Connected to " + str(a, "host") + " (" + client.connections().size() + " connection(s))."
                    : "Not connected to " + str(a, "host") + " within 3s (robot off / wrong host?).";
        }
    }

    private static String ntGet(JsonNode a) {
        try (NtClient client = new NtClient()) {
            client.connect("frc-ai-copilot", str(a, "host"), ntPort(a));
            if (!client.waitForConnection(3.0)) {
                return "Not connected to " + str(a, "host") + " within 3s.";
            }
            String key = str(a, "key");
            var value = client.getValue(key);
            return value.isValid() ? key + " = " + value.getValue() : key + " (no value / not published).";
        }
    }

    private static String ntKeys(JsonNode a) {
        try (NtClient client = new NtClient()) {
            client.connect("frc-ai-copilot", str(a, "host"), ntPort(a));
            if (!client.waitForConnection(3.0)) {
                return "Not connected to " + str(a, "host") + " within 3s.";
            }
            String prefix = a.hasNonNull("prefix") ? a.get("prefix").asText() : "";
            var keys = client.keys(prefix);
            if (keys.isEmpty()) {
                return "(no keys" + (prefix.isEmpty() ? "" : " under " + prefix) + " yet — topics announce shortly after connect)";
            }
            return String.join("\n", keys);
        }
    }

    private static String autoShow(JsonNode a) throws Exception {
        AutoFile auto = AutoFile.load(Path.of(str(a, "auto")));
        return "paths referenced: " + auto.listPathReferences();
    }

    private static String autoSwap(JsonNode a) throws Exception {
        AutoFile before = AutoFile.load(Path.of(str(a, "auto")));
        AutoFile after = before.copy();
        int n = after.replacePathReference(str(a, "oldName"), str(a, "newName"));
        if (n == 0) {
            return "No references to '" + str(a, "oldName") + "' found; no change.";
        }
        List<PathDiff.Change> changes = PathDiff.diff(before.root(), after.root());
        StringBuilder sb = new StringBuilder("Proposed (" + n + " reference(s) renamed):\n");
        changes.forEach(c -> sb.append("  ").append(c).append('\n'));
        if (a.hasNonNull("out")) {
            after.save(Path.of(a.get("out").asText()));
            sb.append("Wrote to: ").append(a.get("out").asText());
        } else {
            sb.append("Dry run — pass 'out' to write a copy (original never overwritten).");
        }
        return sb.toString();
    }

    private static Series seriesFor(WpilogReader r, Map<Integer, LogEntry> index, List<String> candidates) {
        return SignalResolver.resolve(index, candidates)
                .map(s -> Series.fromSamples(r.read(s)))
                .orElse(new Series(new double[0], new long[0]));
    }

    private static String loopCheck(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "log"));
        Scenario scenario = Scenario.load(Path.of(str(a, "scenario")));
        return Verifier.verify(scenario, r::read).render();
    }

    private static String loopSuite(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "log"));
        List<Scenario> scenarios = RegressionSuite.load(Path.of(str(a, "scenarioDir")));
        if (scenarios.isEmpty()) {
            return "No scenarios found in " + str(a, "scenarioDir");
        }
        return RegressionSuite.runAll(scenarios, r::read).render();
    }

    private static String loopIterate(JsonNode a) throws Exception {
        LoopConfig config = LoopConfig.load(LoopConfig.discover(Path.of(optional(a, "config", ""))));
        String scenario = optional(a, "scenario", null);
        LoopRunner.IterationReport report =
                LoopRunner.iterate(config, scenario == null ? null : Path.of(scenario));
        return report.render();
    }

    private static String loopHistory(JsonNode a) throws Exception {
        LoopConfig config = LoopConfig.load(LoopConfig.discover(Path.of(optional(a, "config", ""))));
        return LoopSession.load(config.loopStateDir().resolve("session.json")).render();
    }

    private static String loopGenerate(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "log"));
        String out = optional(a, "out", null);
        String name = optional(a, "name", "generated_scenario");
        Scenario scenario = ScenarioGenerator.generate(
                r, name, optional(a, "phaseSignal", null), optional(a, "phaseEquals", null),
                ScenarioGenerator.DEFAULT_TOLERANCE);
        if (scenario.assertions().isEmpty()) {
            return "No signal in " + str(a, "log") + " had a shape worth asserting (a counter,"
                    + " battery voltage, loop period, or a converging error). Write the scenario"
                    + " by hand instead.";
        }
        String yaml = scenario.toYaml();
        if (out == null) {
            return "Proposed scenario (dry run — pass 'out' to write it):\n" + yaml;
        }
        scenario.save(Path.of(out));
        return "Wrote " + scenario.assertions().size() + " generated checks to " + out + "\n" + yaml;
    }

    private static String loopDiff(JsonNode a) throws Exception {
        LogDiff.Result result = LogDiff.compare(
                new WpilogReader(str(a, "baseline")), new WpilogReader(str(a, "log")));
        return result.render(20);
    }

    private static String applyOrDryRun(PathFile before, PathFile after, String out) throws Exception {
        List<PathDiff.Change> changes = PathDiff.diff(before.root(), after.root());
        if (changes.isEmpty()) {
            return "(no change)";
        }
        StringBuilder sb = new StringBuilder("Proposed changes:\n");
        changes.forEach(c -> sb.append("  ").append(c).append('\n'));
        if (out == null) {
            sb.append("Dry run — pass 'out' to write a copy (the original is never overwritten).");
        } else {
            after.save(Path.of(out));
            sb.append("Wrote edited path to: ").append(out);
        }
        return sb.toString();
    }

    private static String render(Object v) {
        if (v instanceof byte[] b) return "<raw " + b.length + " bytes>";
        if (v instanceof double[] d) return java.util.Arrays.toString(d);
        if (v instanceof long[] l) return java.util.Arrays.toString(l);
        if (v instanceof boolean[] b) return java.util.Arrays.toString(b);
        if (v instanceof Object[] o) return java.util.Arrays.toString(o);
        return String.valueOf(v);
    }

    /** Read an optional string argument, falling back when absent, null, or blank. */
    private static String optional(JsonNode args, String key, String fallback) {
        JsonNode v = args == null ? null : args.get(key);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            return fallback;
        }
        return v.asText();
    }

    private static String str(JsonNode args, String key) {
        JsonNode v = args.get(key);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        return v.asText();
    }

    private static void add(Map<String, Tool> tools, Tool t) {
        tools.put(t.name(), t);
    }

    private ToolRegistry() {}
}
