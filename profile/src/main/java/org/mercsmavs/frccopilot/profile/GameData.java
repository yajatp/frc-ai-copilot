package org.mercsmavs.frccopilot.profile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bundled field/game data by season. FRC field dimensions and standard match timing are well known;
 * game-specific scoring zones and obstacles are approximate and clearly noted — we would rather say
 * "approximate" than invent precise coordinates.
 *
 * <p>Everything season-specific lives in {@link #SEASONS}, and every lookup derives from it. That is
 * deliberate: season knowledge used to be spread across a hardcoded default in the CLI, a second one
 * in the MCP tool, and a {@code game.equals("REBUILT") ? 2026 : null} in the profile builder, so the
 * yearly rollover meant hunting for string literals. Adding next season should be one entry here.
 */
public final class GameData {

    // Standard FRC field (guardrail-to-guardrail), consistent across recent seasons.
    private static final double FIELD_LENGTH_M = 16.541;
    private static final double FIELD_WIDTH_M = 8.211;
    // Standard match timing.
    private static final double AUTO_S = 15.0;
    private static final double TELEOP_S = 135.0;
    private static final double ENDGAME_S = 20.0;

    /**
     * Every season this build knows about, newest first. The first entry is the current season, so
     * {@link #currentSeason()} and {@link #defaultGame()} follow from the ordering rather than from a
     * separate constant that could disagree with it.
     */
    private static final List<GameProfile> SEASONS = List.of(rebuilt2026(), reefscape2025());

    private static final Map<String, GameProfile> BY_GAME = indexByGame();

    /** The season this build treats as current — the newest one bundled. */
    public static int currentSeason() {
        return SEASONS.get(0).season();
    }

    /** The game key used when a caller does not name one. */
    public static String defaultGame() {
        return SEASONS.get(0).game();
    }

    /** Every season key this build knows, newest first — for error messages and tool descriptions. */
    public static List<String> knownGames() {
        return SEASONS.stream().map(GameProfile::game).toList();
    }

    public static Optional<GameProfile> forSeason(int season) {
        return SEASONS.stream().filter(g -> g.season() == season).findFirst();
    }

    public static GameProfile forGame(String game) {
        return game == null ? null : BY_GAME.get(game.toLowerCase());
    }

    /**
     * The season a game key belongs to, empty if the key is not one we bundle. Callers should treat
     * empty as "unknown season" rather than substituting the current one — a profile silently dated
     * to the wrong year is worse than one honestly missing a date.
     */
    public static Optional<Integer> seasonForGame(String game) {
        GameProfile profile = forGame(game);
        return profile == null ? Optional.empty() : Optional.of(profile.season());
    }

    private static Map<String, GameProfile> indexByGame() {
        Map<String, GameProfile> index = new LinkedHashMap<>();
        for (GameProfile profile : SEASONS) {
            index.put(profile.game().toLowerCase(), profile);
        }
        return Map.copyOf(index);
    }

    private static GameProfile rebuilt2026() {
        return new GameProfile(
                "REBUILT",
                2026,
                FIELD_LENGTH_M,
                FIELD_WIDTH_M,
                AUTO_S,
                TELEOP_S,
                ENDGAME_S,
                List.of(
                        // Approximate placeholders — coordinates NOT verified against official CAD.
                        new GameProfile.ScoringZone("hub", FIELD_LENGTH_M / 2, FIELD_WIDTH_M / 2, 1.0, 1.0)),
                List.of(
                        new GameProfile.FieldObstacle("bump", "raised field crossing", null),
                        new GameProfile.FieldObstacle("trench", "low overhead a tall hopper/mechanism must fit under", 0.60)),
                List.of(
                        "Field dimensions and match timing are standard FRC values.",
                        "REBUILT scoring-zone coordinates and the trench clearance (0.60 m) are APPROXIMATE"
                                + " placeholders — verify against the official 2026 field drawings before"
                                + " relying on them for clearance decisions."));
    }

    private static GameProfile reefscape2025() {
        return new GameProfile(
                "Reefscape",
                2025,
                FIELD_LENGTH_M,
                FIELD_WIDTH_M,
                AUTO_S,
                TELEOP_S,
                ENDGAME_S,
                List.of(),
                List.of(),
                List.of("Field dimensions and match timing are standard FRC values;"
                        + " detailed scoring zones not bundled for 2025."));
    }

    private GameData() {}
}
