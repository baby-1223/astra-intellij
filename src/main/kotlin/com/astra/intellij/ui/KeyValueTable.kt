package com.astra.intellij.ui

import com.astra.intellij.model.KeyValue
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JPanel
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
    private val bulkEditor = JBTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.label().size)
        lineWrap = false
        toolTipText = "One entry per line, in key: value form. Prefix a line with // to disable it."
    }
    private val cards = JPanel(CardLayout())
    private val toggleButton = JButton("Bulk Edit").apply {
        toolTipText = "Edit all entries as plain text"
    }
    private var bulkMode = false

    init {
        table.rowHeight = JBUI.scale(26)
        table.putClientProperty("terminateEditOnFocusLost", true)
        table.setShowGrid(false)
        table.columnModel.getColumn(0).maxWidth = 60
        table.preferredScrollableViewportSize = JBUI.size(420, 150)
        val leftRenderer = DefaultTableCellRenderer().apply {
            horizontalAlignment = SwingConstants.LEFT
        }
        table.columnModel.getColumn(1).cellRenderer = leftRenderer
        table.columnModel.getColumn(2).cellRenderer = leftRenderer
        (table.getDefaultEditor(String::class.java) as? javax.swing.DefaultCellEditor)?.clickCountToStart = 1
        val add = JButton("+").apply { toolTipText = "Add row" }
        val remove = JButton("−").apply { toolTipText = "Remove selected row" }
        add.addActionListener { model.addRow(arrayOf<Any>(true, "", "")) }
        remove.addActionListener {
            if (table.isEditing) table.cellEditor.stopCellEditing()
            val row = table.selectedRow
            if (row >= 0) model.removeRow(table.convertRowIndexToModel(row))
        }
        toggleButton.addActionListener { if (bulkMode) showTable() else showBulkEditor() }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(add)
            add(remove)
        }
        val buttons = JPanel(BorderLayout()).apply {
            add(actions, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply { add(toggleButton) }, BorderLayout.EAST)
        }
        cards.add(JBScrollPane(table), "table")
        cards.add(JBScrollPane(bulkEditor), "text")
        add(cards, BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
    }

    private fun showBulkEditor() {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        bulkEditor.text = toText()
        bulkMode = true
        toggleButton.text = "Table View"
        toggleButton.toolTipText = "Switch back to the table view"
        (cards.layout as CardLayout).show(cards, "text")
        bulkEditor.requestFocusInWindow()
    }

    private fun showTable() {
        applyBulkText()
        bulkMode = false
        toggleButton.text = "Bulk Edit"
        toggleButton.toolTipText = "Edit all entries as plain text"
        (cards.layout as CardLayout).show(cards, "table")
    }

    private fun toText(): String = (0 until model.rowCount)
        .map { row ->
            val enabled = model.getValueAt(row, 0) as? Boolean ?: true
            val key = model.getValueAt(row, 1)?.toString().orEmpty()
            val value = model.getValueAt(row, 2)?.toString().orEmpty()
            when {
                key.isBlank() && value.isBlank() -> ""
                enabled -> "$key: $value"
                else -> "// $key: $value"
            }
        }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    private fun applyBulkText() {
        model.rowCount = 0
        bulkEditor.text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val disabled = line.startsWith("//")
            val body = if (disabled) line.removePrefix("//").trim() else line
            if (body.isEmpty()) return@forEach
            val separator = body.indexOf(':')
            val key = if (separator >= 0) body.substring(0, separator).trim() else body
            val value = if (separator >= 0) body.substring(separator + 1).trim() else ""
            model.addRow(arrayOf<Any>(!disabled, key, value))
        }
        if (model.rowCount == 0) model.addRow(arrayOf<Any>(true, "", ""))
    }

    fun setValues(items: List<KeyValue>) {
        model.rowCount = 0
        items.forEach { model.addRow(arrayOf<Any>(it.enabled, it.key, it.value)) }
        if (items.isEmpty()) model.addRow(arrayOf<Any>(true, "", ""))
        if (bulkMode) bulkEditor.text = toText()
    }

    fun values(): MutableList<KeyValue> {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        if (bulkMode) applyBulkText()
        return (0 until model.rowCount).map { row ->
            KeyValue(
                enabled = model.getValueAt(row, 0) as? Boolean ?: true,
                key = model.getValueAt(row, 1)?.toString().orEmpty(),
                value = model.getValueAt(row, 2)?.toString().orEmpty(),
            )
        }.filterTo(mutableListOf()) { it.key.isNotBlank() || it.value.isNotBlank() }
    }
}
