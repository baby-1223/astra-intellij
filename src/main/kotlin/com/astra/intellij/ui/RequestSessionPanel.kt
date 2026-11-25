package com.astra.intellij.ui

import com.astra.intellij.core.CodegenLanguage
import com.astra.intellij.core.CodegenService
import com.astra.intellij.core.CurlService
import com.astra.intellij.core.HttpCall
import com.astra.intellij.core.HttpService
import com.astra.intellij.core.SseCall
import com.astra.intellij.core.PostResponseService
import com.astra.intellij.core.PreRequestService
import com.astra.intellij.core.TestService
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.AstraSettings
import com.astra.intellij.model.HistoryEntry
import com.astra.intellij.model.RequestCollection
import com.astra.intellij.model.copyContentFrom
import com.astra.intellij.model.effectiveAuth
import com.astra.intellij.model.inheritedScripts
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
    private val onDataChanged: () -> Unit = {},
    private val onOpenDraft: (AstraRequest) -> Unit = {},
) : TabSession() {
    private val editor = RequestEditorPanel { workspace.variablesFor(request).keys }
    private val response = ResponsePanel()
    private val http = HttpService()
    private val curl = CurlService()
    private val codegen = CodegenService(curl)
    private val gson = Gson()

    var request: AstraRequest = AstraRequest()
        private set
    var savedRequest: AstraRequest? = null
        private set
    private var activeCall: HttpCall? = null
    private var activeSse: SseCall? = null
    private var preparingPreRequest = false
    private var cancelPreparing = false

    /** Fired when the request name or method changes, so the owning tab can update its title. */
    var onTitleChanged: (() -> Unit)? = null

    init {
        val split = com.intellij.ui.JBSplitter(true, 0.48f).apply {
            dividerWidth = 7
            setShowDividerIcon(true)
            divider.border = javax.swing.BorderFactory.createMatteBorder(1, 0, 1, 0, com.intellij.ui.JBColor.border())
            firstComponent = editor
            secondComponent = response
        }
        add(split, BorderLayout.CENTER)
        editor.showRequest(request)
        editor.codeProvider = { language ->
            commitEditor()
            codegen.generate(request, workspace.variablesFor(request), language)
        }
        editor.sendButton.addActionListener { send() }
        editor.cancelButton.addActionListener {
            cancelPreparing = true
            activeCall?.cancel()
            activeSse?.close()
            activeSse = null
            editor.setSending(false)
            response.setSseStatus("Stream stopped")
        }
        response.onHistorySelected = {
            request = deepCopy(it.request)
            savedRequest = null
            editor.showRequest(request)
            response.setExamples(request.examples)
            onStructureChanged()
        }
        response.onHistoryDelete = { entry ->
            workspace.data.history.remove(entry)
            response.setHistory(workspace.data.history)
            onDataChanged()
        }
        response.onHistoryPinToggle = { entry ->
            entry.pinned = !entry.pinned
            response.setHistory(workspace.data.history)
            onDataChanged()
        }
        response.onHistorySaveAs = { entry ->
            val copy = deepCopy(entry.request)
            copy.id = java.util.UUID.randomUUID().toString()
            copy.name = "${entry.request.name} Copy"
            onOpenDraft(copy)
        }
        response.onExampleAdd = { example ->
            request.examples.add(example)
            response.setExamples(request.examples)
            onDataChanged()
        }
        response.onExampleDelete = { id ->
            request.examples.removeAll { it.id == id }
            response.setExamples(request.examples)
            onDataChanged()
        }
        response.setHistory(workspace.data.history)
        response.setExamples(request.examples)
        editor.onTitleChanged = { onTitleChanged?.invoke() }
        registerShortcuts()
    }

    override fun title(): String = "${editor.currentMethod()}  ${editor.currentName()}"

    override fun modelRequest(): AstraRequest = request

    override fun savedModel(): AstraRequest? = savedRequest

    fun showRequest(value: AstraRequest, bound: Boolean) {
        request = value
        savedRequest = if (bound) value else null
        editor.showRequest(value)
        response.setExamples(value.examples)
    }

    /** Restores an unsaved draft; [saved] is the collection request it belongs to, if still present. */
    fun showDraft(draft: AstraRequest, saved: AstraRequest?) {
        request = draft
        savedRequest = saved
        editor.showRequest(draft)
        response.setExamples(draft.examples)
    }

    /** Returns a copy of this tab when it contains changes that are not stored in the workspace. */
    fun draftSnapshot(): AstraRequest? {
        commitEditor()
        val saved = savedRequest
        if (saved != null) {
            if (gson.toJson(saved) == gson.toJson(request)) return null
        } else if (request.url.isBlank() &&
            request.name == "New Request" &&
            request.params.isEmpty() &&
            request.headers.isEmpty() &&
            request.body.isBlank() &&
            request.preRequest.isEmpty() &&
            request.postResponse.isEmpty() &&
            request.assertions.isEmpty()
        ) {
            return null
        }
        return deepCopy(request)
    }

    override fun commitEditor() {
        editor.readInto(request)
    }

    override fun reloadEditor() = editor.showRequest(request)

    override fun markBound() {
        savedRequest = request
    }

    override fun showMessage(message: String) = response.showMessage(message)

    fun refreshHistory() = response.setHistory(workspace.data.history)

    fun save() {
        commitEditor()
        editor.finishNameEditing()
        val saved = savedRequest
        if (saved != null && saved !== request) {
            request.id = saved.id
            saved.copyContentFrom(request)
            request = saved
            editor.showRequest(saved)
        }
        response.setExamples(request.examples)
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

    fun exportCurl() = copyCode(CodegenLanguage.CURL)

    /** Copies a runnable snippet in [language] for the current request. */
    fun exportCode(language: CodegenLanguage) = copyCode(language)

    private fun copyCode(language: CodegenLanguage) {
        commitEditor()
        val text = codegen.generate(request, workspace.variablesFor(request), language)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        response.showMessage("${language.label} snippet copied to clipboard")
    }

    /** Copies folder/collection auth and scripts onto the outgoing request copy. */
    private fun applyInheritedConfig(snapshot: AstraRequest, collection: RequestCollection?) {
        if (collection == null) return
        collection.effectiveAuth(request)?.let { snapshot.auth = it.copy() }
        val pre = collection.inheritedScripts(request, preRequest = true)
        if (pre.isNotEmpty()) snapshot.preRequest = (pre + snapshot.preRequest).toMutableList()
        val post = collection.inheritedScripts(request, preRequest = false)
        if (post.isNotEmpty()) snapshot.postResponse = (post + snapshot.postResponse).toMutableList()
    }

    override fun disposeSession() {
        activeCall?.cancel()
        activeCall = null
        activeSse?.close()
        activeSse = null
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
                when {
                    activeSse != null -> {
                        activeSse?.close()
                        activeSse = null
                        editor.setSending(false)
                        response.setSseStatus("Stream stopped")
                    }
                    activeCall != null -> activeCall?.cancel()
                    preparingPreRequest -> {
                        cancelPreparing = true
                        activeCall?.cancel()
                    }
                    else -> send()
                }
            }
        })
    }

    private fun send() {
        if (activeCall != null || preparingPreRequest) return
        commitEditor()
        if (request.url.isBlank()) {
            Messages.showErrorDialog(project, "Enter a request URL first", "Cannot send")
            return
        }
        editor.setSending(true)
        response.showLoading()
        val snapshot = deepCopy(request)
        applyInheritedConfig(snapshot, workspace.collectionOf(request))
        // Examples are metadata for the saved request; history snapshots must not duplicate them.
        snapshot.examples = mutableListOf()
        val settings = workspace.data.settings
        val environment = workspace.data.environments.firstOrNull { it.id == workspace.data.activeEnvironmentId }
        val baseVars = workspace.variablesFor(request)
        preparingPreRequest = true
        cancelPreparing = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val vars = try {
                PreRequestService.execute(snapshot.preRequest, baseVars, environment, snapshot) { call, resolved ->
                    http.sendBlocking(call, resolved, settings)
                }
            } catch (error: Exception) {
                ApplicationManager.getApplication().invokeLater({
                    if (project.isDisposed) return@invokeLater
                    preparingPreRequest = false
                    editor.setSending(false)
                    response.showError(error.message ?: "Pre-request failed")
                }, ModalityState.any())
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                preparingPreRequest = false
                if (cancelPreparing) {
                    cancelPreparing = false
                    editor.setSending(false)
                    response.showMessage("Request cancelled")
                    return@invokeLater
                }
                if (snapshot.streamSse) {
                    startEventStream(snapshot, vars, settings)
                    return@invokeLater
                }
                val call = try {
                    http.send(snapshot, vars, settings)
                } catch (error: Exception) {
                    editor.setSending(false)
                    response.showError(error.message ?: "Invalid request URL or configuration")
                    return@invokeLater
                }
                activeCall = call
                call.future.whenComplete { result, error ->
                    ApplicationManager.getApplication().invokeLater({
                        if (project.isDisposed) return@invokeLater
                        activeCall = null
                        editor.setSending(false)
                        val entry = if (result != null) {
                            response.showResult(result)
                            val updates = PostResponseService.execute(
                                snapshot.postResponse,
                                result,
                                vars,
                                environment,
                            )
                            val extracted = TestService.extract(snapshot.assertions, result)
                            extracted.forEach { (name, value) ->
                                vars[name] = value
                                environment?.variables?.set(name, value)
                            }
                            if (updates.isNotEmpty() || extracted.isNotEmpty()) {
                                response.appendStatus("Vars ${updates.size + extracted.size}")
                            }
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
                        onDataChanged()
                    }, ModalityState.any())
                }
            }, ModalityState.any())
        }
    }

    /** Opens an SSE connection and mirrors incoming events into the response panel. */
    private fun startEventStream(snapshot: AstraRequest, vars: Map<String, String>, settings: AstraSettings) {
        response.beginSse()
        response.onSseStop = {
            activeSse?.close()
            activeSse = null
            editor.setSending(false)
            response.setSseStatus("Stream stopped")
        }
        val call = try {
            http.openEventStream(
                snapshot,
                vars,
                settings,
                onOpen = { result ->
                    ApplicationManager.getApplication().invokeLater({
                        if (project.isDisposed) return@invokeLater
                        response.showResult(result)
                        response.setSseStatus("Connected · streaming")
                        workspace.data.history.add(
                            0,
                            HistoryEntry(
                                request = snapshot,
                                status = result.status,
                                statusText = "SSE stream",
                                timeMs = result.elapsedMs,
                                bodySize = 0,
                            ),
                        )
                        while (workspace.data.history.size > 100) workspace.data.history.removeLast()
                        response.setHistory(workspace.data.history)
                        onDataChanged()
                    }, ModalityState.any())
                },
                onEvent = { event ->
                    ApplicationManager.getApplication().invokeLater({
                        if (!project.isDisposed) response.appendSseEvent(event)
                    }, ModalityState.any())
                },
                onClosed = { reason ->
                    ApplicationManager.getApplication().invokeLater({
                        if (project.isDisposed) return@invokeLater
                        activeSse = null
                        editor.setSending(false)
                        response.setSseStatus(reason)
                    }, ModalityState.any())
                },
                onError = { message ->
                    ApplicationManager.getApplication().invokeLater({
                        if (project.isDisposed) return@invokeLater
                        activeSse = null
                        editor.setSending(false)
                        response.setSseStatus(message, error = true)
                    }, ModalityState.any())
                },
            )
        } catch (error: Exception) {
            activeSse = null
            editor.setSending(false)
            response.setSseStatus(error.message ?: "Event stream failed", error = true)
            return
        }
        activeSse = call
    }

    private fun deepCopy(value: AstraRequest): AstraRequest =
        gson.fromJson(gson.toJson(value), AstraRequest::class.java)

    companion object {
        private const val SAVE_ACTION_KEY = "astra.saveRequest"
        private const val SEND_ACTION_KEY = "astra.sendRequest"
    }
}
