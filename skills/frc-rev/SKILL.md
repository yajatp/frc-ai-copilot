---
name: frc-rev
description: Working with REV Robotics hardware in FRC robot code — SPARK MAX and SPARK Flex controllers, NEO/NEO Vortex motors, the Power Distribution Hub, and REVLib configuration/persistence patterns. Load this when writing or reviewing code that drives REV motor controllers, when diagnosing SPARK brownouts, current limits, or lost configuration, or when reading a .revlog alongside a .wpilog.
---

# REV Robotics (REVLib, SPARK MAX / SPARK Flex)

**REV publishes no public documentation repository**, so unlike the other vendors its docs are not
in this copilot's `search_docs` index by default. If your team downloads a local copy of the REV
docs, index it with `knowledge index <db> rev <folder>` and it becomes searchable like the rest.
Until then, verify API specifics against the REVLib Javadoc shipped with the vendordep rather than
from memory.

## The mental model that matters

1. **Configuration can persist to the controller's flash.** This is REV's most distinctive and
   most dangerous behavior: a SPARK remembers settings across power cycles. A robot can work
   because of a setting somebody flashed months ago, and a replacement controller then behaves
   completely differently with identical code.
2. **Closed-loop control runs on the controller, not the roboRIO.** Gains live on the device.
   Same consequence as above — gains can be stale or device-specific.
3. **Current limiting is not optional on NEOs.** Unlimited, a stalled NEO will brown out the
   robot well before anything else in the system complains.

## Pitfalls that actually cost matches

**Relying on flashed configuration instead of setting it in code.** Always configure every SPARK
from robot code on startup. Treat flash persistence as a convenience for bench testing, never as
the source of truth. The failure mode — swap a controller mid-competition, robot behaves
differently, code is identical — is brutal to debug under time pressure.

**Restoring factory defaults inconsistently.** If some subsystems reset to factory defaults before
configuring and others do not, you get a robot whose behavior depends on what was flashed last.
Pick one convention and apply it to every device.

**No current limit, or a limit set too high.** This is the number one cause of REV-based brownouts.
If this copilot's `power_analysis` reports a sustained brownout during a scoring push, check the
current limits on every NEO in that mechanism before touching anything else.

**Confusing the two "current" readings.** Like Phoenix, REV distinguishes what the battery supplies
from what the motor windings see. Diagnosing a jam with the wrong one wastes a lot of time.

**Ignoring returned error codes.** REVLib calls return status. A configuration call to a device
that is not present fails quietly.

**Mixing REV and CTRE on one mechanism.** Legal and sometimes necessary, but the two vendors'
inversion conventions, unit conventions, and current-limit semantics differ. Write down which is
which in the robot profile (`profiles/*.yaml`) so this copilot's analysis is device-aware.

## The .revlog cross-correlation path

REV controllers can log to their own `.revlog` files, separate from the roboRIO's `.wpilog`. This
copilot's `core-ingest` module has a real `.revlog` decoder and a time-sync routine that
cross-correlates the two logs onto a common timebase. That matters because the SPARK's internal
view (applied output, device-side current, faults) is often the evidence that explains something
the roboRIO log only hints at.

When a mechanism misbehaves and the `.wpilog` alone is inconclusive, pull the `.revlog` off the
controller and correlate — the answer is frequently a device-side fault or a current limit
clamping the output at exactly the moment the mechanism stalled.

## Diagnosing REV problems from a match log

```
power_analysis   file=<match.wpilog>                     # brownout events with timestamps
battery_health   file=<match.wpilog>                     # droop / internal resistance
find_peaks       file=<match.wpilog> entry=<current signal> minProminence=40
rate_of_change   file=<match.wpilog> entry=<current signal>   # how fast the draw spikes
correlate        file=<match.wpilog> entry=<current> entryB=<voltage>
```

A brownout is an *energy* problem, not a code problem. Before changing gains, check: current
limits, battery age (`battery_health` estimates internal resistance), and whether two mechanisms
are drawing peak current simultaneously — `find_peaks` on each, then compare timestamps.

## When writing new REV code

1. Configure every device fully in code on startup; never depend on flashed state.
2. Set a current limit on every NEO, sized to the mechanism, before the first test.
3. Check and log returned error codes on configuration.
4. Record CAN IDs in the team's `profiles/*.yaml` so log analysis can name devices.
5. Log applied output, both current readings, velocity, position, and faults per device.

## Related

- `skills/frc-ctre-phoenix6/SKILL.md` — the Phoenix 6 equivalent
- `skills/frc-copilot-usage/SKILL.md` — how to sequence this copilot's analysis tools
