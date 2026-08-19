package emulator.hardware

/** Which physical REV hub a port lives on. Mirrors a two-hub (Control + Expansion) FTC robot. */
enum class HubId(val label: String) {
    CONTROL("Control Hub"),
    EXPANSION("Expansion Hub")
}

enum class PortType(val label: String, val count: Int) {
    MOTOR("Motor", 4),
    SERVO("Servo", 6),
    DIGITAL("Digital", 8),
    ANALOG("Analog", 4),
    I2C("I2C", 4)
}

data class PortId(val hub: HubId, val type: PortType, val index: Int) {
    init {
        require(index in 0 until type.count) { "${type.label} port $index out of range for ${hub.label}" }
    }

    override fun toString() = "${hub.label} ${type.label} $index"
}
