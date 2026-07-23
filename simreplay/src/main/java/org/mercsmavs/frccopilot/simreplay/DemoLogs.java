package org.mercsmavs.frccopilot.simreplay;

import edu.wpi.first.util.datalog.DataLogWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates the canonical 254 "broken auto" demonstration: a log where autonomous scores 0 balls
 * (the bug) and one where a fix ramps it to the target, plus the scenario that distinguishes them.
 * Lets us exercise the whole observe -> verify loop end-to-end on a concrete, recognizable case.
 */
public final class DemoLogs {

    public static void generate(Path outDir) throws IOException {
        Files.createDirectories(outDir);
        writeAuto(outDir.resolve("auto_broken.wpilog"), false);
        writeAuto(outDir.resolve("auto_fixed.wpilog"), true);

        Scenario scenario =
                new Scenario(
                        "auto_scores_balls",
                        "Autonomous must score a non-zero number of balls and reach the target"
                                + " (254 'broken auto' case: a typo made shoot trigger a pass, scoring 0).",
                        List.of(
                                new Assertion("/Autonomous/BallsScored", Assertion.Aggregation.MAX,
                                        Assertion.Op.GT, 0, "/Robot/State", "AUTO", "auto scores non-zero"),
                                new Assertion("/Autonomous/BallsScored", Assertion.Aggregation.MAX,
                                        Assertion.Op.GE, 40, "/Robot/State", "AUTO", "auto meets 40-ball target")));
        scenario.save(outDir.resolve("auto_scores_balls.yaml"));
    }

    private static void writeAuto(Path file, boolean fixed) throws IOException {
        try (DataLogWriter log = new DataLogWriter(file.toString())) {
            int state = log.start("/Robot/State", "string");
            int balls = log.start("/Autonomous/BallsScored", "int64");
            // 0–1.5 s AUTO then TELEOP; 150 samples @ 20 ms.
            for (int i = 0; i < 150; i++) {
                long ts = 1_000_000L + i * 20_000L;
                boolean auto = i < 75;
                log.appendString(state, auto ? "AUTO" : "TELEOP", ts);
                long scored;
                if (!fixed) {
                    scored = 0; // the bug: shoot mis-mapped to a pass, nothing scores
                } else {
                    scored = auto ? Math.round(50.0 * i / 74.0) : 50; // fix ramps to 50 during auto
                }
                log.appendInteger(balls, scored, ts);
            }
            log.flush();
        }
    }

    private DemoLogs() {}
}
