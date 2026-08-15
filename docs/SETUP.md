# Setup — building `frc-ai-copilot` and registering it with Claude Code

This project ships as a Java multi-module Gradle build (`core-ingest`, `profile`, `analysis`,
`write-layer`, `mcp-server`, ...) whose `mcp-server` module is a stdio JSON-RPC MCP server
(`org.mercsmavs.frccopilot.mcp.McpServer`). This doc covers building it and wiring it into
Claude Code as an MCP server.

## Supported platforms

**Linux, macOS and Windows**, on x64 or ARM. ntcore, the HAL and wpiutil are JNI libraries whose
native binaries differ per platform; the build selects the right ones from `os.name`/`os.arch` (see
`wpiNative` in the root `build.gradle`), so there is nothing to configure. CI builds and tests all
three on every push.

| | JDK | WPILib Maven repo | Launcher |
|---|---|---|---|
| Linux | `~/wpilib/2026/jdk` | `~/wpilib/2026/maven` | `bin/mcp-server` |
| macOS | `~/wpilib/2026/jdk` | `~/wpilib/2026/maven` | `bin/mcp-server` |
| Windows | `%USERPROFILE%\wpilib\2026\jdk` | `%USERPROFILE%\wpilib\2026\maven` | `bin\mcp-server.bat` |

On Windows, WPILib's installer may place things under the shared **Public** profile
(`C:\Users\Public\wpilib\2026`) instead of your own — the editor installers check both.

## Prerequisites

- **The WPILib 2026 install.** Not just any JDK — the build depends on WPILib's bundled JDK and its
  offline Maven repo (`~/wpilib/2026/maven`) for `DataLogReader`, `wpimath`, `ntcore`, and the
  CTRE/PathPlanner artifacts. Install WPILib 2026 first (the standard WPILib installer).
  - If the local repo is absent, the build falls back to `frcmaven.wpi.edu` for the same artifacts,
    which is how CI builds with no WPILib install at all. That path needs network.
- The JDK lives at `~/wpilib/2026/jdk` (JDK 17), and the build takes it from **`JAVA_HOME`**.
  `gradle.properties` deliberately does *not* pin `org.gradle.java.home`: it used to hardcode one
  contributor's absolute home directory, which meant the project built on exactly one machine. If
  you would rather not export it per shell, set `org.gradle.java.home` in your own
  `~/.gradle/gradle.properties` — a user-level file outside the repo — rather than in the committed
  one.
- The **installed launcher script** resolves Java independently at run time, so anything that spawns
  it (like Claude Code) needs `JAVA_HOME` set explicitly. See the `.mcp.json` example below.

## Build

Always set `GRADLE_USER_HOME` first so Gradle's cache/wrapper/daemon state stays inside the project
instead of `~/.gradle` — this project is meant to be fully self-contained per-checkout:

```bash
# Linux / macOS
cd /path/to/frc-ai-copilot
export JAVA_HOME=~/wpilib/2026/jdk
export GRADLE_USER_HOME="$PWD/.gradle-home"

./gradlew build                    # compile + test every module
./gradlew :mcp-server:installDist  # build the MCP server's runnable distribution
```

```powershell
# Windows (PowerShell)
cd C:\path\to\frc-ai-copilot
$env:JAVA_HOME = "$HOME\wpilib\2026\jdk"
$env:GRADLE_USER_HOME = "$PWD\.gradle-home"

.\gradlew.bat build
.\gradlew.bat :mcp-server:installDist
```

`installDist` (the Gradle `application` plugin task) produces:

```
mcp-server/build/install/mcp-server/
  bin/mcp-server        # POSIX launcher script (what Claude Code will run)
  bin/mcp-server.bat    # Windows launcher
  lib/*.jar             # mcp-server + core-ingest + profile + analysis + write-layer + deps
```

Re-run `./gradlew :mcp-server:installDist` any time the module's code (or one of its
dependencies — `core-ingest`, `profile`, `analysis`, `write-layer`) changes; Claude Code always
runs the installed launcher script, not the source tree, so a stale `installDist` means stale
tool behavior even after you edit Java files.

You can sanity-check the module builds in isolation the same way as any other module in this
repo, e.g.:

```bash
./gradlew :core-ingest:installDist :analysis:installDist
core-ingest/build/install/core-ingest/bin/core-ingest gen demo.wpilog   # synthetic 150 s match
analysis/build/install/analysis/bin/analysis full demo.wpilog
```

Real `.wpilog` files are gitignored (they are data, not source), so `gen` is how you get something
to point the analysis tools at on a fresh checkout. The generated match deliberately contains a
brownout, a CAN error burst, loop overruns, and vision dropouts, so every primitive has something
to report.

## Building the knowledge index (documentation + game manual search)

The `search_docs` and `search_manual` tools read a local index. It is **not** checked in — it is
built from vendor documentation repositories, and it is one portable SQLite file you can build
once and copy to every laptop.

```bash
./gradlew :knowledge:installDist
K=knowledge/build/install/knowledge/bin/knowledge

# Clone + index WPILib, CTRE Phoenix 6, PhotonVision and PathPlanner docs.
# Uses shallow, sparse clones — it pulls the docs, not entire product repos.
$K sync .knowledge/frc.kdb .knowledge

$K status .knowledge/frc.kdb        # what got indexed
$K search .knowledge/frc.kdb "how do I desaturate swerve wheel speeds"
$K ask    .knowledge/frc.kdb ctre "how do I set a stator current limit"
```

Typical result: roughly 3,600 chunks in a ~6 MB file, built in a couple of minutes.

**Game manual.** Download the season's manual PDF, then:

```bash
$K manual .knowledge/frc.kdb ~/Downloads/2026-game-manual.pdf
```

Manual results carry the **page number** the passage came from, so a cited rule can be checked.

**REV documentation** is not included: REV publishes no public documentation repository. If your
team keeps a local copy, index it like any other folder and it becomes searchable alongside the
rest:

```bash
$K index .knowledge/frc.kdb rev ~/Documents/rev-docs
```

The MCP tools default to `.knowledge/frc.kdb` relative to the working directory; every knowledge
tool also accepts an explicit `db` argument if you keep the index elsewhere.

Search is lexical (SQLite FTS5, bm25-ranked) rather than embedding-based — deliberately, so it
works with no model download and no network on a pit laptop with dead wifi, which is exactly when
you need it most.

## Registering with Claude Code

**The quickest way is not to hand-write this at all.** There is an installer per editor that builds
the server and registers it for you, with the platform-correct launcher path already filled in:

```bash
cd cursor-integration      && node install.js   # or codex-integration, antigravity-integration
```

For VS Code, use the extension in `vscode-extension/`. The rest of this section is what those
installers do, for Claude Code or if you would rather configure it by hand.

MCP servers are configured via a `.mcp.json` file. For a project used by both teams, put it at
the **project root** so it's shared by anyone who checks out the repo:

`.mcp.json` (Linux / macOS — substitute your own checkout and home directory):
```json
{
  "mcpServers": {
    "frc-copilot": {
      "command": "/home/you/code/frc-ai-copilot/mcp-server/build/install/mcp-server/bin/mcp-server",
      "env": {
        "JAVA_HOME": "/home/you/wpilib/2026/jdk"
      }
    }
  }
}
```

On Windows, both paths change — the launcher is the `.bat`, and JSON needs the backslashes escaped:
```json
{
  "mcpServers": {
    "frc-copilot": {
      "command": "C:\\Users\\you\\code\\frc-ai-copilot\\mcp-server\\build\\install\\mcp-server\\bin\\mcp-server.bat",
      "env": {
        "JAVA_HOME": "C:\\Users\\you\\wpilib\\2026\\jdk"
      }
    }
  }
}
```

Notes on this config:
- **Use an absolute path for `command`.** Claude Code spawns the process directly (no shell
  expansion), so `~`, `%USERPROFILE%` and relative paths won't resolve — write out the real path to
  the launcher inside the `installDist` output. Every machine's checkout differs, so this is the one
  value nobody can copy verbatim.
- **`JAVA_HOME` is required in `env`**, not optional. The generated launcher script is a thin
  wrapper that execs `$JAVA_HOME/bin/java` (falling back to whatever `java` is on `PATH`
  otherwise). Claude Code launches MCP servers with a minimal environment, so unless `JAVA_HOME`
  is set here, the server may fail to start or start with the wrong JDK. Point it at the same
  WPILib JDK used to build (it must be able to load the WPILib-derived classes on the classpath).
- Alternatively, register the same server from the CLI, which writes an equivalent config:
  ```bash
  claude mcp add frc-copilot \
    --env JAVA_HOME="$HOME/wpilib/2026/jdk" \
    -- "$PWD/mcp-server/build/install/mcp-server/bin/mcp-server"
  ```
  Add `--scope user` if you want it available across every project on your machine instead of
  just this one.

### Stdio transport, briefly

`mcp-server` speaks the slice of MCP that Claude Code actually uses, over stdin/stdout, one
JSON-RPC message per line:

- `initialize` — protocol handshake; the server replies with its name/version and an
  `instructions` string ("Call `get_guide` first, then `profile_show` for the team's robot.").
- `notifications/initialized` — no reply (it's a notification).
- `tools/list` — returns every tool from `ToolRegistry` with its name, description, and JSON
  input schema.
- `tools/call` — invokes one tool; on failure the *result* carries `isError: true` with a text
  explanation rather than a JSON-RPC protocol error (so a bad log path or missing signal is
  reported back to the model as ordinary tool output, not a crash).
- `ping` — health check.

Nothing is written to stdout except these JSON-RPC response lines — if you need to debug the
server, log to stderr, not stdout, or you'll corrupt the protocol stream.

### Verifying it's registered

1. Restart/open Claude Code in this project (or wherever you registered it).
2. Run `claude mcp list` — `frc-copilot` should show as connected. (Inside an interactive
   session, `/mcp` shows the same thing.)
3. Ask Claude Code to call the `get_guide` tool (or just ask a question that would naturally use
   it, e.g. "what does the FRC copilot server do?"). You should get back the workflow text
   embedded in `ToolRegistry.guide()`.
4. Confirm the tool list is visible — 44 tools at the time of writing, including `profile_init`,
   `log_info`, `power_analysis`, `mode_a`, `pathplanner_show`, `loop_iterate` and `search_docs`.
   Re-check `tools/list` rather than trusting this list; it grows.

**If the server doesn't show up / tools are missing:**
- Double check `command` is an absolute path and the file is executable (`chmod +x` if needed —
  `installDist` normally sets this, but a copied/moved file may lose it).
- Confirm `JAVA_HOME` in `env` actually contains `bin/java` (`$JAVA_HOME/bin/java -version`).
- Confirm you ran `./gradlew :mcp-server:installDist` (not just `./gradlew build`) after the last
  code change — `build` compiles and tests but doesn't necessarily refresh the `installDist`
  output on every invocation depending on what changed.
- Try running the launcher directly in a terminal (`JAVA_HOME=... bin/mcp-server`) and pasting a
  raw `initialize` JSON-RPC line at it — a hung or immediately-exiting process points at a Java
  version mismatch or a missing dependency jar.

## Related docs

- `skills/frc-reference/SKILL.md` — the FRC/AdvantageKit conventions this project assumes.
- `skills/frc-copilot-usage/SKILL.md` — how to actually use the MCP tools (Mode A / Mode B).
- `skills/frc-scaffold/SKILL.md` — scaffolding a new subsystem consistent with a team's profile.
- `profiles/README.md` — how robot profiles are generated/regenerated (`profile init`).
- `../CONTRIBUTING.md` — the walkthrough for a teammate setting this up for the first time.
- `CLOSED-LOOP.md` — the edit/build/run/verify/iterate cycle, sim and replay.
