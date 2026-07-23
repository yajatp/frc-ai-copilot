package org.mercsmavs.frccopilot.write;

/**
 * The human-in-the-loop safety boundary for anything in this module that would ultimately reach a
 * real robot (deploying code, pushing an edited {@code .path}/{@code .auto} into a live deploy
 * directory, triggering a build-and-flash, etc.).
 *
 * <p><b>The agent must never deploy code to a robot autonomously.</b> Every write layer entry point
 * that could result in new code or configuration running on physical hardware must route through
 * {@link #requireConfirmation(String, boolean)} (or an equivalent explicit human gate) and must never
 * synthesize or assume that confirmation itself — it has to come from an actual human decision,
 * passed in as {@code confirmed}. This class does not perform any deploy; it only enforces that the
 * decision to do so was made by a person, not inferred by the agent.
 *
 * <p>In practice, callers in this codebase never actually deploy (there's no build-and-flash step
 * here), so this is modeled as a guard other, future deploy-capable code is expected to call first —
 * a single, auditable choke point rather than a scattered set of ad hoc checks.
 */
public final class DeployGate {

    /**
     * Guards a robot-affecting action. Throws unless a human has explicitly confirmed it.
     *
     * @param action a short description of what's about to happen, e.g. {@code "deploy robot code"}
     *     or {@code "overwrite JAMES1.path on the deploy target"} — used only in the exception
     *     message, so make it specific enough for a human reading a log to understand what almost
     *     happened.
     * @param confirmed must be {@code true} and must originate from an explicit human decision (e.g.
     *     a CLI flag the human typed, or a button a human clicked) — never a default, never a value
     *     the agent computed or assumed on the human's behalf.
     * @throws SecurityException if {@code confirmed} is {@code false}.
     */
    public static void requireConfirmation(String action, boolean confirmed) {
        if (!confirmed) {
            throw new SecurityException(
                    "Refusing to " + action + " without explicit human confirmation. "
                            + "The agent must never deploy code to a robot autonomously.");
        }
    }

    private DeployGate() {}
}
