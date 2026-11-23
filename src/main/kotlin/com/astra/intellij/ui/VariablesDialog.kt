package com.astra.intellij.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/** Edits the variables of a collection or folder in place when confirmed. */
class VariablesDialog(
    dialogTitle: String,
    private val variables: MutableMap<String, String>,
) : DialogWrapper(true) {
    private val tableModel = object : DefaultTableModel(arrayOf("Variable", "Value"), 0) {}
    private val table = JTable(tableModel)

    init {
        title = dialogTitle
        val renderer = DefaultTableCellRenderer().apply { horizontalAlignment = SwingConstants.LEFT }
        table.columnModel.getColumn(0).cellRenderer = renderer
        table.columnModel.getColumn(1).cellRenderer = renderer
        (table.getDefaultEditor(String::class.java) as? DefaultCellEditor)?.clickCountToStart = 1
        variables.forEach { (key, value) -> tableModel.addRow(arrayOf(key, value)) }
        tableModel.addRow(arrayOf("", ""))
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        add(JScrollPane(table), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JButton("Add Variable").apply {
                addActionListener {
                    if (table.isEditing) table.cellEditor.stopCellEditing()
                    tableModel.addRow(arrayOf("", ""))
                    val row = tableModel.rowCount - 1
                    table.setRowSelectionInterval(row, row)
                    table.scrollRectToVisible(table.getCellRect(row, 0, true))
                    table.editCellAt(row, 0)
                    table.editorComponent?.requestFocusInWindow()
                }
            })
            add(JButton("Remove Variable").apply {
                addActionListener {
                    if (table.isEditing) table.cellEditor.stopCellEditing()
                    val row = table.selectedRow
                    if (row >= 0) tableModel.removeRow(table.convertRowIndexToModel(row))
                }
            })
        }, BorderLayout.SOUTH)
        preferredSize = JBUI.size(560, 320)
    }

    override fun doOKAction() {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        variables.clear()
        for (row in 0 until tableModel.rowCount) {
            val key = tableModel.getValueAt(row, 0)?.toString()?.trim().orEmpty()
            if (key.isNotEmpty()) {
                variables[key] = tableModel.getValueAt(row, 1)?.toString().orEmpty()
            }
        }
        super.doOKAction()
    }
}
