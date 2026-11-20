package com.astra.intellij.ui

import com.astra.intellij.model.KeyValue
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

class KeyValueTable : JPanel(BorderLayout()) {
    private val model = object : DefaultTableModel(arrayOf("Enabled", "Key", "Value"), 0) {
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java
    }
    private val table = JTable(model)

    init {
        table.rowHeight = 26
        table.putClientProperty("terminateEditOnFocusLost", true)
        table.setShowGrid(false)
        table.columnModel.getColumn(0).maxWidth = 60
        table.preferredScrollableViewportSize = Dimension(420, 150)
        val leftRenderer = DefaultTableCellRenderer().apply {
            horizontalAlignment = SwingConstants.LEFT
        }
        table.columnModel.getColumn(1).cellRenderer = leftRenderer
        table.columnModel.getColumn(2).cellRenderer = leftRenderer
        (table.getDefaultEditor(String::class.java) as? javax.swing.DefaultCellEditor)?.clickCountToStart = 1
        val add = JButton("Add")
        val remove = JButton("Remove")
        add.addActionListener { model.addRow(arrayOf<Any>(true, "", "")) }
        remove.addActionListener {
            if (table.isEditing) table.cellEditor.stopCellEditing()
            val row = table.selectedRow
            if (row >= 0) model.removeRow(table.convertRowIndexToModel(row))
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(add)
            add(remove)
        }
        add(JScrollPane(table), BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
    }

    fun setValues(items: List<KeyValue>) {
        model.rowCount = 0
        items.forEach { model.addRow(arrayOf<Any>(it.enabled, it.key, it.value)) }
        if (items.isEmpty()) model.addRow(arrayOf<Any>(true, "", ""))
    }

    fun values(): MutableList<KeyValue> {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        return (0 until model.rowCount).map { row ->
            KeyValue(
                enabled = model.getValueAt(row, 0) as? Boolean ?: true,
                key = model.getValueAt(row, 1)?.toString().orEmpty(),
                value = model.getValueAt(row, 2)?.toString().orEmpty(),
            )
        }.filterTo(mutableListOf()) { it.key.isNotBlank() || it.value.isNotBlank() }
    }
}
