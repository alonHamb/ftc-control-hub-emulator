package emulator.sim

import emulator.hardware.SimMotor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class Pose(val x: Double, val y: Double, val headingRad: Double)

/** Wheelbase geometry used to convert individual wheel speeds into a robot-relative velocity. */
data class MecanumGeometry(
    val trackWidthIn: Double = 12.0,
    val wheelBaseIn: Double = 12.0,
    val wheelRadiusIn: Double = 1.89 // ~96mm mecanum wheel
) {
    /** Effective lever arm from robot center to a wheel's contact patch, for the rotation term. */
    val rotationRadiusIn get() = trackWidthIn / 2.0 + wheelBaseIn / 2.0
}

private fun wrapAngle(radians: Double): Double {
    var a = radians % (2 * PI)
    if (a > PI) a -= 2 * PI
    if (a < -PI) a += 2 * PI
    return a
}

/**
 * Integrates a field-frame robot pose from four mecanum drive motors' simulated encoder
 * velocities. [onPoseUpdated], if given, is called with the new pose every tick -- e.g. to mirror
 * it into a simulated odometry computer, the way a goBILDA Pinpoint would report it back to an
 * OpMode.
 */
class MecanumRobot(
    private val frontLeft: SimMotor,
    private val frontRight: SimMotor,
    private val backLeft: SimMotor,
    private val backRight: SimMotor,
    private val geometry: MecanumGeometry = MecanumGeometry(),
    private val onPoseUpdated: ((Pose) -> Unit)? = null
) {
    var pose: Pose = Pose(0.0, 0.0, 0.0)
        private set

    private fun linearInPerSec(motor: SimMotor): Double =
        (motor.getVelocity() / motor.ticksPerRev) * 2 * PI * geometry.wheelRadiusIn

    fun resetPose(newPose: Pose) {
        pose = newPose
    }

    fun update(dt: Double) {
        val vFL = linearInPerSec(frontLeft)
        val vFR = linearInPerSec(frontRight)
        val vBL = linearInPerSec(backLeft)
        val vBR = linearInPerSec(backRight)

        // Standard mecanum forward kinematics (robot-relative velocities from wheel speeds).
        val vx = (vFL + vFR + vBL + vBR) / 4.0
        val vy = (-vFL + vFR + vBL - vBR) / 4.0
        val omega = (-vFL + vFR - vBL + vBR) / (4.0 * geometry.rotationRadiusIn)

        val heading = pose.headingRad
        val fieldVx = vx * cos(heading) - vy * sin(heading)
        val fieldVy = vx * sin(heading) + vy * cos(heading)

        pose = Pose(
            pose.x + fieldVx * dt,
            pose.y + fieldVy * dt,
            wrapAngle(heading + omega * dt)
        )

        onPoseUpdated?.invoke(pose)
    }
}
