# Robot profiles

A **profile** is a team-agnostic description of one robot that makes the copilot's analysis
robot-specific. It is mostly *bootstrapped* from a team's existing repo (nothing is re-entered by
hand that already lives in the code), then human-reviewed.

## Files
- `6369-echo.yaml` — Team 6369's 2026 competition robot "Echo" (pure CTRE Phoenix 6).
- `6773-alphabot.yaml` — Team 6773's "AlphaBot" (mixed CTRE + REV) — proves one schema serves both.

## Schema (see `RobotProfile.java`)
- `team`, `robot`, `season`, `game`
- `vendors` — detected from `vendordeps/*.json` (CTRE / REV / AdvantageKit / PathPlanner / …)
- `drivetrain` — mass, MOI, trackwidth, wheel radius, gearing, max speed, drive motor, current
  limit, wheel COF, robot dimensions, module offsets — parsed from `pathplanner/settings.json`
- `devices` — CAN id → `{label, subsystem, vendor, source, accurate}`, scanned from `*Constants.java`.
  `accurate: false` means the source comment flagged it (`NOT ACCURATE` / `TODO`) — **review these first.**
- `subsystems` — from `src/main/java/frc/robot/subsystems/*`
- `field` — game/season + the AprilTag layout the code loads (flagged if it looks stale)
- `notes` — advisory warnings surfaced during generation

## Regenerate
```bash
export JAVA_HOME=~/wpilib/2026/jdk
export GRADLE_USER_HOME="$PWD/.gradle-home"
./gradlew :profile:installDist
profile/build/install/profile/bin/profile init <robot-repo-dir> <out.yaml> [team] [robot] [game]
```

## Notes
- The CAN-ID scanner is deliberately **best-effort** (regex over `*Constants.java`), not a full Java
  parser. When a team's constants don't use the common `<id>, // …CAN ID` / `= <id>; // …` shapes,
  it recovers nothing and says so (see 6773 AlphaBot, `device count: 0`) rather than guessing — fill
  those in by hand or extend the scanner for that team's convention.
- Field/game data (`field:`) is intentionally separable from the robot profile so it can be shared
  and updated independently.
