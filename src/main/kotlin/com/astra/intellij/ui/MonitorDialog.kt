package com.astra.intellij.ui

import com.astra.intellij.model.MonitorConfig
import com.astra.intellij.model.RequestCollection
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.table.DefaultTableModel

/** Manage the scheduled monitors that belong to one collection. */
class MonitorDialog(
    parent: Component,
    private val collection: RequestCollection,
    private val monitors: MutableList<MonitorConfig>,
    private val onRunNow: (String) -> Unit,
) : DialogWrapper(parent, true) {
    private val model = object : DefaultTableModel(
        arrayOf("Name", "Interval", "Iterations", "Enabled", "Last Run", "Result", "Last Error"),
        0,
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val table = JTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
    }

    init {
        title = "Monitors — ${collection.name}"
        init()
        refresh()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        border = JBUI.Borders.empty(8, 8)
        add(JScrollPane(table), BorderLayout.CENTER)
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(JButton("Add").apply {
                addActionListener {
                    val monitor = MonitorConfig(
                        collectionId = collection.id,
                        name = "Monitor ${monitors.size + 1}",
                    )
                    if (MonitorEditDialog(contentPanel, monitor).showAndGet()) {
                        monitors.add(monitor)
                        refresh()
                    }
                }
            })
            add(JButton("Edit\u2026").apply {
                addActionListener { selected()?.let { monitor ->
                    if (MonitorEditDialog(contentPanel, monitor).showAndGet()) refresh()
                } }
            })
            add(JButton("Enable / Disable").apply {
                addActionListener { selected()?.let { monitor ->
                    monitor.enabled = !monitor.enabled
                    refresh()
                } }
            })
            add(JButton("Run Now").apply {
                addActionListener { selected()?.let { onRunNow(it.id) } }
            })
            add(JButton("Refresh").apply { addActionListener { refresh() } })
            add(JButton("Delete").apply {
                addActionListener {
                    val monitor = selected() ?: return@addActionListener
                    if (Messages.showYesNoDialog(
                            this@MonitorDialog.contentPanel,
                            "Delete monitor \"${monitor.name}\"?",
                            "Delete Monitor",
                            Messages.getQuestionIcon(),
                        ) == Messages.YES
                    ) {
                        monitors.remove(monitor)
                        refresh()
                    }
                }
            })
        }
        add(buttons, BorderLayout.SOUTH)
        preferredSize = JBUI.size(820, 420)
    }

    private fun selected(): MonitorConfig? {
        val row = table.selectedRow
        if (row < 0) return null
        return monitors.getOrNull(row)
    }

    private fun refresh() {
        model.rowCount = 0
        monitors.forEach { monitor ->
            model.addRow(
                arrayOf(
                    monitor.name,
                    "${monitor.intervalMinutes} min",
                    monitor.iterations,
                    if (monitor.enabled) "Yes" else "No",
                    if (monitor.lastRunAt == 0L) "Never" else
                        SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(monitor.lastRunAt)),
                    "${monitor.lastPassed}/${monitor.lastTotal} passed",
                    monitor.lastError,
                ),
            )
        }
    }
}

/** One monitor's editable settings. */
private class MonitorEditDialog(
    parent: Component,
    private val monitor: MonitorConfig,
) : DialogWrapper(parent, true) {
    private val nameField = JTextField(monitor.name)
    private val intervalSpinner = JSpinner(SpinnerNumberModel(monitor.intervalMinutes, 1, 24 * 60, 1))
    private val iterationsSpinner = JSpinner(SpinnerNumberModel(monitor.iterations, 1, 100, 1))
    private val enabledBox = JCheckBox("Run automatically while the IDE is open", monitor.enabled)

    init {
        title = "Edit Monitor"
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(java.awt.GridBagLayout()).apply {
        border = JBUI.Borders.empty(8, 8)
        var row = 0
        add(JLabel("Name"), java.awt.GridBagConstraints().apply {
            gridx = 0
            gridy = row
            anchor = java.awt.GridBagConstraints.WEST
            insets = JBUI.insets(4)
        })
        add(nameField, java.awt.GridBagConstraints().apply {
            gridx = 1
            gridy = row++
            weightx = 1.0
            fill = java.awt.GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
        })
        add(JLabel("Interval (minutes)"), java.awt.GridBagConstraints().apply {
            gridx = 0
            gridy = row
            anchor = java.awt.GridBagConstraints.WEST
            insets = JBUI.insets(4)
        })
        add(intervalSpinner, java.awt.GridBagConstraints().apply {
            gridx = 1
            gridy = row++
            fill = java.awt.GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
        })
        add(JLabel("Iterations"), java.awt.GridBagConstraints().apply {
            gridx = 0
            gridy = row
            anchor = java.awt.GridBagConstraints.WEST
            insets = JBUI.insets(4)
        })
        add(iterationsSpinner, java.awt.GridBagConstraints().apply {
            gridx = 1
            gridy = row++
            fill = java.awt.GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
        })
        add(enabledBox, java.awt.GridBagConstraints().apply {
            gridx = 0
            gridy = row
            gridwidth = 2
            anchor = java.awt.GridBagConstraints.WEST
            insets = JBUI.insets(4)
        })
        preferredSize = JBUI.size(420, 200)
    }

    override fun doOKAction() {
        monitor.name = nameField.text.trim().ifEmpty { "Scheduled Monitor" }
        monitor.intervalMinutes = (intervalSpinner.value as Number).toInt()
        monitor.iterations = (iterationsSpinner.value as Number).toInt()
        monitor.enabled = enabledBox.isSelected
        super.doOKAction()
    }
}
