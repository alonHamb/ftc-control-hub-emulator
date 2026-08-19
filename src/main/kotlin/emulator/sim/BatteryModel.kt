package emulator.sim

/** Very rough 12V SLA battery model: voltage sags under load, like a real robot battery does. */
class BatteryModel(private val nominalVoltage: Double = 12.7, private val internalResistanceOhms: Double = 0.06) {
    var voltage: Double = nominalVoltage
        private set

    fun update(totalCurrentDrawAmps: Double) {
        voltage = (nominalVoltage - totalCurrentDrawAmps * internalResistanceOhms).coerceIn(9.0, nominalVoltage)
    }
}
