# FTC Control Hub Emulator

A plain Kotlin/JVM library (no Android dependency) for building a desktop emulator that drives
FTC-style OpMode code without a physical REV Control/Expansion Hub. It gives you simulated
hardware devices with realistic dynamics, simple field physics, and a ready-made Swing UI to drive
and watch them -- so you can run and debug your actual robot code on a laptop between practice
sessions, on a plane, or anywhere else a Control Hub isn't handy.

The published library itself has no `main()` -- it's the shared building blocks a consuming
project wires up against its own OpModes/subsystems. This repo's own [`:demo` module](#running-the-bundled-demo)
is the simplest example of that; [Integrating into an existing FTC SDK project](#integrating-into-an-existing-ftc-sdk-project)
below walks through doing it for real.

## Table of contents

- [How it fits together](#how-it-fits-together)
- [Running the bundled demo](#running-the-bundled-demo)
- [Adding the dependency](#adding-the-dependency)
- [Feature reference](#feature-reference)
  - [`emulator.hardware` -- simulated devices](#emulatorhardware----simulated-devices)
  - [`emulator.config` -- your hardware configuration](#emulatorconfig----your-hardware-configuration)
  - [`emulator.sim` -- physics](#emulatorsim----physics)
  - [`emulator.ui` -- the desktop shell](#emulatorui----the-desktop-shell)
  - [`emulator.input` -- gamepad/keyboard](#emulatorinput----gamepadkeyboard)
- [Integrating into an existing FTC SDK project](#integrating-into-an-existing-ftc-sdk-project)
  1. [Add the dependency to your TeamCode test source set](#1-add-the-dependency-to-your-teamcode-test-source-set)
  2. [Build a HardwareMap from your real config file](#2-build-a-hardwaremap-from-your-real-config-file)
  3. [Adapt the SDK's hardware interfaces onto the simulated devices](#3-adapt-the-sdks-hardware-interfaces-onto-the-simulated-devices)
  4. [Drive your real OpMode through RunnerShellApp](#4-drive-your-real-opmode-through-runnershellapp)
  5. [Put it all together](#5-put-it-all-together)
- [Known simplifications](#known-simplifications)

## How it fits together

```
your OpMode / subsystem code (real, unmodified)
        |
        | real SDK calls, through your adapters (DcMotorEx/Servo -> SimMotor/SimServo)
        v
emulator.hardware  --update(dt)-->  emulator.sim
SimMotor / SimServo                 MecanumRobot / BatteryModel
(per-device dynamics)               (pose integration, field clamp, voltage sag)
        ^                                   ^
        | drive commands from onTick        | poseSupplier / telemetrySupplier / ...
        |                                   |
emulator.input          gamepads -->  emulator.ui
CombinedGamepadInput                  RunnerShellApp (the Swing shell)
(XInput / winmm / keyboard)           calls onInit/onStart/onStop/onTick, ~20Hz
```

- **`emulator.hardware`** -- `SimMotor`/`SimServo`/`SimDigitalDevice`/`SimAnalogDevice`/`SimImu`/
  `SimI2cDevice`: simulated devices with realistic dynamics where it matters (motor/servo), meant
  to back adapters implementing a real hardware SDK's device interfaces; and `PortId`/`PortType`/
  `HubId` for describing hub ports the way a real robot's wiring would. `SimWebcam`/
  `SimUsbSerialDevice` model USB devices the same way, identified by serial number rather than a
  hub port.
- **`emulator.config`** -- your robot's wiring, as one `RobotConfig`: either `robotConfig { ... }`,
  a small Kotlin DSL you write once with no XML involved, or `parseRobotConfigXml` reading the
  *same* hardware configuration file your project uploads to a real Control Hub. Either way,
  `buildSimulatedRobot` turns it into simulated devices for the emulator, and `writeRobotConfigXml`
  turns a DSL-built one into the real config file -- so your emulated robot's wiring can never
  drift out of sync with your real one, in either direction.
- **`emulator.sim`** -- physics: `MecanumRobot` (forward kinematics turning wheel encoder
  velocities into a field-frame pose, clamped to stay on the field) and `BatteryModel` (voltage
  sag under simulated current draw). Robot-forward aligns with field +x at heading 0.
- **`emulator.ui`** -- `RunnerShellApp`, a generic Swing Init/Start/Stop desktop window (field
  view, port monitor, telemetry, gamepad status), driven entirely through callbacks/suppliers so
  it has no dependency on what's actually being simulated; plus its panels (`PoseFieldPanel`,
  `PortRowMonitorPanel`, `SnapshotTelemetryPanel`) and `KeyTracker` (keyboard input).
- **`emulator.input`** -- `GamepadSnapshot` (a generic gamepad state, field-for-field compatible
  with the real SDK's `Gamepad`) and `CombinedGamepadInput`, which merges `XInputController`,
  `LegacyJoystickController`, and keyboard input into one snapshot per pad per tick.

Everything is driven by callbacks and plain data classes -- nothing in this library depends on
Android, a specific FTC SDK version, or your particular robot's code. You write small adapter
classes that make `SimMotor`/`SimServo` look like the SDK's `DcMotorEx`/`Servo` to your code, and
everything else (physics, UI, input) just works.

## Running the bundled demo

`:demo` is a small runnable module (kept out of the published JitPack artifact, so consumers never
pull in a Swing entrypoint) that wires up `RunnerShellApp` against a simulated mecanum drivetrain
and one servo -- the fastest way to see the library working before wiring it into your own project:

```bash
./gradlew :demo:run
```

Click **Init**, then **Start**, then drive with gamepad1 (left stick to translate, right stick X
to rotate, A/B to open/close the claw) or the keyboard if nothing's plugged in. Drive into a wall
to see the field clamp and wheel-slip behavior described [below](#mecanumrobot). See
[`demo/src/main/kotlin/emulator/demo/DemoMain.kt`](demo/src/main/kotlin/emulator/demo/DemoMain.kt)
for the ~100 lines that wire it up -- it's a good template for your own integration.

## Adding the dependency

Add [JitPack](https://jitpack.io) as a repository and depend on a tagged release (check the
[releases page](https://github.com/alonHamb/ftc-control-hub-emulator/tags) for the latest tag):

```groovy
repositories {
    maven { url = 'https://jitpack.io' }
}

dependencies {
    testImplementation 'com.github.alonHamb:ftc-control-hub-emulator:v1.0.3'
}
```

Use `testImplementation`, not `implementation` -- see
[step 1](#1-add-the-dependency-to-your-teamcode-test-source-set) below for why this needs to live
in your test source set rather than shipping in the APK.

## Feature reference

### `emulator.hardware` -- simulated devices

**`PortId(hub: HubId, type: PortType, index: Int)`** describes where a device lives, the way you'd
wire up a real robot: `HubId` is `CONTROL`, `EXPANSION`, or `SERVO_HUB` (a REV Servo Hub -- its own
module with 6 servo ports, addressed independently of either hub's own servo ports); `PortType` is `MOTOR` (4 ports),
`SERVO` (6), `DIGITAL` (8), `ANALOG` (4), or `I2C` (4). Constructing one with an out-of-range
`index` throws immediately, so a typo in your wiring map fails fast instead of silently doing
nothing.

**`SimMotor(port, name, ticksPerRev = 384.5, maxRpm = 435.0, stallCurrentAmps = 9.2)`** -- a DC
motor with an encoder, defaulting to a goBILDA 5203-series motor's specs. Mirrors `DcMotorEx`'s
shape closely enough that porting real subsystem code onto it is mostly a rename:

| Member | What it does |
|---|---|
| `setPower(Double)` / `getPower()` | Commanded power, coerced to `[-1, 1]`. |
| `getCurrentPosition()` | Encoder position in ticks (rounded to the nearest tick). |
| `getVelocity()` | Encoder velocity in ticks/sec. |
| `resetEncoder()` | Zeroes position and velocity. |
| `direction` | `Direction.FORWARD`/`REVERSE`, flips the sign of applied power. |
| `mode` | `RunMode.RUN_WITHOUT_ENCODER` / `RUN_USING_ENCODER` / `RUN_TO_POSITION` / `STOP_AND_RESET_ENCODER`. In `RUN_TO_POSITION`, a simple proportional controller drives toward `targetPosition`, capped by `commandedPower`. |
| `targetPosition` | Target encoder position for `RUN_TO_POSITION`. |
| `zeroPowerBehavior` | `ZeroPowerBehavior.BRAKE`/`FLOAT` -- stored for adapters to read, doesn't currently change the simulated dynamics. |
| `update(dt)` | **Call this once per tick** to advance the simulated velocity (first-order lag toward the commanded speed, so it doesn't jump instantly like a naive model would) and integrate position. |
| `currentDrawAmps()` | Rough current estimate for `BatteryModel`: ~0.3A idle, scaling toward `stallCurrentAmps` with commanded power. |
| `activitySummary()` | One-line human-readable status, for the port monitor. |

**`SimServo(port, name, sweepSecondsFullRange = 0.4)`** -- a standard positional servo, `0.0`-`1.0`:

| Member | What it does |
|---|---|
| `setPosition(Double)` | Commanded position, coerced to `[0, 1]`. |
| `getPosition()` | *Actual* position -- see below. |
| `direction` | `Direction.FORWARD`/`REVERSE`; `REVERSE` mirrors the commanded position (`1 - position`) before applying it. |
| `update(dt)` | **Call this once per tick.** Slews `getPosition()` toward the commanded target at a fixed rate (`1 / sweepSecondsFullRange` per second), so motion reads naturally on screen instead of teleporting. |
| `currentDrawAmps()` | ~0.4A while still slewing toward the target, ~0.05A once settled. |
| `activitySummary()` | e.g. `pos=0.75 (target 0.75)`, for the port monitor. |

Both extend `SimDevice`, which is what `PortRowView`/the port monitor and `BatteryModel` actually
consume (`activitySummary()`, `currentDrawAmps()`, `update(dt)`) -- if you add your own simulated
device type, extend `SimDevice` and it plugs into the same UI/battery machinery for free.

Four more `SimDevice`s stand in for everything that isn't a motor or servo -- simpler than
`SimMotor`/`SimServo` since there's no realistic dynamics to model, just a value your test/adapter
drives directly:

| Type | Stands in for | Shape |
|---|---|---|
| `SimDigitalDevice` | Touch sensors, limit switches, beam breaks, digital channels, simple on/off LEDs | A single settable/gettable `state: Boolean`. |
| `SimAnalogDevice` | Potentiometers, optical distance sensors, anything read as a raw voltage | A settable/gettable `voltage: Double` (0-3.3V by default). |
| `SimImu` | REV's embedded IMU, a BNO055, or any other orientation sensor | A settable/gettable `headingRad: Double` -- wire it to `MecanumRobot.pose.headingRad` in your `onTick` if you want it to track the simulated chassis. |
| `SimI2cDevice` | Any I2C sensor this library has no specific model for (color, distance, compass, ...) | Free-form named readings via `setReading(key, value)`/`getReading(key)`, e.g. `setReading("distanceMm", 125.0)`, **and** a real register-addressed byte protocol -- `i2cAddress: Int`, `engaged: Boolean`, `writeRegister(register, data)`/`readRegister(register, length)` against a 256-byte register file -- for adapters ported from the real SDK's `I2cDeviceSynchSimple`-shaped interfaces. Use whichever half matches your adapter; they're independent. |

#### USB devices

Two more classes -- `SimWebcam` and `SimUsbSerialDevice` -- model devices connected over USB rather
than plugged into a hub port. They share a `SimUsbDevice` base (`connected: Boolean`, plus
`connect()`/`disconnect()` for simulating hotplug) instead of `SimDevice`, since a USB device is
identified by serial number, not a `PortId`:

| Type | Stands in for | Shape |
|---|---|---|
| `SimWebcam` | A USB webcam | Identity (`name`, `serialNumber`) and `connected` state only -- frames aren't modeled; feed your own fake frames through whatever vision-pipeline adapter you write. |
| `SimUsbSerialDevice` | A generic USB-serial peripheral (an FTDI/CP210x bridge, a non-REV USB sensor or actuator) | Byte-stream I/O: `write(data)`/`lastWrite()` for what your OpMode sent, `feedIncoming(data)`/`read(maxBytes)`/`bytesAvailable()` for what it receives. |

### `emulator.config` -- your hardware configuration

**`parseRobotConfigXml(file: File)`** (or the `String` overload) reads an FTC hardware
configuration XML file -- the exact file the REV Hardware Client / Driver Station app writes when
you configure your real robot, normally found in your TeamCode module at
`src/main/res/xml/<config name>.xml`, and the same one your project uploads to the Control Hub
alongside your code -- into a `RobotConfig`: a plain list of every `<LynxModule>` device (which
hub, name, tag, and port or I2C bus -- plus an optional `I2cAddress` attribute like `"0x3c"` some
I2C tags carry) plus every `<Webcam>` and `<UsbDevice>` (this library's own tag for a generic
USB-serial peripheral -- real REV config files only standardize `<Webcam>` for USB). It doesn't
care whether it recognizes a given tag -- everything in the file comes through, matched or not.

**`buildSimulatedRobot(config: RobotConfig)`** turns that into a `SimulatedRobot`: maps of `name ->`
simulated device, one map per category, matched by tag name against real REV Hardware Client
exports:

| Config tag(s) | Category | Notes |
|---|---|---|
| `Motor` | `motors: Map<String, SimMotor>` | Full simulated dynamics. |
| `Servo`, `CRServo` | `servos: Map<String, SimServo>` | Full simulated dynamics -- the real config format doesn't distinguish CR from positional servos either; that's determined by which SDK interface your OpMode asks for. |
| `TouchSensor`, `DigitalChannel`, `DigitalDevice`, `REV_LED`, `RevBlinkinLedDriver` | `digitalDevices: Map<String, SimDigitalDevice>` | |
| `AnalogInput`, `OpticalDistanceSensor` | `analogDevices: Map<String, SimAnalogDevice>` | |
| `LynxEmbeddedIMU`, `BNO055IMU`, `AdafruitBNO055IMU`, `IMU` | `imus: Map<String, SimImu>` | |
| Anything else with a `bus` attribute | `i2cDevices: Map<String, SimI2cDevice>` | Covers every I2C sensor tag this library doesn't specifically recognize -- `LynxI2cColorRangeSensor`, `RevTOFDistanceSensor`, a brand-new vendor sensor released tomorrow, all land here. An `I2cAddress` attribute, if present, carries through to `SimI2cDevice.i2cAddress`. |
| Anything else with a `port` attribute | `digitalDevices` | Same reasoning: an unrecognized hub-port peripheral is more often digital than not. |
| `Webcam` | `webcams: Map<String, SimWebcam>` | A live USB device (see [USB devices](#usb-devices) above) -- not a hub-port device, so kept out of `allDevices`. |
| `UsbDevice` | `usbSerialDevices: Map<String, SimUsbSerialDevice>` | This library's own tag for a generic USB-serial peripheral -- same USB-device treatment as `Webcam`. |
| `Servo`/`CRServo` nested under `RevRoboticsServoHub` | `servos`, on `HubId.SERVO_HUB` | A REV Servo Hub is its own module (6 servo ports), not a device on the Control/Expansion Hub -- `RevRoboticsServoHub` is parsed as a third hub, same as `LynxModule`. Any non-servo tag nested under it is dropped rather than misplaced onto a port type a Servo Hub doesn't have, since the real hardware has no motor/digital/analog/I2C ports to plug one into. |

**Nothing from your config file is silently dropped.** A tag this parser has genuinely never seen
still resolves to *some* simulated device (by whether it has a `port` or a `bus`, per the table
above), so `hardwareMap.get(...)` for it will succeed in your adapter code even for hardware this
library was never specifically taught about. The only way a device doesn't make it across is if
its `port`/`bus` index is out of range for its slot (`SimulatedRobot.unrecognized` lists those --
check it, since that usually means a real config-file problem worth knowing about).

`SimulatedRobot.allDevices` (every hub-port device across every map, excluding USB devices),
`updateAll(dt)` (calls `update(dt)` on all of them), and `toPortRows()` (builds the whole port
monitor's `List<PortRowView>` for you) save you from wiring each category up by hand.
`SimulatedRobot.allUsbDevices` (`webcams` plus `usbSerialDevices`) and `toUsbRows()` do the same
for USB devices, kept separate since they aren't on a hub port.

#### Defining your wiring in code instead of XML

You don't have to touch a config file at all, or know its shape, to use any of the above --
**`robotConfig { ... }`** builds a `RobotConfig` directly:

```kotlin
val robotMap = robotConfig {
    motor("left_front_drive", port = 0)
    motor("left_back_drive", port = 1)
    motor("right_front_drive", port = 2)
    motor("right_back_drive", port = 3)
    servo("claw_servo", port = 0)
    imu("imu", bus = 0)

    expansionHub {
        motor("arm_motor", port = 0)
        servo("turret_servo", port = 0)
    }

    servoHub {
        servo("wrist_servo", port = 0)
    }

    webcam("Webcam 1", serialNumber = "A1B2C3D4")
    usbSerialDevice("usb_bridge", serialNumber = "FT1234")
}
```

Devices declared at the top level are on the Control Hub; `expansionHub { ... }` opens the same set
of functions for a second hub. `motor`/`servo`/`crServo`/`touchSensor`/`digitalChannel`/
`analogInput`/`imu` cover the common cases; `i2cDevice(tagName, name, bus, i2cAddress = null)` and
the more general `device(tagName, name, port, bus, i2cAddress)` are the same escape hatch
`buildSimulatedRobot` itself relies on -- any tag name REV Hardware Client would recognize works
here too, even ones this library has no specific simulated behavior for.

`servoHub { ... }` opens a REV Servo Hub -- its own module, not devices wired to Control/Expansion
Hub. Unlike `expansionHub`, it only exposes `servo`/`crServo` -- no `motor`, no `i2cDevice`, no
`device(tagName, ...)` escape hatch -- so there's no way to add a non-servo device to it; the
compiler rejects it, the same way a real Servo Hub's ports would reject anything but a servo.

`webcam(name, serialNumber)` and `usbSerialDevice(name, serialNumber)` aren't hub-scoped -- USB
devices connect directly, not through any hub -- so they're available at the top level only, same
as in a real config file.

Feed `robotMap` straight to `buildSimulatedRobot(robotMap)` for the emulator -- no XML step
involved at all. **`writeRobotConfigXml(robotMap)`** (or the `File` overload, which also creates
parent directories) is the reverse of `parseRobotConfigXml`: it renders the same `RobotConfig` as a
real REV Hardware Client-shaped XML file, valid enough that parsing it back gives you an equal
`RobotConfig`. Point the `File` overload at your project's actual config path (e.g.
`TeamCode/src/main/res/xml/master_config.xml`) to regenerate the file your project uploads to the
Control Hub, straight from the same Kotlin object the emulator uses -- see
[step 2](#2-build-a-hardwaremap-from-your-real-config-file) for wiring that regeneration into your
build so it happens automatically, before every upload.

### `emulator.sim` -- physics

#### `MecanumRobot`

Integrates a field-frame `Pose(x, y, headingRad)` (millimeters, radians; `0,0` is field center;
`+x` right; `+y` up; heading `0` faces `+x`) from four `SimMotor`s' simulated encoder velocities,
using standard mecanum forward kinematics. Call `update(dt)` once per tick (after updating the four
motors themselves), and read `pose` any time; `resetPose(Pose)` snaps to a new pose (e.g. for a
"Reset Field" button); `onPoseUpdated`, if given, is called with every new pose -- e.g. to mirror
it into a simulated odometry computer the way a goBILDA Pinpoint would report it back to an OpMode.

`MecanumGeometry` configures the wheelbase (`trackWidthMm`, `wheelBaseMm`, `wheelRadiusMm`, all
defaulting to a 304.8mm x 304.8mm (12in x 12in) chassis with 96mm mecanum wheels) and the chassis
footprint (`robotLengthMm`/`robotWidthMm`, the extent along the forward and left-right axes
respectively, both defaulting to 457.2mm (18in)).

**The pose is clamped to stay on the field.** By default that's a 3657.6mm x 3657.6mm (144in)
square (standard FTC, `MecanumRobot`'s `fieldSizeMm` parameter), origin at center -- matching
`PoseFieldPanel`'s field rendering. The clamp checks the robot's actual *rotated* rectangular
footprint, not just its center point, so a corner or side hitting a wall stops it the same as
driving straight into one would; x and y are clamped independently, so a robot driving into a
corner keeps sliding along whichever wall it's still clear of instead of stopping dead.

**Hitting a wall simulates wheel slip, for free.** Nothing in `MecanumRobot` feeds the clamp back
into the drive motors -- it only *reads* their velocity to integrate pose. So pinning the chassis
against a wall looks exactly like a real robot's wheels spinning uselessly against an obstacle:
each `SimMotor`'s encoder keeps advancing at the commanded speed even while the pose itself has
stopped changing.

If you customize `robotLengthMm`/`robotWidthMm`, pass the same values to `RunnerShellApp`/
`runRunnerShellAndBlock` (or `PoseFieldPanel` directly) so the drawn robot matches where physics
actually stops it -- see [`emulator.ui`](#emulatorui----the-desktop-shell) below.

#### `BatteryModel`

`BatteryModel(nominalVoltage = 12.7, internalResistanceOhms = 0.06)` -- a very rough 12V SLA
battery model. Call `update(totalCurrentDrawAmps)` once per tick with the sum of every simulated
device's `currentDrawAmps()`; read `voltage` any time. Sags linearly with current draw (`V = V₀ -
I·R`), floored at 9.0V so a stalled robot approaches brownout instead of the model driving voltage
to zero.

### `emulator.ui` -- the desktop shell

**`runRunnerShellAndBlock(...)`** is the entry point most consumers want: it builds and shows a
`RunnerShellApp` on the Swing event thread and blocks the calling thread until the window is
closed. Exists so callers never have to reference `javax.swing`/`java.awt` themselves. Parameters:

| Parameter | Called | For |
|---|---|---|
| `title` | -- | Window title. |
| `opModeNames` | -- | Populates the OpMode dropdown. |
| `onInit(selectedIndex)` | Init button clicked | Construct/reset your OpMode and hardware for the selected index. |
| `onStart()` | Start button clicked | Your OpMode's `start()`. |
| `onStop()` | Stop button clicked, or window closed | Your OpMode's `stop()`. |
| `onResetField()` | Reset Field button clicked | Usually `mecanumRobot.resetPose(Pose(0.0, 0.0, 0.0))`. |
| `onTick(dtSeconds, gamepads)` | Every ~50ms (20Hz) | Your OpMode's `loop()` -- read `gamepads.gamepad1`/`gamepad2`, drive your motors/servos, then call `update(dt)` on every `SimMotor`/`SimServo` and on your `MecanumRobot`/`BatteryModel`. |
| `poseSupplier` | Every tick, for the field view | `{ mecanumRobot.pose }` |
| `portRowsSupplier` | Once, at construction | A `List<PortRowView>` describing every device to show in the port monitor. |
| `telemetrySupplier` | Every tick, for the telemetry panel | Whatever lines you want shown -- e.g. your OpMode's telemetry buffer. |
| `crashSupplier` | Every tick | Return a caught `Throwable` here (e.g. from wrapping `onTick`'s body in `try`/`catch`) and the telemetry panel shows its stack trace instead of normal telemetry -- see [step 4](#4-drive-your-real-opmode-through-runnershellapp). |
| `statusSupplier` | Every tick, status bar | e.g. `{ if (running) "State: RUNNING" else "State: STOPPED" }` |
| `batteryVoltageSupplier` | Every tick, status bar | `{ battery.voltage }` |
| `robotLengthMm`/`robotWidthMm` | -- | Optional, default 457.2mm/457.2mm (18in/18in) -- match your `MecanumGeometry` if customized. |

**`PortRowView(hub, type, port, name, activitySummary: () -> String)`** -- one row in the port
monitor table (Hub / Type / Port / Name / Activity columns). Build one per simulated device, e.g.
`PortRowView(HubId.CONTROL.label, PortType.MOTOR.label, 0, motor.name) { motor.activitySummary() }`.

The individual panels (`PoseFieldPanel`, `PortRowMonitorPanel`, `SnapshotTelemetryPanel`) and
`KeyTracker` are exported too, if you want to embed just one in your own Swing window instead of
using the whole `RunnerShellApp` shell.

### `emulator.input` -- gamepad/keyboard

**Plug in a controller and it's used automatically** -- gamepad1 reads the first one found,
gamepad2 the second, no configuration needed. `CombinedGamepadInput.poll()` (called once per tick
by `RunnerShellApp`) tries three sources in priority order, and reports which one won in
`CombinedGamepadState.gamepad1Source`/`gamepad2Source` (shown live in the status bar):

1. **XInput** (`XInputController`, a small JNA binding to `XInputGetState`) -- Xbox controllers and
   anything else that emulates XInput. Official, precise button/axis semantics. Windows-only.
2. **Windows' legacy `winmm` joystick API** (`LegacyJoystickController`, JNA binding to
   `joyGetPosEx`/`joyGetDevCaps`) -- catches everything XInput doesn't, which in practice is most
   other controllers: PlayStation DualShock 4/DualSense (tested against a real DualSense over both
   USB and Bluetooth), and generic/no-name USB gamepads. This API doesn't standardize button/axis
   *meaning* the way XInput does, so the mapping is a best-effort guess (left stick = X/Y axes,
   right stick = Z/R, triggers = U/V, face buttons assumed in Square/Cross/Circle/Triangle bit
   order when the device reports 14+ buttons). If a controller that supports multiple transports
   (e.g. a DualSense connected over USB *and* Bluetooth at once) would otherwise show up as two
   devices, only the first is used, so it isn't double-counted as gamepad1 *and* gamepad2.
   Windows-only.
3. **Keyboard** (`KeyTracker`), always available, drives gamepad1 only:

   | Control | Keys |
   |---|---|
   | Left stick (drive) | W / A / S / D |
   | Right stick (turn) | I / J / K / L |
   | a / b / x / y | Z / X / C / V |
   | Bumpers | Q (left) &nbsp; E (right) |
   | Triggers | 1 (left) &nbsp; 2 (right) |
   | D-pad | Arrow keys |
   | Start / Back / Options | Enter / Backspace / O |

**`GamepadSnapshot`** is field-for-field compatible with the real FTC SDK's
`com.qualcomm.robotcore.hardware.Gamepad` -- copy each field across in your `onTick` (see
[step 4](#4-drive-your-real-opmode-through-runnershellapp)) rather than trying to use the snapshot
directly as a `Gamepad`.

## Integrating into an existing FTC SDK project

This walks through wiring the library into a real TeamCode module so you can run your *actual,
unmodified* OpMode/subsystem code against simulated hardware. It's one way to do it (the approach
used to build and test this repo's own consumers) -- adapt it to your project's structure as
needed.

### 1. Add the dependency to your TeamCode test source set

```groovy
// TeamCode/build.gradle
repositories {
    maven { url = 'https://jitpack.io' }
}

dependencies {
    testImplementation 'com.github.alonHamb:ftc-control-hub-emulator:v1.0.3'
}
```

**Use `testImplementation`, and put your emulator code under `TeamCode/src/test/java/...`, not
`src/main`.** Two reasons:

- It keeps a Swing desktop UI (and the JNA gamepad bindings, which only work on a desktop OS) out
  of the APK that actually ships to the Control Hub.
- `src/test` runs as a plain JVM unit test on your development machine, not on an Android
  device/emulator -- which is exactly what lets you use `java.awt`/`javax.swing` and get useful
  stack traces, breakpoints, and fast iteration. (Android Gradle Plugin's local-unit-test compile
  classpath doesn't carry `java.awt`/`javax.swing` itself, which is why this library's own
  `RunnerShellApp` lives in a separate plain-Kotlin/JVM artifact rather than directly in a
  `src/test` folder -- depending on it as a jar sidesteps that.)

### 2. Build a `HardwareMap` from your real config file

Your OpModes call `hardwareMap.get(DcMotorEx::class.java, "front left motor")`, so you need a real
`HardwareMap` that resolves those calls to your simulated devices instead of talking to actual
hardware. The catch: `HardwareMap`'s real `tryGet`/`get` unconditionally call
`Device.isRevControlHub()`, whose static-init chain reaches `System.loadLibrary("RobotCore")` -- a
real Android-ARM `.so` with no desktop build, so merely *calling* `hardwareMap.get(...)` crashes
with `UnsatisfiedLinkError` on a desktop JVM, no matter what's actually registered in the map.

The fix is to subclass `HardwareMap` with a `null` app context and notifier (the SDK's own
documented way to build one "that won't be used by user code" outside the normal event loop) and
override just `tryGet`/`get` to look the device up from the map yourself -- every other member
(`put`, `getAll`, `size`, `iterator`, ...) is fine left as the real, inherited implementation,
since none of them touch that code path:

```kotlin
private class EmulatedHardwareMap : HardwareMap(null, null) {
    override fun <T> tryGet(classOrInterface: Class<out T>, deviceName: String): T? {
        val list = allDevicesMap[deviceName.trim()] ?: return null
        for (device in list) {
            if (classOrInterface.isInstance(device)) return classOrInterface.cast(device)
        }
        return null
    }

    override fun <T> get(classOrInterface: Class<out T>, deviceName: String): T =
        tryGet(classOrInterface, deviceName)
            ?: throw IllegalArgumentException(
                "Unable to find a hardware device with name \"$deviceName\" and type ${classOrInterface.simpleName}"
            )
}
```

Now populate it from your robot's wiring, using [`emulator.config`](#emulatorconfig----your-hardware-configuration)
instead of hand-typing every device. **Recommended: define the wiring once in Kotlin** with
`robotConfig { ... }`, and generate the real config file *from* it, rather than the other way
around -- that way there's exactly one place your wiring can be wrong, and it's a source file with
compiler errors on typos, not a REV Hardware Client project only your Driver Station app can open:

```kotlin
// RobotMap.kt -- your single source of truth. Also used, unmodified, by buildEmulatedHardwareMap below.
val robotMap = robotConfig {
    motor("front left motor", port = 0)
    // ...every motor, servo, and sensor on your real robot...
}

fun buildEmulatedHardwareMap(): HardwareMap {
    val hardwareMap = EmulatedHardwareMap()
    val robot = buildSimulatedRobot(robotMap) // no XML step needed here at all

    if (robot.unrecognized.isNotEmpty()) {
        println("WARNING: couldn't place these devices: ${robot.unrecognized}")
    }

    robot.motors.forEach { (name, sim) -> hardwareMap.put(name, EmulatedDcMotorEx(sim)) } // see step 3
    robot.servos.forEach { (name, sim) -> hardwareMap.put(name, emulatedServo(sim)) }
    // ...and one more forEach per category you actually use (digitalDevices, analogDevices, imus,
    // i2cDevices) -- each needs its own small adapter the same way, see step 3.

    return hardwareMap
}
```

That leaves generating the real config file your project uploads to the Control Hub -- the
*only* place XML enters the picture, and only as an output. `writeRobotConfigXml(robotMap, File(...))`
does the rendering; run it as a build step so the file is always current **before** anything gets
packaged and uploaded, using the same small-runnable-module pattern this repo's own
[`:demo`](#running-the-bundled-demo) uses:

```groovy
// A tiny :config subproject (application plugin, depends on ftc-control-hub-emulator) whose
// main() calls writeRobotConfigXml(robotMap, File("../TeamCode/src/main/res/xml/master_config.xml")).
// Wired into TeamCode/build.gradle so it runs before AGP merges resources into the APK:
tasks.named("preBuild") {
    dependsOn(":config:run")
}
```

`preBuild` is one of the first tasks in every AGP build's task graph -- resource merging (the step
that bundles `res/xml/master_config.xml` into the APK) always runs after it, so this guarantees the
uploaded config file reflects `robotMap` as of the build that's about to ship, not whatever was
last generated by hand.

If you'd rather keep maintaining your config the REV Hardware Client way and just mirror it into
the emulator, that still works -- swap `robotMap` above for
`parseRobotConfigXml(File("src/main/res/xml/master_config.xml"))` and skip the `:config`/`preBuild`
wiring entirely; see [`emulator.config`](#emulatorconfig----your-hardware-configuration) for that
direction's details.

### 3. Adapt the SDK's hardware interfaces onto the simulated devices

Your OpMode code expects `DcMotorEx`/`Servo`/etc., not `SimMotor`/`SimServo`/etc. -- write small
adapters that implement the SDK interface and delegate to the sim device underneath.

**Motors:** `DcMotorEx` has a large interface (PIDF coefficients, multiple velocity units, etc.),
but most subsystem code only exercises a handful of members. Implement those against `SimMotor`
and stub the rest -- your IDE's "Implement members" will list exactly what's missing for your SDK
version:

```kotlin
class EmulatedDcMotorEx(private val sim: SimMotor) : DcMotorEx {
    override fun setPower(power: Double) = sim.setPower(power)
    override fun getPower(): Double = sim.getPower()
    override fun getCurrentPosition(): Int = sim.getCurrentPosition()
    override fun getVelocity(): Double = sim.getVelocity()
    override fun setTargetPosition(position: Int) { sim.targetPosition = position }
    override fun getTargetPosition(): Int = sim.targetPosition
    override fun getDeviceName(): String = sim.name

    // sim.mode/direction/zeroPowerBehavior are emulator.hardware enums deliberately named to match
    // the SDK's own DcMotor.RunMode/DcMotorSimple.Direction/DcMotor.ZeroPowerBehavior member-for-
    // member, so converting between them is just valueOf(name) rather than a real mapping table.
    override fun setMode(mode: DcMotor.RunMode) { sim.mode = RunMode.valueOf(mode.name) }
    override fun getMode(): DcMotor.RunMode = DcMotor.RunMode.valueOf(sim.mode.name)
    override fun setDirection(direction: DcMotorSimple.Direction) { sim.direction = Direction.valueOf(direction.name) }
    override fun getDirection(): DcMotorSimple.Direction = DcMotorSimple.Direction.valueOf(sim.direction.name)
    override fun setZeroPowerBehavior(behavior: DcMotor.ZeroPowerBehavior) { sim.zeroPowerBehavior = ZeroPowerBehavior.valueOf(behavior.name) }
    override fun getZeroPowerBehavior(): DcMotor.ZeroPowerBehavior = DcMotor.ZeroPowerBehavior.valueOf(sim.zeroPowerBehavior.name)

    // ...everything else your subsystems don't actually call: throw or return a sane default...
    override fun setPIDFCoefficients(mode: DcMotor.RunMode, pidf: PIDFCoefficients) { /* no-op: SimMotor's RUN_TO_POSITION uses a fixed gain */ }
}
```

**Servos:** rather than reimplementing every `Servo`/`ServoImplEx` method, fake just the
*controller* `ServoImplEx` delegates to (`ServoControllerEx`) and let the SDK build you a real
`ServoImplEx` on top of it -- much less surface area, and you get real, unmodified SDK behavior
for everything else:

```kotlin
class EmulatedServoController(private val sim: SimServo) : ServoControllerEx {
    override fun setServoPosition(servo: Int, position: Double) = sim.setPosition(position)
    override fun getServoPosition(servo: Int): Double = sim.getPosition()
    override fun setServoPwmRange(servo: Int, range: PwmControl.PwmRange) {}
    override fun getServoPwmRange(servo: Int): PwmControl.PwmRange = PwmControl.PwmRange.defaultRange
    override fun setServoPwmEnable(servo: Int) {}
    override fun setServoPwmDisable(servo: Int) {}
    override fun isServoPwmEnabled(servo: Int): Boolean = true
    override fun setServoType(servo: Int, type: ServoConfigurationType) {}
    override fun pwmEnable() {}
    override fun pwmDisable() {}
    override fun getPwmStatus(): ServoController.PwmStatus = ServoController.PwmStatus.ENABLED
    override fun getManufacturer(): HardwareDevice.Manufacturer = HardwareDevice.Manufacturer.Unknown
    override fun getDeviceName(): String = "EmulatedServoController"
    override fun getConnectionInfo(): String = "emulated"
    override fun getVersion(): Int = 1
    override fun resetDeviceConfigurationForOpMode() {}
    override fun close() {}
}

fun emulatedServo(sim: SimServo): ServoImplEx =
    ServoImplEx(EmulatedServoController(sim), 0, ServoConfigurationType())
```

**Everything else** (`SimDigitalDevice`, `SimAnalogDevice`, `SimImu`, `SimI2cDevice`) is a much
smaller interface to fake than `DcMotorEx`, since there's no dynamics involved -- just delegate the
one or two methods your SDK's interface actually has. A `TouchSensor` adapter, for instance:

```kotlin
class EmulatedTouchSensor(private val sim: SimDigitalDevice) : TouchSensor {
    override fun isPressed(): Boolean = sim.state
    override fun getValue(): Double = if (sim.state) 1.0 else 0.0
    override fun getDeviceName(): String = sim.name
    override fun getManufacturer(): HardwareDevice.Manufacturer = HardwareDevice.Manufacturer.Unknown
    override fun getConnectionInfo(): String = "emulated"
    override fun getVersion(): Int = 1
    override fun resetDeviceConfigurationForOpMode() {}
    override fun close() {}
}
```

An `IMU` adapter reads `sim.headingRad` (converting units/axes as your SDK version's `IMU`
interface expects); an `AnalogInput` adapter reads `sim.voltage`; a color/distance/other I2C sensor
adapter reads whichever `sim.getReading("...")` keys you've decided to drive from your test code.
If your adapter instead implements a lower-level interface like `I2cDeviceSynchSimple` (`read`/
`write`/`getI2cAddress`), delegate straight to `sim.readRegister(register, length)`/
`sim.writeRegister(register, data)`/`sim.i2cAddress` -- same device, whichever half of its API your
adapter needs.

A `CameraName`/`WebcamName` adapter can resolve against `SimulatedRobot.webcams` by name the same
way `HardwareMap.get(...)` resolves hub-port devices -- `sim.connected` tells you whether to hand
back real (fake) frames or simulate the camera being unplugged. A `SerialPort`-shaped adapter over
a non-REV USB peripheral reads `SimUsbSerialDevice.read(maxBytes)`/`bytesAvailable()` and writes
via `write(data)`, backed by `SimulatedRobot.usbSerialDevices`.

(If your OpMode reads bulk hub data or battery voltage through `LynxModule`, the same pattern
applies -- fake the smaller delegate interface it actually calls into rather than the whole class.
That's more involved and version-specific enough that it's out of scope for this quick-start; treat
it as an advanced extension once the basics above are working.)

### 4. Drive your real OpMode through `RunnerShellApp`

With a working `HardwareMap`, construct your real OpMode/subsystems against it as normal, and wire
its lifecycle into `runRunnerShellAndBlock`'s callbacks. Since `onTick` fires from the Swing timer
rather than a dedicated loop thread, most simple OpModes can just have their `loop()` body called
directly from it -- catch exceptions so a bug in your code shows up in the telemetry panel instead
of silently freezing the window:

```kotlin
var crash: Throwable? = null
var opMode: MyOpMode? = null

runRunnerShellAndBlock(
    title = "My Robot",
    opModeNames = listOf("Teleop"),
    onInit = {
        crash = null
        opMode = MyOpMode().apply { hardwareMap = buildEmulatedHardwareMap(); init() }
    },
    onStart = { opMode?.start() },
    onStop = { opMode?.stop() },
    onResetField = { mecanumRobot.resetPose(Pose(0.0, 0.0, 0.0)) },
    onTick = { dtSeconds, gamepads ->
        try {
            // write this: copy each GamepadSnapshot field (leftStickX, a, dpadUp, ...) onto the
            // SDK's real Gamepad object, e.g. opMode.gamepad1.left_stick_x = gamepads.gamepad1.leftStickX
            copyGamepadSnapshotOnto(opMode?.gamepad1, gamepads.gamepad1)
            opMode?.loop()
            listOf(frontLeft, frontRight, backLeft, backRight, claw).forEach { it.update(dtSeconds) }
            mecanumRobot.update(dtSeconds)
            battery.update(listOf(frontLeft, frontRight, backLeft, backRight, claw).sumOf { it.currentDrawAmps() })
        } catch (t: Throwable) {
            crash = t
        }
    },
    poseSupplier = { mecanumRobot.pose },
    portRowsSupplier = { portRows },
    telemetrySupplier = { opMode?.telemetryLines() ?: emptyList() },
    crashSupplier = { crash },
    statusSupplier = { if (crash != null) "State: CRASHED" else "State: RUNNING" },
    batteryVoltageSupplier = { battery.voltage }
)
```

### 5. Put it all together

This repo's own [`:demo` module](#running-the-bundled-demo)
([`DemoMain.kt`](demo/src/main/kotlin/emulator/demo/DemoMain.kt), ~100 lines) is a complete,
runnable example of the *step 4* wiring pattern -- the `runRunnerShellAndBlock` callback shape and
the `SimMotor`/`SimServo`/`MecanumRobot`/`BatteryModel` update loop -- since the library itself has
no real OpMode or `HardwareMap` to demonstrate steps 2-3 against. Run it with `./gradlew :demo:run`
and read it alongside step 4 above; then layer steps 1-3 (the dependency, `HardwareMap`, and
adapters) on top of that same shape in your own TeamCode module to drive your real OpModes instead.

## Known simplifications

- No IMU noise/drift, no collisions with other robots (only with the field walls, as described
  under [`MecanumRobot`](#mecanumrobot) above).
- `RunnerShellApp` refreshes at 20 Hz (a `javax.swing.Timer` tick), not the ~10-20 ms average loop
  time of a real Driver Station -- fine for watching behavior, not for timing-sensitive tuning.
- `SimMotor.zeroPowerBehavior` is stored but doesn't currently change simulated dynamics (braking
  vs. coasting to a stop look the same).
- `SimDigitalDevice`/`SimAnalogDevice`/`SimImu`/`SimI2cDevice`/`SimWebcam`/`SimUsbSerialDevice`
  ([`emulator.config`](#emulatorconfig----your-hardware-configuration)) have no dynamics of their
  own -- they're values *you* drive from your test code (or from `MecanumRobot`, for the IMU), not
  physically simulated sensors. "Supported" means your config file's device resolves to something
  you can wire up, not that its real-world behavior is modeled. `SimI2cDevice`'s register file has
  no per-address read/write semantics either -- it's a flat byte array, not a model of any
  particular sensor's real register map. `SimWebcam` doesn't produce frames, and `SimUsbSerialDevice`
  doesn't model real USB-serial framing/timing, just a byte queue.
- The config-XML tag classification (`emulator.config`) is a best-effort match against known REV
  Hardware Client tag names, falling back to "has a `bus`? I2C. Has a `port`? digital." for
  anything it doesn't recognize -- it can't know a genuinely novel device's actual behavior, only
  give it a shape to be driven through.
- USB device *hotplug* is manual (`connect()`/`disconnect()`) -- there's no automatic
  disconnect/reconnect simulation, timing, or enumeration order to match a real OS's USB stack.
- `writeRobotConfigXml` always writes an Expansion Hub (when your config has one) at module address
  `2`, the value seen in every real REV Hardware Client export checked against this library. If
  your real robot's Expansion Hub was assigned a different address, edit the generated file's
  `<LynxModule port="...">` by hand after generating, or open it once in REV Hardware Client to fix
  the address -- `parseRobotConfigXml` doesn't care what the number is, only `writeRobotConfigXml`
  assumes the common default.
