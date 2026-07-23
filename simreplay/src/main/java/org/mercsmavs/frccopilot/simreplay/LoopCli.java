package org.mercsmavs.frccopilot.simreplay;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

/**
 * CLI for the closed-loop harness — the observe/run/verify pieces of the agentic loop.
 *
 * <pre>
 *   check    &lt;log.wpilog&gt; &lt;scenario.yaml&gt;                 verify one scenario against a log
 *   suite    &lt;log.wpilog&gt; &lt;scenarioDir&gt;                   run a whole regression suite against a log
 *   run      &lt;workDir&gt; &lt;logSearchDir&gt; &lt;scenario.yaml&gt; -- &lt;cmd...&gt;
 *                                                          run a log-producing command, then verify
 *   gen-demo &lt;outDir&gt;                                     write the 254 broken/fixed-auto demo
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

    private static void usage() {
        System.err.println(
                """
                frc-ai-copilot closed-loop harness
                usage:
                  check    <log.wpilog> <scenario.yaml>
                  suite    <log.wpilog> <scenarioDir>
                  run      <workDir> <logSearchDir> <scenario.yaml> -- <cmd...>
                  gen-demo <outDir>
                """);
    }

    private LoopCli() {}
}
