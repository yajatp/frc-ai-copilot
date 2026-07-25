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
import org.mercsmavs.frccopilot.analysis.LoopTiming;
import org.mercsmavs.frccopilot.analysis.PowerAnalysis;
import org.mercsmavs.frccopilot.analysis.Series;
import org.mercsmavs.frccopilot.analysis.SignalResolver;
import org.mercsmavs.frccopilot.analysis.SwerveAnalysis;
import org.mercsmavs.frccopilot.analysis.VisionAnalysis;
import org.mercsmavs.frccopilot.ingest.LogEntry;
import org.mercsmavs.frccopilot.ingest.WpilogReader;
import org.mercsmavs.frccopilot.ingest.store.LogSummary;
import org.mercsmavs.frccopilot.ingest.store.TrendStore;
import org.mercsmavs.frccopilot.modes.ModeA;
import org.mercsmavs.frccopilot.profile.ProfileMapper;
import org.mercsmavs.frccopilot.profile.RobotProfile;
import org.mercsmavs.frccopilot.simreplay.RegressionSuite;
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

        add(tools, new SimpleTool("mode_a", "Mode A between-match pass: ingest a log, flag"
                + " brownout/battery/CAN/loop issues, and persist metrics to the trend store.",
                Schemas.object(
                        new Schemas.Prop("db", "string", "Path to the SQLite trend store", true),
                        new Schemas.Prop("file", "string", "Path to the match .wpilog", true)),
                ToolRegistry::modeA));

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
                FRC AI Copilot — MCP server (Modules 1–4).

                Workflow:
                  1) Call profile_show on the team's robot profile so analysis is robot-specific
                     (CAN map, drivetrain, current limit, subsystems).
                  2) Use log_info / log_entries / read_entry to explore a match log.
                  3) Use power_analysis and can_health for the Mode-A safety picture (brownouts,
                     battery, CAN) — the 2026 energy-management meta.
                  4) Use pathplanner_show / pathplanner_fudge / pathplanner_set_speed to propose
                     autonomous adjustments (the only thing teams change at competition).

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

    private static final List<String> SWERVE_CANDIDATES =
            List.of("DriveVelocity", "SwerveStates", "DriveAppliedVolts", "ModuleVelocity", "Velocity");
    private static final List<String> VISION_CANDIDATES =
            List.of("hasTarget", "HasTarget", "NumTags", "TagCount", "TargetsSeen");
    private static final List<String> LOOP_CANDIDATES =
            List.of("loopTimeMs", "LoopTime", "codeRuntime", "PeriodicMs", "loopPeriod");

    private static String modeA(JsonNode a) throws Exception {
        WpilogReader r = new WpilogReader(str(a, "file"));
        Map<Integer, LogEntry> index = r.index();
        try (TrendStore store = new TrendStore(str(a, "db"))) {
            long logId = store.ingest(LogSummary.from(r, index), index.values());
            Series voltage = seriesFor(r, index, SignalResolver.VOLTAGE);
            Series current = seriesFor(r, index, SignalResolver.TOTAL_CURRENT);
            Series can = seriesFor(r, index, SignalResolver.CAN_ERRORS);
            Series loop = seriesFor(r, index, LOOP_CANDIDATES);
            ModeA.Result res = ModeA.analyze(store, logId, voltage, current, can, loop);
            return res.report() + "Overall: " + res.worst() + " (log #" + logId + ", metrics persisted)";
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
