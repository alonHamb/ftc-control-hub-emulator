package emulator.ui

import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.table.AbstractTableModel

private class PortRowTableModel(private val rows: List<PortRowView>) : AbstractTableModel() {
    private val columns = arrayOf("Hub", "Type", "Port", "Name", "Activity")

    fun refresh() = fireTableDataChanged()

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        return when (columnIndex) {
            0 -> row.hub
            1 -> row.type
            2 -> row.port
            3 -> row.name
            4 -> row.activitySummary()
            else -> ""
        }
    }
}

/** Live table of every port in a caller-supplied [PortRowView] list. */
class PortRowMonitorPanel(rows: List<PortRowView>) : JPanel(BorderLayout()) {
    private val model = PortRowTableModel(rows)
    private val table = JTable(model)

    init {
        table.fillsViewportHeight = true
        table.rowHeight = 22
        add(JScrollPane(table), BorderLayout.CENTER)
        model.refresh()
    }

    fun onTick() = model.refresh()
}
