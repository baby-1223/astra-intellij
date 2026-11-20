package com.astra.intellij.ui

import com.astra.intellij.core.CurlService
import com.astra.intellij.core.HttpCall
import com.astra.intellij.core.HttpService
import com.astra.intellij.core.PreRequestService
import com.astra.intellij.core.TestService
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.HistoryEntry
import com.astra.intellij.workspace.WorkspaceService
import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.KeyStroke

/** One tab: a request editor plus its response view, with independent send/save state. */
class RequestSessionPanel(
    private val project: Project,
    private val workspace: WorkspaceService,
    private val onStructureChanged: () -> Unit,
    private val onSaveToCollection: (RequestSessionPanel) -> Unit,
) : JPanel(BorderLayout()) {
    private val editor = RequestEditorPanel()
    private val response = ResponsePanel()
    private val http = HttpService()
    private val curl = CurlService()
    private val gson = Gson()

    var request: AstraRequest = AstraRequest()
        private set
    var savedRequest: AstraRequest? = null
        private set
    private var activeCall: HttpCall? = null

    init {
        val split = com.intellij.ui.JBSplitter(true, 0.48f).apply {
            firstComponent = editor
            secondComponent = response
        }
        add(split, BorderLayout.CENTER)
        editor.showRequest(request)
        editor.sendButton.addActionListener { send() }
        editor.cancelButton.addActionListener { activeCall?.cancel() }
        response.onHistorySelected = {
            request = deepCopy(it.request)
            savedRequest = null
            editor.showRequest(request)
            onStructureChanged()
        }
        response.setHistory(workspace.data.history)
        registerShortcuts()
    }

    fun title(): String = "${request.method}  ${request.name.ifBlank { "Untitled" }}"

    fun showRequest(value: AstraRequest, bound: Boolean) {
        request = value
        savedRequest = if (bound) value else null
        editor.showRequest(value)
    }

    fun commitEditor() = editor.readInto(request)

    fun reloadEditor() = editor.showRequest(request)

    fun markBound() {
        savedRequest = request
    }

    fun showMessage(message: String) = response.showMessage(message)

    fun save() {
        commitEditor()
        if (savedRequest == null) onSaveToCollection(this) else onStructureChanged()
        response.showMessage("Request saved")
    }

    fun importCurl() {
        val area = JTextArea(10, 70)
        val answer = JOptionPane.showConfirmDialog(
            this,
            JScrollPane(area),
            "Paste cURL command",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
        )
        if (answer != JOptionPane.OK_OPTION || area.text.isBlank()) return
        try {
            request = curl.parse(area.text)
            savedRequest = null
            editor.showRequest(request)
            onStructureChanged()
        } catch (error: Exception) {
            Messages.showErrorDialog(project, error.message ?: "Unable to parse cURL", "Import failed")
        }
    }

    fun exportCurl() {
        commitEditor()
        val text = curl.export(request, workspace.activeVariables())
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        response.showMessage("cURL copied to clipboard")
    }

    fun disposeSession() {
        activeCall?.cancel()
        activeCall = null
    }

    private fun registerShortcuts() {
        editor.urlField.toolTipText = "Ctrl+Enter to send · Ctrl+S to save · {{variable}} and {{\$timestamp}} supported"
        val inputMap = editor.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), SAVE_ACTION_KEY)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.META_DOWN_MASK), SAVE_ACTION_KEY)
        editor.actionMap.put(SAVE_ACTION_KEY, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = save()
        })
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), SEND_ACTION_KEY)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK), SEND_ACTION_KEY)
        editor.actionMap.put(SEND_ACTION_KEY, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (activeCall == null) send() else activeCall?.cancel()
            }
        })
    }

    private fun send() {
        if (activeCall != null) return
        commitEditor()
        if (request.url.isBlank()) {
            Messages.showErrorDialog(project, "Enter a request URL first", "Cannot send")
            return
        }
        editor.setSending(true)
        response.showLoading()
        val snapshot = deepCopy(request)
        val vars = PreRequestService.execute(
            snapshot.preRequest,
            workspace.activeVariables(),
            workspace.data.environments.firstOrNull { it.id == workspace.data.activeEnvironmentId },
        )
        val call = try {
            http.send(snapshot, vars, workspace.data.settings)
        } catch (error: Exception) {
            editor.setSending(false)
            response.showError(error.message ?: "Invalid request URL or configuration")
            return
        }
        activeCall = call
        call.future.whenComplete { result, error ->
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                activeCall = null
                editor.setSending(false)
                val entry = if (result != null) {
                    response.showResult(result)
                    response.showTestResults(TestService.evaluate(snapshot.assertions, result))
                    HistoryEntry(
                        request = snapshot,
                        status = result.status,
                        statusText = result.statusText,
                        timeMs = result.elapsedMs,
                        bodySize = result.body.size,
                    )
                } else {
                    val cause = error?.cause ?: error
                    val message = if (cause is java.util.concurrent.CancellationException) {
                        "Request cancelled"
                    } else {
                        cause?.message ?: "Unknown network error"
                    }
                    response.showError(message)
                    HistoryEntry(
                        request = snapshot,
                        status = 0,
                        statusText = message,
                        timeMs = 0,
                        bodySize = 0,
                    )
                }
                workspace.data.history.add(0, entry)
                while (workspace.data.history.size > 100) workspace.data.history.removeLast()
                response.setHistory(workspace.data.history)
            }, ModalityState.any())
        }
    }

    private fun deepCopy(value: AstraRequest): AstraRequest =
        gson.fromJson(gson.toJson(value), AstraRequest::class.java)

    companion object {
        private const val SAVE_ACTION_KEY = "astra.saveRequest"
        private const val SEND_ACTION_KEY = "astra.sendRequest"
    }
}
