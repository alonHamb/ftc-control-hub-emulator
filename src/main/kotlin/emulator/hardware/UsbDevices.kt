package emulator.hardware

/** Which kind of USB peripheral a [SimUsbDevice] stands in for. */
enum class UsbDeviceType(val label: String) {
    WEBCAM("Webcam"),
    SERIAL("USB Serial")
}

/**
 * Base type for anything connected over USB rather than plugged into a REV hub port -- a webcam or
 * a generic USB-serial peripheral (an FTDI/CP210x bridge, a non-REV USB sensor or actuator). Real
 * USB devices are identified by serial number and support hotplug rather than living at a fixed
 * hub/port/bus address, so this doesn't extend [SimDevice] -- there's no [PortId] to give it.
 */
sealed class SimUsbDevice(val name: String, val serialNumber: String?, val type: UsbDeviceType) {
    /** Whether the device is currently plugged in. Starts connected; see [connect]/[disconnect]. */
    var connected: Boolean = true
        private set

    fun connect() {
        connected = true
    }

    fun disconnect() {
        connected = false
    }

    open fun activitySummary(): String {
        val state = if (connected) "connected" else "disconnected"
        return if (serialNumber != null) "$state  sn=$serialNumber" else state
    }
}

/**
 * A USB webcam. Frames aren't modeled -- this only tracks identity and connection state, same as
 * `emulator.config.ConfiguredWebcam` did before it became a live device -- so feed fake frames
 * through whatever vision-pipeline adapter you write, keyed off [connected] if it should stop
 * producing frames while unplugged.
 */
class SimWebcam(name: String, serialNumber: String?) : SimUsbDevice(name, serialNumber, UsbDeviceType.WEBCAM)

/**
 * A generic USB-serial peripheral -- anything the real SDK would talk to as a raw byte stream over
 * a USB-serial adapter rather than through a REV hub port or an I2C bus. Holds a simple RX buffer
 * you [feedIncoming] from your test/adapter code and a record of the most recent [write], since
 * that's the shape any adapter actually needs to drive or observe it.
 */
class SimUsbSerialDevice(name: String, serialNumber: String?) : SimUsbDevice(name, serialNumber, UsbDeviceType.SERIAL) {
    private val rxBuffer = ArrayDeque<Byte>()
    private var lastWritten: ByteArray = ByteArray(0)

    /** Records [data] as the most recent write -- see [lastWrite]. */
    fun write(data: ByteArray) {
        lastWritten = data
    }

    /** The bytes passed to the most recent [write], for a test/adapter to assert against. */
    fun lastWrite(): ByteArray = lastWritten

    /** Queues [data] as bytes the device has "received" over the wire, ready for [read]. */
    fun feedIncoming(data: ByteArray) {
        rxBuffer.addAll(data.toList())
    }

    /** How many bytes are queued and ready for [read]. */
    fun bytesAvailable(): Int = rxBuffer.size

    /** Dequeues up to [maxBytes] previously-[feedIncoming]d bytes. */
    fun read(maxBytes: Int): ByteArray {
        val count = minOf(maxBytes, rxBuffer.size)
        return ByteArray(count) { rxBuffer.removeFirst() }
    }

    override fun activitySummary(): String =
        "${super.activitySummary()}  rx=${rxBuffer.size}B pending  lastWrite=${lastWritten.size}B"
}
