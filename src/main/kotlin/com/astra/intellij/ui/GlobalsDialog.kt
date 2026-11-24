package com.astra.intellij.ui

import com.astra.intellij.core.VariableTextService
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

class GlobalsDialog(
    project: Project,
    private val globals: MutableMap<String, String>,
) : DialogWrapper(project) {
    private val model = DefaultTableModel(arrayOf("Variable", "Value"), 0)
    private val table = JTable(model)

    init {
        title = "Global Variables"
        globals.forEach { (key, value) -> model.addRow(arrayOf(key, value)) }
        if (model.rowCount == 0) model.addRow(arrayOf("", ""))
        init()
    }

    override fun createCenterPanel(): JComponent {
        table.putClientProperty("terminateEditOnFocusLost", true)
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 3, 3))
        buttons.add(JButton("+").apply {
            toolTipText = "Add variable"
            addActionListener { this@GlobalsDialog.model.addRow(arrayOf("", "")) }
        })
        buttons.add(JButton("−").apply {
            toolTipText = "Remove selected variable"
            addActionListener {
                val row = table.selectedRow
                if (row >= 0) this@GlobalsDialog.model.removeRow(row)
            }
        })
        buttons.add(JButton("Import .env / CSV").apply {
            addActionListener { importVariables() }
        })
        buttons.add(JButton("Copy .env").apply {
            addActionListener { copyDotEnv() }
        })
        return JPanel(BorderLayout(0, 4)).apply {
            add(JLabel("<html>Globals work in every environment as {{name}}. Environment variables override globals.</html>"), BorderLayout.NORTH)
            add(JScrollPane(table), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
            preferredSize = JBUI.size(480, 320)
        }
    }

    private fun importVariables() {
        val dialog = VariableImportDialog(contentPane)
        if (!dialog.showAndGet()) return
        if (table.isEditing) table.cellEditor.stopCellEditing()
        dialog.result.forEach { (key, value) ->
            var found = -1
            for (row in 0 until model.rowCount) {
                if (model.getValueAt(row, 0)?.toString()?.trim() == key) {
                    found = row
                    break
                }
            }
            if (found >= 0) {
                model.setValueAt(value, found, 1)
            } else {
                val trailingBlank = model.rowCount > 0 &&
                    model.getValueAt(model.rowCount - 1, 0)?.toString().isNullOrBlank()
                model.insertRow(
                    if (trailingBlank) model.rowCount - 1 else model.rowCount,
                    arrayOf<Any>(key, value),
                )
            }
        }
    }

    private fun copyDotEnv() {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        val enabled = linkedMapOf<String, String>()
        for (row in 0 until model.rowCount) {
            val key = model.getValueAt(row, 0)?.toString()?.trim().orEmpty()
            if (key.isEmpty()) continue
            enabled[key] = model.getValueAt(row, 1)?.toString().orEmpty()
        }
        CopyPasteManager.getInstance().setContents(StringSelection(VariableTextService.toDotEnv(enabled)))
    }

    override fun doOKAction() {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        globals.clear()
        for (row in 0 until model.rowCount) {
            val key = model.getValueAt(row, 0).toString().trim()
            if (key.isNotEmpty()) globals[key] = model.getValueAt(row, 1).toString()
        }
        super.doOKAction()
    }
}
