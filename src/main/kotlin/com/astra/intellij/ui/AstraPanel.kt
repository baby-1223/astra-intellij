package com.astra.intellij.ui

import com.astra.intellij.core.CurlService
import com.astra.intellij.core.HttpCall
import com.astra.intellij.core.HttpService
import com.astra.intellij.core.OpenApiService
import com.astra.intellij.core.PostmanService
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
import com.intellij.util.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import javax.swing.AbstractAction
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

class AstraPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val workspace = WorkspaceService.get(project)
    private val editor = RequestEditorPanel()
    private val response = ResponsePanel()
    private val environmentBox = JComboBox<String>()
    private val tree = JTree()
    private val http = HttpService()
    private val curl = CurlService()
    private val postman = PostmanService()
    private val openApi = OpenApiService()
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
        registerSaveShortcut()
    }

    private fun registerSaveShortcut() {
        editor.urlField.toolTipText = "Ctrl+S / Cmd+S to save this request"
        val inputMap = editor.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), SAVE_ACTION_KEY)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.META_DOWN_MASK), SAVE_ACTION_KEY)
        editor.actionMap.put(SAVE_ACTION_KEY, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                saveCurrent()
            }
        })
    }

    private fun buildToolbar(): JPanel = JPanel(WrapLayout(FlowLayout.LEFT, 6, 4)).apply {
        add(javax.swing.JLabel("Environment"))
        add(environmentBox)
        environmentBox.addActionListener {
            if (!refreshingEnvironments) {
                val env = workspace.data.environments.getOrNull(environmentBox.selectedIndex)
                if (env != null) workspace.data.activeEnvironmentId = env.id
            }
        }
        add(JButton("Manage").apply { addActionListener { editEnvironments() } })
        add(JButton("Import \u25BE").apply {
            addActionListener {
                showMenu(this, listOf(
                    "Postman Collection" to ::importPostmanCollection,
                    "Postman Environment" to ::importPostmanEnvironment,
                    "OpenAPI / Swagger JSON" to ::importOpenApi,
                    "cURL Command" to ::importCurl,
                ))
            }
        })
        add(JButton("Export \u25BE").apply {
            addActionListener {
                showMenu(this, listOf(
                    "Postman Collection" to ::exportPostmanCollection,
                    "Postman Environment" to ::exportPostmanEnvironment,
                    "OpenAPI 3.0 JSON" to ::exportOpenApi,
                    "cURL to Clipboard" to ::exportCurl,
                ))
            }
        })
    }

    private fun showMenu(button: JButton, items: List<Pair<String, () -> Unit>>) {
        val menu = JPopupMenu()
        items.forEach { (label, action) ->
            menu.add(javax.swing.JMenuItem(label).apply { addActionListener { action() } })
        }
        menu.show(button, 0, button.height)
    }

    private fun buildCollectionPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 3, 3))
        buttons.add(JButton("+ Collection").apply { addActionListener { addCollection() } })
        panel.add(buttons, BorderLayout.NORTH)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean,
            ): Component {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
                val nodeValue = ((value as? DefaultMutableTreeNode)?.userObject as? Node)?.value
                if (nodeValue is RequestCollection || nodeValue is RequestFolder) {
                    icon = if (expanded) openIcon else closedIcon
                }
                return this
            }
        }
        tree.addTreeSelectionListener {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val value = (node?.userObject as? Node)?.value
            if (value is AstraRequest) loadRequest(value)
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)

            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = tree.getPathForLocation(e.x, e.y)
                if (path != null) tree.selectionPath = path else tree.clearSelection()
                showCollectionPopup(e.x, e.y)
            }
        })
        panel.add(JBScrollPane(tree), BorderLayout.CENTER)
        return panel
    }

    private fun showCollectionPopup(x: Int, y: Int) {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        val value = (node?.userObject as? Node)?.value
        val menu = JPopupMenu()

        fun item(label: String, action: () -> Unit) {
            menu.add(javax.swing.JMenuItem(label).apply { addActionListener { action() } })
        }

        when (value) {
            is RequestCollection -> {
                item("Add Request") { addRequest(value) }
                item("Add Folder") { addFolder(value) }
                item("Add Collection") { addCollection() }
                menu.addSeparator()
                item("Delete Collection") { deleteValue(value) }
            }
            is RequestFolder -> {
                item("Add Request") { addRequest(value) }
                menu.addSeparator()
                item("Delete Folder") { deleteValue(value) }
            }
            is AstraRequest -> item("Delete Request") { deleteValue(value) }
            else -> item("Add Collection") { addCollection() }
        }
        menu.show(tree, x, y)
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

    private fun addFolder(collection: RequestCollection) {
        val name = Messages.showInputDialog(project, "Folder name", "New Folder", null)?.trim()
        if (name.isNullOrEmpty()) return
        collection.folders.add(RequestFolder(name = name))
        refreshTree()
    }

    private fun addRequest(target: Any) {
        saveBoundRequest(refresh = false)
        val request = AstraRequest(name = "New Request")
        when (target) {
            is RequestCollection -> target.requests.add(request)
            is RequestFolder -> target.requests.add(request)
            else -> return
        }
        currentRequest = request
        savedRequest = request
        editor.showRequest(request)
        refreshTree(request)
    }

    private fun deleteValue(value: Any) {
        when (value) {
            is RequestCollection -> workspace.data.collections.removeAll { it.id == value.id }
            is RequestFolder -> workspace.data.collections.forEach { collection ->
                collection.folders.removeAll { it.id == value.id }
            }
            is AstraRequest -> workspace.data.collections.forEach { collection ->
                collection.requests.removeAll { it.id == value.id }
                collection.folders.forEach { folder -> folder.requests.removeAll { it.id == value.id } }
            }
            else -> return
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

    private fun importPostmanCollection() {
        val file = chooseJsonFile("Import Postman Collection") ?: return
        val text = readFile(file) ?: return
        try {
            val collection = postman.parseCollection(text)
            val count = collection.requests.size + collection.folders.sumOf { it.requests.size }
            if (count == 0) {
                Messages.showInfoMessage(project, "No requests found in this file", "Import")
                return
            }
            collection.name = uniqueCollectionName(collection.name)
            workspace.data.collections.add(collection)
            refreshTree(firstRequestOf(collection))
            response.showMessage("Imported $count request(s) into \"${collection.name}\"")
        } catch (error: Exception) {
            Messages.showErrorDialog(project, error.message ?: "Invalid Postman collection JSON", "Import failed")
        }
    }

    private fun importPostmanEnvironment() {
        val file = chooseJsonFile("Import Postman Environment") ?: return
        val text = readFile(file) ?: return
        try {
            val environment = postman.parseEnvironment(text)
            if (environment == null) {
                Messages.showErrorDialog(project, "No variables found in this environment file", "Import failed")
                return
            }
            environment.name = uniqueEnvironmentName(environment.name)
            workspace.data.environments.add(environment)
            workspace.data.activeEnvironmentId = environment.id
            refreshEnvironments()
            response.showMessage("Imported environment \"${environment.name}\"")
        } catch (error: Exception) {
            Messages.showErrorDialog(project, error.message ?: "Invalid Postman environment JSON", "Import failed")
        }
    }

    private fun importOpenApi() {
        val file = chooseJsonFile("Import OpenAPI / Swagger JSON") ?: return
        val text = readFile(file) ?: return
        try {
            val imported = openApi.parse(text)
            val collection = imported.collection
            val count = collection.requests.size + collection.folders.sumOf { it.requests.size }
            if (count == 0) {
                Messages.showInfoMessage(project, "No operations found in this file", "Import")
                return
            }
            collection.name = uniqueCollectionName(collection.name)
            workspace.data.collections.add(collection)
            imported.environment?.let { environment ->
                environment.name = uniqueEnvironmentName(environment.name)
                workspace.data.environments.add(environment)
                workspace.data.activeEnvironmentId = environment.id
                refreshEnvironments()
            }
            refreshTree(firstRequestOf(collection))
            response.showMessage("Imported $count operation(s) into \"${collection.name}\"")
        } catch (error: Exception) {
            Messages.showErrorDialog(project, error.message ?: "Invalid OpenAPI JSON", "Import failed")
        }
    }

    private fun exportPostmanCollection() {
        val collections = exportTargets() ?: return
        val name = if (collections.size == 1) collections.first().name else "Astra Workspace"
        val file = chooseSaveFile("Export Postman Collection", "${sanitizeFileName(name)}.postman_collection.json")
            ?: return
        writeFile(file, postman.exportCollection(collections, name))
    }

    private fun exportPostmanEnvironment() {
        val environment = workspace.activeEnvironment()
        val file = chooseSaveFile(
            "Export Postman Environment",
            "${sanitizeFileName(environment.name)}.postman_environment.json",
        ) ?: return
        writeFile(file, postman.exportEnvironment(environment))
    }

    private fun exportOpenApi() {
        val collections = exportTargets() ?: return
        val name = if (collections.size == 1) collections.first().name else "Astra API"
        val file = chooseSaveFile("Export OpenAPI 3.0", "${sanitizeFileName(name)}.openapi.json") ?: return
        writeFile(file, openApi.export(collections, name))
    }

    private fun exportTargets(): List<RequestCollection>? {
        selectedCollection()?.let { return listOf(it) }
        if (workspace.data.collections.isEmpty()) {
            Messages.showInfoMessage(project, "There is nothing to export", "Export")
            return null
        }
        return workspace.data.collections.toList()
    }

    private fun firstRequestOf(collection: RequestCollection): AstraRequest? =
        collection.requests.firstOrNull() ?: collection.folders.firstOrNull()?.requests?.firstOrNull()

    private fun chooseJsonFile(title: String): File? {
        val chooser = JFileChooser()
        chooser.dialogTitle = title
        chooser.fileFilter = FileNameExtensionFilter("JSON files (*.json)", "json")
        return if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    private fun chooseSaveFile(title: String, defaultName: String): File? {
        val chooser = JFileChooser()
        chooser.dialogTitle = title
        chooser.selectedFile = File(defaultName)
        chooser.fileFilter = FileNameExtensionFilter("JSON files (*.json)", "json")
        return if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    private fun readFile(file: File): String? = try {
        Files.readString(file.toPath())
    } catch (error: Exception) {
        Messages.showErrorDialog(project, error.message ?: "Unable to read file", "Import failed")
        null
    }

    private fun writeFile(file: File, content: String) {
        try {
            Files.writeString(file.toPath(), content)
            response.showMessage("Exported to ${file.name}")
        } catch (error: Exception) {
            Messages.showErrorDialog(project, error.message ?: "Unable to write file", "Export failed")
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "astra" }

    private fun uniqueCollectionName(base: String): String {
        if (workspace.data.collections.none { it.name == base }) return base
        var index = 2
        while (workspace.data.collections.any { it.name == "$base ($index)" }) index++
        return "$base ($index)"
    }

    private fun uniqueEnvironmentName(base: String): String {
        if (workspace.data.environments.none { it.name == base }) return base
        var index = 2
        while (workspace.data.environments.any { it.name == "$base ($index)" }) index++
        return "$base ($index)"
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

    companion object {
        private const val SAVE_ACTION_KEY = "astra.saveRequest"
    }
}
