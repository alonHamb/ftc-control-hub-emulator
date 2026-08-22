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
 * clamp. [robotLengthMm] is the extent along the robot's forward axis, [robotWidthMm] along its
 * left-right axis; the 457.2mm x 457.2mm (18in x 18in) default matches
 * [emulator.ui.PoseFieldPanel]'s hardcoded robot size, so the physical stopping point lines up
 * with what's drawn on screen.
 */
data class MecanumGeometry(
    val trackWidthMm: Double = 304.8,
    val wheelBaseMm: Double = 304.8,
    val wheelRadiusMm: Double = 48.0, // 96mm mecanum wheel
    val robotLengthMm: Double = 457.2,
    val robotWidthMm: Double = 457.2
) {
    /** Effective lever arm from robot center to a wheel's contact patch, for the rotation term. */
    val rotationRadiusMm get() = trackWidthMm / 2.0 + wheelBaseMm / 2.0
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
 * The pose is clamped to a [fieldSizeMm] x [fieldSizeMm] square field (3657.6mm / 144in, standard
 * FTC, by default), origin at center -- matching [emulator.ui.PoseFieldPanel]'s field rendering.
 * The clamp checks the robot's actual rotated footprint (see [MecanumGeometry.robotLengthMm]/
 * [MecanumGeometry.robotWidthMm]), not just its center point, so a corner or side hitting a wall
 * stops the robot the same as driving straight into one would. Nothing here feeds the clamp back
 * into the drive motors, so pinning the chassis against a wall shows up exactly like real wheel
 * slip: [SimMotor]'s encoders keep advancing at the commanded speed even while the pose itself
 * stops changing, because [linearMmPerSec] only reads motor velocity and has no idea the chassis
 * is blocked.
 */
class MecanumRobot(
    private val frontLeft: SimMotor,
    private val frontRight: SimMotor,
    private val backLeft: SimMotor,
    private val backRight: SimMotor,
    private val geometry: MecanumGeometry = MecanumGeometry(),
    private val fieldSizeMm: Double = 3657.6,
    private val onPoseUpdated: ((Pose) -> Unit)? = null
) {
    var pose: Pose = Pose(0.0, 0.0, 0.0)
        private set

    private fun linearMmPerSec(motor: SimMotor): Double =
        (motor.getVelocity() / motor.ticksPerRev) * 2 * PI * geometry.wheelRadiusMm

    fun resetPose(newPose: Pose) {
        pose = clampToField(newPose)
    }

    /**
     * Clamps x and y independently (rather than the whole pose as one unit) so a robot driving
     * into a corner keeps sliding along whichever wall it's still clear of, instead of stopping
     * dead the instant either axis touches a wall.
     */
    private fun clampToField(candidate: Pose): Pose {
        val halfField = fieldSizeMm / 2.0
        val cosAbs = abs(cos(candidate.headingRad))
        val sinAbs = abs(sin(candidate.headingRad))
        val halfLength = geometry.robotLengthMm / 2.0
        val halfWidth = geometry.robotWidthMm / 2.0
        val halfExtentX = halfLength * cosAbs + halfWidth * sinAbs
        val halfExtentY = halfLength * sinAbs + halfWidth * cosAbs

        return Pose(
            candidate.x.coerceIn(-halfField + halfExtentX, halfField - halfExtentX),
            candidate.y.coerceIn(-halfField + halfExtentY, halfField - halfExtentY),
            candidate.headingRad
        )
    }

    fun update(dt: Double) {
        val vFL = linearMmPerSec(frontLeft)
        val vFR = linearMmPerSec(frontRight)
        val vBL = linearMmPerSec(backLeft)
        val vBR = linearMmPerSec(backRight)

        // Standard mecanum forward kinematics (robot-relative velocities from wheel speeds).
        val vx = (vFL + vFR + vBL + vBR) / 4.0
        val vy = (-vFL + vFR + vBL - vBR) / 4.0
        val omega = (-vFL + vFR - vBL + vBR) / (4.0 * geometry.rotationRadiusMm)

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
