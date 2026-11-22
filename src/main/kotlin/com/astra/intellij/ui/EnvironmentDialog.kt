package com.astra.intellij.ui

import com.astra.intellij.model.AstraEnvironment
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultCellEditor
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

class EnvironmentDialog(
    private val source: MutableList<AstraEnvironment>,
    activeId: String?,
) : DialogWrapper(true) {
    private val environments = source.map {
        AstraEnvironment(it.id, it.name, LinkedHashMap(it.variables))
    }.toMutableList()
    private val listModel = DefaultListModel<String>()
    private val list = JList(listModel)
    private val nameField = JTextField()
    private val tableModel = object : DefaultTableModel(arrayOf("Variable", "Value"), 0) {}
    private val table = JTable(tableModel)
    private var selectedId: String? = null

    init {
        title = "Environments"
        environments.forEach { listModel.addElement(it.name) }
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.addListSelectionListener {
            if (!it.valueIsAdjusting) saveSelected()
            val env = environments.getOrNull(list.selectedIndex)
            if (env != null) {
                selectedId = env.id
                nameField.text = env.name
                loadVariables(env)
            }
        }
        nameField.addActionListener { renameSelected() }
        val leftRenderer = DefaultTableCellRenderer().apply {
            horizontalAlignment = SwingConstants.LEFT
        }
        table.columnModel.getColumn(0).cellRenderer = leftRenderer
        table.columnModel.getColumn(1).cellRenderer = leftRenderer
        (table.getDefaultEditor(String::class.java) as? DefaultCellEditor)?.clickCountToStart = 1
        val index = environments.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        list.selectedIndex = index
        init()
    }

    override fun createCenterPanel(): JComponent {
        val left = JPanel(BorderLayout()).apply {
            add(JScrollPane(list), BorderLayout.CENTER)
            preferredSize = JBUI.size(180, 320)
        }
        val right = JPanel(BorderLayout(0, 8)).apply {
            add(JPanel(BorderLayout()).apply {
                add(JLabel("Environment Name"), BorderLayout.WEST)
                add(nameField, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
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
        }
        return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply {
            dividerLocation = JBUI.scale(180)
            preferredSize = JBUI.size(680, 360)
        }
    }

    override fun createLeftSideActions(): Array<Action> {
        val add = object : AbstractAction("New Environment") {
            override fun actionPerformed(e: ActionEvent?) {
                saveSelected()
                val env = AstraEnvironment(name = "New Environment")
                environments.add(env)
                listModel.addElement(env.name)
                list.selectedIndex = listModel.size - 1
            }
        }
        val delete = object : AbstractAction("Delete Environment") {
            override fun actionPerformed(e: ActionEvent?) {
                if (environments.size <= 1) return
                val index = list.selectedIndex.coerceAtLeast(0)
                environments.removeAt(index)
                listModel.remove(index)
                list.selectedIndex = index.coerceAtMost(listModel.size - 1)
            }
        }
        return arrayOf(add, delete)
    }

    override fun doOKAction() {
        saveSelected()
        renameSelected()
        source.clear()
        source.addAll(environments)
        super.doOKAction()
    }

    fun selectedEnvironmentId(): String? = selectedId

    private fun renameSelected() {
        val env = environments.getOrNull(list.selectedIndex) ?: return
        val name = nameField.text.trim().ifEmpty { "Untitled Environment" }
        env.name = name
        listModel.set(list.selectedIndex, name)
    }

    private fun saveSelected() {
        val env = environments.firstOrNull { it.id == selectedId } ?: return
        if (table.isEditing) table.cellEditor.stopCellEditing()
        env.variables.clear()
        for (row in 0 until tableModel.rowCount) {
            val key = tableModel.getValueAt(row, 0)?.toString()?.trim().orEmpty()
            if (key.isNotEmpty()) env.variables[key] = tableModel.getValueAt(row, 1)?.toString().orEmpty()
        }
    }

    private fun loadVariables(env: AstraEnvironment) {
        tableModel.rowCount = 0
        env.variables.forEach { (key, value) -> tableModel.addRow(arrayOf(key, value)) }
        tableModel.addRow(arrayOf("", ""))
    }
}
