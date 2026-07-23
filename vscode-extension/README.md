# FRC AI Copilot (VS Code extension)

A small VS Code extension that helps you build and register the **FRC AI Copilot MCP
server** — the project's stdio JSON-RPC MCP server
(`org.mercsmavs.frccopilot.mcp.McpServer`) — with an MCP-capable client such as Claude Code
or GitHub Copilot.

This extension does not implement the MCP protocol itself and does not run the server in
the background. It automates the two fiddly parts described in `../docs/SETUP.md`:

1. Building the server with the right JDK (`./gradlew :mcp-server:installDist`, run with
   `JAVA_HOME` pointed at the WPILib JDK).
2. Generating the exact `.mcp.json` snippet (with absolute, machine-specific paths) that
   Claude Code / Copilot needs to launch it.

## Install

From this directory (`vscode-extension/`):

```bash
npm install
npm run compile
```

`npm install` installs dev dependencies locally into `vscode-extension/node_modules` (never
globally). `npm run compile` runs `tsc` and emits JavaScript into `vscode-extension/out/`.

To iterate on the extension, use `npm run watch` instead, and press F5 in VS Code (with
this folder open) to launch an Extension Development Host.

There is no published `.vsix` package yet; if you want one, install `@vscode/vsce` as a dev
dependency and run `npx vsce package` after compiling.

## What it does

The extension contributes three commands (Command Palette → search "FRC AI Copilot"):

- **`FRC AI Copilot: Build MCP Server`** (`frcCopilot.buildServer`) — locates the project
  root (the parent of this `vscode-extension/` folder, or the open workspace folder,
  whichever contains `gradlew`/`settings.gradle`), locates the WPILib JDK, and runs
  `./gradlew :mcp-server:installDist` via `child_process.spawn`, streaming output to an
  "FRC AI Copilot" output channel. Sets `JAVA_HOME` to the located JDK and
  `GRADLE_USER_HOME` to `<project root>/.gradle-home`, matching `docs/SETUP.md`.

- **`FRC AI Copilot: Show .mcp.json Config`** (`frcCopilot.showConfig`) — computes the
  absolute path to the built launcher script
  (`mcp-server/build/install/mcp-server/bin/mcp-server`, or `mcp-server.bat` on Windows)
  and the absolute path to the WPILib JDK, then opens a ready-to-save `.mcp.json` snippet
  in a new editor tab and copies it to the clipboard. Warns if the launcher hasn't been
  built yet.

- **`FRC AI Copilot: Locate WPILib JDK`** (`frcCopilot.locateJdk`) — scans `~/wpilib/*/jdk`
  and reports the newest year that has a `jdk` subdirectory (e.g. `~/wpilib/2026/jdk`), or
  warns if none is found.

The extension activates on startup (`onStartupFinished`) so the commands are always
available; it has no persistent background process and nothing to clean up in
`deactivate()`.

## Manual `.mcp.json` fallback

If you'd rather not use the extension, build and register the server by hand (see
`../docs/SETUP.md` for the full explanation):

```bash
cd /path/to/frc-ai-copilot
export JAVA_HOME=~/wpilib/2026/jdk
export GRADLE_USER_HOME="$PWD/.gradle-home"
./gradlew :mcp-server:installDist
```

Then create (or merge into) a `.mcp.json` at the **project root**:

```json
{
  "mcpServers": {
    "frc-copilot": {
      "command": "/absolute/path/to/frc-ai-copilot/mcp-server/build/install/mcp-server/bin/mcp-server",
      "env": {
        "JAVA_HOME": "/absolute/path/to/wpilib/2026/jdk"
      }
    }
  }
}
```

Both `command` and `JAVA_HOME` must be absolute paths — Claude Code spawns the process
directly without shell expansion, so `~` won't resolve. Adjust the paths for wherever the
repo and WPILib are actually installed on your machine.

Alternatively, register it via the CLI, which writes an equivalent config:

```bash
claude mcp add frc-copilot \
  --env JAVA_HOME=/absolute/path/to/wpilib/2026/jdk \
  -- "/absolute/path/to/frc-ai-copilot/mcp-server/build/install/mcp-server/bin/mcp-server"
```

After registering, restart/open Claude Code and run `claude mcp list` (or `/mcp` in an
interactive session) to confirm `frc-copilot` shows as connected.
