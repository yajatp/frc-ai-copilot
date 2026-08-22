---
name: frc-copilot-start
description: Get the FRC AI Copilot running and hand the user a guided starting point. Use this whenever someone asks to get started with, set up, boot, launch, onboard onto, or "help me with" FRC Copilot / frc-ai-copilot / this repo, or asks what this tool can do or where to begin. Works on a fresh clone and on a checkout that is already fully built — it detects which and only does the missing work. Ends by opening the dashboard and printing a short menu of things to ask for next.
---

# Getting started with the FRC AI Copilot

Bring the copilot up, then hand the user a menu. **Do the work — do not print a list of
commands for the user to run.** They asked to get started, not to be given homework.

Two rules that keep this fast:

- **Detect before you build.** A built checkout needs almost nothing. Running `./gradlew build`
  on a laptop that is already built wastes a minute of a pit session; running it on a fresh clone
  is unavoidable. Step 1 tells you which you are in.
- **Never rebuild what is already there.** Every step below is a check first, an action only if
  the check fails.

## Step 1 — Read the current state

Run this from the repo root. It is read-only and answers every branch below at once:

```bash
echo "JAVA_HOME=${JAVA_HOME:-<unset>}"
ls ~/wpilib/2026/jdk/bin/java  >/dev/null 2>&1 && echo "wpilib-jdk: yes"   || echo "wpilib-jdk: NO"
python3 -c "import json,os,sys; c=json.load(open('.mcp.json'))['mcpServers']['frc-copilot']['command']; sys.exit(0 if os.path.exists(c) else 1)" 2>/dev/null \
                                                && echo "mcp-json: yes"     || echo "mcp-json: NO"
ls mcp-server/build/install/mcp-server/bin/mcp-server >/dev/null 2>&1 \
                                                && echo "built: yes"       || echo "built: NO"
ls .knowledge/frc.kdb          >/dev/null 2>&1 && echo "knowledge: yes"    || echo "knowledge: NO"
ls profiles/*.yaml             >/dev/null 2>&1 && echo "profiles: yes"     || echo "profiles: NO"
```

Report the result in one line ("Already built — profile and docs index present, bringing the
dashboard up") rather than narrating each check.

## Step 2 — Environment

Everything below needs these two exported, in **every** bash call you make — the harness does not
keep shell state between calls, so re-export them each time rather than assuming they persist:

```bash
export JAVA_HOME=~/wpilib/2026/jdk
export GRADLE_USER_HOME="$PWD/.gradle-home"
```

`GRADLE_USER_HOME` is not optional. This project keeps all Gradle state inside the checkout; without
it the build scatters caches into `~/.gradle`.

If `wpilib-jdk: NO`, stop and tell the user to install WPILib 2026 — nothing here works without it,
and it is not something you can install for them. That is the one genuine blocker in this skill.

**The repo path contains a space.** Quote every path you pass to a shell command.

## Step 3 — Build, only if `built: NO`

```bash
./gradlew build installDist --offline
```

`--offline` because the WPILib artifacts resolve from the local `~/wpilib/2026/maven`. Drop it only
if the build complains about a missing artifact. Expect a few minutes on a fresh clone and seconds
on a warm one.

If `built: yes`, skip this entirely — but do re-run `./gradlew installDist --offline` if the user
has edited Java source since, because every CLI and the MCP server run from
`*/build/install/`, not from source. A stale `installDist` means stale behavior with no error.

## Step 4 — MCP registration, only if `mcp-json: NO`

Claude Code reads `.mcp.json` at the project root. Note what the step-1 check actually tests: not
that the file exists, but that the launcher path **inside** it resolves. `.mcp.json` holds absolute
paths, so a copy of this repo on a different laptop — a different home directory, a different
checkout location — has a file that looks fine and points at nothing. Rewriting it is the fix, and
it is the single most likely thing to be wrong on a machine that is not the one it was set up on.

Write it with **absolute** paths — the launcher is spawned directly with no shell expansion, so `~`
does not resolve:

```json
{
  "mcpServers": {
    "frc-copilot": {
      "command": "<abs repo path>/mcp-server/build/install/mcp-server/bin/mcp-server",
      "args": [],
      "env": { "JAVA_HOME": "<abs home>/wpilib/2026/jdk" }
    }
  }
}
```

Then tell the user plainly: **the MCP server's 46 tools only appear after restarting Claude Code**,
and the first launch asks them to approve the project's MCP server. Until they do, use the module
CLIs in `*/build/install/*/bin/` — every tool has one, so nothing is blocked in the meantime.

Verify without a restart:

```bash
printf '%s\n' \
 '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"c","version":"1"}}}' \
 '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
 '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
 | mcp-server/build/install/mcp-server/bin/mcp-server 2>/dev/null | tail -1 | head -c 200
```

## Step 5 — Robot profile, only if `profiles: NO`

Everything else reads a profile, so analysis is about *this* robot rather than a generic one. Ask
the user for the path to their robot repository, their team number, and a robot name, then:

```bash
profile/build/install/profile/bin/profile init <robot-repo> profiles/<name>.yaml <team> <name>
```

**Read the warnings back to the user.** It flags CAN IDs the code marked `TODO` or `NOT ACCURATE`,
IDs claimed twice, and an AprilTag field layout that does not match the season. A wrong CAN ID
sends someone to check the wrong motor, so these are not boilerplate.

If profiles already exist but the robot code has changed since, offer to regenerate — the scanner
improves, and a stale profile silently under-reports CAN IDs.

## Step 6 — Docs index, only if `knowledge: NO`

`search_docs` / `search_manual` read `.knowledge/frc.kdb`, which is gitignored and built, not
committed. **This step needs the internet**, so if the user is already at an event, say so and move
on rather than stalling the rest of setup.

```bash
knowledge/build/install/knowledge/bin/knowledge sync .knowledge/frc.kdb .knowledge
```

The game manual is separate and needs the season PDF:

```bash
knowledge/build/install/knowledge/bin/knowledge manual .knowledge/frc.kdb <2026GameManual.pdf>
```

Faster than either: `.knowledge/frc.kdb` is one portable SQLite file. If another team laptop has a
good one, copying it over is the whole job.

## Step 7 — Bring the dashboard up and open it

Always finish here. Pick the mode from what the user is doing:

```bash
# On the bench with no robot — a built-in simulated robot, good for demos and first runs
dashboard/build/install/dashboard/bin/dashboard --sim --open

# Connected to the real robot (derives 10.TE.AM.2 from the team number)
dashboard/build/install/dashboard/bin/dashboard --team 6369 --open
```

`--open` launches the browser at `http://localhost:5800` once the server is actually serving.

Run it **in the background** — it blocks until killed, and a foreground call will hang the turn.
Then confirm it is really up before telling the user it is:

```bash
curl -s -o /dev/null -w '%{http_code}\n' --max-time 5 http://localhost:5800/
```

If the user is analysing logs, add `--db trends.db` so the dashboard and the log watcher share one
database. If port 5800 is taken, pass `--port`.

## Step 8 — Hand them the menu

Close with this, adapted to what you actually found. Keep it short — it is a menu, not a manual.

> **The dashboard is open at http://localhost:5800.** It is live: health tiles turn WATCH and
> CRITICAL on their own as voltage sags and the CAN error count climbs.
>
> Things worth asking me for:
>
> **Between matches** — *"Run Mode A on this log"* is the whole pit workflow: brownout, battery,
> CAN and loop timing in one pass, in the gap between two matches. *"Watch the USB drive for new
> logs"* leaves that running automatically for the rest of the event.
>
> **Reading a match** — *"Why did we brown out in Q14?"*, *"Compare our loop times across the last
> five matches"*, *"Was vision dropping out during auto?"* Every answer carries a sample count and
> a confidence level, and hedges when one match cannot support a verdict.
>
> **Autonomous** — *"Nudge the third waypoint 20 cm left"* or *"Slow this path to 80%."* Edits come
> back as a reviewable diff and are dry-run by default; the original file is never overwritten.
>
> **Robot code** — *"Make this auto score five and prove it"* runs the closed loop: edit, build, run
> in simulation, check against the success criteria, and iterate until they pass.
>
> **Rules and docs, offline** — *"What is the weight limit with bumpers?"* is answered from the game
> manual with a page number, and API questions from the vendor docs. No network, which matters in a
> pit.
>
> **Live robot** — *"What is the battery voltage right now?"* reads NetworkTables directly.
>
> Two things to know: the live connection is **read-only** except for a small whitelist of tuning
> values, so nothing here can move the robot. And nothing writes to a file without showing you the
> diff first.

## Things that look like bugs and are not

- **"No knowledge index"** — step 6 has not been run on this laptop. Expected on a fresh clone.
- **An empty closed-loop history** — `.loop/` is gitignored local state. A fresh checkout has none.
- **The first closed-loop build against the user's own robot project is slow and needs the
  internet** — it resolves that project's vendordeps (PathPlanner, AdvantageKit, Phoenix 6), which
  are not in WPILib's offline repo. Cached after one build. Worth doing before an event, not at one.
- **MCP tools missing right after writing `.mcp.json`** — Claude Code needs a restart and an
  approval prompt. Not a broken install.
