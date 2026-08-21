package emulator.hardware

import kotlin.math.abs
import kotlin.math.roundToInt

/** Base type for anything plugged into a hub port, shown as one row in the port monitor. */
sealed class SimDevice(val port: PortId, val name: String) {
    /** One-line human-readable snapshot of the device's current activity, for the port table. */
    abstract fun activitySummary(): String

    /** Rough current draw estimate in amps, used for the simulated battery voltage sag. */
    open fun currentDrawAmps(): Double = 0.0

    /** Advance internal dynamics by [dt] seconds. Called once per emulator tick. */
    open fun update(dt: Double) {}
}

enum class Direction { FORWARD, REVERSE }
enum class RunMode { RUN_WITHOUT_ENCODER, RUN_USING_ENCODER, RUN_TO_POSITION, STOP_AND_RESET_ENCODER }
enum class ZeroPowerBehavior { BRAKE, FLOAT }

/**
 * A DC motor with an encoder, modeled like a goBILDA 5203-series motor by default. Mirrors the
 * shape of `DcMotorEx` closely enough that porting real subsystem code is mostly a rename.
 */
class SimMotor(
    port: PortId,
    name: String,
    val ticksPerRev: Double = 384.5,
    val maxRpm: Double = 435.0,
    private val stallCurrentAmps: Double = 9.2
) : SimDevice(port, name) {
    var direction: Direction = Direction.FORWARD
    var mode: RunMode = RunMode.RUN_WITHOUT_ENCODER
    var zeroPowerBehavior: ZeroPowerBehavior = ZeroPowerBehavior.BRAKE
    var targetPosition: Int = 0

    private var commandedPower: Double = 0.0
    private var positionTicks: Double = 0.0
    private var velocityTicksPerSec: Double = 0.0

    private val maxTicksPerSec get() = (maxRpm / 60.0) * ticksPerRev
    private val motorTimeConstantSec = 0.12 // first-order lag approximating rotor/gearbox inertia

    fun setPower(power: Double) {
        commandedPower = power.coerceIn(-1.0, 1.0)
    }

    fun getPower(): Double = commandedPower

    fun getCurrentPosition(): Int = positionTicks.roundToInt()

    fun getVelocity(): Double = velocityTicksPerSec

    fun resetEncoder() {
        positionTicks = 0.0
        velocityTicksPerSec = 0.0
    }

    override fun update(dt: Double) {
        if (mode == RunMode.STOP_AND_RESET_ENCODER) {
            resetEncoder()
        }
        val sign = if (direction == Direction.REVERSE) -1.0 else 1.0

        val desiredTicksPerSec = when (mode) {
            RunMode.RUN_TO_POSITION -> {
                val error = targetPosition - positionTicks
                val cap = maxTicksPerSec * abs(commandedPower).coerceAtLeast(0.05)
                (error * 8.0).coerceIn(-cap, cap)
            }
            else -> commandedPower * maxTicksPerSec
        } * sign

        // Exponential approach instead of an instant velocity jump -- real motors have inertia.
        val alpha = (dt / motorTimeConstantSec).coerceIn(0.0, 1.0)
        velocityTicksPerSec += (desiredTicksPerSec - velocityTicksPerSec) * alpha
        positionTicks += velocityTicksPerSec * dt
    }

    override fun currentDrawAmps(): Double =
        if (commandedPower == 0.0 && mode != RunMode.RUN_TO_POSITION) 0.3 else stallCurrentAmps * abs(commandedPower).coerceIn(0.15, 1.0)

    override fun activitySummary(): String {
        val rpm = (velocityTicksPerSec / ticksPerRev) * 60.0
        return "pwr=%+.2f  pos=%d ticks  vel=%.0f rpm  mode=%s".format(commandedPower, getCurrentPosition(), rpm, mode)
    }
}

/** A standard positional servo, 0.0-1.0, with a slew rate so motion reads naturally on screen. */
class SimServo(port: PortId, name: String, private val sweepSecondsFullRange: Double = 0.4) : SimDevice(port, name) {
    var direction: Direction = Direction.FORWARD
    private var commandedPosition: Double = 0.5
    private var actualPosition: Double = 0.5

    fun setPosition(position: Double) {
        commandedPosition = position.coerceIn(0.0, 1.0)
    }

    fun getPosition(): Double = actualPosition

    override fun update(dt: Double) {
        val target = if (direction == Direction.REVERSE) 1.0 - commandedPosition else commandedPosition
        val maxStep = dt / sweepSecondsFullRange
        val delta = (target - actualPosition).coerceIn(-maxStep, maxStep)
        actualPosition += delta
    }

    override fun currentDrawAmps(): Double = if (abs(commandedPosition - actualPosition) > 0.001) 0.4 else 0.05

    override fun activitySummary(): String = "pos=%.2f (target %.2f)".format(actualPosition, commandedPosition)
}

/**
 * A generic digital I/O device -- stand-in for anything the real SDK exposes as a single boolean
 * (touch sensors, limit switches, beam breaks, digital channels, simple on/off LEDs/indicators).
 * Real digital devices vary in whether they're read-only (a sensor) or read/write (an output);
 * [state] is just settable/gettable either way, and it's on your adapter -- see
 * `emulator.config.RobotConfig` -- to only call the half of that it actually needs.
 */
class SimDigitalDevice(port: PortId, name: String) : SimDevice(port, name) {
    var state: Boolean = false

    override fun activitySummary(): String = "state=$state"
}

/**
 * A generic analog input -- stand-in for potentiometers, optical distance sensors, and anything
 * else the real SDK reads as a raw voltage. [voltage] defaults to a REV analog port's 0-3.3V
 * range; set it directly to feed your adapter a fake reading (e.g. driven by [SimMotor.getVelocity]
 * for a simulated potentiometer, or a fixed value for a simulated distance sensor).
 */
class SimAnalogDevice(port: PortId, name: String, private val maxVoltage: Double = 3.3) : SimDevice(port, name) {
    var voltage: Double = 0.0
        set(value) {
            field = value.coerceIn(0.0, maxVoltage)
        }

    override fun activitySummary(): String = "voltage=%.2fV".format(voltage)
}

/**
 * A generic IMU stand-in (REV's embedded IMU, an Adafruit BNO055, or anything else the real SDK
 * reads orientation from) -- just a settable [headingRad], since that's what field-centric drive
 * code actually needs. Drive it from your own simulated dynamics -- e.g. mirror
 * [emulator.sim.MecanumRobot.pose]'s heading onto it in your `onTick`, the same way a real IMU
 * would track the chassis's actual orientation.
 */
class SimImu(port: PortId, name: String) : SimDevice(port, name) {
    var headingRad: Double = 0.0

    override fun activitySummary(): String = "heading=%.1f°".format(Math.toDegrees(headingRad))
}

/**
 * A catch-all stand-in for any I2C device this library has no specific physics for -- color
 * sensors, distance sensors, compasses, and whatever new sensor a vendor ships next. Holds
 * whatever named numeric readings your test/adapter chooses to set (e.g. `"distanceMm"`,
 * `"red"`/`"green"`/`"blue"`, `"headingDeg"`) rather than modeling any particular sensor's real
 * output shape, so a device type this library has never heard of still resolves to *something*
 * instead of failing to simulate at all -- see `emulator.config.RobotConfig`.
 *
 * Also models the register-addressed byte protocol real I2C devices actually speak on the wire --
 * [i2cAddress] plus a 256-byte register file you [readRegister]/[writeRegister] against -- for
 * adapters ported from the real SDK's lower-level `I2cDeviceSynchSimple`-shaped interfaces rather
 * than a vendor-specific sensor class. The two APIs are independent; use whichever matches what
 * your adapter needs.
 */
class SimI2cDevice(port: PortId, name: String, val i2cAddress: Int = 0x00) : SimDevice(port, name) {
    private val readings = mutableMapOf<String, Double>()
    private val registers = ByteArray(256)

    /** Whether the bus is currently talking to this device -- mirrors `I2cDevice.engage()`/`disengage()`. */
    var engaged: Boolean = true

    fun setReading(key: String, value: Double) {
        readings[key] = value
    }

    fun getReading(key: String): Double = readings[key] ?: 0.0

    /** Writes [data] into the register file starting at [register], clamped to the 256-byte range. */
    fun writeRegister(register: Int, data: ByteArray) {
        for (i in data.indices) {
            val address = register + i
            if (address in registers.indices) registers[address] = data[i]
        }
    }

    /** Reads [length] bytes from the register file starting at [register]; out-of-range bytes read as 0. */
    fun readRegister(register: Int, length: Int): ByteArray =
        ByteArray(length) { i -> registers.getOrElse(register + i) { 0 } }

    override fun activitySummary(): String {
        val addr = "addr=0x%02X".format(i2cAddress)
        val state = if (engaged) "engaged" else "disengaged"
        val readingsText = if (readings.isEmpty()) "(no readings set)" else readings.entries.joinToString { "${it.key}=${it.value}" }
        return "$addr  $state  $readingsText"
    }
}
