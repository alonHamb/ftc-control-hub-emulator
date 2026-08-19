# FTC Control Hub Emulator

A plain Kotlin/JVM library (no Android dependency) for building a desktop emulator that drives
FTC-style OpMode code without a physical REV Control/Expansion Hub. It's not a runnable
application by itself -- there's no `main()` -- it's the shared building blocks a consuming
project wires up:

- **`emulator.sim`** -- physics: `MecanumRobot` (forward kinematics turning wheel encoder
  velocities into a field-frame pose) and `BatteryModel` (voltage sag under simulated current
  draw). Robot-forward aligns with field +x at heading 0.
- **`emulator.hardware`** -- `SimMotor`/`SimServo`, simulated devices with realistic dynamics
  (first-order lag so motor velocity doesn't jump instantly, servo slew rate), meant to back
  adapters implementing a real hardware SDK's motor/servo interfaces; and `PortId`/`PortType`/
  `HubId` for describing hub ports.
- **`emulator.ui`** -- `RunnerShellApp`, a generic Swing Init/Start/Stop desktop window (field
  view, port monitor, telemetry, gamepad status), driven entirely through callbacks/suppliers so
  it has no dependency on what's actually being simulated; plus its panels (`PoseFieldPanel`,
  `PortRowMonitorPanel`, `SnapshotTelemetryPanel`) and `KeyTracker` (keyboard input).
- **`emulator.input`** -- `GamepadSnapshot` (a generic gamepad state) and `CombinedGamepadInput`,
  which merges `XInputController`, `LegacyJoystickController`, and keyboard input into one
  snapshot per pad per tick.

This was extracted from [Robot-Base](https://github.com/alonHamb/Robot-Base), an FTC robot
codebase, where it backs a desktop emulator that runs that repo's *real, unmodified* OpMode
classes against simulated hardware -- see that repo's
[`TeamCode/src/test/.../emulator`](https://github.com/alonHamb/Robot-Base/tree/master/TeamCode/src/test/java/org/firstinspires/ftc/teamcode/emulator)
for a full worked example of wiring this library up: implementing a real SDK's hardware
interfaces on top of `SimMotor`/`SimServo`, and driving an OpMode lifecycle through
`RunnerShellApp`.

## Using it

Add [JitPack](https://jitpack.io) as a repository and depend on a tagged release:

```groovy
repositories {
    maven { url = 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.alonHamb:ftc-control-hub-emulator:1.0.0'
}
```

## Gamepad / controller support

**Plug in a controller and it's used automatically** -- gamepad1 reads the first one found,
gamepad2 the second, no configuration needed. Two backends are tried in order, both Windows-only
(elsewhere, or if nothing's connected, callers fall back to the keyboard):

1. **XInput** (`XInputController`, a small JNA binding to `XInputGetState`) -- Xbox controllers and
   anything else that emulates XInput. Official, precise button/axis semantics.
2. **Windows' legacy `winmm` joystick API** (`LegacyJoystickController`, JNA binding to
   `joyGetPosEx`/`joyGetDevCaps`) -- catches everything XInput doesn't, which in practice is most
   other controllers: PlayStation DualShock 4/DualSense (tested against a real DualSense over both
   USB and Bluetooth), and generic/no-name USB gamepads. This API doesn't standardize button/axis
   *meaning* the way XInput does, so the mapping is a best-effort guess (left stick = X/Y axes,
   right stick = Z/R, triggers = U/V, face buttons assumed in Square/Cross/Circle/Triangle bit
   order when the device reports 14+ buttons). If a controller that supports multiple transports
   (e.g. a DualSense connected over USB *and* Bluetooth at once) would otherwise show up as two
   devices, only the first is used, so it isn't double-counted as gamepad1 *and* gamepad2.

## Known simplifications

- No IMU noise/drift, no wheel slip, no collisions with field walls or other robots.
- `RunnerShellApp` refreshes at 20 Hz (a `javax.swing.Timer` tick), not the ~10-20 ms average loop
  time of a real Driver Station -- fine for watching behavior, not for timing-sensitive tuning.
