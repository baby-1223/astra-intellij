package com.astra.intellij.ui

import com.astra.intellij.model.HistoryEntry
import com.astra.intellij.model.HttpResult
import java.awt.BorderLayout
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

class ResponsePanel : JPanel(BorderLayout()) {
    private val statusLabel = JLabel("尚未发送")
    private val rawArea = JTextArea().apply {
        isEditable = false
        lineWrap = false
    }
    private val jsonContainer = JPanel(BorderLayout())
    private val headersModel = DefaultTableModel(arrayOf("响应头", "值"), 0)
    private val cookiesModel = DefaultTableModel(arrayOf("名称", "值", "Domain", "Path"), 0)
    private val historyModel = object : DefaultTableModel(arrayOf("时间", "方法", "状态", "耗时", "请求"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val historyTable = JTable(historyModel)
    private var history = listOf<HistoryEntry>()
    var onHistorySelected: (HistoryEntry) -> Unit = {}

    init {
        val tabs = JTabbedPane()
        tabs.addTab("JSON 树", jsonContainer)
        tabs.addTab("原始响应", JScrollPane(rawArea))
        tabs.addTab("响应头", JScrollPane(JTable(headersModel)))
        tabs.addTab("Cookies", JScrollPane(JTable(cookiesModel)))
        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        historyTable.selectionModel.addListSelectionListener {
            val row = historyTable.selectedRow
            if (row >= 0 && row < history.size) onHistorySelected(history[row])
        }
        tabs.addTab("历史记录", JScrollPane(historyTable))
        add(statusLabel, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        showJsonPlaceholder("发送请求后，这里会展示 JSON 树")
    }

    fun showMessage(message: String) {
        statusLabel.text = message
    }

    fun showLoading() {
        statusLabel.text = "请求发送中…"
        rawArea.text = ""
        showJsonPlaceholder("请求发送中…")
    }

    fun showResult(result: HttpResult) {
        val text = String(result.body, StandardCharsets.UTF_8)
        statusLabel.text = "${result.status} ${result.statusText}  ·  ${result.elapsedMs} ms  ·  ${formatBytes(result.body.size)}"
        rawArea.text = text
        rawArea.caretPosition = 0
        jsonContainer.removeAll()
        jsonContainer.add(JScrollPane(JsonTrees.parse(text)), BorderLayout.CENTER)
        jsonContainer.revalidate()
        jsonContainer.repaint()
        headersModel.rowCount = 0
        result.headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (key, values) ->
            values.forEach { headersModel.addRow(arrayOf(key, it)) }
        }
        cookiesModel.rowCount = 0
        result.cookies.forEach {
            cookiesModel.addRow(arrayOf(it.name, it.value, it.domain, it.path))
        }
    }

    fun showError(message: String) {
        statusLabel.text = "请求失败"
        rawArea.text = message
        showJsonPlaceholder(message)
    }

    fun setHistory(entries: List<HistoryEntry>) {
        history = entries
        historyModel.rowCount = 0
        val format = SimpleDateFormat("MM-dd HH:mm:ss")
        entries.forEach {
            historyModel.addRow(arrayOf(format.format(Date(it.createdAt)), it.request.method, it.status, "${it.timeMs} ms", it.request.name))
        }
    }

    private fun showJsonPlaceholder(text: String) {
        jsonContainer.removeAll()
        jsonContainer.add(JScrollPane(JTextArea(text).apply { isEditable = false }), BorderLayout.CENTER)
        jsonContainer.revalidate()
        jsonContainer.repaint()
    }

    private fun formatBytes(size: Int): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
        else -> "%.1f MB".format(size / 1024.0 / 1024.0)
    }
}
