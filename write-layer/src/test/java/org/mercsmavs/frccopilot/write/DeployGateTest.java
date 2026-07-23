package org.mercsmavs.frccopilot.write;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeployGateTest {

    @Test
    void throwsWithoutConfirmation() {
        SecurityException ex =
                assertThrows(
                        SecurityException.class,
                        () -> DeployGate.requireConfirmation("deploy robot code", false));
        assertTrue(ex.getMessage().contains("deploy robot code"));
        assertTrue(ex.getMessage().toLowerCase().contains("confirmation"));
    }

    @Test
    void passesWithConfirmation() {
        assertDoesNotThrow(() -> DeployGate.requireConfirmation("deploy robot code", true));
    }
}
