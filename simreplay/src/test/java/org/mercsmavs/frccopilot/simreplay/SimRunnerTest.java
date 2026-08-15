package org.mercsmavs.frccopilot.simreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Executable resolution for the loop's run/build commands.
 *
 * <p>These assertions are platform-independent on purpose. The bug they guard against only shows up
 * on Windows — {@code CreateProcess} looks the program up relative to the calling process, not the
 * child's working directory — so a test that only ran there would be a test nobody on this team ever
 * runs. Checking the argv we hand to {@code ProcessBuilder} catches it everywhere.
 */
class SimRunnerTest {

    @Test
    void absolutizesARelativeCommandAgainstTheWorkingDirectory(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("robot"));
        Path script = project.resolve("build.sh");
        Files.writeString(script, "#!/bin/sh\nexit 0\n");

        List<String> resolved = SimRunner.resolveExecutable(project, List.of("./build.sh", "--flag"));

        assertEquals(script.toAbsolutePath().toString(), resolved.get(0),
                "a relative script must be absolutized, or Windows cannot find it");
        assertEquals(List.of("--flag"), resolved.subList(1, resolved.size()),
                "arguments must be passed through untouched");
    }

    @Test
    void resolvesACommandWithNoLeadingDotSlashToo(@TempDir Path tmp) throws Exception {
        // The natural form on Windows, where "./" is not idiomatic.
        Path project = Files.createDirectories(tmp.resolve("robot"));
        Path script = project.resolve("build.bat");
        Files.writeString(script, "@echo off\r\nexit /b 0\r\n");

        List<String> resolved = SimRunner.resolveExecutable(project, List.of("build.bat"));
        assertEquals(script.toAbsolutePath().toString(), resolved.get(0));
    }

    @Test
    void leavesAToolFromThePathAlone(@TempDir Path tmp) throws Exception {
        // `gradle`, `npm`, `python` — nothing by that name is in the project, so the OS should
        // search PATH exactly as it would without us.
        Path project = Files.createDirectories(tmp.resolve("robot"));
        List<String> command = List.of("gradle", "build");
        assertEquals(command, SimRunner.resolveExecutable(project, command));
    }

    @Test
    void leavesAnAbsoluteCommandAlone(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("robot"));
        String absolute = tmp.resolve("elsewhere/run").toAbsolutePath().toString();
        List<String> command = List.of(absolute, "{log}");
        assertEquals(command, SimRunner.resolveExecutable(project, command));
    }

    @Test
    void aDirectoryIsNotAnExecutable(@TempDir Path tmp) throws Exception {
        // A project with a `build/` directory must not have `build` rewritten to point at it.
        Path project = Files.createDirectories(tmp.resolve("robot"));
        Files.createDirectories(project.resolve("build"));
        List<String> command = List.of("build");
        assertEquals(command, SimRunner.resolveExecutable(project, command));
    }

    @Test
    void anEmptyCommandIsHandled(@TempDir Path tmp) throws Exception {
        assertTrue(SimRunner.resolveExecutable(tmp, List.of()).isEmpty());
    }
}
