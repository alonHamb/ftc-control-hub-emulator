package emulator.sim

import emulator.hardware.SimMotor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class Pose(val x: Double, val y: Double, val headingRad: Double)

/**
 * Wheelbase geometry used to convert individual wheel speeds into a robot-relative velocity, plus
 * the chassis footprint used to keep the robot on the field -- see [MecanumRobot]'s field-wall
 * clamp. [robotLengthIn] is the extent along the robot's forward axis, [robotWidthIn] along its
 * left-right axis; the 18in x 18in default matches [emulator.ui.PoseFieldPanel]'s hardcoded robot
 * size, so the physical stopping point lines up with what's drawn on screen.
 */
data class MecanumGeometry(
    val trackWidthIn: Double = 12.0,
    val wheelBaseIn: Double = 12.0,
    val wheelRadiusIn: Double = 1.89, // ~96mm mecanum wheel
    val robotLengthIn: Double = 18.0,
    val robotWidthIn: Double = 18.0
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
 *
 * The pose is clamped to a [fieldSizeIn] x [fieldSizeIn] square field (144in, standard FTC, by
 * default), origin at center -- matching [emulator.ui.PoseFieldPanel]'s field rendering. The clamp
 * checks the robot's actual rotated footprint (see [MecanumGeometry.robotLengthIn]/
 * [MecanumGeometry.robotWidthIn]), not just its center point, so a corner or side hitting a wall
 * stops the robot the same as driving straight into one would. Nothing here feeds the clamp back
 * into the drive motors, so pinning the chassis against a wall shows up exactly like real wheel
 * slip: [SimMotor]'s encoders keep advancing at the commanded speed even while the pose itself
 * stops changing, because [linearInPerSec] only reads motor velocity and has no idea the chassis
 * is blocked.
 */
class MecanumRobot(
    private val frontLeft: SimMotor,
    private val frontRight: SimMotor,
    private val backLeft: SimMotor,
    private val backRight: SimMotor,
    private val geometry: MecanumGeometry = MecanumGeometry(),
    private val fieldSizeIn: Double = 144.0,
    private val onPoseUpdated: ((Pose) -> Unit)? = null
) {
    var pose: Pose = Pose(0.0, 0.0, 0.0)
        private set

    private fun linearInPerSec(motor: SimMotor): Double =
        (motor.getVelocity() / motor.ticksPerRev) * 2 * PI * geometry.wheelRadiusIn

    fun resetPose(newPose: Pose) {
        pose = clampToField(newPose)
    }

    /**
     * Clamps x and y independently (rather than the whole pose as one unit) so a robot driving
     * into a corner keeps sliding along whichever wall it's still clear of, instead of stopping
     * dead the instant either axis touches a wall.
     */
    private fun clampToField(candidate: Pose): Pose {
        val halfField = fieldSizeIn / 2.0
        val cosAbs = abs(cos(candidate.headingRad))
        val sinAbs = abs(sin(candidate.headingRad))
        val halfLength = geometry.robotLengthIn / 2.0
        val halfWidth = geometry.robotWidthIn / 2.0
        val halfExtentX = halfLength * cosAbs + halfWidth * sinAbs
        val halfExtentY = halfLength * sinAbs + halfWidth * cosAbs

        return Pose(
            candidate.x.coerceIn(-halfField + halfExtentX, halfField - halfExtentX),
            candidate.y.coerceIn(-halfField + halfExtentY, halfField - halfExtentY),
            candidate.headingRad
        )
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

        pose = clampToField(
            Pose(
                pose.x + fieldVx * dt,
                pose.y + fieldVy * dt,
                wrapAngle(heading + omega * dt)
            )
        )

        onPoseUpdated?.invoke(pose)
    }
}
