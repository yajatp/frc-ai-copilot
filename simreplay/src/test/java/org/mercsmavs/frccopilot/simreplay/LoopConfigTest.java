package org.mercsmavs.frccopilot.simreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoopConfigTest {

    private static Path write(Path dir, String yaml) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(LoopConfig.FILE_NAME);
        Files.writeString(file, yaml);
        return file;
    }

    private static final String MINIMAL = """
            name: demo
            run: ["./sim", "{log}"]
            scenarioDir: scenarios
            """;

    @Test
    void resolvesPathsRelativeToTheConfigNotTheWorkingDirectory(@TempDir Path tmp) throws Exception {
        // A committed loop.yaml has to work regardless of where the command was invoked from.
        LoopConfig config = LoopConfig.load(write(tmp.resolve("robot"), MINIMAL));
        assertEquals(tmp.resolve("robot/scenarios"), config.scenarioDirPath());
        assertEquals(tmp.resolve("robot/.loop/logs"), config.logDirPath());
        assertEquals(tmp.resolve("robot"), config.workDirPath());
    }

    @Test
    void substitutesTheLogPlaceholderIntoTheRunCommand(@TempDir Path tmp) throws Exception {
        LoopConfig config = LoopConfig.load(write(tmp.resolve("robot"), MINIMAL));
        assertTrue(config.producesLogPath());
        List<String> argv = config.runCommand(Path.of("/logs/iteration-007.wpilog"));
        assertEquals(List.of("./sim", "/logs/iteration-007.wpilog"), argv);
    }

    @Test
    void expandsDirectoryPlaceholdersInTheEnvironment(@TempDir Path tmp) throws Exception {
        // How a committed config points Gradle at the checkout's own cache without hardcoding a machine.
        LoopConfig config = LoopConfig.load(write(tmp.resolve("robot"), MINIMAL + """
                workDir: ..
                env:
                  GRADLE_USER_HOME: "{workDir}/.gradle-home"
                """));
        assertEquals(
                tmp.toAbsolutePath().resolve(".gradle-home").toString(),
                config.resolvedEnv().get("GRADLE_USER_HOME"));
    }

    @Test
    void findsTheConfigFromAnywhereInsideTheProject(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("robot");
        write(root, MINIMAL);
        Path deep = root.resolve("src/main/java");
        Files.createDirectories(deep);
        assertEquals(root.resolve(LoopConfig.FILE_NAME).toAbsolutePath(), LoopConfig.discover(deep));
    }

    @Test
    void rejectsAConfigWhoseProducedLogCouldNotBeFound(@TempDir Path tmp) throws Exception {
        // No {log} placeholder and no logDir: nothing could locate the output, so say so at load
        // time rather than reporting a confusing "no log produced" after every run.
        Path file = write(tmp.resolve("robot"), """
                name: demo
                run: ["./gradlew", "simulateJava"]
                scenarioDir: scenarios
                """);
        IOException e = assertThrows(IOException.class, () -> LoopConfig.load(file));
        assertTrue(e.getMessage().contains("logDir"), e.getMessage());
    }

    @Test
    void rejectsAConfigWithNothingToVerifyAgainst(@TempDir Path tmp) throws Exception {
        Path file = write(tmp.resolve("robot"), """
                name: demo
                run: ["./sim", "{log}"]
                """);
        assertTrue(assertThrows(IOException.class, () -> LoopConfig.load(file))
                .getMessage().contains("scenarioDir"));
    }

    @Test
    void rejectsAConfigWithNoRunCommand(@TempDir Path tmp) throws Exception {
        Path file = write(tmp.resolve("robot"), """
                name: demo
                scenarioDir: scenarios
                """);
        assertTrue(assertThrows(IOException.class, () -> LoopConfig.load(file))
                .getMessage().contains("'run' is required"));
    }

    @Test
    void searchesTheLogDirectoryWhenTheRunChoosesItsOwnPath(@TempDir Path tmp) throws Exception {
        LoopConfig config = LoopConfig.load(write(tmp.resolve("robot"), """
                name: demo
                run: ["./gradlew", "simulateJava"]
                logDir: logs
                scenarioDir: scenarios
                """));
        assertFalse(config.producesLogPath());
        assertEquals(tmp.resolve("robot/logs"), config.logDirPath());
    }
}
