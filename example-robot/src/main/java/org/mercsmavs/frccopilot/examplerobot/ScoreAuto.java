package org.mercsmavs.frccopilot.examplerobot;

import edu.wpi.first.wpilibj2.command.Command;

/**
 * A tiny autonomous routine (a real WPILib {@link Command}): collect pieces over the first part of
 * auto, then shoot them. If {@code broken}, the shoot step scores nothing (the 254 typo).
 */
public class ScoreAuto extends Command {

    private final ScoringSubsystem scoring;
    private final boolean broken;
    private int tick;

    public ScoreAuto(ScoringSubsystem scoring, boolean broken) {
        this.scoring = scoring;
        this.broken = broken;
        addRequirements(scoring);
    }

    @Override
    public void initialize() {
        tick = 0;
    }

    @Override
    public void execute() {
        tick++;
        if (tick == 20) {
            scoring.loadHopper(3); // drive over the field, collect 3
        } else if (tick == 40) {
            scoring.loadHopper(2); // collect 2 more
        } else if (tick == 60) {
            scoring.shoot(broken); // shoot the 5 -> scores 5 (fixed) or 0 (broken)
        }
    }

    @Override
    public boolean isFinished() {
        return tick >= 61;
    }
}
