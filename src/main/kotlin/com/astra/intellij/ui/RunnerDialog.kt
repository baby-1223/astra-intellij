package com.astra.intellij.ui

import com.astra.intellij.core.CollectionRunner
import com.astra.intellij.core.DataFileService
import com.astra.intellij.core.RunItemResult
import com.astra.intellij.core.RunnerConfig
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.workspace.WorkspaceService
import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.io.File
import java.time.Instant
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants
import javax.swing.ToolTipManager
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.JTableHeader
import javax.swing.table.TableRowSorter

class RunnerDialog(
    project: Project,
    private val workspace: WorkspaceService,
    private val runName: String,
    private val requests: List<AstraRequest>,
) : DialogWrapper(project) {
    private val ownerProject = project

    private data class RunRow(
        val iteration: Int,
        val index: Int,
        val result: RunItemResult,
    )

    private val rows = mutableListOf<RunRow>()
    private val resultModel = DefaultTableModel(
        arrayOf<Any>("Iteration", "Request", "Method", "Status", "Time", "Tests", "Result"),
        0,
    )
    private val resultTable = JTable(resultModel)
    private val failureModel = DefaultTableModel(arrayOf<Any>("Iteration", "Request", "Status", "Reason"), 0)
    private val failureTable = JTable(failureModel)
    private val iterationFilter = JComboBox<String>().apply {
        addItem("All Iterations")
        preferredSize = Dimension(200, preferredSize.height)
        maximumSize = Dimension(200, preferredSize.height)
    }
    private val iterationsSpinner = numberSpinner(1, 1, 10_000, 1).apply { preferredSize = Dimension(90, preferredSize.height) }
    private val delaySpinner = numberSpinner(0, 0, 600_000, 100).apply { preferredSize = Dimension(90, preferredSize.height) }
    private val continueCheckBox = JCheckBox("Continue on failure", true)
    private val chooseButton = JButton("Choose Data File")
    private val clearButton = JButton("Clear")
    private val dataFileLabel = JBLabel("No data file").apply { foreground = RunnerColors.secondary }
    private val progressLabel = JBLabel("Ready").apply { foreground = RunnerColors.secondary }
    private val emptyStateLabel = JBLabel("No results yet — click Run to start").apply {
        horizontalAlignment = SwingConstants.CENTER
        foreground = RunnerColors.secondary
    }
    private val resultsCards = JPanel(CardLayout())
    private val summaryPanel = JPanel(GridLayout(0, 4, 10, 10))
    private val summaryLabels = linkedMapOf(
        "Total Runs" to "0",
        "Passed Tests" to "0",
        "Failed Tests" to "0",
        "Failed Requests" to "0",
        "Duration" to "0 ms",
        "Avg Response" to "0 ms",
        "Response Size" to "0 B",
    ).mapValues { (_, value) -> JBLabel(value) }

    private var dataFile: File? = null
    private val runner = CollectionRunner()
    private val runStartedAt = java.util.concurrent.atomic.AtomicLong(0L)
    private val runFinishedAt = java.util.concurrent.atomic.AtomicLong(0L)
    private var lastRunConfig: RunnerConfig? = null

    @Volatile
    private var running = false

    private val runAction = object : DialogWrapperAction("Run") {
        override fun doAction(e: java.awt.event.ActionEvent?) = startRun()
    }
    private val stopAction = object : DialogWrapperAction("Stop") {
        override fun doAction(e: java.awt.event.ActionEvent?) {
            runner.cancel()
        }
    }
    private val exportAction = object : DialogWrapperAction("Export Report") {
        override fun doAction(e: java.awt.event.ActionEvent?) = exportReport()
    }

    init {
        title = "Run $runName"
        setCancelButtonText("Close")
        init()
        stopAction.isEnabled = false
        exportAction.isEnabled = false
        iterationFilter.addActionListener { refreshVisibleRows() }
        styleTable(resultTable, failureTable)
        installRunShortcut()
    }

    private fun installRunShortcut() {
        val runKey = KeyStroke.getKeyStroke(
            KeyEvent.VK_ENTER,
            Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx,
        )
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(runKey, "astra.runRunner")
        rootPane.actionMap.put("astra.runRunner", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (!running) startRun()
            }
        })
    }

    private fun styleTable(vararg tables: JTable) {
        tables.forEach { table ->
            table.rowHeight = 26
            table.setShowGrid(false)
            table.intercellSpacing = Dimension(0, 0)
            table.fillsViewportHeight = true
            table.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
            (table.tableHeader as? JTableHeader)?.apply {
                reorderingAllowed = false
                font = font.deriveFont(Font.BOLD)
            }
            table.setDefaultRenderer(Object::class.java, paddedRenderer())
            ToolTipManager.sharedInstance().registerComponent(table)
        }
        resultTable.columnModel.let { columns ->
            columns.getColumn(0).preferredWidth = 80
            columns.getColumn(1).preferredWidth = 300
            columns.getColumn(2).preferredWidth = 90
            columns.getColumn(3).preferredWidth = 90
            columns.getColumn(4).preferredWidth = 100
            columns.getColumn(5).preferredWidth = 80
            columns.getColumn(6).preferredWidth = 260
            columns.getColumn(1).cellRenderer = ellipsisRenderer()
            columns.getColumn(4).cellRenderer = alignmentRenderer(SwingConstants.RIGHT)
            columns.getColumn(5).cellRenderer = alignmentRenderer(SwingConstants.RIGHT)
        }
        failureTable.columnModel.let { columns ->
            columns.getColumn(0).preferredWidth = 90
            columns.getColumn(1).preferredWidth = 220
            columns.getColumn(2).preferredWidth = 90
            columns.getColumn(3).preferredWidth = 500
            columns.getColumn(1).cellRenderer = ellipsisRenderer()
            columns.getColumn(3).cellRenderer = ellipsisRenderer()
        }
        resultTable.autoCreateRowSorter = true
        @Suppress("UNCHECKED_CAST")
        val sorter = resultTable.rowSorter as TableRowSorter<DefaultTableModel>
        sorter.setComparator(0, Comparator { a: Any, b: Any -> (a as Int).compareTo(b as Int) })
        sorter.setComparator(4, Comparator { a: Any, b: Any -> parseMillis(a) - parseMillis(b) })
    }

    private fun parseMillis(value: Any?): Int = value?.toString()?.substringBefore(' ')?.toIntOrNull() ?: 0

    private fun paddedRenderer(): DefaultTableCellRenderer = object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
            component.border = JBUI.Borders.empty(0, 10)
            if (!isSelected) {
                val text = value?.toString().orEmpty()
                component.foreground = when {
                    text == "PASS" -> RunnerColors.success
                    text == "FAIL" || text == "ERR" -> RunnerColors.failure
                    column == 3 && text.matches(Regex("\\d{3}")) ->
                        if (text.startsWith("2")) RunnerColors.success else RunnerColors.failure
                    else -> UIUtil.getLabelForeground()
                }
            }
            return component
        }
    }

    private fun alignmentRenderer(alignment: Int): DefaultTableCellRenderer = object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
            component.horizontalAlignment = alignment
            component.border = JBUI.Borders.empty(0, 10)
            if (!isSelected) component.foreground = UIUtil.getLabelForeground()
            return component
        }
    }

    private fun ellipsisRenderer(): DefaultTableCellRenderer = object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
            component.border = JBUI.Borders.empty(0, 10)
            if (!isSelected) component.foreground = UIUtil.getLabelForeground()
            val full = value?.toString().orEmpty()
            component.toolTipText = full
            val available = table.getCellRect(row, column, false).width - JBUI.scale(20)
            component.text = if (available > 0) ellipsize(full, component.getFontMetrics(component.font), available) else full
            return component
        }
    }

    private fun ellipsize(text: String, metrics: FontMetrics, width: Int): String {
        if (metrics.stringWidth(text) <= width) return text
        val suffix = "..."
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + suffix) > width) end--
        return text.substring(0, end) + suffix
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 10)).apply {
        border = JBUI.Borders.empty(12, 14, 6, 14)
        add(createConfigPanel(), BorderLayout.NORTH)
        add(createResultsPanel(), BorderLayout.CENTER)
        add(progressLabel, BorderLayout.SOUTH)
        preferredSize = Dimension(980, 680)
    }

    override fun createActions(): Array<Action> = arrayOf(exportAction, stopAction, runAction, cancelAction)

    override fun doCancelAction() {
        runner.cancel()
        super.doCancelAction()
    }

    private fun createConfigPanel(): JPanel {
        chooseButton.addActionListener { chooseDataFile() }
        clearButton.addActionListener {
            dataFile = null
            updateDataFileState()
        }
        val panel = JPanel(GridBagLayout())
        fun cell(component: Component, x: Int, y: Int, width: Int = 1, weight: Double = 0.0, fill: Int = GridBagConstraints.NONE) {
            panel.add(component, GridBagConstraints().apply {
                gridx = x
                gridy = y
                gridwidth = width
                weightx = weight
                this.fill = fill
                anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, if (y == 0) JBUI.scale(8) else 0, JBUI.scale(10))
            })
        }
        cell(JBLabel("Iterations"), 0, 0)
        cell(iterationsSpinner, 1, 0)
        cell(JBLabel("Delay (ms)"), 2, 0)
        cell(delaySpinner, 3, 0)
        cell(continueCheckBox, 4, 0, width = 2)
        cell(chooseButton, 0, 1)
        cell(clearButton, 1, 1)
        cell(dataFileLabel, 2, 1, width = 4, weight = 1.0, fill = GridBagConstraints.HORIZONTAL)
        return panel.apply { border = JBUI.Borders.emptyBottom(4) }
    }

    private fun createSummaryPanel(): JPanel {
        summaryPanel.removeAll()
        summaryLabels.forEach { (caption, valueLabel) ->
            summaryPanel.add(JPanel(BorderLayout(0, 2)).apply {
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(RunnerColors.border, 1),
                    JBUI.Borders.empty(7, 10),
                )
                add(JBLabel(caption).apply {
                    font = font.deriveFont(font.size2D - 1f)
                    foreground = RunnerColors.secondary
                }, BorderLayout.NORTH)
                add(valueLabel.apply { font = font.deriveFont(Font.BOLD, font.size2D + 4f) }, BorderLayout.CENTER)
            })
        }
        summaryPanel.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                val columns = if (summaryPanel.width in 1 until JBUI.scale(760)) 2 else 4
                val layout = summaryPanel.layout as GridLayout
                if (layout.columns != columns) {
                    layout.columns = columns
                    summaryPanel.revalidate()
                }
            }
        })
        return summaryPanel
    }

    private fun createResultsPanel(): JComponent {
        resultsCards.add(JBScrollPane(resultTable), "table")
        resultsCards.add(emptyStateLabel, "empty")
        (resultsCards.layout as CardLayout).show(resultsCards, "empty")
        val header = JPanel(BorderLayout(0, 8)).apply {
            add(createSummaryPanel(), BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                add(JBLabel("Filter"))
                add(iterationFilter)
            }, BorderLayout.SOUTH)
        }
        val resultsSection = JPanel(BorderLayout(0, 6)).apply {
            add(sectionTitle("Results"), BorderLayout.NORTH)
            add(resultsCards, BorderLayout.CENTER)
        }
        val failuresSection = JPanel(BorderLayout(0, 6)).apply {
            add(sectionTitle("Failures"), BorderLayout.NORTH)
            add(JBScrollPane(failureTable), BorderLayout.CENTER)
        }
        return JPanel(BorderLayout(0, 12)).apply {
            add(header, BorderLayout.NORTH)
            add(ResizableSplitPanel(resultsSection, failuresSection), BorderLayout.CENTER)
        }
    }

    private fun sectionTitle(text: String): JBLabel = JBLabel(text).apply {
        font = font.deriveFont(Font.BOLD)
    }

    private fun chooseDataFile() {
        val chosen = AstraFileChooser.open(
            contentPane,
            "Choose Data File",
            "CSV or JSON files",
            "csv",
            "json",
        ) ?: return
        dataFile = chosen
        updateDataFileState()
    }

    private fun updateDataFileState() {
        val file = dataFile
        iterationsSpinner.isEnabled = !running && file == null
        if (file == null) {
            dataFileLabel.text = "No data file"
        } else {
            runCatching { DataFileService.parse(file) }
                .onSuccess { rows ->
                    dataFileLabel.text = "${file.name} - ${rows.size} data row(s)"
                }
                .onFailure { error ->
                    dataFile = null
                    iterationsSpinner.isEnabled = !running
                    dataFileLabel.text = "No data file"
                    Messages.showErrorDialog(ownerProject, error.message ?: "Invalid data file", "Invalid Data File")
                }
        }
    }

    private fun setConfigEnabled(enabled: Boolean) {
        iterationsSpinner.isEnabled = enabled && dataFile == null
        delaySpinner.isEnabled = enabled
        continueCheckBox.isEnabled = enabled
        chooseButton.isEnabled = enabled
        clearButton.isEnabled = enabled
    }

    private fun currentConfig(): RunnerConfig? {
        val iterations = (iterationsSpinner.value as Int).coerceAtLeast(1)
        val delayMs = (delaySpinner.value as Int).toLong().coerceAtLeast(0L)
        val file = dataFile
        val dataRows = if (file == null) {
            emptyList()
        } else {
            try {
                DataFileService.parse(file)
            } catch (e: IllegalArgumentException) {
                Messages.showErrorDialog(ownerProject, e.message ?: "Invalid data file", "Invalid Data File")
                return null
            }
        }
        if (file != null && dataRows.isEmpty()) {
            Messages.showErrorDialog(ownerProject, "Data file contains no data rows", "Invalid Data File")
            return null
        }
        return RunnerConfig(
            iterations = if (dataRows.isEmpty()) iterations else dataRows.size,
            delayMs = delayMs,
            continueOnFailure = continueCheckBox.isSelected,
            dataRows = dataRows,
        )
    }

    private fun startRun() {
        if (running) return
        val config = currentConfig() ?: return
        lastRunConfig = config
        running = true
        runAction.isEnabled = false
        stopAction.isEnabled = true
        exportAction.isEnabled = false
        setConfigEnabled(false)
        rows.clear()
        resultModel.rowCount = 0
        failureModel.rowCount = 0
        val plannedIterations = config.dataRows.ifEmpty { List(config.iterations) { emptyMap() } }.size
        val planned = plannedIterations * requests.size
        resetIterationFilter(plannedIterations)
        progressLabel.text = "0 / $planned"
        runStartedAt.set(System.nanoTime())
        runFinishedAt.set(0L)
        val baseVars = workspace.activeVariables()
        val environment = workspace.data.environments.firstOrNull { it.id == workspace.data.activeEnvironmentId }
        val settings = workspace.data.settings
        Thread {
            runner.run(requests, baseVars, environment, settings, config) { iteration, index, result ->
                ApplicationManager.getApplication().invokeLater({
                    addResultRow(iteration, index, result, planned)
                }, ModalityState.any())
            }
            ApplicationManager.getApplication().invokeLater({
                runFinishedAt.set(System.nanoTime())
                refreshSummary()
                running = false
                runAction.isEnabled = true
                stopAction.isEnabled = false
                exportAction.isEnabled = rows.isNotEmpty()
                setConfigEnabled(true)
                refreshResultsCard()
            }, ModalityState.any())
        }.apply {
            isDaemon = true
            name = "Astra-Collection-Runner"
            start()
        }
    }

    private fun resetIterationFilter(iterations: Int) {
        iterationFilter.removeAllItems()
        iterationFilter.addItem("All Iterations")
        for (iteration in 1..iterations) {
            iterationFilter.addItem("Iteration $iteration")
        }
    }

    private fun addResultRow(iteration: Int, index: Int, result: RunItemResult, planned: Int) {
        rows.add(RunRow(iteration, index, result))
        refreshVisibleRows()
        refreshFailures()
        refreshSummary()
        progressLabel.text = "${rows.size} / $planned"
    }

    private fun refreshVisibleRows() {
        val selected = iterationFilter.selectedItem?.toString()
        val selectedIteration = selected?.removePrefix("Iteration ")?.toIntOrNull()?.minus(1)
        resultModel.rowCount = 0
        rows.filter { selectedIteration == null || it.iteration == selectedIteration }
            .forEach { row ->
                resultModel.addRow(
                    arrayOf<Any>(
                        row.iteration + 1,
                        row.result.request.name,
                        row.result.request.method,
                        row.result.error?.let { "ERR" } ?: row.result.status,
                        "${row.result.timeMs} ms",
                        if (row.result.tests.isEmpty()) "-" else "${row.result.tests.count { it.passed }}/${row.result.tests.size}",
                        row.result.error ?: if (row.result.passed) "PASS" else "FAIL",
                    ),
                )
            }
        refreshResultsCard()
    }

    private fun refreshResultsCard() {
        val card = if (resultModel.rowCount == 0) "empty" else "table"
        (resultsCards.layout as CardLayout).show(resultsCards, card)
    }

    private fun refreshFailures() {
        failureModel.rowCount = 0
        rows.filterNot { it.result.passed }.forEach { row ->
            failureModel.addRow(
                arrayOf<Any>(
                    row.iteration + 1,
                    row.result.request.name,
                    row.result.error?.let { "ERR" } ?: row.result.status,
                    failureReason(row.result),
                ),
            )
        }
    }

    private fun refreshSummary() {
        val passedTests = rows.sumOf { it.result.tests.count { test -> test.passed } }
        val failedTests = rows.sumOf { it.result.tests.count { test -> !test.passed } }
        val failedRequests = rows.count { !it.result.passed }
        val totalTime = rows.sumOf { it.result.timeMs }
        val avgTime = if (rows.isEmpty()) 0L else totalTime / rows.size
        val bodySize = rows.sumOf { it.result.bodySize }
        updateSummaryLabel("Duration", "${runDurationMs()} ms")
        updateSummaryLabel("Total Runs", rows.size.toString())
        updateSummaryLabel("Passed Tests", passedTests.toString())
        updateSummaryLabel("Failed Tests", failedTests.toString(), alert = failedTests > 0)
        updateSummaryLabel("Failed Requests", failedRequests.toString(), alert = failedRequests > 0)
        updateSummaryLabel("Avg Response", "$avgTime ms")
        updateSummaryLabel("Response Size", formatBytes(bodySize))
    }

    private fun runDurationMs(): Long {
        val started = runStartedAt.get()
        if (started == 0L) return 0L
        val finished = runFinishedAt.get()
        val end = if (finished > 0L) finished else System.nanoTime()
        return (end - started) / 1_000_000
    }

    private fun updateSummaryLabel(key: String, value: String, alert: Boolean = false) {
        val label = summaryLabels[key] ?: return
        label.text = value
        label.foreground = if (alert) RunnerColors.failure else UIUtil.getLabelForeground()
    }

    private fun failureReason(result: RunItemResult): String = result.error
        ?: result.tests.filterNot { it.passed }
            .joinToString("; ") { "${it.assertion}: ${it.detail}" }
            .ifEmpty { "Failed" }

    private fun exportReport() {
        if (rows.isEmpty()) {
            Messages.showInfoMessage(ownerProject, "No run results to export", "Export Report")
            return
        }
        val fileName = "astra-runner-${runName.replace(Regex("[^A-Za-z0-9_-]"), "-")}-${Instant.now().epochSecond}.json"
        val file = AstraFileChooser.save(contentPane, "Export Run Report", fileName, "json") ?: return
        val report = mapOf(
            "name" to runName,
            "exportedAt" to Instant.now().toString(),
            "config" to runConfigMap(),
            "summary" to summaryMap(),
            "results" to rows.map { row ->
                mapOf(
                    "iteration" to row.iteration + 1,
                    "request" to row.result.request.name,
                    "method" to row.result.request.method,
                    "status" to row.result.status,
                    "timeMs" to row.result.timeMs,
                    "bodySize" to row.result.bodySize,
                    "passed" to row.result.passed,
                    "error" to row.result.error,
                    "tests" to row.result.tests.map { test ->
                        mapOf("assertion" to test.assertion, "passed" to test.passed, "detail" to test.detail)
                    },
                )
            },
            "failures" to rows.filterNot { it.result.passed }.map { row ->
                mapOf(
                    "iteration" to row.iteration + 1,
                    "request" to row.result.request.name,
                    "status" to row.result.status,
                    "reason" to failureReason(row.result),
                )
            },
        )
        try {
            file.writeText(GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(report))
            Messages.showInfoMessage(ownerProject, "Report exported to ${file.absolutePath}", "Export Report")
        } catch (e: Exception) {
            Messages.showErrorDialog(ownerProject, e.message ?: "Unable to export report", "Export Failed")
        }
    }

    private fun runConfigMap(): Map<String, Any?> {
        val snapshot = lastRunConfig
        val fallbackRows = dataFile?.let { runCatching { DataFileService.parse(it) }.getOrNull() }.orEmpty()
        return mapOf(
            "iterations" to (snapshot?.iterations
                ?: if (fallbackRows.isEmpty()) (iterationsSpinner.value as Int) else fallbackRows.size),
            "delayMs" to (snapshot?.delayMs ?: (delaySpinner.value as Int).toLong()),
            "continueOnFailure" to (snapshot?.continueOnFailure ?: continueCheckBox.isSelected),
            "dataFile" to dataFile?.absolutePath,
            "dataRows" to (snapshot?.dataRows?.size ?: fallbackRows.size),
        )
    }

    private fun summaryMap(): Map<String, Any?> {
        val passedTests = rows.sumOf { it.result.tests.count { test -> test.passed } }
        val failedTests = rows.sumOf { it.result.tests.count { test -> !test.passed } }
        val totalTime = rows.sumOf { it.result.timeMs }
        return mapOf(
            "totalRuns" to rows.size,
            "passedTests" to passedTests,
            "failedTests" to failedTests,
            "failedRequests" to rows.count { !it.result.passed },
            "durationMs" to runDurationMs(),
            "averageResponseMs" to if (rows.isEmpty()) 0L else totalTime / rows.size,
            "responseSizeBytes" to rows.sumOf { it.result.bodySize },
        )
    }

    private fun numberSpinner(value: Int, min: Int, max: Int, step: Int): JSpinner =
        JSpinner(SpinnerNumberModel().apply {
            this.value = value
            minimum = min
            maximum = max
            stepSize = step
        })

    private fun formatBytes(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

/** Vertical stack with a draggable 1px divider that is drawn with IDE theme colors. */
private class ResizableSplitPanel(
    private val top: JComponent,
    private val bottom: JComponent,
) : JPanel(BorderLayout()) {
    private val divider = object : JComponent() {
        init {
            preferredSize = Dimension(0, JBUI.scale(9))
            cursor = Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    dragStartY = e.yOnScreen
                    dragStartHeight = bottom.height
                }
            })
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    val delta = dragStartY - e.yOnScreen
                    val limitLow = JBUI.scale(90)
                    val limitHigh = (height - JBUI.scale(200)).coerceAtLeast(limitLow)
                    val target = (dragStartHeight + delta).coerceIn(limitLow, limitHigh)
                    bottom.preferredSize = Dimension(0, target)
                    revalidate()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            g.color = UIUtil.getPanelBackground()
            g.fillRect(0, 0, width, height)
            g.color = RunnerColors.border
            g.drawLine(0, height / 2, width, height / 2)
        }
    }

    private var dragStartY = 0
    private var dragStartHeight = 0

    init {
        bottom.preferredSize = Dimension(0, JBUI.scale(190))
        add(top, BorderLayout.CENTER)
        add(divider, BorderLayout.SOUTH)
        add(bottom, BorderLayout.SOUTH)
    }

    override fun doLayout() {
        val dividerHeight = divider.preferredSize.height
        val bottomHeight = bottom.preferredSize.height.coerceAtMost((height - dividerHeight - JBUI.scale(120)).coerceAtLeast(0))
        bottom.setBounds(0, height - bottomHeight, width, bottomHeight)
        divider.setBounds(0, height - bottomHeight - dividerHeight, width, dividerHeight)
        top.setBounds(0, 0, width, (height - bottomHeight - dividerHeight).coerceAtLeast(0))
    }
}

/** Theme-aware colors so the runner follows light and dark IDE themes and high-DPI scaling. */
private object RunnerColors {
    val success: Color = JBColor.namedColor("Astra.Runner.Success", JBColor(0x1F7A3D, 0x63C98B))
    val failure: Color = JBColor.namedColor("Astra.Runner.Failure", JBColor(0xB3261E, 0xF08A85))
    val secondary: Color get() = UIUtil.getContextHelpForeground()
    val border: Color get() = JBColor.border()
}
