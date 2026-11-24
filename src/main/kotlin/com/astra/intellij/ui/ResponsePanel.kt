package com.astra.intellij.ui

import com.astra.intellij.core.GrpcCodec
import com.astra.intellij.core.ResponseCharset
import com.astra.intellij.core.SseEvent
import com.astra.intellij.model.HistoryEntry
import com.astra.intellij.model.HttpResult
import com.astra.intellij.model.ResponseExample
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
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
    private val bodyModeButtons = listOf("Pretty", "Raw", "Preview", "Tree").map { javax.swing.JToggleButton(it) }
    private val bodyModeGroup = javax.swing.ButtonGroup()
    private val bodyFormatBox = JComboBox(arrayOf("Auto", "JSON", "XML", "HTML", "Text"))
    private val bodyContainer = JPanel(BorderLayout())
    private val headersModel = DefaultTableModel(arrayOf("Header", "Value"), 0)
    private val cookiesModel = DefaultTableModel(arrayOf("Name", "Value", "Domain", "Path"), 0)
    private val historyModel = object : DefaultTableModel(arrayOf("\u2605", "Time", "Method", "Status", "Duration", "Request"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val historyTable = JTable(historyModel)
    private val historySearch = JBTextField()
    private val testsModel = object : DefaultTableModel(arrayOf("Assertion", "Result", "Detail"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val testsTable = JTable(testsModel)
    private val examplesModel = object : DefaultTableModel(arrayOf("Name", "Status", "Saved"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val examplesTable = JTable(examplesModel)
    private val examplePreview = createPreviewArea()
    private val jsonTree = JsonTreePanel()
    private val diffPanel = ResponseDiffPanel()
    private val tabs = JTabbedPane()
    private val sseArea = createTextArea("")
    private val sseStatus = JLabel("No stream")
    private lateinit var ssePanel: JPanel
    private val captured = mutableListOf<ResponseDiffPanel.Source>()
    private var baseStatus = "No response yet"
    private var history = listOf<HistoryEntry>()
    private var visibleHistory = listOf<HistoryEntry>()
    private var examples = listOf<ResponseExample>()
    private var lastResult: HttpResult? = null
    private var lastError: String? = null
    private var lastBodyMode = 0
    private var cachedBody: String? = null

    var onHistorySelected: (HistoryEntry) -> Unit = {}
    var onHistoryDelete: (HistoryEntry) -> Unit = {}
    var onHistoryPinToggle: (HistoryEntry) -> Unit = {}
    var onHistorySaveAs: (HistoryEntry) -> Unit = {}
    var onExampleAdd: ((ResponseExample) -> Unit)? = null
    var onExampleDelete: ((String) -> Unit)? = null
    var onSseStop: (() -> Unit)? = null

    init {
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
            add(toolbarButton("Copy", "Copy response body") {
                val text = decodedBody()
                if (text.isNotEmpty()) setClipboard(text)
            })
            add(toolbarButton("Save\u2026", "Save the full response body to a file") { saveBody() })
            add(toolbarButton("Save as Example", "Store this response as a reusable example") { saveExample() })
        }
        val bodyPanel = JPanel(BorderLayout()).apply {
            add(bodyToolbar, BorderLayout.NORTH)
            add(bodyContainer, BorderLayout.CENTER)
        }
        tabs.addTab("Body", bodyPanel)
        tabs.addTab("SSE", buildSsePanel())
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
        tabs.addTab("History", buildHistoryPanel())
        tabs.addTab("Diff", diffPanel)
        tabs.addTab("Examples", buildExamplesPanel())
        add(statusLabel, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        statusLabel.border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
        statusLabel.font = statusLabel.font.deriveFont(Font.BOLD)
        bodyFormatBox.addActionListener { renderBody() }
        showBodyText("Send a request to see the response body here.")
    }

    private fun toolbarButton(text: String, tooltip: String, action: () -> Unit): JButton = JButton(text).apply {
        toolTipText = tooltip
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusable = false
        foreground = com.intellij.ui.JBColor.GRAY
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        addActionListener { action() }
    }

    private fun buildHistoryPanel(): JPanel {
        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        historyTable.selectionModel.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val row = historyTable.selectedRow
            if (row in visibleHistory.indices) onHistorySelected(visibleHistory[row])
        }
        historySearch.emptyText.text = "Filter history by name, method, URL or status"
        historySearch.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = refreshHistoryRows()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = refreshHistoryRows()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = refreshHistoryRows()
        })
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(historySearch.apply { preferredSize = java.awt.Dimension(280, 28) })
            add(JButton("Pin / Unpin").apply {
                addActionListener {
                    selectedHistory()?.let { onHistoryPinToggle(it) }
                }
            })
            add(JButton("Delete").apply {
                addActionListener { deleteSelectedHistory() }
            })
            add(JButton("Save as Request").apply {
                addActionListener { selectedHistory()?.let { onHistorySaveAs(it) } }
            })
        }
        return JPanel(BorderLayout(0, 4)).apply {
            add(toolbar, BorderLayout.NORTH)
            add(JScrollPane(historyTable), BorderLayout.CENTER)
        }
    }

    private fun buildExamplesPanel(): JPanel {
        examplesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        examplesTable.selectionModel.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val row = examplesTable.selectedRow
            if (row in examples.indices) {
                val example = examples[row]
                examplePreview.text = examplePreviewText(example)
                examplePreview.caretPosition = 0
            }
        }
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(JButton("Save Current").apply {
                addActionListener { saveExample() }
            })
            add(JButton("Use in Diff").apply {
                addActionListener { useExampleInDiff() }
            })
            add(JButton("Delete").apply {
                addActionListener { deleteSelectedExample() }
            })
        }
        val split = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JScrollPane(examplesTable),
            JScrollPane(examplePreview),
        ).apply {
            resizeWeight = 0.35
            border = null
        }
        return JPanel(BorderLayout(0, 4)).apply {
            add(toolbar, BorderLayout.NORTH)
            add(split, BorderLayout.CENTER)
        }
    }

    private fun selectedHistory(): HistoryEntry? {
        val row = historyTable.selectedRow
        return if (row in visibleHistory.indices) visibleHistory[row] else null
    }

    private fun deleteSelectedHistory() {
        val entry = selectedHistory() ?: return
        val answer = Messages.showYesNoDialog(
            "Delete this history entry for ${entry.request.name}?",
            "Delete History",
            Messages.getWarningIcon(),
        )
        if (answer == Messages.YES) onHistoryDelete(entry)
    }

    private fun selectedExample(): ResponseExample? {
        val row = examplesTable.selectedRow
        return if (row in examples.indices) examples[row] else null
    }

    private fun deleteSelectedExample() {
        val example = selectedExample() ?: return
        onExampleDelete?.invoke(example.id)
    }

    private fun useExampleInDiff() {
        val example = selectedExample() ?: return
        val source = ResponseDiffPanel.Source("Example: ${example.name}", example.body)
        captured.removeAll { it.label == source.label }
        captured.add(0, source)
        if (captured.size > MAX_CAPTURED) captured.removeAt(captured.lastIndex)
        diffPanel.setSources(captured.toList())
    }

    private fun saveExample() {
        val result = lastResult ?: return
        val suggestion = "Example ${examples.size + 1}"
        val name = Messages.showInputDialog(
            this,
            "Example name:",
            "Save Response Example",
            Messages.getQuestionIcon(),
            suggestion,
            null,
        )?.trim().orEmpty()
        if (name.isEmpty()) return
        val body = decodedBody()
        onExampleAdd?.invoke(
            ResponseExample(
                name = name,
                status = result.status,
                statusText = result.statusText,
                headers = result.headers,
                body = if (body.length > MAX_EXAMPLE_CHARS) body.take(MAX_EXAMPLE_CHARS) else body,
            ),
        )
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
        baseStatus = "$baseStatus  \u00b7  $suffix"
        statusLabel.text = baseStatus
    }

    fun showLoading() {
        statusLabel.foreground = com.intellij.ui.JBColor.foreground()
        baseStatus = "Sending request..."
        statusLabel.text = baseStatus
        testsModel.rowCount = 0
        lastResult = null
        lastError = null
        cachedBody = null
        showBodyText("Sending request...")
    }

    fun showResult(result: HttpResult) {
        lastResult = result
        lastError = null
        cachedBody = null
        statusLabel.foreground = when (result.status) {
            in 200..299 -> PASS_COLOR
            in 300..399 -> INFO_COLOR
            in 400..499 -> WARN_COLOR
            else -> FAIL_COLOR
        }
        baseStatus = "${result.status} ${result.statusText}  \u00b7  ${result.elapsedMs} ms  \u00b7  ${formatBytes(result.body.size)}"
        statusLabel.text = baseStatus
        headersModel.rowCount = 0
        result.headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (key, values) ->
            values.forEach { headersModel.addRow(arrayOf(key, it)) }
        }
        cookiesModel.rowCount = 0
        result.cookies.forEach {
            cookiesModel.addRow(arrayOf(it.name, it.value, it.domain, it.path))
        }
        captureResponse("${timeNow()}  \u00b7  ${result.status} ${result.statusText}", decodedBody())
        renderBody()
    }

    fun showError(message: String) {
        statusLabel.foreground = FAIL_COLOR
        baseStatus = "Request failed"
        statusLabel.text = baseStatus
        lastResult = null
        lastError = message
        cachedBody = null
        captureResponse("${timeNow()}  \u00b7  error", message)
        renderBody()
    }

    private fun captureResponse(label: String, text: String) {
        captured.add(0, ResponseDiffPanel.Source(label, text))
        while (captured.size > MAX_CAPTURED) captured.removeAt(captured.lastIndex)
        diffPanel.setSources(captured.toList())
    }

    fun showTestResults(results: List<com.astra.intellij.core.AssertionResult>) {
        testsModel.rowCount = 0
        results.forEach {
            testsModel.addRow(arrayOf(it.assertion, if (it.passed) "PASS" else "FAIL", it.detail))
        }
        if (results.isNotEmpty()) {
            val passed = results.count { it.passed }
            statusLabel.text = "$baseStatus  \u00b7  Tests $passed/${results.size}"
        }
    }

    fun setHistory(entries: List<HistoryEntry>) {
        history = entries
        refreshHistoryRows()
    }

    private fun refreshHistoryRows() {
        val query = historySearch.text.trim()
        visibleHistory = history
            .filter { entry ->
                query.isEmpty() ||
                    entry.request.name.contains(query, ignoreCase = true) ||
                    entry.request.method.contains(query, ignoreCase = true) ||
                    entry.request.url.contains(query, ignoreCase = true) ||
                    entry.status.toString().contains(query)
            }
            .sortedWith(compareByDescending<HistoryEntry> { it.pinned }.thenByDescending { it.createdAt })
        historyModel.rowCount = 0
        val format = SimpleDateFormat("MM-dd HH:mm:ss")
        visibleHistory.forEach {
            historyModel.addRow(
                arrayOf(
                    if (it.pinned) "\u2605" else "",
                    format.format(Date(it.createdAt)),
                    it.request.method,
                    it.status,
                    "${it.timeMs} ms",
                    it.request.name,
                ),
            )
        }
    }

    fun setExamples(items: List<ResponseExample>) {
        examples = items
        examplesModel.rowCount = 0
        val format = SimpleDateFormat("MM-dd HH:mm")
        items.forEach {
            examplesModel.addRow(arrayOf(it.name, it.status, format.format(Date(it.createdAt))))
        }
        if (items.isNotEmpty() && examplesTable.selectedRow < 0) {
            examplesTable.setRowSelectionInterval(0, 0)
        } else if (items.isEmpty()) {
            examplePreview.text = ""
        }
    }

    private fun renderBody() {
        val result = lastResult
        val error = lastError
        if (result == null) {
            showBodyText(error ?: "No response body.")
            return
        }
        val raw = decodedBody()
        val format = detectFormat(result, raw)
        val display = if (raw.length > MAX_RENDER_CHARS) {
            raw.take(MAX_RENDER_CHARS) +
                "\n\n\u2026 Response truncated: showing ${"%,d".format(MAX_RENDER_CHARS)} of " +
                "${"%,d".format(raw.length)} characters. Use Save\u2026 to export the full body."
        } else {
            raw
        }
        when (lastBodyMode) {
            1 -> showBodyText(display)
            2 -> showPreview(display, format)
            3 -> showJsonTree(raw, format)
            else -> showBodyText(prettify(display, format))
        }
    }

    private fun buildSsePanel(): JPanel = JPanel(BorderLayout()).also { panel ->
        sseArea.isEditable = false
        sseArea.lineWrap = true
        sseStatus.foreground = com.intellij.ui.JBColor.GRAY
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(sseStatus)
            add(toolbarButton("Stop", "Close the event stream") { onSseStop?.invoke() })
            add(toolbarButton("Clear", "Clear the received events") { sseArea.text = "" })
            add(toolbarButton("Copy", "Copy all events") {
                if (sseArea.text.isNotEmpty()) setClipboard(sseArea.text)
            })
        }
        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(JScrollPane(sseArea), BorderLayout.CENTER)
        ssePanel = panel
    }

    /** Clears the log and switches to the SSE tab when a stream starts. */
    fun beginSse() {
        sseArea.text = ""
        setSseStatus("Connecting…")
        tabs.selectedComponent = ssePanel
    }

    fun appendSseEvent(event: SseEvent) {
        val label = if (event.event == "message") "" else "event: ${event.event}  "
        val id = event.id?.let { "id: $it  " }.orEmpty()
        val time = SimpleDateFormat("HH:mm:ss").format(Date(event.timeMs))
        sseArea.append("[$time]  $label$id\n${event.data}\n\n")
        sseArea.caretPosition = sseArea.document.length
    }

    fun setSseStatus(message: String, error: Boolean = false) {
        sseStatus.text = message
        sseStatus.foreground = if (error) FAIL_COLOR else com.intellij.ui.JBColor.GRAY
    }

    private fun decodedBody(): String {
        cachedBody?.let { return it }
        val result = lastResult
        val text = when {
            result == null -> lastError.orEmpty()
            isGrpcResponse(result) -> GrpcCodec.decodeFrames(result.body)
            else -> ResponseCharset.decode(result)
        }
        cachedBody = text
        return text
    }

    private fun isGrpcResponse(result: HttpResult): Boolean = GrpcCodec.isGrpcContentType(
        result.headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.firstOrNull(),
    )

    private fun saveBody() {
        val result = lastResult ?: return
        val format = detectFormat(result, decodedBody())
        val extension = when (format) {
            "JSON" -> "json"
            "XML" -> "xml"
            "HTML" -> "html"
            else -> "txt"
        }
        val file = AstraFileChooser.save(this, "Save Response Body", "response.$extension", extension) ?: return
        runCatching { file.writeBytes(result.body) }
            .onFailure {
                Messages.showErrorDialog(this, it.message ?: "Unable to write the file", "Save failed")
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
            raw.length <= MAX_FORMAT_CHARS && runCatching { JsonParser.parseString(raw) }.isSuccess -> "JSON"
            else -> "Text"
        }
    }

    private fun prettify(raw: String, format: String): String = when (format) {
        "JSON" -> if (raw.length > MAX_FORMAT_CHARS) raw else try {
            GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                .toJson(JsonParser.parseString(raw))
        } catch (_: Exception) {
            raw
        }
        "XML" -> if (raw.length > MAX_FORMAT_CHARS) raw else try {
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

    private fun showJsonTree(raw: String, format: String) {
        bodyContainer.removeAll()
        when {
            format != "JSON" -> bodyContainer.add(
                JScrollPane(createTextArea("Tree view is available for JSON responses. Switch the format to JSON to force parsing.")),
                BorderLayout.CENTER,
            )
            raw.length > MAX_FORMAT_CHARS -> bodyContainer.add(
                JScrollPane(createTextArea("Response is too large for the tree view. Use Raw or Save\u2026 instead.")),
                BorderLayout.CENTER,
            )
            else -> {
                jsonTree.setJson(raw)
                bodyContainer.add(jsonTree, BorderLayout.CENTER)
            }
        }
        bodyContainer.revalidate()
        bodyContainer.repaint()
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

    private fun createPreviewArea(): JTextArea = createTextArea("")

    private fun examplePreviewText(example: ResponseExample): String = buildString {
        append("${example.status} ${example.statusText}")
        append("\n\n")
        append(example.body)
    }

    private fun formatBytes(size: Int): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
        else -> "%.1f MB".format(size / 1024.0 / 1024.0)
    }

    private fun setClipboard(text: String) {
        com.intellij.openapi.ide.CopyPasteManager.getInstance()
            .setContents(java.awt.datatransfer.StringSelection(text))
    }

    private fun timeNow(): String = SimpleDateFormat("HH:mm:ss").format(Date())

    companion object {
        /** Bodies larger than this are truncated before rendering so the EDT stays responsive. */
        private const val MAX_RENDER_CHARS = 1_500_000
        /** Skip JSON/XML formatting above this size; the full body is still available via Save. */
        private const val MAX_FORMAT_CHARS = 1_000_000
        private const val MAX_EXAMPLE_CHARS = 1_000_000
        private const val MAX_CAPTURED = 8
        private val PASS_COLOR = com.intellij.ui.JBColor(java.awt.Color(0x1F7A4D), java.awt.Color(0x4CC38A))
        private val FAIL_COLOR = com.intellij.ui.JBColor(java.awt.Color(0xC93B3B), java.awt.Color(0xFF6B6B))
        private val WARN_COLOR = com.intellij.ui.JBColor(java.awt.Color(0xB0720F), java.awt.Color(0xE8A33D))
        private val INFO_COLOR = com.intellij.ui.JBColor(java.awt.Color(0x2F6FED), java.awt.Color(0x5EA8FF))
    }
}
