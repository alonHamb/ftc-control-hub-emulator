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
