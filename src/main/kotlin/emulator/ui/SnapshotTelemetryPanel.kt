package emulator.ui

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

/** Shows a caller-supplied telemetry snapshot, or a crash's stack trace if [crashSupplier] returns one. */
class SnapshotTelemetryPanel(
    private val snapshotSupplier: () -> List<String>,
    private val crashSupplier: () -> Throwable?
) : JPanel(BorderLayout()) {
    private val normalColor = Color(0, 0, 0) // <- change this to recolor the telemetry text
    private val textArea = JTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        background = Color(255, 255, 255)
    }

    init {
        add(JScrollPane(textArea), BorderLayout.CENTER)
    }

    fun onTick() {
        val crash = crashSupplier()
        if (crash != null) {
            textArea.foreground = Color(255, 110, 110)
            textArea.text = "OpMode threw ${crash.javaClass.simpleName}: ${crash.message}\n\n" +
                crash.stackTrace.take(20).joinToString("\n") { "  at $it" }
        } else {
            textArea.foreground = normalColor
            textArea.text = snapshotSupplier().joinToString("\n")
        }
    }
}
