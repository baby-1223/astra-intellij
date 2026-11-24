package com.astra.intellij.ui

import com.astra.intellij.core.VariableTextService
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JTextArea

/**
 * Paste-import for `.env` and CSV variables.
 *
 * On OK the parsed rows are exposed through [result]; callers merge them into their own tables.
 */
class VariableImportDialog(parent: Component) : DialogWrapper(parent, true) {
    private val area = JTextArea(12, 64).apply {
        lineWrap = false
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
    }

    var result: List<Pair<String, String>> = emptyList()
        private set

    init {
        title = "Import Variables (.env / CSV)"
        setOKButtonText("Import")
        init()
    }

    override fun createCenterPanel(): JComponent = JBScrollPane(area).apply {
        preferredSize = JBUI.size(640, 320)
        border = JBUI.Borders.empty(4)
    }

    override fun doOKAction() {
        val parsed = VariableTextService.parse(area.text)
        if (parsed.isEmpty()) {
            Messages.showErrorDialog(this.contentPane, "No KEY=VALUE or CSV rows found", "Import Variables")
            return
        }
        result = parsed
        super.doOKAction()
    }
}
