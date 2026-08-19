package emulator.demo

import emulator.hardware.HubId
import emulator.hardware.PortId
import emulator.hardware.PortType
import emulator.hardware.SimMotor
import emulator.hardware.SimServo
import emulator.sim.BatteryModel
import emulator.sim.MecanumRobot
import emulator.sim.Pose
import emulator.ui.PortRowView
import emulator.ui.runRunnerShellAndBlock
import kotlin.math.abs

/**
 * A minimal "robot" wired up purely to give this library something to run and click around -- see
 * the README's "Integrating into an existing FTC SDK project" section for how a real consumer
 * instead adapts these sim devices to back a real hardware SDK's interfaces and drives an actual
 * OpMode lifecycle.
 */
fun main() {
    val frontLeft = SimMotor(PortId(HubId.CONTROL, PortType.MOTOR, 0), "front left motor")
    val frontRight = SimMotor(PortId(HubId.CONTROL, PortType.MOTOR, 1), "front right motor")
    val backLeft = SimMotor(PortId(HubId.CONTROL, PortType.MOTOR, 2), "back left motor")
    val backRight = SimMotor(PortId(HubId.CONTROL, PortType.MOTOR, 3), "back right motor")
    val claw = SimServo(PortId(HubId.CONTROL, PortType.SERVO, 0), "claw servo")
    val motors = listOf(frontLeft, frontRight, backLeft, backRight)
    val devices = motors + claw

    val drivetrain = MecanumRobot(frontLeft, frontRight, backLeft, backRight)
    val battery = BatteryModel()

    var running = false
    var clawOpen = false

    val portRows = listOf(
        PortRowView(HubId.CONTROL.label, PortType.MOTOR.label, 0, frontLeft.name) { frontLeft.activitySummary() },
        PortRowView(HubId.CONTROL.label, PortType.MOTOR.label, 1, frontRight.name) { frontRight.activitySummary() },
        PortRowView(HubId.CONTROL.label, PortType.MOTOR.label, 2, backLeft.name) { backLeft.activitySummary() },
        PortRowView(HubId.CONTROL.label, PortType.MOTOR.label, 3, backRight.name) { backRight.activitySummary() },
        PortRowView(HubId.CONTROL.label, PortType.SERVO.label, 0, claw.name) { claw.activitySummary() }
    )

    runRunnerShellAndBlock(
        title = "FTC Control Hub Emulator -- Demo",
        opModeNames = listOf("Demo: Mecanum Drive + Claw"),
        onInit = {
            running = false
            clawOpen = false
            motors.forEach { it.setPower(0.0) }
            claw.setPosition(0.0)
        },
        onStart = { running = true },
        onStop = { running = false },
        onResetField = { drivetrain.resetPose(Pose(0.0, 0.0, 0.0)) },
        onTick = { dtSeconds, gamepads ->
            if (running) {
                val gp = gamepads.gamepad1
                val y = -gp.leftStickY.toDouble()
                val x = gp.leftStickX.toDouble() * 1.1 // mecanum strafes slightly weaker than it drives forward; counteract that
                val rx = gp.rightStickX.toDouble()

                var fl = y + x + rx
                var bl = y - x + rx
                var fr = y - x - rx
                var br = y + x - rx
                val scale = maxOf(abs(fl), abs(bl), abs(fr), abs(br), 1.0)
                fl /= scale; bl /= scale; fr /= scale; br /= scale

                frontLeft.setPower(fl)
                backLeft.setPower(bl)
                frontRight.setPower(fr)
                backRight.setPower(br)

                if (gp.a) clawOpen = true
                if (gp.b) clawOpen = false
                claw.setPosition(if (clawOpen) 1.0 else 0.0)
            } else {
                motors.forEach { it.setPower(0.0) }
            }

            devices.forEach { it.update(dtSeconds) }
            drivetrain.update(dtSeconds)
            battery.update(devices.sumOf { it.currentDrawAmps() })
        },
        poseSupplier = { drivetrain.pose },
        portRowsSupplier = { portRows },
        telemetrySupplier = {
            val pose = drivetrain.pose
            listOf(
                "pose x=%.1f\" y=%.1f\" heading=%.0f°".format(pose.x, pose.y, Math.toDegrees(pose.headingRad)),
                "battery=%.2fV".format(battery.voltage),
                "claw=${if (clawOpen) "open" else "closed"}"
            )
        },
        crashSupplier = { null },
        statusSupplier = { if (running) "State: RUNNING" else "State: STOPPED" },
        batteryVoltageSupplier = { battery.voltage }
    )
}
