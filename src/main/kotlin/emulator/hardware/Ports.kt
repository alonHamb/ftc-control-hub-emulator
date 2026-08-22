package emulator.hardware

/**
 * Which physical REV hub a port lives on. Mirrors a Control + Expansion Hub FTC robot, plus an
 * optional REV Servo Hub -- a separate module (its own 6 servo ports, addressed independently of
 * the Control/Expansion Hub's own servo ports) rather than a device wired to one of them.
 */
enum class HubId(val label: String) {
    CONTROL("Control Hub"),
    EXPANSION("Expansion Hub"),
    SERVO_HUB("REV Servo Hub")
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
