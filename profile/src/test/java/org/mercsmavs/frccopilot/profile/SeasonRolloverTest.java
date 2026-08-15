package org.mercsmavs.frccopilot.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Season handling, which used to be hardcoded string literals in three files.
 *
 * <p>These tests are written against {@link GameData}'s registry rather than against the literal
 * "REBUILT"/2026, so they keep passing when next season is added and start failing if someone
 * reintroduces a hardcoded default somewhere.
 */
class SeasonRolloverTest {

    @Test
    void theCurrentSeasonAndDefaultGameAgreeWithTheRegistry() {
        // Both derive from the newest bundled season, so they cannot drift apart.
        assertEquals(GameData.currentSeason(), GameData.forGame(GameData.defaultGame()).season());
        assertTrue(GameData.knownGames().contains(GameData.defaultGame()));
    }

    @Test
    void everyBundledSeasonResolvesBothWays() {
        for (String game : GameData.knownGames()) {
            GameProfile byGame = GameData.forGame(game);
            assertNotNull(byGame, () -> "no profile for known game " + game);
            assertEquals(byGame, GameData.forSeason(byGame.season()).orElseThrow(),
                    () -> "game and season lookups disagree for " + game);
            assertEquals(byGame.season(), GameData.seasonForGame(game).orElseThrow());
        }
    }

    @Test
    void gameLookupIsCaseInsensitive() {
        String game = GameData.defaultGame();
        assertEquals(GameData.forGame(game), GameData.forGame(game.toLowerCase()));
        assertEquals(GameData.forGame(game), GameData.forGame(game.toUpperCase()));
    }

    @Test
    void anUnknownGameHasNoSeasonRatherThanTheCurrentOne() {
        // Substituting the current season would silently date a profile to the wrong year, which is
        // worse than honestly having no date.
        assertTrue(GameData.seasonForGame("Crescendo").isEmpty());
        assertTrue(GameData.seasonForGame(null).isEmpty());
        assertNull(GameData.forGame("Crescendo"));
    }

    // --- the profile builder's use of it ---

    private static Path repoWithLayout(Path dir, String aprilTagField) throws Exception {
        Path constants = dir.resolve("src/main/java/frc/robot/Constants.java");
        Files.createDirectories(constants.getParent());
        Files.writeString(constants, """
                package frc.robot;
                public final class Constants {
                  public static final var FIELD = AprilTagFields.%s;
                }
                """.formatted(aprilTagField));
        return dir;
    }

    @Test
    void flagsRobotCodeStillLoadingAPriorSeasonsFieldLayout(@TempDir Path tmp) throws Exception {
        // The expensive start-of-season carry-over: every vision pose resolves against last year's
        // field. Detected by comparing the year in the layout name, not by matching a game name.
        Path repo = repoWithLayout(tmp.resolve("robot"), "k2025ReefscapeWelded");
        ProfileBuilder.Result result = ProfileBuilder.fromRepo(repo, 1234, "bot", "REBUILT");

        assertEquals(2026, result.profile().field().season());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("2025") && w.contains("2026")),
                () -> "expected a stale-layout warning: " + result.warnings());
    }

    @Test
    void doesNotFlagALayoutThatMatchesTheProfilesSeason(@TempDir Path tmp) throws Exception {
        Path repo = repoWithLayout(tmp.resolve("robot"), "k2026RebuiltWelded");
        ProfileBuilder.Result result = ProfileBuilder.fromRepo(repo, 1234, "bot", "REBUILT");

        assertFalse(result.warnings().stream().anyMatch(w -> w.contains("AprilTag")),
                () -> "a current-season layout should not warn: " + result.warnings());
    }

    @Test
    void aPriorSeasonProfileIsDatedCorrectlyAndJudgedAgainstItsOwnYear(@TempDir Path tmp) throws Exception {
        // Building a 2025 profile must not be forced to the current season, and its layout check
        // must compare against 2025 — the whole point of removing the hardcoded year.
        Path repo = repoWithLayout(tmp.resolve("robot"), "k2025ReefscapeWelded");
        ProfileBuilder.Result result = ProfileBuilder.fromRepo(repo, 1234, "bot", "Reefscape");

        assertEquals(2025, result.profile().field().season());
        assertFalse(result.warnings().stream().anyMatch(w -> w.contains("AprilTag")),
                () -> "2025 layout in a 2025 profile is correct: " + result.warnings());
    }

    @Test
    void anUnknownGameWarnsInsteadOfSilentlyDatingTheProfile(@TempDir Path tmp) throws Exception {
        Path repo = repoWithLayout(tmp.resolve("robot"), "k2025ReefscapeWelded");
        ProfileBuilder.Result result = ProfileBuilder.fromRepo(repo, 1234, "bot", "Crescendo");

        assertNull(result.profile().field().season(), "an unknown game has no season");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Unknown game")),
                () -> "expected an unknown-game warning: " + result.warnings());
    }

    @Test
    void noSourceFileHardcodesTheSeasonDefaultAnyMore() throws Exception {
        // A guard against the rollover problem coming back: the game default and the game->season
        // mapping belong to GameData alone. Adding next season should be one entry there.
        List<Path> offenders = new java.util.ArrayList<>();
        List<String> scanned = new java.util.ArrayList<>();
        Path root = Path.of(".."); // Gradle runs tests with the module dir as the working dir
        for (String module : List.of("profile", "mcp-server")) {
            Path src = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (var paths = Files.walk(src)) {
                for (Path f : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    scanned.add(f.getFileName().toString());
                    if (f.getFileName().toString().equals("GameData.java")) {
                        continue; // the one place season literals belong
                    }
                    String body = Files.readString(f);
                    // A quoted game name outside GameData means a default or comparison crept back.
                    if (body.contains("\"REBUILT\"") || body.contains("\"Reefscape\"")) {
                        offenders.add(f.getFileName());
                    }
                }
            }
        }
        // Prove the scan actually reached both modules. A wrong relative path would find no files,
        // report no offenders, and pass while checking nothing — name the files rather than counting
        // them, since a count is a magic number that goes stale as the modules grow.
        assertTrue(scanned.contains("GameData.java"),
                () -> "scan never reached :profile — found " + scanned);
        assertTrue(scanned.contains("ToolRegistry.java"),
                () -> "scan never reached :mcp-server — found " + scanned);
        assertEquals(List.of(), offenders,
                "season literals belong in GameData; use GameData.defaultGame()/seasonForGame()");
    }
}
