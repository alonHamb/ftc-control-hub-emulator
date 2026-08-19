package emulator.sim

/** Very rough 12V SLA battery model: voltage sags under load, like a real robot battery does. */
class BatteryModel(private val nominalVoltage: Double = 12.7, private val internalResistanceOhms: Double = 0.06) {
    var voltage: Double = nominalVoltage
        private set

    fun update(totalCurrentDrawAmps: Double) {
        // 9.0V floor: roughly where a real 12V SLA pack is considered dead/brownout territory,
        // so a stalled robot sags toward it instead of the model driving voltage to zero.
        voltage = (nominalVoltage - totalCurrentDrawAmps * internalResistanceOhms).coerceIn(9.0, nominalVoltage)
    }
}
