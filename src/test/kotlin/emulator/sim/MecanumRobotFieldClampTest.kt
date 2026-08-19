package emulator.sim

import emulator.hardware.HubId
import emulator.hardware.PortId
import emulator.hardware.PortType
import emulator.hardware.SimMotor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

private const val DT = 0.02 // 50 Hz, matching a real update loop
private const val HALF_FIELD = 144.0 / 2.0 // MecanumRobot's default fieldSizeIn
private const val HALF_FOOTPRINT = 18.0 / 2.0 // MecanumGeometry's default robot length/width

private fun motor(index: Int) = SimMotor(PortId(HubId.CONTROL, PortType.MOTOR, index), "motor $index")

class MecanumRobotFieldClampTest {

    @Test
    fun `driving straight for a long time stops the robot at the field wall, not past it`() {
        val fl = motor(0); val fr = motor(1); val bl = motor(2); val br = motor(3)
        val robot = MecanumRobot(fl, fr, bl, br)

        listOf(fl, fr, bl, br).forEach { it.setPower(1.0) }
        repeat(500) { // 10s simulated -- plenty of time to ramp up and reach the wall
            listOf(fl, fr, bl, br).forEach { it.update(DT) }
            robot.update(DT)
            assertTrue("robot crossed the field wall: pose=${robot.pose}", robot.pose.x <= HALF_FIELD - HALF_FOOTPRINT + 1e-9)
        }

        assertEquals("expected the robot to have settled against the +x wall", HALF_FIELD - HALF_FOOTPRINT, robot.pose.x, 0.05)
    }

    @Test
    fun `a robot driving in diagonally reaches the wall sooner than one driving straight, because its rotated footprint is wider`() {
        val fl = motor(0); val fr = motor(1); val bl = motor(2); val br = motor(3)
        val robot = MecanumRobot(fl, fr, bl, br)
        robot.resetPose(Pose(0.0, 0.0, PI / 4)) // 45 degrees

        listOf(fl, fr, bl, br).forEach { it.setPower(1.0) }
        repeat(500) {
            listOf(fl, fr, bl, br).forEach { it.update(DT) }
            robot.update(DT)
        }

        // At 45deg a square robot's rotated AABB half-extent is halfFootprint*sqrt(2), so its x
        // (== y here, driving straight along the diagonal) should stop noticeably short of where
        // the unrotated case does (63in) -- not out past it.
        assertTrue(
            "expected the 45deg robot's x to stop well short of the unrotated 63in stopping point, was ${robot.pose.x}",
            robot.pose.x < HALF_FIELD - HALF_FOOTPRINT - 1.0
        )
        assertEquals("expected x and y to be equal driving straight along the 45deg diagonal", robot.pose.x, robot.pose.y, 0.05)
    }

    @Test
    fun `pinning the chassis against a wall looks like wheel slip -- the motor keeps advancing while the pose doesn't`() {
        val fl = motor(0); val fr = motor(1); val bl = motor(2); val br = motor(3)
        val robot = MecanumRobot(fl, fr, bl, br)
        robot.resetPose(Pose(HALF_FIELD - HALF_FOOTPRINT, 0.0, 0.0)) // already pinned against +x wall

        listOf(fl, fr, bl, br).forEach { it.setPower(1.0) } // keep driving straight into the wall

        var previousEncoder = fl.getCurrentPosition()
        repeat(100) {
            listOf(fl, fr, bl, br).forEach { it.update(DT) }
            robot.update(DT)

            assertEquals("pose.x should stay pinned at the wall", HALF_FIELD - HALF_FOOTPRINT, robot.pose.x, 1e-9)
            assertTrue("the motor's encoder should keep advancing even though the chassis is blocked", fl.getCurrentPosition() > previousEncoder)
            previousEncoder = fl.getCurrentPosition()
        }
    }

    @Test
    fun `x and y clamp independently, so the robot can slide along a wall it's pinned against`() {
        val fl = motor(0); val fr = motor(1); val bl = motor(2); val br = motor(3)
        val robot = MecanumRobot(fl, fr, bl, br)
        robot.resetPose(Pose(HALF_FIELD - HALF_FOOTPRINT, 0.0, 0.0)) // pinned against +x wall

        // Commands both a push further into the +x wall (blocked) and a lateral component (free).
        fl.setPower(1.0); br.setPower(1.0); fr.setPower(0.0); bl.setPower(0.0)

        repeat(200) {
            listOf(fl, fr, bl, br).forEach { it.update(DT) }
            robot.update(DT)
            assertEquals("x should stay pinned at the wall the whole time", HALF_FIELD - HALF_FOOTPRINT, robot.pose.x, 1e-9)
        }

        assertTrue("expected the robot to have slid laterally along the wall", kotlin.math.abs(robot.pose.y) > 5.0)
    }
}
