package emulator.config

import emulator.hardware.HubId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotConfigDslTest {

    private fun sampleConfig() = robotConfig {
        motor("left_front_drive", port = 0)
        motor("left_back_drive", port = 1)
        servo("claw_servo", port = 0)
        crServo("intake_cr_servo", port = 1)
        touchSensor("arm_limit_switch", port = 0)
        digitalChannel("beam_break", port = 1)
        analogInput("arm_potentiometer", port = 0)
        imu("imu", bus = 0)
        i2cDevice("LynxI2cColorRangeSensor", "color_sensor", bus = 1, i2cAddress = 0x3c)
        device("SomeBrandNewSensorNobodyHasHeardOf", "mystery_i2c", bus = 2)
        webcam("Webcam 1", serialNumber = "A1B2C3D4")
        usbSerialDevice("usb_bridge", serialNumber = "FT1234")

        expansionHub {
            motor("arm_motor", port = 0)
            servo("turret_servo", port = 0)
        }

        servoHub {
            servo("hub_servo", port = 0)
        }
    }

    @Test
    fun `the DSL builds devices under the right hub without any XML`() {
        val config = sampleConfig()

        val leftFront = config.devices.single { it.name == "left_front_drive" }
        assertEquals("Motor", leftFront.tagName)
        assertEquals(HubId.CONTROL, leftFront.hub)
        assertEquals(0, leftFront.port)

        val armMotor = config.devices.single { it.name == "arm_motor" }
        assertEquals(HubId.EXPANSION, armMotor.hub)

        val mystery = config.devices.single { it.name == "mystery_i2c" }
        assertEquals("SomeBrandNewSensorNobodyHasHeardOf", mystery.tagName)
        assertEquals(2, mystery.bus)

        val colorSensor = config.devices.single { it.name == "color_sensor" }
        assertEquals(0x3c, colorSensor.i2cAddress)

        val hubServo = config.devices.single { it.name == "hub_servo" }
        assertEquals(HubId.SERVO_HUB, hubServo.hub)
        assertEquals("Servo", hubServo.tagName)

        assertEquals(listOf(ConfiguredWebcam("Webcam 1", "A1B2C3D4")), config.webcams)
        assertEquals(listOf(ConfiguredUsbSerialDevice("usb_bridge", "FT1234")), config.usbSerialDevices)
    }

    @Test
    fun `a config built in the DSL feeds buildSimulatedRobot directly, with no XML step at all`() {
        val robot = buildSimulatedRobot(sampleConfig())

        assertTrue(robot.motors.containsKey("left_front_drive"))
        assertTrue(robot.motors.containsKey("arm_motor"))
        assertTrue(robot.servos.containsKey("claw_servo"))
        assertTrue(robot.imus.containsKey("imu"))
        assertTrue(robot.i2cDevices.containsKey("mystery_i2c"))
        assertEquals(0x3c, robot.i2cDevices.getValue("color_sensor").i2cAddress)
        assertTrue(robot.webcams.containsKey("Webcam 1"))
        assertTrue(robot.usbSerialDevices.containsKey("usb_bridge"))
        assertTrue(robot.unrecognized.isEmpty())
        assertTrue("hub_servo (on the REV Servo Hub) should also resolve", robot.servos.containsKey("hub_servo"))
    }

    @Test
    fun `ServoHubBuilder exposes no way to add a non-servo device`() {
        // HubDeviceBuilder (used by the Control Hub and expansionHub) has motor/touchSensor/imu/
        // i2cDevice/device functions; ServoHubBuilder deliberately doesn't inherit from it, so
        // there's no motor(), no device("AnyTag", ...) escape hatch -- only servo ports exist on
        // a real REV Servo Hub, so that's all this builder can produce.
        val members = ServoHubBuilder::class.java.declaredMethods.map { it.name }
        val nonServoDeviceApis = setOf("motor", "touchSensor", "digitalChannel", "analogInput", "imu", "i2cDevice", "device")

        assertTrue("servo() should be exposed", members.contains("servo"))
        assertTrue("crServo() should be exposed", members.contains("crServo"))
        assertTrue(
            "ServoHubBuilder should expose none of $nonServoDeviceApis, found: $members",
            members.none { it in nonServoDeviceApis }
        )
    }

    @Test
    fun `writing then parsing a DSL-built config round-trips to an equal RobotConfig`() {
        val original = sampleConfig()

        val xml = writeRobotConfigXml(original)
        val roundTripped = parseRobotConfigXml(xml)

        assertEquals(original, roundTripped)
    }

    @Test
    fun `the generated XML is real REV Hardware Client shape -- Control Hub always at module port 173`() {
        val xml = writeRobotConfigXml(sampleConfig())

        assertTrue(xml.contains("""<Robot type="FirstInspires-FTC">"""))
        assertTrue(xml.contains("""port="173""""))
        assertTrue(xml.contains("<Motor"))
        assertTrue(xml.contains("""name="left_front_drive""""))
    }

    @Test
    fun `a config with no expansion hub devices doesn't invent an empty Expansion Hub module`() {
        val config = robotConfig { motor("only_motor", port = 0) }

        val xml = writeRobotConfigXml(config)

        assertTrue("no LynxModule for an unused Expansion Hub", !xml.contains("Expansion Hub"))
        assertEquals(config, parseRobotConfigXml(xml))
    }
}
