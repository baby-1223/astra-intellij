package com.astra.intellij.ui

import com.astra.intellij.core.ArchiveService
import com.astra.intellij.core.HttpClients
import com.astra.intellij.core.OpenApiService
import com.astra.intellij.core.PostmanService
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.RequestCollection
import com.astra.intellij.model.RequestFolder
import com.astra.intellij.model.allFolders
import com.astra.intellij.model.allRequests
import com.astra.intellij.model.normalize
import com.astra.intellij.workspace.WorkspaceService
import com.google.gson.Gson
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
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
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class AstraPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val workspace = WorkspaceService.get(project)
    private val environmentBox = JComboBox<String>()
    private val tree = JTree()
    private val tabStrip = RequestTabStrip(
        onSelectionChanged = { /* selection is tracked by the strip itself */ },
        onCloseRequested = { closeSession(it) },
        onNewRequest = { newRequest() },
    )
    private val sessions = mutableListOf<RequestSessionPanel>()
    private val postman = PostmanService()
    private val openApi = OpenApiService()
    private val archive = ArchiveService()
    private val gson = Gson()
    private var refreshingEnvironments = false

    init {
        add(buildToolbar(), BorderLayout.NORTH)
        val rootSplit = com.intellij.ui.JBSplitter(false, 0.24f).apply {
            firstComponent = buildCollectionPanel()
            secondComponent = tabStrip
        }
        add(rootSplit, BorderLayout.CENTER)
        refreshEnvironments()
        refreshTree()
        registerTabShortcuts()
        newRequest()
        installThemeListener()
    }

    /** Re-applies IDE theme colors when the user switches the appearance (light/dark). */
    private fun installThemeListener() {
        ApplicationManager.getApplication().messageBus
            .connect(project)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener {
                SwingUtilities.updateComponentTreeUI(this)
                tabStrip.refreshTheme()
                refreshTree()
                revalidate()
                repaint()
            })
    }

    private fun currentSession(): RequestSessionPanel? = tabStrip.selectedSession

    private fun notify(message: String) = currentSession()?.showMessage(message) ?: Unit

    private fun structureChanged() {
        refreshTree()
        refreshTabTitles()
    }

    private fun openRequest(request: AstraRequest, bound: Boolean) {
        request.normalize()
        sessions.firstOrNull { it.request === request }?.let { existing ->
            tabStrip.selectSession(existing)
            return
        }
        currentSession()?.commitEditor()
        val session = RequestSessionPanel(
            project = project,
            workspace = workspace,
            onStructureChanged = ::structureChanged,
            onSaveToCollection = ::saveToCollection,
        )
        try {
            session.showRequest(request, bound)
            session.onTitleChanged = { tabStrip.updateTitle(session) }
            sessions.add(session)
            tabStrip.addSession(session)
        } catch (t: Throwable) {
            sessions.remove(session)
            com.intellij.openapi.diagnostic.Logger.getInstance(AstraPanel::class.java).warn("Open request failed", t)
            Messages.showErrorDialog(project, t.message ?: "Unknown error", "Cannot Open Request")
        }
    }

    private fun refreshTabTitles() {
        sessions.forEach { session -> tabStrip.updateTitle(session) }
    }

    private fun newRequest() = openRequest(AstraRequest(name = "New Request"), bound = false)

    private fun closeSession(session: RequestSessionPanel) {
        session.disposeSession()
        sessions.remove(session)
        if (selectedNodeValue() === session.request) tree.clearSelection()
        tabStrip.removeSession(session)
    }

    private fun saveToCollection(session: RequestSessionPanel) {
        val target = selectedTarget() ?: run {
            if (workspace.data.collections.isEmpty()) {
                workspace.data.collections.add(RequestCollection(name = "Default Collection"))
            }
            workspace.data.collections.first()
        }
        when (target) {
            is RequestCollection -> target.requests.add(session.request)
            is RequestFolder -> target.requests.add(session.request)
        }
        session.markBound()
        structureChanged()
    }

    private fun registerTabShortcuts() {
        val inputMap = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), NEW_TAB_ACTION)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.META_DOWN_MASK), NEW_TAB_ACTION)
        actionMap.put(NEW_TAB_ACTION, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = newRequest()
        })
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK), CLOSE_TAB_ACTION)
        actionMap.put(CLOSE_TAB_ACTION, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                currentSession()?.let { closeSession(it) }
            }
        })
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK), PREV_TAB_ACTION)
        actionMap.put(PREV_TAB_ACTION, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = tabStrip.selectPrevious()
        })
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK), NEXT_TAB_ACTION)
        actionMap.put(NEXT_TAB_ACTION, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = tabStrip.selectNext()
        })
    }

    private fun buildToolbar(): JPanel = JPanel(WrapLayout(FlowLayout.LEFT, 6, 4)).apply {
        add(JLabel("Environment"))
        add(environmentBox)
        environmentBox.addActionListener {
            if (!refreshingEnvironments) {
                val env = workspace.data.environments.getOrNull(environmentBox.selectedIndex)
                if (env != null) workspace.data.activeEnvironmentId = env.id
            }
        }
        add(JButton("Manage \u25BE").apply {
            addActionListener {
                showMenu(this, listOf(
                    "Environments..." to ::editEnvironments,
                    "Globals..." to ::editGlobals,
                    "Settings..." to ::editSettings,
                    "Clear Cookies" to {
                        HttpClients.clearCookies()
                        notify("Cookies cleared")
                    },
                ))
            }
        })
        add(JButton("Import \u25BE").apply {
            addActionListener {
                showMenu(this, listOf(
                    "Postman Collection" to ::importPostmanCollection,
                    "Postman Environment" to ::importPostmanEnvironment,
                    "OpenAPI / Swagger JSON" to ::importOpenApi,
                    "Astra Workspace (.json)" to ::importWorkspace,
                    "cURL Command" to { currentSession()?.importCurl() },
                ))
            }
        })
        add(JButton("Export \u25BE").apply {
            addActionListener {
                showMenu(this, listOf(
                    "Postman Collection" to ::exportPostmanCollection,
                    "Postman Environment" to ::exportPostmanEnvironment,
                    "OpenAPI 3.0 JSON" to ::exportOpenApi,
                    "Astra Workspace (.json)" to ::exportWorkspace,
                    "cURL to Clipboard" to { currentSession()?.exportCurl() },
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
        tree.isRootVisible = false
        tree.showsRootHandles = true
        // IntelliJ paints a full-width selection over custom renderers; disable it so rows stay flat.
        tree.putClientProperty("JTree.wideSelection", false)
        tree.cellRenderer = NodeRenderer()
        tree.addTreeSelectionListener {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val value = (node?.userObject as? Node)?.value
            if (value is AstraRequest) openRequest(value, bound = true)
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)

            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)

            private fun maybeShowPopup(e: MouseEvent) {
                // Left clicks belong to the tree (select, expand/collapse); only the platform
                // popup trigger opens the action menu. Check both press and release because
                // Windows and macOS report popup triggers at different times.
                if (!e.isPopupTrigger) return
                val row = tree.getRowForLocation(e.x, e.y)
                if (row >= 0) {
                    tree.selectionPath = tree.getPathForRow(row)
                } else {
                    tree.clearSelection()
                }
                showCollectionPopup(e.x, e.y)
                e.consume()
            }
        })
        panel.add(JBScrollPane(tree), BorderLayout.CENTER)
        tree.dragEnabled = true
        tree.dropMode = javax.swing.DropMode.ON
        tree.transferHandler = TreeTransferHandler()
        registerTreeActions()
        return panel
    }

    /** Drag & drop moves requests/folders between collections and folders. */
    private inner class TreeTransferHandler : javax.swing.TransferHandler() {
        override fun getSourceActions(c: JComponent?): Int = MOVE

        override fun createTransferable(c: JComponent?): java.awt.datatransfer.Transferable? {
            val path = tree.selectionPath ?: return null
            val value = ((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Node)?.value
            return if (value is AstraRequest || value is RequestFolder) NodeTransferable(value) else null
        }

        override fun canImport(support: TransferSupport): Boolean {
            if (!support.isDrop) return false
            val target = dropTarget(support) ?: return false
            val source = draggedValue(support) ?: return false
            return com.astra.intellij.model.TreeOperations.canDrop(source, target)
        }

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false
            val target = dropTarget(support) ?: return false
            val source = draggedValue(support) ?: return false
            val moved = com.astra.intellij.model.TreeOperations.move(workspace.data, source, target)
            if (moved) refreshTree()
            return moved
        }

        private fun dropTarget(support: TransferSupport): Any? {
            val location = support.dropLocation as? JTree.DropLocation ?: return null
            val node = location.path?.lastPathComponent as? DefaultMutableTreeNode
            return (node?.userObject as? Node)?.value
        }

        private fun draggedValue(support: TransferSupport): Any? =
            runCatching { support.transferable.getTransferData(NodeTransferable.FLAVOR) }.getOrNull()
    }

    private class NodeTransferable(private val value: Any) : java.awt.datatransfer.Transferable {
        override fun getTransferDataFlavors(): Array<java.awt.datatransfer.DataFlavor> = arrayOf(FLAVOR)
        override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor): Boolean = flavor == FLAVOR
        override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any = value

        companion object {
            val FLAVOR = java.awt.datatransfer.DataFlavor(
                java.awt.datatransfer.DataFlavor.javaJVMLocalObjectMimeType,
                "Astra tree node",
            )
        }
    }

    /** Renders icon + name on the left and a right-aligned vertical dots affordance. */
    private inner class NodeRenderer : JPanel(BorderLayout()), javax.swing.tree.TreeCellRenderer {
        private val label = JLabel()
        private val dots = JLabel("⋮")
        private var extraWidth = 0
        private var measuringRowBounds = false

        init {
            isOpaque = true
            label.isOpaque = false
            label.border = javax.swing.BorderFactory.createEmptyBorder(2, 2, 2, 2)
            dots.isOpaque = false
            dots.foreground = com.intellij.ui.JBColor.GRAY
            dots.font = dots.font.deriveFont(java.awt.Font.BOLD, dots.font.size + 1f)
            dots.border = javax.swing.BorderFactory.createEmptyBorder(0, 8, 0, 6)
            add(label, BorderLayout.WEST)
            add(dots, BorderLayout.EAST)
        }

        override fun getPreferredSize(): java.awt.Dimension {
            val size = super.getPreferredSize()
            return java.awt.Dimension(size.width + extraWidth, size.height)
        }

        override fun getTreeCellRendererComponent(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ): Component {
            // DefaultTreeUI asks for this renderer again while calculating row bounds.
            // Return the base renderer for that nested measurement instead of recursing forever.
            if (measuringRowBounds) {
                extraWidth = 0
                return this
            }
            val nodeValue = ((value as? DefaultMutableTreeNode)?.userObject as? Node)?.value
            when (nodeValue) {
                is RequestCollection -> {
                    label.icon = AstraIcons.collection
                    label.text = escapeHtml(nodeValue.name)
                }
                is RequestFolder -> {
                    label.icon = AstraIcons.folder
                    label.text = escapeHtml(nodeValue.name)
                }
                is AstraRequest -> {
                    label.icon = AstraIcons.request(nodeValue.method)
                    val methodColor = AstraIcons.methodColor(nodeValue.method)
                    label.text = "<html><span style='color:$methodColor'><b>${nodeValue.method}</b></span> " +
                        escapeHtml(nodeValue.name) + "</html>"
                }
                else -> {
                    label.icon = null
                    label.text = value?.toString().orEmpty()
                }
            }
            label.foreground = com.intellij.ui.JBColor.foreground()
            // Clicking a row must not show a selection highlight; only the dots open actions.
            background = com.intellij.ui.JBColor.background()
            val base = super.getPreferredSize()
            measuringRowBounds = true
            val xOffset = try {
                runCatching { tree.getRowBounds(row)?.x ?: 0 }.getOrDefault(0)
            } finally {
                measuringRowBounds = false
            }
            val viewportWidth = (tree.parent as? javax.swing.JViewport)?.width ?: tree.width
            extraWidth = (viewportWidth - xOffset - 4 - base.width).coerceAtLeast(0)
            return this
        }
    }

    private fun registerTreeActions() {
        fun bind(stroke: KeyStroke, name: String, action: () -> Unit) {
            tree.getInputMap(JComponent.WHEN_FOCUSED).put(stroke, name)
            tree.actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = action()
            })
        }
        bind(ADD_COLLECTION_STROKE, "astra.addCollection") {
            // Collections are top-level only: inside a collection or folder only requests/folders are allowed.
            val target = selectedTarget()
            if (target !is RequestCollection && target !is RequestFolder) addCollection()
        }
        bind(ADD_REQUEST_STROKE, "astra.addRequest") {
            val target = selectedTarget()
            if (target is RequestCollection || target is RequestFolder) addRequest(target) else newRequest()
        }
        bind(ADD_FOLDER_STROKE, "astra.addFolder") {
            selectedTarget()?.let { if (it is RequestCollection || it is RequestFolder) addFolder(it) }
        }
        bind(RUN_STROKE, "astra.runSelection") {
            when (val value = selectedNodeValue()) {
                is RequestCollection -> runRequests(value.name, value.allRequests())
                is RequestFolder -> runRequests(value.name, value.allRequests())
            }
        }
        bind(RENAME_STROKE, "astra.renameSelection") { selectedNodeValue()?.let { renameValue(it) } }
        bind(DUPLICATE_STROKE, "astra.duplicateSelection") { selectedNodeValue()?.let { duplicateValue(it) } }
        bind(DELETE_STROKE, "astra.deleteSelection") { selectedNodeValue()?.let { deleteValue(it) } }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "astra.deleteSelectionBs") {
            selectedNodeValue()?.let { deleteValue(it) }
        }
    }

    private fun selectedNodeValue(): Any? =
        ((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Node)?.value

    private fun showCollectionPopup(x: Int, y: Int) {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        val value = (node?.userObject as? Node)?.value
        val menu = JPopupMenu()

        fun item(label: String, icon: javax.swing.Icon? = null, accelerator: KeyStroke? = null, action: () -> Unit) {
            menu.add(javax.swing.JMenuItem(label).apply {
                this.icon = icon
                this.accelerator = accelerator
                addActionListener { action() }
            })
        }

        when (value) {
            is RequestCollection -> {
                item("Add Request", AstraIcons.request("GET"), ADD_REQUEST_STROKE) { addRequest(value) }
                item("Add Folder", AstraIcons.folder, ADD_FOLDER_STROKE) { addFolder(value) }
                menu.addSeparator()
                item("Run Collection", AstraIcons.run, RUN_STROKE) { runRequests(value.name, value.allRequests()) }
                menu.addSeparator()
                item("Rename", AstraIcons.rename, RENAME_STROKE) { renameValue(value) }
                item("Duplicate", AstraIcons.duplicate, DUPLICATE_STROKE) { duplicateValue(value) }
                menu.addSeparator()
                item("Delete Collection", AstraIcons.delete, DELETE_STROKE) { deleteValue(value) }
            }
            is RequestFolder -> {
                item("Add Request", AstraIcons.request("GET"), ADD_REQUEST_STROKE) { addRequest(value) }
                item("Add Folder", AstraIcons.folder, ADD_FOLDER_STROKE) { addFolder(value) }
                menu.addSeparator()
                item("Run Folder", AstraIcons.run, RUN_STROKE) { runRequests(value.name, value.allRequests()) }
                menu.addSeparator()
                item("Rename", AstraIcons.rename, RENAME_STROKE) { renameValue(value) }
                item("Duplicate", AstraIcons.duplicate, DUPLICATE_STROKE) { duplicateValue(value) }
                menu.addSeparator()
                item("Delete Folder", AstraIcons.delete, DELETE_STROKE) { deleteValue(value) }
            }
            is AstraRequest -> {
                item("Rename", AstraIcons.rename, RENAME_STROKE) { renameValue(value) }
                item("Duplicate", AstraIcons.duplicate, DUPLICATE_STROKE) { duplicateValue(value) }
                menu.addSeparator()
                item("Delete Request", AstraIcons.delete, DELETE_STROKE) { deleteValue(value) }
            }
            else -> {
                item("Add Request", AstraIcons.request("GET"), ADD_REQUEST_STROKE) { newRequest() }
                item("Add Collection", AstraIcons.collection, ADD_COLLECTION_STROKE) { addCollection() }
            }
        }
        menu.show(tree, x, y)
    }

    private fun runRequests(name: String, requests: List<AstraRequest>) {
        if (requests.isEmpty()) {
            Messages.showInfoMessage(project, "No requests to run", "Collection Runner")
            return
        }
        RunnerDialog(project, workspace, name, requests).show()
    }

    private fun renameValue(value: Any) {
        val currentName = when (value) {
            is RequestCollection -> value.name
            is RequestFolder -> value.name
            is AstraRequest -> value.name
            else -> return
        }
        sessions.firstOrNull { it.request === value }?.commitEditor()
        val name = Messages.showInputDialog(project, "Name", "Rename", null, currentName, null)?.trim()
        if (name.isNullOrEmpty()) return
        when (value) {
            is RequestCollection -> value.name = name
            is RequestFolder -> value.name = name
            is AstraRequest -> value.name = name
        }
        sessions.firstOrNull { it.request === value }?.reloadEditor()
        structureChanged()
    }

    private fun duplicateValue(value: Any) {
        when (value) {
            is AstraRequest -> {
                val parent = workspace.data.collections.firstOrNull { it.requests.any { r -> r === value } }
                    ?: workspace.data.collections.asSequence().flatMap { it.allFolders() }
                        .firstOrNull { folder -> folder.requests.any { r -> r === value } } ?: return
                val duplicate = deepCopy(value, AstraRequest::class.java).apply {
                    id = java.util.UUID.randomUUID().toString()
                    name = "${value.name} Copy"
                }
                when (parent) {
                    is RequestCollection -> parent.requests.add(parent.requests.indexOf(value) + 1, duplicate)
                    is RequestFolder -> parent.requests.add(parent.requests.indexOf(value) + 1, duplicate)
                }
                openRequest(duplicate, bound = true)
                structureChanged()
            }
            is RequestFolder -> {
                val duplicate = deepCopy(value, RequestFolder::class.java).apply {
                    regenerateFolderIds(this)
                    name = "${value.name} Copy"
                }
                val parentCollection = workspace.data.collections.firstOrNull { it.folders.any { f -> f === value } }
                if (parentCollection != null) {
                    parentCollection.folders.add(parentCollection.folders.indexOf(value) + 1, duplicate)
                } else {
                    val parentFolder = workspace.data.collections.asSequence().flatMap { it.allFolders() }
                        .firstOrNull { it.folders.any { f -> f === value } } ?: return
                    parentFolder.folders.add(parentFolder.folders.indexOf(value) + 1, duplicate)
                }
                refreshTree()
            }
            is RequestCollection -> {
                val duplicate = deepCopy(value, RequestCollection::class.java).apply {
                    id = java.util.UUID.randomUUID().toString()
                    name = uniqueCollectionName("${value.name} Copy")
                    requests.forEach { it.id = java.util.UUID.randomUUID().toString() }
                    folders.forEach { regenerateFolderIds(it) }
                }
                workspace.data.collections.add(workspace.data.collections.indexOf(value) + 1, duplicate)
                refreshTree()
            }
        }
    }

    private fun regenerateFolderIds(folder: RequestFolder) {
        folder.id = java.util.UUID.randomUUID().toString()
        folder.requests.forEach { it.id = java.util.UUID.randomUUID().toString() }
        folder.folders.forEach { regenerateFolderIds(it) }
    }

    private fun <T> deepCopy(value: T, type: Class<T>): T = gson.fromJson(gson.toJson(value), type)

    private fun refreshTree(select: AstraRequest? = null) {
        workspace.data.normalize()
        val root = DefaultMutableTreeNode(Node("Workspace", workspace.data))
        var selectNode: DefaultMutableTreeNode? = null

        fun requestNode(request: AstraRequest): DefaultMutableTreeNode {
            val node = DefaultMutableTreeNode(Node("${request.method}  ${request.name}", request))
            if (request === select) selectNode = node
            return node
        }

        fun appendFolder(parentNode: DefaultMutableTreeNode, folder: RequestFolder) {
            val folderNode = DefaultMutableTreeNode(Node(folder.name, folder))
            parentNode.add(folderNode)
            folder.requests.forEach { folderNode.add(requestNode(it)) }
            folder.folders.forEach { appendFolder(folderNode, it) }
        }

        workspace.data.collections.forEach { collection ->
            val collectionNode = DefaultMutableTreeNode(Node(collection.name, collection))
            root.add(collectionNode)
            collection.folders.forEach { appendFolder(collectionNode, it) }
            collection.requests.forEach { collectionNode.add(requestNode(it)) }
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

    private fun addFolder(target: Any) {
        val name = Messages.showInputDialog(project, "Folder name", "New Folder", null)?.trim()
        if (name.isNullOrEmpty()) return
        when (target) {
            is RequestCollection -> target.folders.add(RequestFolder(name = name))
            is RequestFolder -> target.folders.add(RequestFolder(name = name))
            else -> return
        }
        refreshTree()
    }

    private fun addRequest(target: Any) {
        val request = AstraRequest(name = "New Request")
        when (target) {
            is RequestCollection -> target.requests.add(request)
            is RequestFolder -> target.requests.add(request)
            else -> return
        }
        openRequest(request, bound = true)
        refreshTree(request)
    }

    private fun deleteValue(value: Any) {
        if (!com.astra.intellij.model.TreeOperations.remove(workspace.data, value)) return
        // Repaint first so a failing tab close can never leave a stale node on screen.
        refreshTree()
        sessions.filter { session ->
            val bound = session.savedRequest
            bound != null && workspace.data.collections.none { collection ->
                collection.allRequests().any { it === bound }
            }
        }.forEach { runCatching { closeSession(it) } }
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

    private fun editGlobals() {
        GlobalsDialog(project, workspace.data.globals).show()
    }

    private fun editSettings() {
        SettingsDialog(project, workspace.data.settings).show()
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

    private fun importPostmanCollection() {
        val file = chooseJsonFile("Import Postman Collection") ?: return
        val text = readFile(file) ?: return
        try {
            val collection = postman.parseCollection(text)
            val count = collection.allRequests().size
            if (count == 0) {
                Messages.showInfoMessage(project, "No requests found in this file", "Import")
                return
            }
            collection.name = uniqueCollectionName(collection.name)
            workspace.data.collections.add(collection)
            refreshTree(firstRequestOf(collection))
            notify("Imported $count request(s) into \"${collection.name}\"")
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
            notify("Imported environment \"${environment.name}\"")
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
            val count = collection.allRequests().size
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
            notify("Imported $count operation(s) into \"${collection.name}\"")
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

    private fun importWorkspace() {
        val file = chooseJsonFile("Import Astra Workspace") ?: return
        val data = runCatching { archive.import(String(Files.readAllBytes(file.toPath()))) }
            .getOrElse {
                Messages.showErrorDialog(project, it.message ?: "Invalid Astra workspace file", "Import failed")
                return
            }
        currentSession()?.commitEditor()
        data.collections.orEmpty().forEach { collection ->
            collection.id = java.util.UUID.randomUUID().toString()
            collection.name = uniqueCollectionName(collection.name)
            workspace.data.collections.add(collection)
        }
        data.environments.orEmpty().forEach { environment ->
            environment.id = java.util.UUID.randomUUID().toString()
            if (workspace.data.environments.any { it.name == environment.name }) {
                environment.name = "${environment.name} (imported)"
            }
            workspace.data.environments.add(environment)
        }
        refreshTree()
        refreshEnvironments()
        notify(
            "Imported ${data.collections.orEmpty().size} collection(s), ${data.environments.orEmpty().size} environment(s)",
        )
    }

    private fun exportWorkspace() {
        val file = chooseSaveFile("Export Astra Workspace", "workspace.astra.json") ?: return
        writeFile(
            file,
            archive.export(workspace.data.collections, workspace.data.environments, workspace.data.activeEnvironmentId),
        )
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
        collection.allRequests().firstOrNull()

    private fun chooseJsonFile(title: String): File? =
        AstraFileChooser.open(this, title, "JSON files", "json")

    private fun chooseSaveFile(title: String, defaultName: String): File? =
        AstraFileChooser.save(this, title, defaultName, "json")

    private fun readFile(file: File): String? = try {
        Files.readString(file.toPath())
    } catch (error: Exception) {
        Messages.showErrorDialog(project, error.message ?: "Unable to read file", "Import failed")
        null
    }

    private fun writeFile(file: File, content: String) {
        try {
            Files.writeString(file.toPath(), content)
            notify("Exported to ${file.name}")
        } catch (error: Exception) {
            Messages.showErrorDialog(project, error.message ?: "Unable to write file", "Export failed")
        }
    }

    private fun escapeHtml(text: String): String = buildString {
        text.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(char)
            }
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

    override fun dispose() {
        sessions.forEach { it.disposeSession() }
        sessions.clear()
    }

    private data class Node(val label: String, val value: Any) {
        override fun toString(): String = label
    }

    companion object {
        private const val NEW_TAB_ACTION = "astra.newTab"
        private const val CLOSE_TAB_ACTION = "astra.closeTab"
        private const val PREV_TAB_ACTION = "astra.prevTab"
        private const val NEXT_TAB_ACTION = "astra.nextTab"
        private val ADD_REQUEST_STROKE =
            KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK)
        private val ADD_FOLDER_STROKE =
            KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK)
        private val ADD_COLLECTION_STROKE =
            KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK)
        private val RUN_STROKE =
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)
        private val RENAME_STROKE = KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0)
        private val DUPLICATE_STROKE = KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK)
        private val DELETE_STROKE = KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0)
        private const val DOTS_HTML = " <span style='color:#999999'>\u22EF</span>"
    }
}
