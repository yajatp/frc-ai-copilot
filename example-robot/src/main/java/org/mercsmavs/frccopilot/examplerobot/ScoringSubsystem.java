package org.mercsmavs.frccopilot.examplerobot;

import edu.wpi.first.wpilibj2.command.SubsystemBase;

/**
 * A minimal command-based subsystem: collect game pieces into a hopper, then shoot them to score.
 *
 * <p>The {@code broken} switch reproduces the exact class of bug from Team 254's talk — a shoot
 * action mis-mapped so it "passes" the pieces instead of scoring them, yielding zero points.
 */
public class ScoringSubsystem extends SubsystemBase {

    private int hopper = 0;
    private int ballsScored = 0;
    private int passes = 0;

    public void loadHopper(int n) {
        hopper += n;
    }

    /** Shoot whatever is in the hopper. When broken, the shot is mis-routed to a pass (scores 0). */
    public void shoot(boolean broken) {
        if (broken) {
            passes += hopper; // BUG: shoot mapped to a passing sequence -> nothing scores
        } else {
            ballsScored += hopper;
        }
        hopper = 0;
    }

    public int ballsScored() {
        return ballsScored;
    }

    public int hopper() {
        return hopper;
    }

    public int passes() {
        return passes;
    }
}
