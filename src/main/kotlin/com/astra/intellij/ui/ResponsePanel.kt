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
    private val bodyModeBox = JComboBox(arrayOf("Pretty", "Raw", "Preview"))
    private val bodyFormatBox = JComboBox(arrayOf("Auto", "JSON", "XML", "HTML", "Text"))
    private val bodyContainer = JPanel(BorderLayout())
    private val headersModel = DefaultTableModel(arrayOf("Header", "Value"), 0)
    private val cookiesModel = DefaultTableModel(arrayOf("Name", "Value", "Domain", "Path"), 0)
    private val historyModel = object : DefaultTableModel(arrayOf("Time", "Method", "Status", "Duration", "Request"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val historyTable = JTable(historyModel)
    private var history = listOf<HistoryEntry>()
    private var lastResult: HttpResult? = null
    private var lastError: String? = null
    var onHistorySelected: (HistoryEntry) -> Unit = {}

    init {
        val tabs = JTabbedPane()
        val bodyToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(bodyModeBox)
            add(bodyFormatBox)
        }
        val bodyPanel = JPanel(BorderLayout()).apply {
            add(bodyToolbar, BorderLayout.NORTH)
            add(bodyContainer, BorderLayout.CENTER)
        }
        tabs.addTab("Body", bodyPanel)
        tabs.addTab("Cookies", JScrollPane(JTable(cookiesModel)))
        tabs.addTab("Headers", JScrollPane(JTable(headersModel)))
        val testResults = JTextArea("No tests were run for this request.").apply {
            isEditable = false
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        }
        tabs.addTab("Test Results", JScrollPane(testResults))
        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        historyTable.selectionModel.addListSelectionListener {
            val row = historyTable.selectedRow
            if (row >= 0 && row < history.size) onHistorySelected(history[row])
        }
        tabs.addTab("History", JScrollPane(historyTable))
        add(statusLabel, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        statusLabel.border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
        bodyModeBox.addActionListener { renderBody() }
        bodyFormatBox.addActionListener { renderBody() }
        showBodyText("Send a request to see the response body here.")
    }

    fun showMessage(message: String) {
        statusLabel.text = message
    }

    fun showLoading() {
        statusLabel.text = "Sending request..."
        lastResult = null
        lastError = null
        showBodyText("Sending request...")
    }

    fun showResult(result: HttpResult) {
        lastResult = result
        lastError = null
        statusLabel.text = "${result.status} ${result.statusText}  ·  ${result.elapsedMs} ms  ·  ${formatBytes(result.body.size)}"
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
        statusLabel.text = "Request failed"
        lastResult = null
        lastError = message
        renderBody()
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
        val mode = bodyModeBox.selectedItem?.toString() ?: "Pretty"
        when (mode) {
            "Raw" -> showBodyText(raw)
            "Preview" -> showPreview(raw, format)
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
}
