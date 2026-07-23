package org.mercsmavs.frccopilot.livent;

import edu.wpi.first.networktables.NetworkTable;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The safety boundary between the copilot and the live robot.
 *
 * <p><b>This class never lets the agent move the robot.</b> It is the one and only path by which
 * anything in this codebase is allowed to write to NetworkTables, and it enforces that boundary in
 * code, not by asking an LLM to behave: writes are permitted only for a small, explicit whitelist
 * of tunable numeric keys (things like PID gains under {@code /Tuning/...}), and only as plain
 * doubles. There is no code path here (or reachable from here) that can command a motor, set an
 * actuator output, flip the robot enable/disable state, or touch Driver Station / FMS state &mdash;
 * those keys are simply never on the whitelist, and every other key is rejected by default.
 *
 * <p>Design:
 *
 * <ul>
 *   <li>Default-deny: {@link #isAllowed(String)} returns {@code true} only if the (normalized) key
 *       exactly matches an explicitly-allowed key, or starts with an explicitly-allowed prefix.
 *       Anything not on the list &mdash; including things nobody thought to list &mdash; is
 *       rejected.
 *   <li>Hard, non-configurable denylist: a small set of top-level tables that are always rejected
 *       regardless of configured prefixes, as defense-in-depth against a future misconfiguration
 *       (e.g. an overly-broad allowed prefix) accidentally opening up actuator or safety state.
 *   <li>Type-limited: the only write entry point, {@link #writeDouble(NtClient, String, double)},
 *       only accepts a {@code double}. There is no {@code writeBoolean}/{@code writeString}/etc, so
 *       even a whitelisted key can only ever receive a plain numeric tunable value through this
 *       class.
 * </ul>
 */
public final class NtWriteGuard {

    /**
     * Top-level tables that can never be written through this guard, no matter how {@link
     * #allowedPrefixes} is configured. These are exactly the kinds of keys this class exists to
     * protect: actuator/robot output tables, and Driver Station / FMS state.
     */
    private static final List<String> HARD_DENY_PREFIXES =
            List.of("/DriverStation", "/FMSInfo", "/Robot", "/LiveWindow");

    /** Default whitelist: dashboard "Tuning" sub-tables, the conventional home for tunables. */
    private static final List<String> DEFAULT_ALLOWED_PREFIXES = List.of("/Tuning", "/SmartDashboard/Tuning");

    private final List<String> allowedPrefixes;
    private final Set<String> allowedKeys;

    /** Creates a guard using the default whitelist ({@code /Tuning}, {@code /SmartDashboard/Tuning}). */
    public NtWriteGuard() {
        this(DEFAULT_ALLOWED_PREFIXES, Set.of());
    }

    /**
     * Creates a guard with a custom whitelist.
     *
     * @param allowedPrefixes key prefixes that are permitted to be written (e.g. {@code "/Tuning"});
     *     a key is allowed if it equals a prefix or starts with {@code prefix + "/"}
     * @param allowedKeys individual keys that are permitted to be written, beyond the prefixes
     */
    public NtWriteGuard(List<String> allowedPrefixes, Set<String> allowedKeys) {
        this.allowedPrefixes = allowedPrefixes.stream().map(NtWriteGuard::normalize).toList();
        this.allowedKeys = allowedKeys.stream().map(NtWriteGuard::normalize).collect(Collectors.toSet());
    }

    /**
     * Returns whether {@code key} is permitted to be written by this guard.
     *
     * @param key the NetworkTables key (leading slash optional; normalized internally)
     * @return true only if the key is whitelisted and not on the hard denylist
     */
    public boolean isAllowed(String key) {
        String normalized = normalize(key);

        for (String deny : HARD_DENY_PREFIXES) {
            if (matchesPrefix(normalized, deny)) {
                return false;
            }
        }

        if (allowedKeys.contains(normalized)) {
            return true;
        }
        for (String prefix : allowedPrefixes) {
            if (matchesPrefix(normalized, prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes a double value to {@code key} via {@code client}, but only if {@link
     * #isAllowed(String)} says the key is whitelisted.
     *
     * @param client the client to write through
     * @param key the NetworkTables key to write
     * @param value the double value to publish
     * @throws SecurityException if the key is not on the whitelist (or is on the hard denylist)
     */
    public void writeDouble(NtClient client, String key, double value) {
        String normalized = normalize(key);
        if (!isAllowed(normalized)) {
            throw new SecurityException(
                    "NtWriteGuard: refusing to write to '"
                            + normalized
                            + "' - not a whitelisted tunable key. This safety boundary never allows"
                            + " writes to actuator outputs, enable/DS/FMS state, or any key that isn't"
                            + " explicitly whitelisted.");
        }
        client.publishDouble(normalized, value);
    }

    private static boolean matchesPrefix(String key, String prefix) {
        return key.equals(prefix) || key.startsWith(prefix + "/");
    }

    private static String normalize(String key) {
        return NetworkTable.normalizeKey(key, true);
    }
}
