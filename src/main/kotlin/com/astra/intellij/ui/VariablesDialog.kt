package com.astra.intellij.ui

import com.astra.intellij.core.VariableTextService
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
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
    private val disabledVariables: MutableSet<String>,
) : DialogWrapper(true) {
    private val tableModel = object : DefaultTableModel(arrayOf("Enabled", "Variable", "Value"), 0) {
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java
    }
    private val table = JTable(tableModel)

    init {
        title = dialogTitle
        val renderer = DefaultTableCellRenderer().apply { horizontalAlignment = SwingConstants.LEFT }
        table.columnModel.getColumn(1).cellRenderer = renderer
        table.columnModel.getColumn(2).cellRenderer = renderer
        table.columnModel.getColumn(0).preferredWidth = 70
        table.columnModel.getColumn(0).maxWidth = 70
        (table.getDefaultEditor(String::class.java) as? DefaultCellEditor)?.clickCountToStart = 1
        variables.forEach { (key, value) ->
            tableModel.addRow(arrayOf<Any>(key !in disabledVariables, key, value))
        }
        tableModel.addRow(arrayOf<Any>(true, "", ""))
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        add(JScrollPane(table), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JButton("Add Variable").apply {
                addActionListener {
                    if (table.isEditing) table.cellEditor.stopCellEditing()
                    tableModel.addRow(arrayOf<Any>(true, "", ""))
                    val row = tableModel.rowCount - 1
                    table.setRowSelectionInterval(row, row)
                    table.scrollRectToVisible(table.getCellRect(row, 0, true))
                    table.editCellAt(row, 1)
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
            add(JButton("Import .env / CSV").apply {
                addActionListener { importVariables() }
            })
            add(JButton("Copy .env").apply {
                addActionListener { copyDotEnv() }
            })
        }, BorderLayout.SOUTH)
        preferredSize = JBUI.size(560, 320)
    }

    private fun importVariables() {
        val dialog = VariableImportDialog(this.contentPane)
        if (!dialog.showAndGet()) return
        if (table.isEditing) table.cellEditor.stopCellEditing()
        dialog.result.forEach { (key, value) ->
            var found = -1
            for (row in 0 until tableModel.rowCount) {
                if (tableModel.getValueAt(row, 1)?.toString()?.trim() == key) {
                    found = row
                    break
                }
            }
            if (found >= 0) {
                tableModel.setValueAt(true, found, 0)
                tableModel.setValueAt(value, found, 2)
            } else {
                val trailingBlank = tableModel.rowCount > 0 &&
                    tableModel.getValueAt(tableModel.rowCount - 1, 1)?.toString().isNullOrBlank()
                val insertAt = if (trailingBlank) tableModel.rowCount - 1 else tableModel.rowCount
                tableModel.insertRow(insertAt, arrayOf<Any>(true, key, value))
            }
        }
    }

    private fun copyDotEnv() {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        val enabled = linkedMapOf<String, String>()
        for (row in 0 until tableModel.rowCount) {
            val key = tableModel.getValueAt(row, 1)?.toString()?.trim().orEmpty()
            if (key.isEmpty()) continue
            if (tableModel.getValueAt(row, 0) as? Boolean != true) continue
            enabled[key] = tableModel.getValueAt(row, 2)?.toString().orEmpty()
        }
        CopyPasteManager.getInstance().setContents(StringSelection(VariableTextService.toDotEnv(enabled)))
    }

    override fun doOKAction() {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        variables.clear()
        disabledVariables.clear()
        for (row in 0 until tableModel.rowCount) {
            val key = tableModel.getValueAt(row, 1)?.toString()?.trim().orEmpty()
            if (key.isEmpty()) continue
            variables[key] = tableModel.getValueAt(row, 2)?.toString().orEmpty()
            val enabled = tableModel.getValueAt(row, 0) as? Boolean ?: true
            if (!enabled) disabledVariables.add(key)
        }
        super.doOKAction()
    }
}
