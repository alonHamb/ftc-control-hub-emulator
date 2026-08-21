package emulator.config

import emulator.hardware.HubId
import java.io.File
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** The address REV Hardware Client uses for a daisy-chained Expansion Hub in every real config file this was checked against. */
private const val EXPANSION_HUB_MODULE_PORT = "2"

/**
 * The reverse of [parseRobotConfigXml]: renders [config] as a REV Hardware Client-shaped hardware
 * configuration XML string -- real enough that [parseRobotConfigXml] round-trips it back to an
 * equal [RobotConfig], and that the REV Hardware Client / Driver Station app accepts it as a
 * config file in its own right. Every device keeps whichever tag name it was given (by
 * [buildSimulatedRobot]'s classification or, more likely, by whichever [HubDeviceBuilder] function
 * you built it with -- see [robotConfig]), so this is really just "the same wiring, written out
 * the way REV's tools expect" rather than a new source of truth.
 */
fun writeRobotConfigXml(config: RobotConfig): String {
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()

    val robot = document.createElement("Robot").apply { setAttribute("type", "FirstInspires-FTC") }
    document.appendChild(robot)

    val usbDevice = document.createElement("LynxUsbDevice").apply {
        setAttribute("name", "Control Hub Portal")
        setAttribute("parentModuleAddress", CONTROL_HUB_MODULE_PORT)
        setAttribute("serialNumber", "(embedded)")
    }
    robot.appendChild(usbDevice)

    fun appendModule(moduleName: String, modulePort: String, devices: List<ConfiguredDevice>) {
        if (devices.isEmpty() && modulePort == EXPANSION_HUB_MODULE_PORT) return // no expansion hub configured, don't invent one
        val module = document.createElement("LynxModule").apply {
            setAttribute("name", moduleName)
            setAttribute("port", modulePort)
        }
        for (configuredDevice in devices) {
            val deviceElement = document.createElement(configuredDevice.tagName)
            deviceElement.setAttribute("name", configuredDevice.name)
            configuredDevice.port?.let { deviceElement.setAttribute("port", it.toString()) }
            configuredDevice.bus?.let { deviceElement.setAttribute("bus", it.toString()) }
            configuredDevice.i2cAddress?.let { deviceElement.setAttribute("I2cAddress", "0x%02x".format(it)) }
            module.appendChild(deviceElement)
        }
        usbDevice.appendChild(module)
    }

    val devicesByHub = config.devices.groupBy { it.hub }
    appendModule("Control Hub", CONTROL_HUB_MODULE_PORT, devicesByHub[HubId.CONTROL].orEmpty())
    appendModule("Expansion Hub 2", EXPANSION_HUB_MODULE_PORT, devicesByHub[HubId.EXPANSION].orEmpty())

    for (webcam in config.webcams) {
        val webcamElement = document.createElement("Webcam")
        webcamElement.setAttribute("name", webcam.name)
        webcam.serialNumber?.let { webcamElement.setAttribute("serialNumber", it) }
        robot.appendChild(webcamElement)
    }

    for (usbSerialDevice in config.usbSerialDevices) {
        val usbDeviceElement = document.createElement("UsbDevice")
        usbDeviceElement.setAttribute("name", usbSerialDevice.name)
        usbSerialDevice.serialNumber?.let { usbDeviceElement.setAttribute("serialNumber", it) }
        robot.appendChild(usbDeviceElement)
    }

    val transformer = TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.INDENT, "yes")
        setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
    }
    val writer = StringWriter()
    transformer.transform(DOMSource(document), StreamResult(writer))
    return writer.toString()
}

/**
 * Writes [writeRobotConfigXml]'s output to [file], creating parent directories if needed -- point
 * this at your project's real config path (e.g. `TeamCode/src/main/res/xml/<config name>.xml`) and
 * run it as a build step before packaging, so the file that ships to your Control Hub is always
 * freshly generated from the same [RobotConfig] the emulator uses -- see the README.
 */
fun writeRobotConfigXml(config: RobotConfig, file: File) {
    file.parentFile?.mkdirs()
    file.writeText(writeRobotConfigXml(config))
}
