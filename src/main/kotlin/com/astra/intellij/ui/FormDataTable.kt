package com.astra.intellij.ui

import com.astra.intellij.model.FormField
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Editor for `multipart/form-data` rows. Unlike [KeyValueTable] each row has a type: text rows send
 * their value, file rows send the bytes of the selected file with its file name.
 */
class FormDataTable : JPanel(BorderLayout()) {
    private val model = FieldModel()
    private val table = JTable(model)

    init {
        table.rowHeight = JBUI.scale(26)
        table.putClientProperty("terminateEditOnFocusLost", true)
        table.setShowGrid(false)
        table.columnModel.getColumn(0).maxWidth = 60
        table.columnModel.getColumn(2).maxWidth = 90
        val combo = JComboBox(arrayOf("text", "file"))
        table.columnModel.getColumn(2).cellEditor = DefaultCellEditor(combo)
        val left = DefaultTableCellRenderer().apply {
            horizontalAlignment = javax.swing.SwingConstants.LEFT
        }
        table.columnModel.getColumn(1).cellRenderer = left
        table.columnModel.getColumn(3).cellRenderer = left
        (table.getDefaultEditor(String::class.java) as? DefaultCellEditor)?.clickCountToStart = 1

        val add = JButton("+").apply { toolTipText = "Add field" }
        val remove = JButton("−").apply { toolTipText = "Remove selected field" }
        val choose = JButton("Choose File…").apply {
            toolTipText = "Pick the file sent by the selected field; its type becomes file"
        }
        add.addActionListener { model.add(FormField()) }
        remove.addActionListener {
            if (table.isEditing) table.cellEditor.stopCellEditing()
            val row = table.selectedRow
            if (row >= 0) model.removeAt(table.convertRowIndexToModel(row))
        }
        choose.addActionListener { chooseFile() }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(add)
            add(remove)
            add(choose)
        }
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(actions, BorderLayout.SOUTH)
    }

    private fun chooseFile() {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        val row = table.selectedRow
        if (row < 0) return
        val index = table.convertRowIndexToModel(row)
        val file: File = AstraFileChooser.openAny(this, "Select Upload File") ?: return
        model.update(index) {
            it.type = "file"
            it.filePath = file.absolutePath
            it.fileName = file.name
            it.value = ""
        }
    }

    fun setValues(items: List<FormField>) {
        model.setAll(items)
    }

    fun values(): MutableList<FormField> {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        return model.rows
            .filter { it.key.isNotBlank() || it.value.isNotBlank() || it.filePath.isNotBlank() }
            .map { it.copy() }
            .toMutableList()
    }

    fun installVariableCompletion(provider: () -> Collection<String>) {
        (table.getDefaultEditor(String::class.java) as? DefaultCellEditor)?.let { editor ->
            (editor.component as? javax.swing.text.JTextComponent)?.let {
                VariableCompletion.install(it, provider)
            }
        }
    }

    private class FieldModel : AbstractTableModel() {
        val rows = mutableListOf<FormField>()

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 4

        override fun getColumnName(column: Int): String = when (column) {
            0 -> "Enabled"
            1 -> "Key"
            2 -> "Type"
            else -> "Value"
        }

        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.enabled
                1 -> row.key
                2 -> if (row.isFile) "file" else "text"
                else -> if (row.isFile) row.fileName else row.value
            }
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            val row = rows[rowIndex]
            when (columnIndex) {
                0 -> row.enabled = value as? Boolean ?: true
                1 -> row.key = value?.toString().orEmpty()
                2 -> {
                    val type = value?.toString()?.lowercase() ?: "text"
                    row.type = if (type == "file") "file" else "text"
                    if (!row.isFile) {
                        row.filePath = ""
                        row.fileName = ""
                    }
                }
                else -> if (!row.isFile) row.value = value?.toString().orEmpty()
            }
            fireTableCellUpdated(rowIndex, columnIndex)
        }

        fun add(field: FormField) {
            rows.add(field)
            fireTableRowsInserted(rows.size - 1, rows.size - 1)
        }

        fun removeAt(index: Int) {
            if (index !in rows.indices) return
            rows.removeAt(index)
            fireTableRowsDeleted(index, index)
        }

        fun update(index: Int, block: (FormField) -> Unit) {
            if (index !in rows.indices) return
            block(rows[index])
            fireTableRowsUpdated(index, index)
        }

        fun setAll(items: List<FormField>) {
            rows.clear()
            rows.addAll(items.map { it.copy() })
            if (rows.isEmpty()) rows.add(FormField())
            fireTableDataChanged()
        }
    }
}
