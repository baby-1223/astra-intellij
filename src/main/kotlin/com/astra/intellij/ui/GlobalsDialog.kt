package com.astra.intellij.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
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
        return JPanel(BorderLayout(0, 4)).apply {
            add(JLabel("<html>Globals work in every environment as {{name}}. Environment variables override globals.</html>"), BorderLayout.NORTH)
            add(JScrollPane(table), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
            preferredSize = JBUI.size(480, 320)
        }
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
