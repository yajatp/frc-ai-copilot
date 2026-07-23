package org.mercsmavs.frccopilot.livent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link NtWriteGuard} safety boundary: a small whitelist of tunable keys can be
 * written, and everything else - especially actuator outputs and enable/DS/FMS state - is rejected
 * in code, not merely by convention.
 */
class NtWriteGuardTest {

    private NtClient client;
    private NtWriteGuard guard;

    @BeforeEach
    void setUp() {
        client = new NtClient();
        guard = new NtWriteGuard();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void allowsWhitelistedTunableKey() {
        assertTrue(guard.isAllowed("/Tuning/kP"));
        assertTrue(guard.isAllowed("/SmartDashboard/Tuning/maxVelocity"));

        guard.writeDouble(client, "/Tuning/kP", 0.42);

        assertEquals(0.42, client.getDouble("/Tuning/kP", Double.NaN), 1e-9);
    }

    @Test
    void allowsKeyWithoutLeadingSlashByNormalizing() {
        assertTrue(guard.isAllowed("Tuning/kI"));
        guard.writeDouble(client, "Tuning/kI", 1.5);
        assertEquals(1.5, client.getDouble("/Tuning/kI", Double.NaN), 1e-9);
    }

    @Test
    void rejectsActuatorOutputKey() {
        assertFalse(guard.isAllowed("/Robot/Drive/leftOutput"));
        SecurityException ex =
                assertThrows(
                        SecurityException.class,
                        () -> guard.writeDouble(client, "/Robot/Drive/leftOutput", 1.0));
        assertTrue(ex.getMessage().contains("/Robot/Drive/leftOutput"));

        // And the value must never actually have been written.
        assertEquals(0.0, client.getDouble("/Robot/Drive/leftOutput", 0.0), 1e-9);
    }

    @Test
    void rejectsDriverStationEnableKey() {
        assertFalse(guard.isAllowed("/DriverStation/Enabled"));
        assertThrows(
                SecurityException.class, () -> guard.writeDouble(client, "/DriverStation/Enabled", 1.0));
    }

    @Test
    void rejectsKeyNotOnWhitelistEvenIfItLooksHarmless() {
        assertFalse(guard.isAllowed("/SmartDashboard/someRandomNumber"));
        assertThrows(
                SecurityException.class,
                () -> guard.writeDouble(client, "/SmartDashboard/someRandomNumber", 3.0));
    }

    @Test
    void hardDenylistWinsEvenOverAMisconfiguredCustomWhitelist() {
        // Defense-in-depth: even if a caller (mis)configures an overly broad allowed prefix,
        // the hard-coded deny list for actuator/DS/FMS tables still wins.
        NtWriteGuard permissiveGuard =
                new NtWriteGuard(java.util.List.of("/Robot", "/DriverStation"), java.util.Set.of());

        assertFalse(permissiveGuard.isAllowed("/Robot/Drive/leftOutput"));
        assertFalse(permissiveGuard.isAllowed("/DriverStation/Enabled"));
        assertThrows(
                SecurityException.class,
                () -> permissiveGuard.writeDouble(client, "/Robot/Drive/leftOutput", 1.0));
    }

    @Test
    void explicitAllowedKeySetIsRespected() {
        NtWriteGuard customGuard =
                new NtWriteGuard(java.util.List.of(), java.util.Set.of("/Config/oneOffTunable"));

        assertTrue(customGuard.isAllowed("/Config/oneOffTunable"));
        assertFalse(customGuard.isAllowed("/Config/otherKey"));

        customGuard.writeDouble(client, "/Config/oneOffTunable", 9.0);
        assertEquals(9.0, client.getDouble("/Config/oneOffTunable", Double.NaN), 1e-9);
    }
}
