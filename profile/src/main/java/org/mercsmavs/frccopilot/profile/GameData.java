package org.mercsmavs.frccopilot.profile;

import java.util.List;
import java.util.Optional;

/**
 * Bundled field/game data by season. FRC field dimensions and standard match timing are well known;
 * game-specific scoring zones / obstacles for the current season are approximate and clearly noted
 * — we would rather say "approximate" than invent precise coordinates.
 */
public final class GameData {

    // Standard FRC field (guardrail-to-guardrail), consistent across recent seasons.
    private static final double FIELD_LENGTH_M = 16.541;
    private static final double FIELD_WIDTH_M = 8.211;
    // Standard match timing.
    private static final double AUTO_S = 15.0;
    private static final double TELEOP_S = 135.0;
    private static final double ENDGAME_S = 20.0;

    public static Optional<GameProfile> forSeason(int season) {
        return switch (season) {
            case 2026 -> Optional.of(rebuilt2026());
            case 2025 -> Optional.of(reefscape2025());
            default -> Optional.empty();
        };
    }

    public static GameProfile forGame(String game) {
        if (game == null) {
            return null;
        }
        return switch (game.toLowerCase()) {
            case "rebuilt" -> rebuilt2026();
            case "reefscape" -> reefscape2025();
            default -> null;
        };
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
                        new GameProfile.FieldObstacle("bump", "raised field crossing (per 254's traversal case)", null),
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
