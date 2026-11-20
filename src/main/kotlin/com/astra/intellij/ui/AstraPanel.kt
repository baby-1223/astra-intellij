package com.astra.intellij.ui

import com.astra.intellij.core.CurlService
import com.astra.intellij.core.HttpCall
import com.astra.intellij.core.HttpService
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.HistoryEntry
import com.astra.intellij.model.RequestCollection
import com.astra.intellij.model.RequestFolder
import com.astra.intellij.workspace.WorkspaceService
import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class AstraPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val workspace = WorkspaceService.get(project)
    private val editor = RequestEditorPanel()
    private val response = ResponsePanel()
    private val environmentBox = JComboBox<String>()
    private val tree = JTree()
    private val http = HttpService()
    private val curl = CurlService()
    private val gson = Gson()
    private var currentRequest = AstraRequest()
    private var savedRequest: AstraRequest? = null
    private var activeCall: HttpCall? = null
    private var refreshingEnvironments = false

    init {
        add(buildToolbar(), BorderLayout.NORTH)
        val mainSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, editor, response).apply {
            resizeWeight = 0.48
            dividerLocation = 430
        }
        val rootSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildCollectionPanel(), mainSplit).apply {
            dividerLocation = 250
            resizeWeight = 0.0
        }
        add(rootSplit, BorderLayout.CENTER)
        editor.showRequest(currentRequest)
        editor.sendButton.addActionListener { send() }
        editor.cancelButton.addActionListener { activeCall?.cancel() }
        response.onHistorySelected = {
            currentRequest = copy(it.request)
            savedRequest = null
            editor.showRequest(currentRequest)
        }
        refreshEnvironments()
        refreshTree()
        response.setHistory(workspace.data.history)
    }

    private fun buildToolbar(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
        add(javax.swing.JLabel("Environment"))
        add(environmentBox)
        environmentBox.addActionListener {
            if (!refreshingEnvironments) {
                val env = workspace.data.environments.getOrNull(environmentBox.selectedIndex)
                if (env != null) workspace.data.activeEnvironmentId = env.id
            }
        }
        add(JButton("Manage").apply { addActionListener { editEnvironments() } })
        add(JButton("Import cURL").apply { addActionListener { importCurl() } })
        add(JButton("Copy cURL").apply { addActionListener { exportCurl() } })
        add(JButton("Save").apply { addActionListener { saveCurrent() } })
    }

    private fun buildCollectionPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 3, 3))
        buttons.add(JButton("+ Collection").apply { addActionListener { addCollection() } })
        buttons.add(JButton("+ Folder").apply { addActionListener { addFolder() } })
        buttons.add(JButton("+ Request").apply { addActionListener { addRequest() } })
        buttons.add(JButton("Delete").apply { addActionListener { deleteSelected() } })
        panel.add(buttons, BorderLayout.NORTH)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.addTreeSelectionListener {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val value = (node?.userObject as? Node)?.value
            if (value is AstraRequest) loadRequest(value)
        }
        panel.add(JBScrollPane(tree), BorderLayout.CENTER)
        return panel
    }

    private fun refreshTree(select: AstraRequest? = null) {
        val root = DefaultMutableTreeNode(Node("Workspace", workspace.data))
        var selectNode: DefaultMutableTreeNode? = null
        workspace.data.collections.forEach { collection ->
            val collectionNode = DefaultMutableTreeNode(Node(collection.name, collection))
            root.add(collectionNode)
            collection.folders.forEach { folder ->
                val folderNode = DefaultMutableTreeNode(Node(folder.name, folder))
                collectionNode.add(folderNode)
                folder.requests.forEach { request ->
                    val node = DefaultMutableTreeNode(Node("${request.method}  ${request.name}", request))
                    folderNode.add(node)
                    if (request === select) selectNode = node
                }
            }
            collection.requests.forEach { request ->
                val node = DefaultMutableTreeNode(Node("${request.method}  ${request.name}", request))
                collectionNode.add(node)
                if (request === select) selectNode = node
            }
        }
        tree.model = DefaultTreeModel(root)
        for (row in 0 until tree.rowCount) tree.expandRow(row)
        if (selectNode != null) {
            tree.selectionPath = javax.swing.tree.TreePath(selectNode!!.path)
        }
    }

    private fun addCollection() {
        val name = Messages.showInputDialog(project, "Collection name", "New Collection", null)?.trim()
        if (name.isNullOrEmpty()) return
        workspace.data.collections.add(RequestCollection(name = name))
        refreshTree()
    }

    private fun addFolder() {
        val collection = selectedCollection() ?: run {
            Messages.showInfoMessage(project, "Select a collection first", "New Folder")
            return
        }
        val name = Messages.showInputDialog(project, "Folder name", "New Folder", null)?.trim()
        if (name.isNullOrEmpty()) return
        collection.folders.add(RequestFolder(name = name))
        refreshTree()
    }

    private fun addRequest() {
        saveBoundRequest(refresh = false)
        val target = selectedTarget() ?: run {
            if (workspace.data.collections.isEmpty()) {
                workspace.data.collections.add(RequestCollection(name = "Default Collection"))
            }
            workspace.data.collections.first()
        }
        val request = AstraRequest(name = "New Request")
        when (target) {
            is RequestCollection -> target.requests.add(request)
            is RequestFolder -> target.requests.add(request)
        }
        currentRequest = request
        savedRequest = request
        editor.showRequest(request)
        refreshTree(request)
    }

    private fun deleteSelected() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val value = (node.userObject as? Node)?.value ?: return
        when (value) {
            is RequestCollection -> workspace.data.collections.removeAll { it.id == value.id }
            is RequestFolder -> workspace.data.collections.forEach { collection ->
                collection.folders.removeAll { it.id == value.id }
            }
            is AstraRequest -> workspace.data.collections.forEach { collection ->
                collection.requests.removeAll { it.id == value.id }
                collection.folders.forEach { folder -> folder.requests.removeAll { it.id == value.id } }
            }
        }
        val currentExists = workspace.data.collections.any { collection ->
            collection.requests.any { it === currentRequest } ||
                collection.folders.any { folder -> folder.requests.any { it === currentRequest } }
        }
        if (savedRequest != null && !currentExists) {
            currentRequest = AstraRequest()
            savedRequest = null
            editor.showRequest(currentRequest)
        }
        refreshTree()
    }

    private fun loadRequest(request: AstraRequest) {
        if (request === currentRequest) return
        saveBoundRequest(refresh = false)
        currentRequest = request
        savedRequest = request
        editor.showRequest(request)
    }

    private fun saveBoundRequest(refresh: Boolean = true) {
        val saved = savedRequest ?: return
        editor.readInto(saved)
        if (refresh) refreshTree()
    }

    private fun saveCurrent() {
        editor.readInto(currentRequest)
        if (savedRequest == null) {
            val target = selectedTarget() ?: run {
                if (workspace.data.collections.isEmpty()) {
                    workspace.data.collections.add(RequestCollection(name = "Default Collection"))
                }
                workspace.data.collections.first()
            }
            when (target) {
                is RequestCollection -> target.requests.add(currentRequest)
                is RequestFolder -> target.requests.add(currentRequest)
            }
            savedRequest = currentRequest
        }
        refreshTree(currentRequest)
        response.showMessage("Request saved")
    }

    private fun editEnvironments() {
        val dialog = EnvironmentDialog(workspace.data.environments, workspace.data.activeEnvironmentId)
        if (dialog.showAndGet()) {
            val selected = dialog.selectedEnvironmentId()
            workspace.data.activeEnvironmentId = if (workspace.data.environments.any { it.id == selected }) {
                selected
            } else {
                workspace.data.environments.first().id
            }
            refreshEnvironments()
        }
    }

    private fun refreshEnvironments() {
        val environments = workspace.data.environments
        val active = environments.indexOfFirst { it.id == workspace.data.activeEnvironmentId }.coerceAtLeast(0)
        refreshingEnvironments = true
        try {
            environmentBox.model = DefaultComboBoxModel(environments.map { it.name }.toTypedArray())
            environmentBox.selectedIndex = active
        } finally {
            refreshingEnvironments = false
        }
        workspace.data.activeEnvironmentId = environments[active].id
    }

    private fun importCurl() {
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
            currentRequest = curl.parse(area.text)
            savedRequest = null
            editor.showRequest(currentRequest)
        } catch (error: Exception) {
            Messages.showErrorDialog(project, error.message ?: "Unable to parse cURL", "Import failed")
        }
    }

    private fun exportCurl() {
        editor.readInto(currentRequest)
        val text = curl.export(currentRequest, workspace.activeVariables())
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        response.showMessage("cURL copied to clipboard")
    }

    private fun send() {
        if (activeCall != null) return
        editor.readInto(currentRequest)
        if (currentRequest.url.isBlank()) {
            Messages.showErrorDialog(project, "Enter a request URL first", "Cannot send")
            return
        }
        editor.setSending(true)
        response.showLoading()
        val snapshot = copy(currentRequest)
        val call = try {
            http.send(snapshot, workspace.activeVariables())
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

    private fun selectedTarget(): Any? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        val value = (node.userObject as? Node)?.value
        return when (value) {
            is RequestCollection, is RequestFolder -> value
            is AstraRequest -> (node.parent as? DefaultMutableTreeNode)?.let {
                (it.userObject as? Node)?.value
            }
            else -> null
        }
    }

    private fun selectedCollection(): RequestCollection? {
        var node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        while (node != null) {
            val value = (node.userObject as? Node)?.value
            if (value is RequestCollection) return value
            node = node.parent as? DefaultMutableTreeNode
        }
        return null
    }

    private fun copy(request: AstraRequest): AstraRequest =
        gson.fromJson(gson.toJson(request), AstraRequest::class.java)

    override fun dispose() {
        activeCall?.cancel()
        activeCall = null
    }

    private data class Node(val label: String, val value: Any) {
        override fun toString(): String = label
    }
}
