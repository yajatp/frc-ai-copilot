---
name: frc-pathplanner
description: Working with PathPlanner autonomous routines in FRC — .path and .auto file structure, waypoints and constraints, event markers, and the between-match tuning workflow. Load this when editing or diagnosing an autonomous routine, when an auto scores less than expected, or when proposing waypoint/speed adjustments at a competition.
---

# PathPlanner

**Verify API and file-format specifics with `search_docs source=pathplanner`.** The file schema
has changed across PathPlanner versions; this copilot's `write-layer` reads the version field and
you should too.

## Why this skill matters more than the others

Teams do not recompile robot code at competition. They tweak autonomous paths. This is the single
highest-leverage thing an AI copilot can help with between matches, and it is the reason this
project's `write-layer` exists: PathPlanner edits as reviewable diffs, dry-run by default, with
the original file never overwritten.

## The two file types

- **`.path`** — one trajectory: a list of waypoints (anchor points with control handles), plus
  per-path and global constraints (max velocity, max acceleration).
- **`.auto`** — a routine that *references* paths by name and sequences them with commands and
  event markers. Swapping which path an auto runs is an `.auto` edit, not a `.path` edit.

Knowing which file to change is most of the diagnosis. "The auto drives the wrong way" is usually
a `.path` problem; "the auto does the right things in the wrong order" is an `.auto` problem.

## The between-match workflow (Mode A)

This is the competition loop this copilot is built for:

```
1. mode_a           file=<match.wpilog> db=<trends.db>    # safety first: brownout/CAN/battery
2. auto_show        auto=<routine.auto>                   # what paths does this routine run?
3. pathplanner_show path=<segment.path>                   # waypoints + constraints
4. pathplanner_fudge path=<segment.path> index=<n> dx=<m> dy=<m>    # DRY RUN — prints a diff
5. (human reviews the diff)
6. pathplanner_fudge ... out=<newfile.path>               # only now does anything get written
```

**Run the safety pass first.** If the robot browned out during auto, the path is not the problem
and moving waypoints will not fix it — the robot did not have the energy to follow the path it
already had. Changing the path in that situation makes things worse by hiding the real fault.

**Every write tool is dry-run unless `out` is given, and never overwrites the original.** Keep it
that way. At a competition, an un-reviewed automated edit to an auto routine is how a working
15-second routine becomes a zero.

## Diagnosing an auto that underperformed

Ask what actually happened before deciding what to change:

```
log_entries  file=<match.wpilog> filter=odometry     # find the pose signals
analyze_cycles file=<match.wpilog>                   # did it score at all, and how fast?
loop_timing  file=<match.wpilog>                     # overruns wreck trajectory following
power_analysis file=<match.wpilog>                   # brownout during auto?
swerve_analysis file=<match.wpilog>                  # oscillating modules = tuning, not pathing
```

Common causes, in the order they are usually true:

1. **Energy** — brownout or a tired battery. Check `power_analysis` and `battery_health` first.
2. **Loop overruns** — a robot that misses its 20 ms deadline cannot follow a trajectory
   accurately. `loop_timing` finds this immediately.
3. **Starting pose** — the robot was not placed where the path assumes. No waypoint edit fixes a
   placement error; it just moves the error somewhere else.
4. **Odometry drift** — compare the commanded pose to the measured pose over the run.
5. **Actual path geometry** — the least common cause, and the first one everybody reaches for.

## Constraints

Lowering max velocity/acceleration is often the right competition fix: a slower auto that scores
beats a faster one that misses. `pathplanner_set_speed` proposes exactly this as a reviewable
diff. Raising them without checking `power_analysis` first is how you create a brownout.

## Event markers

Markers fire commands at points along a path. When an auto does the right things at the wrong
times, suspect marker placement before suspecting the trajectory. `auto_show` lists what a routine
references so you can see the intended sequence.

## Related

- `skills/frc-copilot-usage/SKILL.md` — full tool sequencing for Mode A and Mode B
- `skills/frc-sim-in-the-loop/SKILL.md` — verifying an auto change in sim before trusting it
