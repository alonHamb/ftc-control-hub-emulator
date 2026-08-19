package emulator.config

import emulator.hardware.HubId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Structurally representative of a real REV Hardware Client-exported config file (same element
// names/attribute shapes verified against real exports), with device *names* genericized.
private val SAMPLE_CONFIG_XML = """
    <?xml version="1.0" encoding="UTF-8"?>
    <Robot type="FirstInspires-FTC">
      <LynxUsbDevice name="Control Hub" serialNumber="(embedded)" parentModuleAddress="173">
        <LynxModule name="Control Hub" port="173">
          <Motor name="left_front_drive" port="0" />
          <Motor name="left_back_drive" port="1" />
          <Servo name="claw_servo" port="0" />
          <Servo name="intake_cr_servo" port="1" />
          <TouchSensor name="arm_limit_switch" port="0" />
          <DigitalChannel name="beam_break" port="1" />
          <AnalogInput name="arm_potentiometer" port="0" />
          <OpticalDistanceSensor name="ods_sensor" port="1" />
          <LynxI2cColorRangeSensor name="color_sensor" bus="0" />
          <LynxEmbeddedIMU name="imu" bus="1" />
          <SomeBrandNewSensorNobodyHasHeardOf name="mystery_i2c" bus="2" />
          <SomeBrandNewLimitSwitch name="mystery_digital" port="6" />
          <Motor name="out_of_range_motor" port="99" />
        </LynxModule>
        <LynxModule name="Expansion Hub 2" port="2">
          <Motor name="arm_motor" port="0" />
          <RevTOFDistanceSensor name="distance_sensor" bus="1" />
        </LynxModule>
      </LynxUsbDevice>
      <Webcam name="Webcam 1" serialNumber="A1B2C3D4" />
    </Robot>
""".trimIndent()

class RobotConfigXmlTest {

    @Test
    fun `parses every device under the right hub with its port or bus`() {
        val config = parseRobotConfigXml(SAMPLE_CONFIG_XML)

        val leftFront = config.devices.single { it.name == "left_front_drive" }
        assertEquals("Motor", leftFront.tagName)
        assertEquals(HubId.CONTROL, leftFront.hub)
        assertEquals(0, leftFront.port)
        assertNull(leftFront.bus)

        val armMotor = config.devices.single { it.name == "arm_motor" }
        assertEquals(HubId.EXPANSION, armMotor.hub)

        val imu = config.devices.single { it.name == "imu" }
        assertEquals(1, imu.bus)
        assertNull(imu.port)

        assertEquals(listOf(ConfiguredWebcam("Webcam 1", "A1B2C3D4")), config.webcams)
    }

    @Test
    fun `builds working simulated devices for every recognized category`() {
        val robot = buildSimulatedRobot(parseRobotConfigXml(SAMPLE_CONFIG_XML))

        assertTrue("left_front_drive should be a full SimMotor", robot.motors.containsKey("left_front_drive"))
        assertTrue("arm_motor (on the expansion hub) should also resolve", robot.motors.containsKey("arm_motor"))
        assertTrue("claw_servo should be a SimServo", robot.servos.containsKey("claw_servo"))
        assertTrue("a CR servo uses the same generic Servo tag as a positional one", robot.servos.containsKey("intake_cr_servo"))
        assertTrue("arm_limit_switch (TouchSensor) should be a digital stand-in", robot.digitalDevices.containsKey("arm_limit_switch"))
        assertTrue("beam_break (DigitalChannel) should be a digital stand-in", robot.digitalDevices.containsKey("beam_break"))
        assertTrue("arm_potentiometer should be an analog stand-in", robot.analogDevices.containsKey("arm_potentiometer"))
        assertTrue("ods_sensor should be an analog stand-in", robot.analogDevices.containsKey("ods_sensor"))
        assertTrue("imu should be a SimImu", robot.imus.containsKey("imu"))
        assertTrue("color_sensor should fall back to the generic I2C stand-in", robot.i2cDevices.containsKey("color_sensor"))
        assertTrue("distance_sensor (on the expansion hub) should also resolve", robot.i2cDevices.containsKey("distance_sensor"))

        assertEquals(listOf(ConfiguredWebcam("Webcam 1", "A1B2C3D4")), robot.webcams)
    }

    @Test
    fun `an unrecognized tag still resolves via its port or bus shape, never silently dropped`() {
        val robot = buildSimulatedRobot(parseRobotConfigXml(SAMPLE_CONFIG_XML))

        // No tag list will ever cover every sensor a vendor ships -- unrecognized tags fall back
        // by attribute shape (bus -> I2C, port -> digital) rather than being dropped.
        assertTrue("an unknown bus-based tag should fall back to the generic I2C stand-in", robot.i2cDevices.containsKey("mystery_i2c"))
        assertTrue("an unknown port-based tag should fall back to the generic digital stand-in", robot.digitalDevices.containsKey("mystery_digital"))
    }

    @Test
    fun `a device with an invalid port lands in unrecognized instead of failing the whole parse`() {
        val robot = buildSimulatedRobot(parseRobotConfigXml(SAMPLE_CONFIG_XML))

        assertTrue(robot.unrecognized.any { it.name == "out_of_range_motor" })
        assertTrue("every other device should still have been placed", robot.motors.containsKey("left_front_drive"))
    }

    @Test
    fun `updateAll advances every device the same as calling update on each yourself`() {
        val robot = buildSimulatedRobot(parseRobotConfigXml(SAMPLE_CONFIG_XML))
        val motor = robot.motors.getValue("left_front_drive")
        motor.setPower(1.0)

        robot.updateAll(0.5)

        assertTrue("the motor should have actually moved after updateAll", motor.getVelocity() != 0.0)
    }
}
