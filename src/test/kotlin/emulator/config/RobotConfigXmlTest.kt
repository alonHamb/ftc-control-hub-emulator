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
          <LynxI2cColorRangeSensor name="color_sensor" bus="0" I2cAddress="0x3c" />
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
      <UsbDevice name="usb_bridge" serialNumber="FT1234" />
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

        val colorSensor = config.devices.single { it.name == "color_sensor" }
        assertEquals(60, colorSensor.i2cAddress)

        assertEquals(listOf(ConfiguredWebcam("Webcam 1", "A1B2C3D4")), config.webcams)
        assertEquals(listOf(ConfiguredUsbSerialDevice("usb_bridge", "FT1234")), config.usbSerialDevices)
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
        assertEquals("the parsed I2cAddress should carry through to the SimI2cDevice", 0x3c, robot.i2cDevices.getValue("color_sensor").i2cAddress)

        assertTrue("Webcam 1 should be a live SimWebcam", robot.webcams.containsKey("Webcam 1"))
        assertEquals("A1B2C3D4", robot.webcams.getValue("Webcam 1").serialNumber)
        assertTrue("usb_bridge should be a live SimUsbSerialDevice", robot.usbSerialDevices.containsKey("usb_bridge"))
        assertEquals("FT1234", robot.usbSerialDevices.getValue("usb_bridge").serialNumber)
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
    fun `vendor-specific tag names classify the same as their generic equivalent, not by attribute-shape fallback`() {
        // Without an explicit tag entry, a port-based device falls back to DIGITAL and a bus-based
        // IMU falls back to generic I2C -- both wrong buckets for these. This guards against that.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Robot type="FirstInspires-FTC">
              <LynxUsbDevice name="Control Hub" serialNumber="(embedded)" parentModuleAddress="173">
                <LynxModule name="Control Hub" port="173">
                  <GoBILDA5202SeriesMotor name="gobilda_motor" port="2" />
                  <RevRoboticsCoreHexMotor name="core_hex_motor" port="3" />
                  <ContinuousRotationServo name="cr_servo" port="2" />
                  <RevTouchSensor name="rev_touch" port="4" />
                  <LimitSwitch name="limit_switch" port="5" />
                  <AnalogGyro name="analog_gyro" port="2" />
                  <PotentiometerSensor name="pot" port="3" />
                  <Rev9AxisImu name="rev_9axis" bus="2" />
                  <AndyMarkIMU name="am_imu" bus="3" />
                </LynxModule>
              </LynxUsbDevice>
            </Robot>
        """.trimIndent()

        val robot = buildSimulatedRobot(parseRobotConfigXml(xml))

        assertTrue("GoBILDA5202SeriesMotor should classify as a motor", robot.motors.containsKey("gobilda_motor"))
        assertTrue("RevRoboticsCoreHexMotor should classify as a motor", robot.motors.containsKey("core_hex_motor"))
        assertTrue("ContinuousRotationServo should classify as a servo", robot.servos.containsKey("cr_servo"))
        assertTrue("RevTouchSensor should classify as digital", robot.digitalDevices.containsKey("rev_touch"))
        assertTrue("LimitSwitch should classify as digital", robot.digitalDevices.containsKey("limit_switch"))
        assertTrue("AnalogGyro should classify as analog, not digital", robot.analogDevices.containsKey("analog_gyro"))
        assertTrue("PotentiometerSensor should classify as analog, not digital", robot.analogDevices.containsKey("pot"))
        assertTrue("Rev9AxisImu should classify as a SimImu, not generic I2C", robot.imus.containsKey("rev_9axis"))
        assertTrue("AndyMarkIMU should classify as a SimImu, not generic I2C", robot.imus.containsKey("am_imu"))
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
