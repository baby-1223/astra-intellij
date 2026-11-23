package com.astra.intellij.ui

import com.astra.intellij.model.HistoryEntry
import com.astra.intellij.model.HttpResult
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.JComboBox
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource
import java.io.StringReader
import java.io.StringWriter

class ResponsePanel : JPanel(BorderLayout()) {
    private val statusLabel = JLabel("No response yet")
    private val bodyModeButtons = listOf("Pretty", "Raw", "Preview").map { javax.swing.JToggleButton(it) }
    private val bodyModeGroup = javax.swing.ButtonGroup()
    private val bodyFormatBox = JComboBox(arrayOf("Auto", "JSON", "XML", "HTML", "Text"))
    private val bodyContainer = JPanel(BorderLayout())
    private val headersModel = DefaultTableModel(arrayOf("Header", "Value"), 0)
    private val cookiesModel = DefaultTableModel(arrayOf("Name", "Value", "Domain", "Path"), 0)
    private val historyModel = object : DefaultTableModel(arrayOf("Time", "Method", "Status", "Duration", "Request"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val historyTable = JTable(historyModel)
    private val testsModel = object : DefaultTableModel(arrayOf("Assertion", "Result", "Detail"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val testsTable = JTable(testsModel)
    private var baseStatus = "No response yet"
    private var history = listOf<HistoryEntry>()
    private var lastResult: HttpResult? = null
    private var lastError: String? = null
    private var lastBodyMode = 0
    var onHistorySelected: (HistoryEntry) -> Unit = {}

    init {
        val tabs = JTabbedPane()
        bodyModeButtons.forEachIndexed { index, button ->
            bodyModeGroup.add(button)
            button.isFocusable = false
            button.isBorderPainted = false
            button.isContentAreaFilled = false
            button.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            button.margin = java.awt.Insets(2, 4, 2, 4)
            button.addActionListener {
                lastBodyMode = index
                updateBodyModeStyles()
                if (lastResult != null || lastError != null) renderBody()
            }
        }
        bodyModeButtons.first().isSelected = true
        updateBodyModeStyles()
        val bodyToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            bodyModeButtons.forEach { add(it) }
            add(bodyFormatBox)
            add(javax.swing.JButton("Copy").apply {
                toolTipText = "Copy response body"
                isBorderPainted = false
                isContentAreaFilled = false
                isFocusable = false
                foreground = com.intellij.ui.JBColor.GRAY
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                addActionListener {
                    val text = lastResult?.let { String(it.body, StandardCharsets.UTF_8) } ?: lastError.orEmpty()
                    if (text.isNotEmpty()) {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(java.awt.datatransfer.StringSelection(text), null)
                    }
                }
            })
        }
        val bodyPanel = JPanel(BorderLayout()).apply {
            add(bodyToolbar, BorderLayout.NORTH)
            add(bodyContainer, BorderLayout.CENTER)
        }
        tabs.addTab("Body", bodyPanel)
        tabs.addTab("Cookies", JScrollPane(JTable(cookiesModel)))
        tabs.addTab("Headers", JScrollPane(JTable(headersModel)))
        testsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        val assertionRenderer = javax.swing.table.DefaultTableCellRenderer()
        testsTable.columnModel.getColumn(1).cellRenderer = object : javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
            ): java.awt.Component {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                foreground = if (value == "PASS") PASS_COLOR else FAIL_COLOR
                font = font.deriveFont(Font.BOLD)
                return this
            }
        }
        testsTable.columnModel.getColumn(0).cellRenderer = assertionRenderer
        tabs.addTab("Test Results", JScrollPane(testsTable))
        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        historyTable.selectionModel.addListSelectionListener {
            val row = historyTable.selectedRow
            if (row >= 0 && row < history.size) onHistorySelected(history[row])
        }
        tabs.addTab("History", JScrollPane(historyTable))
        add(statusLabel, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        statusLabel.border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
        statusLabel.font = statusLabel.font.deriveFont(Font.BOLD)
        bodyFormatBox.addActionListener { renderBody() }
        showBodyText("Send a request to see the response body here.")
    }

    private fun updateBodyModeStyles() {
        bodyModeButtons.forEach { button ->
            button.foreground = if (button.isSelected) INFO_COLOR else com.intellij.ui.JBColor.GRAY
            button.font = button.font.deriveFont(if (button.isSelected) Font.BOLD else Font.PLAIN)
        }
    }

    fun showMessage(message: String) {
        statusLabel.foreground = com.intellij.ui.JBColor.foreground()
        baseStatus = message
        statusLabel.text = message
    }

    /** Appends a short suffix such as "Vars 2" without discarding the previous status. */
    fun appendStatus(suffix: String) {
        baseStatus = "$baseStatus  ·  $suffix"
        statusLabel.text = baseStatus
    }

    fun showLoading() {
        statusLabel.foreground = com.intellij.ui.JBColor.foreground()
        baseStatus = "Sending request..."
        statusLabel.text = baseStatus
        testsModel.rowCount = 0
        lastResult = null
        lastError = null
        showBodyText("Sending request...")
    }

    fun showResult(result: HttpResult) {
        lastResult = result
        lastError = null
        statusLabel.foreground = when (result.status) {
            in 200..299 -> PASS_COLOR
            in 300..399 -> INFO_COLOR
            in 400..499 -> WARN_COLOR
            else -> FAIL_COLOR
        }
        baseStatus = "${result.status} ${result.statusText}  ·  ${result.elapsedMs} ms  ·  ${formatBytes(result.body.size)}"
        statusLabel.text = baseStatus
        headersModel.rowCount = 0
        result.headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (key, values) ->
            values.forEach { headersModel.addRow(arrayOf(key, it)) }
        }
        cookiesModel.rowCount = 0
        result.cookies.forEach {
            cookiesModel.addRow(arrayOf(it.name, it.value, it.domain, it.path))
        }
        renderBody()
    }

    fun showError(message: String) {
        statusLabel.foreground = FAIL_COLOR
        baseStatus = "Request failed"
        statusLabel.text = baseStatus
        lastResult = null
        lastError = message
        renderBody()
    }

    fun showTestResults(results: List<com.astra.intellij.core.AssertionResult>) {
        testsModel.rowCount = 0
        results.forEach {
            testsModel.addRow(arrayOf(it.assertion, if (it.passed) "PASS" else "FAIL", it.detail))
        }
        if (results.isNotEmpty()) {
            val passed = results.count { it.passed }
            statusLabel.text = "$baseStatus  ·  Tests $passed/${results.size}"
        }
    }

    fun setHistory(entries: List<HistoryEntry>) {
        history = entries
        historyModel.rowCount = 0
        val format = SimpleDateFormat("MM-dd HH:mm:ss")
        entries.forEach {
            historyModel.addRow(arrayOf(format.format(Date(it.createdAt)), it.request.method, it.status, "${it.timeMs} ms", it.request.name))
        }
    }

    private fun renderBody() {
        val result = lastResult
        val error = lastError
        if (result == null) {
            showBodyText(error ?: "No response body.")
            return
        }
        val raw = String(result.body, StandardCharsets.UTF_8)
        val format = detectFormat(result, raw)
        when (lastBodyMode) {
            1 -> showBodyText(raw)
            2 -> showPreview(raw, format)
            else -> showBodyText(prettify(raw, format))
        }
    }

    private fun detectFormat(result: HttpResult, raw: String): String {
        val selected = bodyFormatBox.selectedItem?.toString() ?: "Auto"
        if (selected != "Auto") return selected
        val contentType = result.headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.firstOrNull()?.lowercase().orEmpty()
        return when {
            "json" in contentType -> "JSON"
            "xml" in contentType -> "XML"
            "html" in contentType -> "HTML"
            contentType.isNotEmpty() -> "Text"
            raw.trimStart().startsWith("<!DOCTYPE", ignoreCase = true) ||
                raw.trimStart().startsWith("<html", ignoreCase = true) -> "HTML"
            raw.trimStart().startsWith("<") -> "XML"
            runCatching { JsonParser.parseString(raw) }.isSuccess -> "JSON"
            else -> "Text"
        }
    }

    private fun prettify(raw: String, format: String): String = when (format) {
        "JSON" -> try {
            GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                .toJson(JsonParser.parseString(raw))
        } catch (_: Exception) {
            raw
        }
        "XML" -> try {
            val transformer = TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.INDENT, "yes")
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
                setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
            }
            val writer = StringWriter()
            transformer.transform(StreamSource(StringReader(raw)), StreamResult(writer))
            writer.toString()
        } catch (_: Exception) {
            raw
        }
        else -> raw
    }

    private fun showPreview(raw: String, format: String) {
        bodyContainer.removeAll()
        if (format == "HTML") {
            val pane = JEditorPane("text/html", raw).apply {
                isEditable = false
            }
            bodyContainer.add(JScrollPane(pane), BorderLayout.CENTER)
        } else {
            bodyContainer.add(JScrollPane(createTextArea(prettify(raw, format))), BorderLayout.CENTER)
        }
        bodyContainer.revalidate()
        bodyContainer.repaint()
    }

    private fun showBodyText(text: String) {
        bodyContainer.removeAll()
        bodyContainer.add(JScrollPane(createTextArea(text)), BorderLayout.CENTER)
        bodyContainer.revalidate()
        bodyContainer.repaint()
    }

    private fun createTextArea(text: String): JTextArea = JTextArea(text).apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        caretPosition = 0
    }

    private fun formatBytes(size: Int): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
        else -> "%.1f MB".format(size / 1024.0 / 1024.0)
    }

    companion object {
        private val PASS_COLOR = com.intellij.ui.JBColor(java.awt.Color(0x1F7A4D), java.awt.Color(0x4CC38A))
        private val FAIL_COLOR = com.intellij.ui.JBColor(java.awt.Color(0xC93B3B), java.awt.Color(0xFF6B6B))
        private val WARN_COLOR = com.intellij.ui.JBColor(java.awt.Color(0xB0720F), java.awt.Color(0xE8A33D))
        private val INFO_COLOR = com.intellij.ui.JBColor(java.awt.Color(0x2F6FED), java.awt.Color(0x5EA8FF))
    }
}
