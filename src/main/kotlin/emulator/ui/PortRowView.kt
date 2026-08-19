package emulator.ui

/** A plain, dependency-free description of one hub port, for [PortRowMonitorPanel]. */
data class PortRowView(val hub: String, val type: String, val port: Int, val name: String, val activitySummary: () -> String)
