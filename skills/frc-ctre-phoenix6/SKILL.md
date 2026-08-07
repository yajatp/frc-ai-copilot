---
name: frc-ctre-phoenix6
description: Working with CTRE Phoenix 6 devices (TalonFX/Kraken, CANcoder, Pigeon 2, CANivore) in FRC robot code — configuration objects, control requests, status signals and their update rates, closed-loop slots, and the failure modes that show up in match logs. Load this when writing or reviewing code that touches Phoenix 6, when diagnosing CAN utilization or stale-signal problems, or when a TalonFX is behaving unexpectedly.
---

# CTRE Phoenix 6

**Before quoting any API signature, call `search_docs` with `source: "ctre"`.** Phoenix 6 changed
its API substantially from Phoenix 5 and continues to evolve each season. The patterns below are
durable; the exact method names and config field names are not — verify them.

## The mental model that matters

Phoenix 6 is built on three ideas that differ from most FRC motor APIs:

1. **Configuration is an object, not a series of setter calls.** You build a configuration struct,
   then apply it in one transaction. Partial configuration is the single most common source of
   "it worked on the bench, not on the field" — applying a config object *replaces* settings, so
   a config applied later in `robotInit` can silently undo an earlier one.
2. **Control is a request object, not a raw number.** You hand the motor a typed request
   (voltage, duty cycle, position, velocity, motion-magic) rather than a percent. This is why
   Phoenix 6 code is unit-safe and why mixing control modes mid-loop produces jerky behavior.
3. **Telemetry is a status signal with an explicit update rate.** Every signal has a frequency you
   control. This is the lever for CAN utilization, and it is where most bus problems originate.

## Pitfalls that actually cost matches

**Applying configs in the wrong order, or partially.** Build the full configuration for a device
once and apply it once. If you must change one field at runtime, read-modify-write the whole
config object rather than applying a fresh one with defaults in every other field.

**Leaving every status signal at default frequency.** A swerve drivetrain is eight Phoenix
devices plus encoders; defaults across all of them will saturate the bus. Set the signals you
actually read to the rate you actually need, and explicitly slow down the ones you never read.
If `can_health` in this copilot reports rising error counts, this is the first thing to check.

**Not checking the status code returned by config application.** Applying a config to a device
that is not on the bus fails silently unless you check the returned status. A motor that "does
nothing" with no error in the driver station is usually a config that never landed.

**Forgetting the encoder-to-mechanism ratio.** Phoenix 6 can apply sensor-to-mechanism and
rotor-to-sensor ratios so closed-loop setpoints are in mechanism units. Teams that skip this end
up hand-converting units at every call site, and the conversions drift apart over a season.

**Assuming a CANivore behaves like the roboRIO bus.** Devices on a CANivore are addressed on a
separate bus with its own name. A device that "isn't found" is often on the other bus.

**Closed-loop slots.** Phoenix 6 supports multiple gain slots per device. Tuning a mechanism for
one situation and then wondering why it behaves differently in another usually means two
different slots are active in two different commands.

## Diagnosing Phoenix 6 problems from a match log

This copilot's own tools are the fastest route:

```
log_entries      file=<match.wpilog> filter=Talon    # what the device actually published
can_health       file=<match.wpilog>                 # bus error trend — config/wiring/utilization
signal_stats     file=<match.wpilog> entry=<supply current signal>
find_peaks       file=<match.wpilog> entry=<stator current signal> minProminence=40
correlate        file=<match.wpilog> entry=<stator current> entryB=<battery voltage>
```

- **Stator current spiking with no motion** — mechanism is jammed or the motor is fighting itself
  (two controllers driving one mechanism in opposite directions; check inversions).
- **Supply current high while stator is low** — usually a gearing/efficiency problem, not electrical.
- **Strong negative correlation between total current and battery voltage** is normal physics; it
  only matters when `power_analysis` also reports a sustained brownout.
- **Signals present in the log but flat/stale** — the status signal frequency is too low, or the
  signal is never refreshed in the loop.

## When writing new Phoenix 6 code

1. `search_docs source=ctre` for the current names of the config and control classes involved.
2. Put device configuration in one place per subsystem, applied once, with the status code checked.
3. Set status signal frequencies deliberately for every device you add.
4. Express setpoints in mechanism units via the ratio configs, not by hand-converting.
5. Log the signals you will want in the pit: supply and stator current, applied voltage, position,
   velocity, and device temperature. This copilot can only diagnose what the log contains.

## Related

- `skills/frc-rev/SKILL.md` — the REVLib equivalent, for mixed-vendor robots
- `skills/frc-copilot-usage/SKILL.md` — how to sequence this copilot's analysis tools
