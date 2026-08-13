# Getting started

For a teammate who just cloned this and wants it running. Read it top to bottom once; most of the
surprises are in the first three sections.

[docs/SETUP.md](docs/SETUP.md) is the reference version of the build and editor registration — this
page is the walkthrough, and it covers the things that look like bugs but aren't.

## 1. Install WPILib 2026

The build needs it, and not just for convenience: it provides the JDK **and** an offline Maven
repository holding every WPILib artifact this project compiles against. Without it, the build cannot
resolve its dependencies.

Get it from WPILib's own installer (the same one you'd use for a robot project). It lands in
`~/wpilib/2026`.

## 2. Two environment variables, every time

```bash
export JAVA_HOME=~/wpilib/2026/jdk
export GRADLE_USER_HOME="$PWD/.gradle-home"
```

`JAVA_HOME` points at the JDK WPILib installed.

`GRADLE_USER_HOME` is **a hard project rule, not a preference: all Gradle state stays inside the
project folder.** Never let it fall back to `~/.gradle`. Every cache, daemon, and wrapper download
belongs in `.gradle-home/` inside the checkout, which is gitignored. This keeps the project
self-contained and reproducible, and means deleting the folder actually removes everything.

Worth knowing: the closed loop spawns its own Gradle builds, so `example-robot/loop.yaml` sets
`GRADLE_USER_HOME` in its `env:` block and passes `JAVA_HOME` down from the running JVM. That is
deliberate — an MCP server launched by an editor inherits no shell profile, so without it a spawned
build would escape to `~/.gradle`. Keep it if you touch those files.

Consider putting both exports in a shell function or direnv file so you never forget them.

## 3. Build

```bash
./gradlew build          # compile + test every module
```

That should be green. If it is, you have a working checkout.

## 4. Three things that look broken and are not

These are the ones that trip people up. All three are gitignored local state, which is correct — they
are data or scratch, not source.

**Documentation search says "No knowledge index".** That is the expected state of a fresh checkout,
not a bug. `.knowledge/frc.kdb` is built, not committed. Build it once (takes a couple of minutes,
and needs network the first time since it clones the docs):

```bash
./gradlew :knowledge:installDist
knowledge/build/install/knowledge/bin/knowledge sync .knowledge/frc.kdb .knowledge
knowledge/build/install/knowledge/bin/knowledge manual .knowledge/frc.kdb <game-manual.pdf>
```

After that it works fully offline, which is the point — a pit laptop has no usable wifi.

**The closed loop has no history.** `.loop/` holds per-iteration logs, the iteration journal, and the
adopted baseline. A fresh checkout starts with none of it. Expected.

**There are no logs to analyze.** `.wpilog` files are gitignored. Generate a synthetic match:

```bash
./gradlew :core-ingest:installDist
core-ingest/build/install/core-ingest/bin/core-ingest gen demo.wpilog
```

It deliberately contains a brownout, a CAN error burst, loop overruns and vision dropouts, so every
analysis primitive has something real to report.

## 5. Register the MCP server with your editor

This is what makes the tools available to your AI assistant. There is an installer per editor:

| Editor | Installer |
|---|---|
| VS Code | [`vscode-extension/`](vscode-extension/) — an extension that builds and registers the server |
| Cursor | [`cursor-integration/`](cursor-integration/) |
| Codex | [`codex-integration/`](codex-integration/) |
| Antigravity | [`antigravity-integration/`](antigravity-integration/) |

Each has its own README. [docs/SETUP.md](docs/SETUP.md) covers manual registration, the stdio
transport, and how to verify the server is actually connected.

Sanity check without any editor involved — the server speaks JSON-RPC on stdin/stdout:

```bash
./gradlew :mcp-server:installDist
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  | mcp-server/build/install/mcp-server/bin/mcp-server
```

You should get a tool list back. `get_guide` is the tool to call first from an assistant — it
describes the intended workflow.

## 6. See it actually do something

```bash
# The local dashboard, against a built-in simulated robot.
./gradlew :dashboard:run --args="--sim"        # http://localhost:5800

# One turn of the closed loop against a real headless robot (~1 s).
./gradlew :simreplay:installDist
simreplay/build/install/simreplay/bin/simreplay iterate example-robot/loop.yaml
```

That second one is the best single smoke test in the repo: it builds robot code, runs it on simulated
physics through the real command scheduler, and verifies the log it produced.

## Finding your way around

Each module is one concern, and the dependency direction runs left to right:

```
                 ┌──► analysis ──► modes ────┐
                 ├──► profile               ├──► mcp-server
core-ingest ─────┼──► write-layer           │    dashboard
                 ├──► live-nt               │
                 ├──► simreplay ────────────┤
                 └──► smallmodel ───────────┘
knowledge  (standalone) ──────────────────────► mcp-server
```

- `core-ingest` — log parsing and the trend store. Everything that reads logs goes through it.
- `analysis` — the primitives. Pure functions over `Series` (parallel value/timestamp arrays), which
  is why the same code analyzes a `.wpilog` and a live NetworkTables window.
- `knowledge` — deliberately depends on nothing: it is a text index, unrelated to log parsing.
- `mcp-server` / `dashboard` — the two front ends. Neither holds analysis logic of its own.
- `example-robot` — **not** a dependency of anything. It is a standalone robot project that the
  closed loop builds and runs as a subprocess, exactly as it would a real team's repo. That is what
  makes it a genuine test of the loop rather than an in-process fake.

## House style

Worth matching, because the codebase is consistent about it:

- **Comments explain *why*, not *what*.** If a line needs a comment to say what it does, the line
  probably wants rewriting instead. The valuable comments here record a decision and its reason —
  they are what stops someone "simplifying" a deliberate choice six months later.
- **Tool descriptions are written for an agent reading them cold**, with no other context. Say what
  the tool is for, what the arguments mean in robot terms, and what the result does *not* prove.
- **Claims get verified, not asserted.** Run the thing. A test that silently passes when its data is
  missing is worse than no test — use `assumeTrue` so a skip is visible.
- **Hedge honestly.** Analysis output carries its confidence, and write tools stay dry-run by default.
  Both are load-bearing, not decoration.

## Before you push

```bash
./gradlew build
```

Keep it green and don't let the test count regress. Commit in coherent chunks, and write commit
messages that explain the reasoning rather than restating the diff.
