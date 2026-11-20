package com.astra.intellij.ui

import com.astra.intellij.model.AstraEnvironment
import com.intellij.openapi.ui.DialogWrapper
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
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
    private val tableModel = object : DefaultTableModel(arrayOf("变量", "值"), 0) {}
    private val table = JTable(tableModel)
    private var selectedId: String? = null

    init {
        title = "环境变量"
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
        val index = environments.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        list.selectedIndex = index
        init()
    }

    override fun createCenterPanel(): JComponent {
        val left = JPanel(BorderLayout()).apply {
            add(JScrollPane(list), BorderLayout.CENTER)
            preferredSize = Dimension(180, 320)
        }
        val right = JPanel(BorderLayout(0, 8)).apply {
            add(JPanel(BorderLayout()).apply {
                add(javax.swing.JLabel("环境名称"), BorderLayout.WEST)
                add(nameField, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(JScrollPane(table), BorderLayout.CENTER)
        }
        return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply {
            dividerLocation = 180
            preferredSize = Dimension(680, 360)
        }
    }

    override fun createSouthPanel(): JComponent {
        val panel = super.createSouthPanel() as JPanel
        val add = javax.swing.JButton("新建环境")
        val delete = javax.swing.JButton("删除环境")
        add.addActionListener {
            saveSelected()
            val env = AstraEnvironment(name = "新环境")
            environments.add(env)
            listModel.addElement(env.name)
            list.selectedIndex = listModel.size - 1
        }
        delete.addActionListener {
            if (environments.size <= 1) return@addActionListener
            val index = list.selectedIndex.coerceAtLeast(0)
            environments.removeAt(index)
            listModel.remove(index)
            list.selectedIndex = index.coerceAtMost(listModel.size - 1)
        }
        panel.add(add, 0)
        panel.add(delete, 1)
        return panel
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
        val name = nameField.text.trim().ifEmpty { "未命名环境" }
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
