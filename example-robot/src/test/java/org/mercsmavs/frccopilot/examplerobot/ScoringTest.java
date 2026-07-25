package org.mercsmavs.frccopilot.examplerobot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import edu.wpi.first.hal.HAL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ScoringTest {

    @BeforeAll
    static void initHal() {
        HAL.initialize(500, 0); // command-based classes touch the sim HAL
    }

    @AfterAll
    static void shutdownHal() {
        HAL.shutdown();
    }

    @Test
    void fixedShootScoresBrokenShootScoresNothing() {
        ScoringSubsystem fixed = new ScoringSubsystem();
        fixed.loadHopper(5);
        fixed.shoot(false);
        assertEquals(5, fixed.ballsScored());

        ScoringSubsystem broken = new ScoringSubsystem();
        broken.loadHopper(5);
        broken.shoot(true); // the 254 typo: shoot routed to a pass
        assertEquals(0, broken.ballsScored());
        assertEquals(5, broken.passes());
    }
}
