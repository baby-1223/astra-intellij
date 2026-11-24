package com.astra.intellij.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/** Line-based diff between two response bodies kept in the current session. */
class ResponseDiffPanel : JPanel(BorderLayout(0, 4)) {
    data class Source(val label: String, val text: String) {
        override fun toString(): String = label
    }

    private enum class Kind { SAME, ADDED, REMOVED }

    private val leftBox = JComboBox<Source>()
    private val rightBox = JComboBox<Source>()
    private val summary = JBLabel(" ")
    private val output = JTextPane()

    init {
        output.isEditable = false
        output.font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
        output.background = JBColor.PanelBackground

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JLabel("Before:"))
            add(leftBox)
            add(JLabel("After:"))
            add(rightBox)
            add(JButton("Compare").apply { addActionListener { compareNow() } })
            add(summary)
        }
        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(output), BorderLayout.CENTER)
    }

    /** Passes the newest-first list of captured responses; keeps the user's selections when possible. */
    fun setSources(sources: List<Source>) {
        val leftSelected = leftBox.selectedItem as? Source
        val rightSelected = rightBox.selectedItem as? Source
        val model = DefaultComboBoxModel<Source>()
        sources.forEach { model.addElement(it) }
        leftBox.model = model
        rightBox.model = DefaultComboBoxModel<Source>().also { copy -> sources.forEach { copy.addElement(it) } }
        if (sources.isEmpty()) {
            summary.text = "No responses captured yet"
            output.text = ""
            return
        }
        leftBox.selectedItem = sources.firstOrNull { it.label == leftSelected?.label }
            ?: sources.getOrNull(1)
            ?: sources.first()
        rightBox.selectedItem = sources.firstOrNull { it.label == rightSelected?.label }
            ?: sources.first()
        if (sources.size == 1) summary.text = "Run the request once more to compare"
    }

    fun compareNow() {
        val before = leftBox.selectedItem as? Source ?: return
        val after = rightBox.selectedItem as? Source ?: return
        val leftLines = before.text.take(MAX_CHARS).lines().take(MAX_LINES)
        val rightLines = after.text.take(MAX_CHARS).lines().take(MAX_LINES)
        val operations = diff(leftLines, rightLines)
        val doc = output.styledDocument
        doc.remove(0, doc.length)
        var added = 0
        var removed = 0
        operations.forEach { (kind, line) ->
            when (kind) {
                Kind.ADDED -> added++
                Kind.REMOVED -> removed++
                Kind.SAME -> Unit
            }
            doc.append(kind, line)
        }
        summary.text = "+$added  \u2212$removed"
        output.caretPosition = 0
    }

    private fun StyledDocument.append(kind: Kind, line: String) {
        val style = SimpleAttributeSet()
        StyleConstants.setFontFamily(style, "JetBrains Mono")
        StyleConstants.setFontSize(style, JBUI.scale(12))
        val prefix = when (kind) {
            Kind.ADDED -> "+ "
            Kind.REMOVED -> "- "
            Kind.SAME -> "  "
        }
        StyleConstants.setForeground(
            style,
            when (kind) {
                Kind.ADDED -> JBColor(java.awt.Color(0x1A, 0x7F, 0x37), java.awt.Color(0x6A, 0xC7, 0x8A))
                Kind.REMOVED -> JBColor(java.awt.Color(0xB0, 0x2A, 0x2A), java.awt.Color(0xF0, 0x7A, 0x6A))
                Kind.SAME -> JBColor(java.awt.Color(0x6B, 0x6B, 0x6B), java.awt.Color(0x9A, 0x9A, 0x9A))
            },
        )
        insertString(length, prefix + line + "\n", style)
    }

    private fun diff(left: List<String>, right: List<String>): List<Pair<Kind, String>> {
        val n = left.size
        val m = right.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (left[i] == right[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val result = ArrayList<Pair<Kind, String>>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                left[i] == right[j] -> {
                    result += Kind.SAME to left[i]
                    i++
                    j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    result += Kind.REMOVED to left[i]
                    i++
                }
                else -> {
                    result += Kind.ADDED to right[j]
                    j++
                }
            }
        }
        while (i < n) result += Kind.REMOVED to left[i++]
        while (j < m) result += Kind.ADDED to right[j++]
        return result
    }

    private companion object {
        const val MAX_LINES = 1500
        const val MAX_CHARS = 300_000
    }
}
