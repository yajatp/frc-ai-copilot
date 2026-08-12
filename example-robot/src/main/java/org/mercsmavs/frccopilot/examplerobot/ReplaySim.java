package org.mercsmavs.frccopilot.examplerobot;

import edu.wpi.first.hal.HAL;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.util.datalog.DataLogReader;
import edu.wpi.first.util.datalog.DataLogRecord;
import edu.wpi.first.util.datalog.DataLogWriter;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.simulation.DriverStationSim;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays a recorded {@code .wpilog} through the current robot code and writes a new log of what
 * would have happened — the "replay" half of Module 6, as distinct from {@link HeadlessSim}'s
 * simulation.
 *
 * <pre>
 *   ReplaySim &lt;in.wpilog&gt; &lt;out.wpilog&gt; [--from &lt;sec&gt;] [--to &lt;sec&gt;] [broken]
 * </pre>
 *
 * <p><b>What replay is, and what it is not.</b> This does not simulate anything. It reads the
 * recorded timeline — the phase signal and the recorded chassis pose — and re-executes the robot's
 * command-based scoring logic against it through the real {@link CommandScheduler}, at the
 * timestamps the log actually contains. No physics engine runs, which is the whole point: answering
 * "would my change have scored on match 10" costs a pass over a file rather than a resimulation, and
 * `--from`/`--to` narrows it to just the segment in question.
 *
 * <p>The corresponding limitation is real and worth being explicit about, because it is a property
 * of replay in general and not of this implementation: replay is only valid for code
 * <em>downstream</em> of the signals in the log. Scoring logic is a function of the recorded tick
 * timeline, so a change to it is faithfully evaluated here. The chassis pose is not — it depends on
 * actuation feeding back through the physics, so a change to {@link DriveAuto} cannot move the
 * replayed pose and must be evaluated in {@code HeadlessSim} instead. Pose is therefore passed
 * through from the input log, and {@code /Drivetrain/DistanceToTarget} is recomputed from it rather
 * than invented.
 */
public final class ReplaySim {

    /** One recorded cycle: the timestamp, the phase, and the pose the robot was actually at. */
    private record Cycle(long timestampUs, String phase, double poseX, double poseY, double voltage) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println(
                    "usage: ReplaySim <in.wpilog> <out.wpilog> [--from <sec>] [--to <sec>] [broken]");
            System.exit(2);
            return;
        }
        String inPath = args[0];
        String outPath = args[1];
        double from = 0;
        double to = Double.MAX_VALUE;
        boolean broken = false;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--from" -> from = Double.parseDouble(args[++i]);
                case "--to" -> to = Double.parseDouble(args[++i]);
                case "broken" -> broken = true;
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    System.exit(2);
                    return;
                }
            }
        }

        List<Cycle> cycles = readCycles(inPath);
        if (cycles.isEmpty()) {
            System.err.println("No replayable cycles in " + inPath
                    + " — expected /Robot/State plus /Drivetrain/Pose/X,Y."
                    + " Replay needs the recorded inputs it re-executes against.");
            System.exit(1);
            return;
        }

        // Segment selection is relative to the log's own start, so callers reason in match time
        // rather than in the robot's arbitrary microsecond epoch.
        long epoch = cycles.get(0).timestampUs();
        long fromUs = epoch + (long) (from * 1_000_000);
        long toUs = to == Double.MAX_VALUE ? Long.MAX_VALUE : epoch + (long) (to * 1_000_000);
        List<Cycle> segment = new ArrayList<>();
        for (Cycle c : cycles) {
            if (c.timestampUs() >= fromUs && c.timestampUs() <= toUs) {
                segment.add(c);
            }
        }
        if (segment.isEmpty()) {
            System.err.println("Segment [" + from + ", " + to + "] s selected no cycles from "
                    + cycles.size() + " recorded.");
            System.exit(1);
            return;
        }

        if (!HAL.initialize(500, 0)) {
            System.err.println("HAL failed to initialize (simulation)");
            System.exit(1);
            return;
        }

        ScoringSubsystem scoring = new ScoringSubsystem();
        CommandScheduler scheduler = CommandScheduler.getInstance();
        Command scoreAuto = new ScoreAuto(scoring, broken);

        DriverStationSim.setDsAttached(true);
        DriverStationSim.setAutonomous(true);
        DriverStationSim.setEnabled(true);
        DriverStationSim.notifyNewData();
        DriverStation.refreshData();
        scheduler.schedule(scoreAuto);

        int replayedAuto = 0;
        try (DataLogWriter log = new DataLogWriter(outPath)) {
            int state = log.start("/Robot/State", "string");
            int balls = log.start("/Autonomous/BallsScored", "int64");
            int voltage = log.start("/PowerDistribution/Voltage", "double");
            int poseX = log.start("/Drivetrain/Pose/X", "double");
            int poseY = log.start("/Drivetrain/Pose/Y", "double");
            int distToTarget = log.start("/Drivetrain/DistanceToTarget", "double");

            for (Cycle c : segment) {
                boolean autonomous = "AUTO".equals(c.phase());
                if (autonomous) {
                    // Real command-based execution, driven by the recorded timeline. No physics.
                    DriverStation.refreshData();
                    scheduler.run();
                    replayedAuto++;
                } else if (scoreAuto.isScheduled()) {
                    scoreAuto.cancel();
                    DriverStationSim.setEnabled(false);
                    DriverStationSim.setAutonomous(false);
                    DriverStationSim.notifyNewData();
                }
                Pose2d pose = new Pose2d(c.poseX(), c.poseY(), new Rotation2d());
                log.appendString(state, c.phase(), c.timestampUs());
                log.appendInteger(balls, scoring.ballsScored(), c.timestampUs());
                log.appendDouble(voltage, c.voltage(), c.timestampUs());
                log.appendDouble(poseX, c.poseX(), c.timestampUs());
                log.appendDouble(poseY, c.poseY(), c.timestampUs());
                log.appendDouble(distToTarget, DriveAuto.distanceToTarget(pose), c.timestampUs());
            }
            log.flush();
        }

        System.out.printf(
                "ReplaySim replayed %d cycles (%d in AUTO) from %s -> %s (broken=%s, ballsScored=%d)%n",
                segment.size(), replayedAuto, inPath, outPath, broken, scoring.ballsScored());
        HAL.shutdown();
        System.exit(0);
    }

    /**
     * Read the recorded inputs, joined by timestamp. Every signal in a wpilog is its own sparse
     * stream, so a cycle is emitted per timestamp at which the phase signal was written, carrying the
     * most recent pose/voltage seen at or before it — which is what "the state the robot was in"
     * means for a log.
     */
    private static List<Cycle> readCycles(String path) throws IOException {
        DataLogReader reader = new DataLogReader(path);
        if (!reader.isValid()) {
            throw new IOException("Not a valid WPILOG file: " + path);
        }

        Map<Integer, String> names = new HashMap<>();
        List<Cycle> cycles = new ArrayList<>();
        String phase = null;
        double poseX = 0;
        double poseY = 0;
        double voltage = 0;
        boolean sawPose = false;

        for (DataLogRecord record : reader) {
            if (record.isStart()) {
                names.put(record.getStartData().entry, record.getStartData().name);
                continue;
            }
            if (record.isControl()) {
                continue;
            }
            String name = names.get(record.getEntry());
            if (name == null) {
                continue;
            }
            switch (name) {
                case "/Drivetrain/Pose/X" -> {
                    poseX = record.getDouble();
                    sawPose = true;
                }
                case "/Drivetrain/Pose/Y" -> poseY = record.getDouble();
                case "/PowerDistribution/Voltage" -> voltage = record.getDouble();
                case "/Robot/State" -> phase = record.getString();
                default -> { /* not an input this replay consumes */ }
            }
            // Emit on the pose sample: it is written once per cycle after the phase in the producing
            // log, so by this point the phase for this cycle is already current.
            if (name.equals("/Drivetrain/Pose/Y") && phase != null && sawPose) {
                cycles.add(new Cycle(record.getTimestamp(), phase, poseX, poseY, voltage));
            }
        }
        return cycles;
    }

    private ReplaySim() {}
}
