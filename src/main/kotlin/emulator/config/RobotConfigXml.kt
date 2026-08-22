package emulator.config

import emulator.hardware.HubId
import emulator.hardware.PortId
import emulator.hardware.PortType
import emulator.hardware.SimAnalogDevice
import emulator.hardware.SimDevice
import emulator.hardware.SimDigitalDevice
import emulator.hardware.SimI2cDevice
import emulator.hardware.SimImu
import emulator.hardware.SimMotor
import emulator.hardware.SimServo
import emulator.hardware.SimUsbDevice
import emulator.hardware.SimUsbSerialDevice
import emulator.hardware.SimWebcam
import emulator.ui.PortRowView
import org.w3c.dom.Element
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * One non-webcam device entry parsed out of a hardware configuration XML file -- an FTC config's
 * device element (`<Motor name="..." port="0" />`, `<LynxEmbeddedIMU name="..." bus="0" />`, ...)
 * before it's been matched to a simulated device type. [port] is set for hub-port devices (motors,
 * servos, digital, analog); [bus] is set for I2C-bus devices (color sensors, IMUs, ...); a device
 * can have neither if the file uses an attribute this parser doesn't recognize. [i2cAddress] is the
 * optional `I2cAddress` attribute (hex like `"0x3c"` or decimal) some I2C device tags carry.
 */
data class ConfiguredDevice(
    val tagName: String,
    val name: String,
    val hub: HubId,
    val port: Int?,
    val bus: Int?,
    val i2cAddress: Int? = null
)

/** A `<Webcam>` entry -- a USB device with no hub port, so it's tracked separately from everything else. */
data class ConfiguredWebcam(val name: String, val serialNumber: String?)

/**
 * A `<UsbDevice>` entry -- this library's own escape hatch for a generic USB-serial peripheral (the
 * real REV config format has no standard tag for these; only [ConfiguredWebcam] is standardized).
 * Not a hub-port device, same as [ConfiguredWebcam].
 */
data class ConfiguredUsbSerialDevice(val name: String, val serialNumber: String?)

/**
 * Everything parsed out of a hardware configuration XML file -- the same file the REV Hardware
 * Client / Driver Station app writes when you configure your real robot, and that your project
 * uploads to the Control Hub alongside your code. See [parseRobotConfigXml] to build one, and
 * [buildSimulatedRobot] to turn it into ready-to-use simulated devices.
 */
data class RobotConfig(
    val devices: List<ConfiguredDevice>,
    val webcams: List<ConfiguredWebcam>,
    val usbSerialDevices: List<ConfiguredUsbSerialDevice> = emptyList()
)

/** A REV Control Hub's own embedded `LynxModule` is always configured at this address. */
internal const val CONTROL_HUB_MODULE_PORT = "173"

/**
 * Parses an FTC hardware configuration XML file's text into a [RobotConfig]. Every `<LynxModule>`
 * in the file becomes a hub (the one at the fixed embedded address [CONTROL_HUB_MODULE_PORT] is
 * [HubId.CONTROL], every other one is treated as [HubId.EXPANSION] -- FTC only supports one of
 * each), and every element nested directly inside it becomes one [ConfiguredDevice], regardless of
 * whether this library recognizes its tag name -- see [buildSimulatedRobot] for what happens next.
 */
fun parseRobotConfigXml(xml: String): RobotConfig {
    val factory = DocumentBuilderFactory.newInstance().apply {
        // These are your own robot's config, not untrusted input, but there's no reason a hardware
        // config file should ever need to declare a DOCTYPE or pull in an external entity.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isExpandEntityReferences = false
    }
    val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    document.documentElement.normalize()

    fun Element.attr(attrName: String): String? = if (hasAttribute(attrName)) getAttribute(attrName) else null

    // Accepts the hex form REV Hardware Client writes ("0x3c") as well as a plain decimal string.
    fun parseI2cAddress(raw: String?): Int? = raw?.let {
        if (it.startsWith("0x", ignoreCase = true)) it.substring(2).toIntOrNull(16) else it.toIntOrNull()
    }

    val devices = mutableListOf<ConfiguredDevice>()
    val moduleNodes = document.getElementsByTagName("LynxModule")
    for (m in 0 until moduleNodes.length) {
        val module = moduleNodes.item(m) as? Element ?: continue
        val hub = if (module.attr("port") == CONTROL_HUB_MODULE_PORT) HubId.CONTROL else HubId.EXPANSION

        val children = module.childNodes
        for (c in 0 until children.length) {
            val element = children.item(c) as? Element ?: continue
            if (element.tagName == "RevRoboticsServoHub") continue // its own module, handled below
            val name = element.attr("name") ?: continue
            devices += ConfiguredDevice(
                tagName = element.tagName,
                name = name,
                hub = hub,
                port = element.attr("port")?.toIntOrNull(),
                bus = element.attr("bus")?.toIntOrNull(),
                i2cAddress = parseI2cAddress(element.attr("I2cAddress"))
            )
        }
    }

    // REV Servo Hub -- its own module with 6 servo ports, not a device wired to Control/Expansion
    // Hub, even though REV Hardware Client can nest its element under one for wiring purposes. A
    // Servo Hub has no motor/digital/analog/I2C ports on the real hardware, so any non-servo tag
    // nested under it is dropped rather than misclassified onto a port type it doesn't have.
    val servoHubNodes = document.getElementsByTagName("RevRoboticsServoHub")
    for (s in 0 until servoHubNodes.length) {
        val servoHub = servoHubNodes.item(s) as? Element ?: continue
        val children = servoHub.childNodes
        for (c in 0 until children.length) {
            val element = children.item(c) as? Element ?: continue
            if (element.tagName !in servoTags) continue // Servo Hub only has servo ports
            val name = element.attr("name") ?: continue
            devices += ConfiguredDevice(
                tagName = element.tagName,
                name = name,
                hub = HubId.SERVO_HUB,
                port = element.attr("port")?.toIntOrNull(),
                bus = null,
                i2cAddress = null
            )
        }
    }

    val webcams = mutableListOf<ConfiguredWebcam>()
    val webcamNodes = document.getElementsByTagName("Webcam")
    for (w in 0 until webcamNodes.length) {
        val element = webcamNodes.item(w) as? Element ?: continue
        val name = element.attr("name") ?: continue
        webcams += ConfiguredWebcam(name, element.attr("serialNumber"))
    }

    val usbSerialDevices = mutableListOf<ConfiguredUsbSerialDevice>()
    val usbDeviceNodes = document.getElementsByTagName("UsbDevice")
    for (u in 0 until usbDeviceNodes.length) {
        val element = usbDeviceNodes.item(u) as? Element ?: continue
        val name = element.attr("name") ?: continue
        usbSerialDevices += ConfiguredUsbSerialDevice(name, element.attr("serialNumber"))
    }

    return RobotConfig(devices, webcams, usbSerialDevices)
}

/** Reads and parses [file] -- see [parseRobotConfigXml]. */
fun parseRobotConfigXml(file: File): RobotConfig = parseRobotConfigXml(file.readText())

private enum class DeviceCategory { MOTOR, SERVO, DIGITAL, ANALOG, IMU, I2C, UNKNOWN }

// Tag names taken from real REV Hardware Client-exported config files. This list can't be
// exhaustive -- new sensor tags ship regularly -- so anything not listed here still gets a
// reasonable bucket: an unrecognized tag with a `bus` attribute is treated as a generic I2C
// device (matches how the real config format itself distinguishes I2C devices), and an
// unrecognized tag with a `port` attribute falls back to a generic digital device, since most
// unlabeled hub-port peripherals (limit switches, LEDs, misc I/O) are digital in practice. I2C
// sensor tags (LynxI2cColorRangeSensor, RevTOFDistanceSensor, AdaFruitColorSensor,
// ModernRoboticsI2cCompassSensor/Gyro/RangeSensor, MaxSonarI2CXL, HuskyLens, OctoQuad,
// GoBildaPinpointDriver, SparkFunOTOS, AndyMarkColorSensor/TimeOfFlight, SparkFunLEDStick, ...)
// are deliberately left off this list and handled by that generic `bus` fallback instead, since
// this library treats every non-IMU I2C device identically regardless of vendor/model.
private val motorTags = setOf("Motor", "GoBILDA5202SeriesMotor", "RevRoboticsCoreHexMotor", "RevRobotics20HDMotor")
private val servoTags = setOf("Servo", "CRServo", "ContinuousRotationServo")
private val imuTags = setOf("LynxEmbeddedIMU", "BNO055IMU", "AdafruitBNO055IMU", "IMU", "Rev9AxisImu", "AndyMarkIMU")
private val digitalTags = setOf(
    "TouchSensor", "RevTouchSensor", "DigitalChannel", "DigitalDevice", "REV_LED", "RevBlinkinLedDriver", "LimitSwitch"
)
private val analogTags = setOf("AnalogInput", "OpticalDistanceSensor", "AnalogGyro", "PotentiometerSensor")

private fun classify(device: ConfiguredDevice): DeviceCategory = when {
    device.tagName in motorTags -> DeviceCategory.MOTOR
    device.tagName in servoTags -> DeviceCategory.SERVO
    device.tagName in imuTags -> DeviceCategory.IMU
    device.bus != null -> DeviceCategory.I2C
    device.tagName in digitalTags -> DeviceCategory.DIGITAL
    device.tagName in analogTags -> DeviceCategory.ANALOG
    device.port != null -> DeviceCategory.DIGITAL
    else -> DeviceCategory.UNKNOWN
}

/**
 * Every device a [RobotConfig] resolved to a simulated stand-in, keyed by the name your OpMode
 * looks it up by -- [motors]/[servos] get this library's full simulated dynamics (see
 * `emulator.hardware.SimMotor`/`SimServo`); everything else gets a simpler stand-in you drive or
 * read directly (see `emulator.hardware.SimDigitalDevice`/`SimAnalogDevice`/`SimImu`/`SimI2cDevice`).
 * [webcams] and [usbSerialDevices] are USB devices -- see `emulator.hardware.SimUsbDevice` -- kept
 * separate from [allDevices] since they aren't plugged into a hub port. [unrecognized] lists
 * entries this parser couldn't place at all (an out-of-range port/bus index, usually) -- check it
 * rather than assuming every device in your config file made it across.
 */
class SimulatedRobot(
    val motors: Map<String, SimMotor>,
    val servos: Map<String, SimServo>,
    val digitalDevices: Map<String, SimDigitalDevice>,
    val analogDevices: Map<String, SimAnalogDevice>,
    val imus: Map<String, SimImu>,
    val i2cDevices: Map<String, SimI2cDevice>,
    val webcams: Map<String, SimWebcam>,
    val usbSerialDevices: Map<String, SimUsbSerialDevice>,
    val unrecognized: List<ConfiguredDevice>
) {
    /** Every simulated hub-port device -- everything except USB devices, which aren't on a hub port. */
    val allDevices: List<SimDevice> =
        motors.values + servos.values + digitalDevices.values + analogDevices.values + imus.values + i2cDevices.values

    /** Every simulated USB device -- [webcams] plus [usbSerialDevices]. */
    val allUsbDevices: List<SimUsbDevice> = webcams.values + usbSerialDevices.values

    /** Advances every device's dynamics by [dt] seconds -- call once per tick, same as calling [SimDevice.update] on each yourself. */
    fun updateAll(dt: Double) = allDevices.forEach { it.update(dt) }

    /** One [PortRowView] per device, ready to hand to `RunnerShellApp`'s `portRowsSupplier`. */
    fun toPortRows(): List<PortRowView> = allDevices.map { device ->
        PortRowView(device.port.hub.label, device.port.type.label, device.port.index, device.name) { device.activitySummary() }
    }

    /** One [PortRowView] per USB device -- "USB" in place of a hub, and an index within [allUsbDevices] in place of a port. */
    fun toUsbRows(): List<PortRowView> = allUsbDevices.mapIndexed { index, device ->
        PortRowView("USB", device.type.label, index, device.name) { device.activitySummary() }
    }
}

/**
 * Builds a simulated stand-in for every device in [config], matching it against known FTC config
 * tag names -- see [buildSimulatedRobot]'s implementation for the exact list -- with an unknown
 * tag falling back to a generic bucket by attribute shape rather than being dropped. A device
 * whose resulting [PortId] would be invalid (an out-of-range port/bus index) lands in
 * [SimulatedRobot.unrecognized] instead of throwing, so one malformed entry can't fail the whole
 * config.
 */
fun buildSimulatedRobot(config: RobotConfig): SimulatedRobot {
    val motors = mutableMapOf<String, SimMotor>()
    val servos = mutableMapOf<String, SimServo>()
    val digitalDevices = mutableMapOf<String, SimDigitalDevice>()
    val analogDevices = mutableMapOf<String, SimAnalogDevice>()
    val imus = mutableMapOf<String, SimImu>()
    val i2cDevices = mutableMapOf<String, SimI2cDevice>()
    val unrecognized = mutableListOf<ConfiguredDevice>()

    for (device in config.devices) {
        val placed = runCatching {
            when (classify(device)) {
                DeviceCategory.MOTOR -> motors[device.name] = SimMotor(PortId(device.hub, PortType.MOTOR, device.port!!), device.name)
                DeviceCategory.SERVO -> servos[device.name] = SimServo(PortId(device.hub, PortType.SERVO, device.port!!), device.name)
                DeviceCategory.DIGITAL -> digitalDevices[device.name] = SimDigitalDevice(PortId(device.hub, PortType.DIGITAL, device.port!!), device.name)
                DeviceCategory.ANALOG -> analogDevices[device.name] = SimAnalogDevice(PortId(device.hub, PortType.ANALOG, device.port!!), device.name)
                DeviceCategory.IMU -> imus[device.name] = SimImu(PortId(device.hub, PortType.I2C, device.bus ?: device.port ?: 0), device.name)
                DeviceCategory.I2C -> i2cDevices[device.name] = SimI2cDevice(
                    PortId(device.hub, PortType.I2C, device.bus ?: device.port ?: 0),
                    device.name,
                    device.i2cAddress ?: 0x00
                )
                DeviceCategory.UNKNOWN -> error("no attribute this parser recognizes")
            }
        }.isSuccess
        if (!placed) unrecognized += device
    }

    val webcams = config.webcams.associate { it.name to SimWebcam(it.name, it.serialNumber) }
    val usbSerialDevices = config.usbSerialDevices.associate { it.name to SimUsbSerialDevice(it.name, it.serialNumber) }

    return SimulatedRobot(motors, servos, digitalDevices, analogDevices, imus, i2cDevices, webcams, usbSerialDevices, unrecognized)
}
