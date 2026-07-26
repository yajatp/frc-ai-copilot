# Setup — building `frc-ai-copilot` and registering it with Claude Code

This project ships as a Java multi-module Gradle build (`core-ingest`, `profile`, `analysis`,
`write-layer`, `mcp-server`, ...) whose `mcp-server` module is a stdio JSON-RPC MCP server
(`org.mercsmavs.frccopilot.mcp.McpServer`). This doc covers building it and wiring it into
Claude Code as an MCP server.

## Prerequisites

- **The WPILib 2026 install.** Not just any JDK — the build depends on WPILib's bundled JDK and
  its offline Maven repo (`~/wpilib/2026/maven`, referenced directly in the root `build.gradle`)
  for `DataLogReader`, `wpimath`, `ntcore`, and the AdvantageKit/CTRE/PathPlanner artifacts. Install
  WPILib 2026 first (the standard WPILib installer) if you haven't.
- The JDK lives at `~/wpilib/2026/jdk` (JDK 17). `gradle.properties` already pins
  `org.gradle.java.home` to this path for the Gradle daemon itself — but the **installed launcher
  script** (`bin/mcp-server`) resolves Java independently at run time, so anything that spawns it
  (like Claude Code) needs `JAVA_HOME` set explicitly. See the `.mcp.json` example below.

## Build

Always export `GRADLE_USER_HOME` first so Gradle's cache/wrapper/daemon state stays inside the
project instead of `~/.gradle` — this project is meant to be fully self-contained per-checkout:

```bash
cd /path/to/frc-ai-copilot
export JAVA_HOME=~/wpilib/2026/jdk
export GRADLE_USER_HOME="$PWD/.gradle-home"

./gradlew build                    # compile + test every module
./gradlew :mcp-server:installDist  # build the MCP server's runnable distribution
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
./gradlew :analysis:installDist
analysis/build/install/analysis/bin/analysis analyze sample-data/some.wpilog
```

## Registering with Claude Code

MCP servers are configured via a `.mcp.json` file. For a project used by both teams, put it at
the **project root** so it's shared by anyone who checks out the repo:

`.mcp.json`:
```json
{
  "mcpServers": {
    "frc-copilot": {
      "command": "/Users/yajatparmar/code/6369/frc ai copilot/mcp-server/build/install/mcp-server/bin/mcp-server",
      "env": {
        "JAVA_HOME": "/Users/yajatparmar/wpilib/2026/jdk"
      }
    }
  }
}
```

Notes on this config:
- **Use an absolute path for `command`.** Claude Code spawns the process directly (no shell
  expansion), so `~` and relative paths won't resolve — write out the real path to
  `bin/mcp-server` inside the `installDist` output. Adjust the `/Users/yajatparmar/...` prefix to
  wherever the repo is actually checked out on each machine (6369's and 6773's checkouts will
  differ).
- **`JAVA_HOME` is required in `env`**, not optional. The generated launcher script is a thin
  wrapper that execs `$JAVA_HOME/bin/java` (falling back to whatever `java` is on `PATH`
  otherwise). Claude Code launches MCP servers with a minimal environment, so unless `JAVA_HOME`
  is set here, the server may fail to start or start with the wrong JDK. Point it at the same
  WPILib JDK used to build (it must be able to load the WPILib-derived classes on the classpath).
- Alternatively, register the same server from the CLI, which writes an equivalent config:
  ```bash
  claude mcp add frc-copilot \
    --env JAVA_HOME=/Users/yajatparmar/wpilib/2026/jdk \
    -- "/Users/yajatparmar/code/6369/frc ai copilot/mcp-server/build/install/mcp-server/bin/mcp-server"
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
4. Confirm the full tool list is visible: `log_info`, `log_entries`, `read_entry`, `ingest_log`,
   `power_analysis`, `can_health`, `profile_show`, `pathplanner_show`, `pathplanner_fudge`,
   `pathplanner_set_speed`.

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
