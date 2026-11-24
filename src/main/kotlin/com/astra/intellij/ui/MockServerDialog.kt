package com.astra.intellij.ui

import com.astra.intellij.core.MockRoute
import com.astra.intellij.core.MockServer
import com.astra.intellij.core.MockSpec
import com.astra.intellij.core.MockSpecLoader
import com.astra.intellij.model.RequestCollection
import com.astra.intellij.workspace.WorkspaceService
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.Timer
import javax.swing.table.DefaultTableModel

/**
 * Small control panel for the local mock server: pick a collection or an OpenAPI document, start
 * the server, and watch the incoming requests. The dialog is modeless so it can stay open while
 * requests are tried in another window.
 */
class MockServerDialog(
    private val project: Project,
    private val workspace: WorkspaceService,
) : DialogWrapper(project, false) {
    private var server: MockServer? = null
    private var openApiFile: File? = null

    private val sourceBox = JComboBox<SourceOption>()
    private val portSpinner = JSpinner(SpinnerNumberModel(8080, 1024, 65535, 1))
    private val delaySpinner = JSpinner(SpinnerNumberModel(0, 0, 10000, 50))
    private val startButton = JButton("Start")
    private val openApiButton = JButton("OpenAPI File…")
    private val statusLabel = JBLabel("Stopped")
    private val baseUrlField = JTextField().apply { isEditable = false }
    private val copyButton = JButton("Copy")
    private val routeModel = ReadOnlyTableModel(arrayOf("Method", "Path", "Status", "Content-Type"))
    private val logModel = ReadOnlyTableModel(arrayOf("Time", "Method", "Path", "Status", "ms", "Matched"))
    private val logTimer = Timer(1000) { refreshLog() }

    init {
        title = "Astra Mock Server"
        isModal = false
        init()
        applyCollections()
        startButton.addActionListener { toggleServer() }
        openApiButton.addActionListener { chooseOpenApiFile() }
        copyButton.addActionListener {
            val url = baseUrlField.text
            if (url.isNotBlank()) {
                CopyPasteManager.getInstance().setContents(StringSelection(url))
                statusLabel.text = "Base URL copied"
            }
        }
        sourceBox.addActionListener {
            openApiButton.isEnabled = (sourceBox.selectedItem as? SourceOption)?.collection == null
        }
    }

    override fun createActions(): Array<Action> = emptyArray()

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        panel.preferredSize = JBUI.size(860, 520)
        panel.border = JBUI.Borders.empty(10)
        panel.add(buildForm(), BorderLayout.NORTH)
        panel.add(buildTabs(), BorderLayout.CENTER)
        return panel
    }

    override fun dispose() {
        logTimer.stop()
        stopServer()
        super.dispose()
    }

    private fun buildForm(): JComponent {
        val form = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            insets = Insets(JBUI.scale(4), JBUI.scale(4), JBUI.scale(4), JBUI.scale(4))
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        fun add(component: JComponent, x: Int, y: Int, width: Int, weight: Double) {
            constraints.gridx = x
            constraints.gridy = y
            constraints.gridwidth = width
            constraints.weightx = weight
            form.add(component, constraints)
            constraints.gridwidth = 1
            constraints.weightx = 0.0
        }

        add(JBLabel("Source"), 0, 0, 1, 0.0)
        add(sourceBox, 1, 0, 2, 1.0)
        add(openApiButton, 3, 0, 1, 0.0)

        add(JBLabel("Port"), 0, 1, 1, 0.0)
        add(portSpinner, 1, 1, 1, 0.3)
        add(JBLabel("Delay (ms)"), 2, 1, 1, 0.0)
        add(delaySpinner, 3, 1, 1, 0.3)

        val buttons = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(startButton, BorderLayout.WEST)
            add(statusLabel, BorderLayout.CENTER)
        }
        add(buttons, 0, 2, 2, 0.0)

        add(JBLabel("Base URL"), 0, 3, 1, 0.0)
        add(baseUrlField, 1, 3, 2, 1.0)
        add(copyButton, 3, 3, 1, 0.0)

        val hint = JBLabel(
            "Serves generated responses on 127.0.0.1 only. " +
                "Undocumented endpoints get sample fields derived from the request name.",
        ).apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.emptyTop(6)
        }
        add(hint, 0, 4, 4, 1.0)
        return form
    }

    private fun buildTabs(): JComponent {
        val tabs = JBTabbedPane()
        tabs.addTab("Routes", JBScrollPane(JTable(routeModel).apply {
            setShowGrid(false)
            intercellSpacing = Dimension(0, 0)
            rowHeight = JBUI.scale(22)
        }))
        tabs.addTab("Requests", JBScrollPane(JTable(logModel).apply {
            setShowGrid(false)
            intercellSpacing = Dimension(0, 0)
            rowHeight = JBUI.scale(22)
        }))
        return tabs
    }

    private fun applyCollections() {
        workspace.data.collections.forEach { collection ->
            sourceBox.addItem(SourceOption(collection.name, collection))
        }
        sourceBox.addItem(SourceOption("OpenAPI document…", null))
        if (workspace.data.collections.isEmpty()) sourceBox.selectedIndex = 0
    }

    private fun chooseOpenApiFile() {
        val file = AstraFileChooser.open(
            window,
            "Choose an OpenAPI document",
            "OpenAPI / Swagger (json, yaml, yml)",
            "json",
            "yaml",
            "yml",
        ) ?: return
        openApiFile = file
        val label = "OpenAPI: ${file.name}"
        for (index in 0 until sourceBox.itemCount) {
            val item = sourceBox.getItemAt(index)
            if (item.collection == null) {
                sourceBox.removeItemAt(index)
                sourceBox.insertItemAt(SourceOption(label, null), index)
                sourceBox.selectedIndex = index
                break
            }
        }
    }

    private fun buildSpec(): MockSpec {
        val delay = (delaySpinner.value as Number).toInt()
        val option = sourceBox.selectedItem as? SourceOption
            ?: throw IllegalArgumentException("Choose a collection or an OpenAPI document")
        return if (option.collection != null) {
            MockSpecLoader.fromCollection(option.collection, delay)
        } else {
            val file = openApiFile ?: throw IllegalArgumentException("Choose an OpenAPI document first")
            MockSpecLoader.fromOpenApi(file.readText(), delay)
        }
    }

    private fun toggleServer() {
        if (server != null) {
            stopServer()
            return
        }
        val spec = try {
            buildSpec()
        } catch (error: Exception) {
            Messages.showErrorDialog(project, error.message ?: "Cannot build the mock spec", "Mock Server")
            return
        }
        val instance = MockServer(spec)
        try {
            instance.start((portSpinner.value as Number).toInt())
        } catch (error: Exception) {
            Messages.showErrorDialog(project, error.message ?: "Cannot start the mock server", "Mock Server")
            return
        }
        server = instance
        populateRoutes(spec.routes)
        baseUrlField.text = instance.baseUrl
        statusLabel.text = "Running — ${spec.routes.size} route(s)"
        statusLabel.foreground = JBColor(0x1F7A3D, 0x6FD08C)
        startButton.text = "Stop"
        sourceBox.isEnabled = false
        portSpinner.isEnabled = false
        delaySpinner.isEnabled = false
        logTimer.start()
    }

    private fun stopServer() {
        server?.stop()
        server = null
        logTimer.stop()
        statusLabel.text = "Stopped"
        statusLabel.foreground = JBColor.GRAY
        startButton.text = "Start"
        baseUrlField.text = ""
        sourceBox.isEnabled = true
        portSpinner.isEnabled = true
        delaySpinner.isEnabled = true
    }

    private fun populateRoutes(routes: List<MockRoute>) {
        routeModel.rowCount = 0
        routes.forEach { route ->
            routeModel.addRow(arrayOf<Any>(route.method, route.path, route.status, route.contentType))
        }
    }

    private fun refreshLog() {
        val current = server ?: return
        logModel.rowCount = 0
        current.log().forEach { entry ->
            logModel.addRow(
                arrayOf<Any>(
                    java.time.LocalTime.ofInstant(
                        java.time.Instant.ofEpochMilli(entry.at),
                        java.time.ZoneId.systemDefault(),
                    ).withNano(0).toString(),
                    entry.method,
                    entry.path,
                    entry.status,
                    entry.elapsedMs,
                    if (entry.matched) "yes" else "no",
                ),
            )
        }
    }

    private data class SourceOption(val label: String, val collection: RequestCollection?) {
        override fun toString(): String = label
    }

    private class ReadOnlyTableModel(columns: Array<String>) : DefaultTableModel(columns, 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
}
