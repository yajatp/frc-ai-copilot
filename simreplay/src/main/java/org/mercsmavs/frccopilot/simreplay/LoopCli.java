package org.mercsmavs.frccopilot.simreplay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

/**
 * CLI for the closed-loop harness — the full edit/build/run/verify/iterate cycle.
 *
 * <pre>
 *   iterate  [loop.yaml] [--scenario &lt;f.yaml&gt;]        one full turn: build, run, verify, diagnose
 *   history  [loop.yaml]                              the iteration journal for this project
 *   baseline [loop.yaml]                              adopt the latest passing log as the baseline
 *   generate &lt;good.wpilog&gt; &lt;out.yaml&gt; [name] [phaseSignal phaseValue]
 *                                                     derive a scenario from a known-good run
 *   diff     &lt;baseline.wpilog&gt; &lt;run.wpilog&gt;           rank signals by divergence
 *   check    &lt;log.wpilog&gt; &lt;scenario.yaml&gt;             verify one scenario against a log
 *   suite    &lt;log.wpilog&gt; &lt;scenarioDir&gt;               run a regression suite against a log
 *   run      &lt;workDir&gt; &lt;logSearchDir&gt; &lt;scenario.yaml&gt; -- &lt;cmd...&gt;
 *                                                     run a log-producing command, then verify
 *   gen-demo &lt;outDir&gt;                                 write the 254 broken/fixed-auto demo
 * </pre>
 */
public final class LoopCli {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            usage();
            System.exit(2);
            return;
        }
        switch (args[0]) {
            case "iterate" -> iterate(args);
            case "history" -> history(args);
            case "baseline" -> baseline(args);
            case "generate" -> generate(args);
            case "diff" -> diff(args);
            case "check" -> {
                Verifier.LoopResult r = check(Path.of(args[1]), Path.of(args[2]));
                System.out.print(r.render());
                System.exit(r.allPassed() ? 0 : 1);
            }
            case "suite" -> {
                WpilogReader reader = new WpilogReader(args[1]);
                RegressionSuite.SuiteResult res =
                        RegressionSuite.runAll(RegressionSuite.load(Path.of(args[2])), reader::read);
                System.out.print(res.render());
                System.exit(res.allPassed() ? 0 : 1);
            }
            case "run" -> run(args);
            case "gen-demo" -> {
                DemoLogs.generate(Path.of(args[1]));
                System.out.println("Wrote demo logs + scenario to: " + args[1]);
            }
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    /** iterate [loop.yaml] [--scenario <file>] */
    private static void iterate(String[] args) throws Exception {
        Path config = LoopConfig.discover(configArg(args));
        Path scenario = flag(args, "--scenario");
        LoopConfig loop = LoopConfig.load(config);
        LoopRunner.IterationReport report = LoopRunner.iterate(loop, scenario);
        System.out.print(report.render());
        System.exit(report.passed() ? 0 : 1);
    }

    private static void history(String[] args) throws Exception {
        Path config = LoopConfig.discover(configArg(args));
        LoopConfig loop = LoopConfig.load(config);
        System.out.print(LoopSession.load(loop.loopStateDir().resolve("session.json")).render());
    }

    /** Adopt the most recent passing run's log as the baseline future failures are diffed against. */
    private static void baseline(String[] args) throws Exception {
        Path config = LoopConfig.discover(configArg(args));
        LoopConfig loop = LoopConfig.load(config);
        LoopSession session = LoopSession.load(loop.loopStateDir().resolve("session.json"));
        LoopSession.Iteration passing = null;
        for (LoopSession.Iteration it : session.iterations) {
            if (it.passed && it.log != null) {
                passing = it;
            }
        }
        if (passing == null) {
            System.err.println("No passing iteration with a log yet — run `iterate` until it passes.");
            System.exit(1);
            return;
        }
        Path target = loop.baselinePath();
        if (target == null) {
            System.err.println("Set 'baseline: <path>' in " + config + " to say where it should live.");
            System.exit(2);
            return;
        }
        Files.createDirectories(target.getParent());
        Files.copy(Path.of(passing.log), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Baseline set from iteration #" + passing.number + ": " + target);
    }

    /** generate <good.wpilog> <out.yaml> [name] [phaseSignal phaseValue] */
    private static void generate(String[] args) throws Exception {
        if (args.length < 3) {
            usage();
            System.exit(2);
            return;
        }
        WpilogReader reader = new WpilogReader(args[1]);
        Path out = Path.of(args[2]);
        String name = args.length > 3 ? args[3] : stripExtension(out.getFileName().toString());
        String phaseSignal = args.length > 5 ? args[4] : null;
        String phaseEquals = args.length > 5 ? args[5] : null;
        Scenario scenario = ScenarioGenerator.generate(
                reader, name, phaseSignal, phaseEquals, ScenarioGenerator.DEFAULT_TOLERANCE);
        if (scenario.assertions().isEmpty()) {
            System.err.println(
                    "No signal in " + args[1] + " had a shape worth asserting (counters, battery"
                            + " voltage, loop period, or a converging error). Write the scenario by hand.");
            System.exit(1);
            return;
        }
        scenario.save(out);
        System.out.println("Wrote " + scenario.assertions().size() + " generated checks to " + out);
        System.out.print(scenario.toYaml());
    }

    private static void diff(String[] args) throws Exception {
        if (args.length < 3) {
            usage();
            System.exit(2);
            return;
        }
        LogDiff.Result result =
                LogDiff.compare(new WpilogReader(args[1]), new WpilogReader(args[2]));
        System.out.print(result.render(20));
    }

    private static Verifier.LoopResult check(Path log, Path scenario) throws Exception {
        WpilogReader reader = new WpilogReader(log.toString());
        return Verifier.verify(Scenario.load(scenario), reader::read);
    }

    /** run <workDir> <logSearchDir> <scenario.yaml> -- <cmd...> */
    private static void run(String[] args) throws Exception {
        int sep = Arrays.asList(args).indexOf("--");
        if (sep < 0 || sep < 4) {
            usage();
            System.exit(2);
            return;
        }
        Path workDir = Path.of(args[1]);
        Path logDir = Path.of(args[2]);
        Path scenario = Path.of(args[3]);
        List<String> cmd = Arrays.asList(args).subList(sep + 1, args.length);

        System.out.println("Running: " + String.join(" ", cmd));
        SimRunner.RunResult run = SimRunner.run(workDir, cmd, logDir, 600);
        System.out.println("exit=" + run.exitCode());
        if (run.log().isEmpty()) {
            System.out.println("No .wpilog produced under " + logDir + "\n--- output tail ---\n" + run.tail());
            System.exit(2);
            return;
        }
        System.out.println("log: " + run.log().get());
        WpilogReader reader = new WpilogReader(run.log().get().toString());
        Verifier.LoopResult r = Verifier.verify(Scenario.load(scenario), reader::read);
        System.out.print(r.render());
        System.exit(r.allPassed() ? 0 : 1);
    }

    /** First non-flag argument, or the working directory (where discovery walks up from). */
    private static Path configArg(String[] args) {
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")
                    && (i == 1 || !args[i - 1].startsWith("--"))) {
                return Path.of(args[i]);
            }
        }
        return Path.of("");
    }

    private static Path flag(String[] args, String name) {
        for (int i = 1; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return Path.of(args[i + 1]);
            }
        }
        return null;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static void usage() {
        System.err.println(
                """
                frc-ai-copilot closed-loop harness
                usage:
                  iterate  [loop.yaml] [--scenario <f.yaml>]   one turn: build, run, verify, diagnose
                  history  [loop.yaml]                         iteration journal for this project
                  baseline [loop.yaml]                         adopt latest passing log as baseline
                  generate <good.wpilog> <out.yaml> [name] [phaseSignal phaseValue]
                  diff     <baseline.wpilog> <run.wpilog>      rank signals by divergence
                  check    <log.wpilog> <scenario.yaml>
                  suite    <log.wpilog> <scenarioDir>
                  run      <workDir> <logSearchDir> <scenario.yaml> -- <cmd...>
                  gen-demo <outDir>
                """);
    }

    private LoopCli() {}
}
