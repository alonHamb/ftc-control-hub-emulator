package emulator.config

import emulator.hardware.HubId

@DslMarker
annotation class RobotConfigDsl

/**
 * Devices on one hub -- shared by [RobotConfigBuilder] (the Control Hub, implicitly) and
 * [RobotConfigBuilder.expansionHub]. Named functions cover the device types this library actually
 * simulates (see `emulator.hardware`); [device] is the escape hatch for anything else the real SDK
 * supports -- it accepts any tag name REV Hardware Client would recognize, so nothing you can wire
 * up on real hardware is unreachable from this DSL even if this library has no specific simulated
 * behavior for it (see [buildSimulatedRobot]'s fallback rules). See [ServoHubBuilder] for
 * [RobotConfigBuilder.servoHub], which deliberately does not share this class.
 */
@RobotConfigDsl
open class HubDeviceBuilder internal constructor(private val hub: HubId) {
    internal val devices = mutableListOf<ConfiguredDevice>()

    fun motor(name: String, port: Int) = device("Motor", name, port = port)
    fun servo(name: String, port: Int) = device("Servo", name, port = port)
    fun crServo(name: String, port: Int) = device("CRServo", name, port = port)
    fun touchSensor(name: String, port: Int) = device("TouchSensor", name, port = port)
    fun digitalChannel(name: String, port: Int) = device("DigitalChannel", name, port = port)
    fun analogInput(name: String, port: Int) = device("AnalogInput", name, port = port)

    /** [tagName] defaults to REV's own embedded IMU; pass e.g. `"AdafruitBNO055IMU"` for a different one. */
    fun imu(name: String, bus: Int, tagName: String = "LynxEmbeddedIMU") = device(tagName, name, bus = bus)

    /**
     * Any other I2C sensor -- a color/distance/compass sensor, or anything a vendor ships next.
     * [i2cAddress] is optional -- pass it (e.g. `0x3c`) if your adapter needs to see the device's
     * real I2C address on `SimI2cDevice.i2cAddress`; most adapters don't need it.
     */
    fun i2cDevice(tagName: String, name: String, bus: Int, i2cAddress: Int? = null) =
        device(tagName, name, bus = bus, i2cAddress = i2cAddress)

    /** The escape hatch every named function above is built on -- use this for any tag they don't cover. */
    fun device(tagName: String, name: String, port: Int? = null, bus: Int? = null, i2cAddress: Int? = null) {
        devices += ConfiguredDevice(tagName, name, hub, port, bus, i2cAddress)
    }
}

/**
 * Devices on a REV Servo Hub, for [RobotConfigBuilder.servoHub] -- deliberately not a
 * [HubDeviceBuilder]: a Servo Hub has 6 servo ports and nothing else, so unlike every other hub
 * this builder exposes no motor/sensor/IMU functions and no [HubDeviceBuilder.device] escape
 * hatch. There's no way to add a non-servo device to it -- the Kotlin compiler rejects it, the
 * same way REV's own hardware would reject plugging a motor into a servo port.
 */
@RobotConfigDsl
class ServoHubBuilder internal constructor() {
    internal val devices = mutableListOf<ConfiguredDevice>()

    fun servo(name: String, port: Int) {
        devices += ConfiguredDevice("Servo", name, HubId.SERVO_HUB, port, bus = null)
    }

    fun crServo(name: String, port: Int) {
        devices += ConfiguredDevice("CRServo", name, HubId.SERVO_HUB, port, bus = null)
    }
}

/**
 * Builds a [RobotConfig] entirely in Kotlin -- no handwritten XML, no REV Hardware Client round
 * trip required to keep it up to date. Devices declared directly on this builder are on the
 * Control Hub; wrap a block in [expansionHub] for a second hub. See [robotConfig] and
 * [writeRobotConfigXml] to turn the result into the real config file your project uploads, and
 * [buildSimulatedRobot] to use it in the emulator directly, with no XML step at all.
 */
@RobotConfigDsl
class RobotConfigBuilder internal constructor() : HubDeviceBuilder(HubId.CONTROL) {
    private var expansionDevices: List<ConfiguredDevice> = emptyList()
    private var servoHubDevices: List<ConfiguredDevice> = emptyList()
    private val webcams = mutableListOf<ConfiguredWebcam>()
    private val usbSerialDevices = mutableListOf<ConfiguredUsbSerialDevice>()

    fun expansionHub(block: HubDeviceBuilder.() -> Unit) {
        expansionDevices = HubDeviceBuilder(HubId.EXPANSION).apply(block).devices
    }

    /** A REV Servo Hub -- its own module with 6 servo ports, not devices wired to Control/Expansion Hub. */
    fun servoHub(block: ServoHubBuilder.() -> Unit) {
        servoHubDevices = ServoHubBuilder().apply(block).devices
    }

    fun webcam(name: String, serialNumber: String? = null) {
        webcams += ConfiguredWebcam(name, serialNumber)
    }

    /** A generic USB-serial peripheral -- see [ConfiguredUsbSerialDevice]. */
    fun usbSerialDevice(name: String, serialNumber: String? = null) {
        usbSerialDevices += ConfiguredUsbSerialDevice(name, serialNumber)
    }

    internal fun build(): RobotConfig = RobotConfig(devices + expansionDevices + servoHubDevices, webcams, usbSerialDevices)
}

/**
 * The single source of truth for your robot's wiring, written once in Kotlin:
 *
 * ```
 * val robotMap = robotConfig {
 *     motor("left_front_drive", port = 0)
 *     motor("left_back_drive", port = 1)
 *     servo("claw_servo", port = 0)
 *     imu("imu", bus = 0)
 *     expansionHub {
 *         motor("arm_motor", port = 0)
 *     }
 * }
 * ```
 *
 * Feed the result to [buildSimulatedRobot] directly for the emulator, and to [writeRobotConfigXml]
 * to (re)generate the real hardware configuration file your project uploads to the Control Hub --
 * see the README for wiring that generation step into your build so the two can never drift apart.
 */
fun robotConfig(block: RobotConfigBuilder.() -> Unit): RobotConfig = RobotConfigBuilder().apply(block).build()
