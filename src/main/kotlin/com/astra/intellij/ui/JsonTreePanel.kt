package com.astra.intellij.ui

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** Collapsible JSON tree with search and per-node copy, similar to the desktop response viewer. */
class JsonTreePanel : JPanel(BorderLayout(0, 4)) {
    private data class Node(
        val key: String?,
        val display: String,
        val json: JsonElement,
        val path: String,
        val container: Boolean,
        val keyText: String,
    )

    private val searchField = JBTextField()
    private val countLabel = JBLabel(" ")
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = JTree(model)
    private var nodeCount = 0
    private var truncated = false

    init {
        searchField.emptyText.text = "Find key or value"
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.rowHeight = 20
        tree.cellRenderer = Renderer()
        tree.font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
        installContextMenu()

        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = search()
            override fun removeUpdate(e: DocumentEvent) = search()
            override fun changedUpdate(e: DocumentEvent) = search()
        })

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(searchField.apply { preferredSize = java.awt.Dimension(280, 28) })
            add(countLabel)
            add(JButton("Expand All").apply {
                addActionListener { expandAll(true) }
            })
            add(JButton("Collapse All").apply {
                addActionListener { expandAll(false) }
            })
            add(JButton("Copy Node").apply {
                addActionListener { selectedNode()?.let { copyNode(it) } }
            })
        }
        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    fun setJson(text: String) {
        root.removeAllChildren()
        nodeCount = 1
        truncated = false
        val element = runCatching { JsonParser.parseString(text) }.getOrNull()
        if (element == null) {
            root.add(DefaultMutableTreeNode(Node(null, "Invalid JSON", JsonNull.INSTANCE, "$", false, "")))
        } else {
            appendChildren(element, "$", root, 0)
            expandDepth(2)
            if (root.childCount > 0) {
                tree.selectionPath = TreePath((root.getChildAt(0) as DefaultMutableTreeNode).path)
            }
        }
        model.reload()
        countLabel.text = if (truncated) "Large JSON: tree truncated" else " "
        SwingUtilities.invokeLater { tree.scrollRowToVisible(0) }
    }

    fun clear() {
        root.removeAllChildren()
        model.reload()
        searchField.text = ""
        countLabel.text = " "
    }

    private fun appendChildren(element: JsonElement, path: String, parent: DefaultMutableTreeNode, depth: Int) {
        if (nodeCount > MAX_NODES) {
            truncated = true
            return
        }
        when {
            element.isJsonObject -> {
                val entries = element.asJsonObject.entrySet()
                entries.forEach { (key, value) ->
                    if (nodeCount > MAX_NODES) {
                        truncated = true
                        return
                    }
                    val childPath = "$path.$key"
                    val node = DefaultMutableTreeNode(describe(key, value, childPath))
                    parent.add(node)
                    nodeCount++
                    appendChildren(value, childPath, node, depth + 1)
                }
            }
            element.isJsonArray -> {
                element.asJsonArray.forEachIndexed { index, value ->
                    if (nodeCount > MAX_NODES) {
                        truncated = true
                        return
                    }
                    val childPath = "$path[$index]"
                    val node = DefaultMutableTreeNode(describe(null, value, childPath))
                    parent.add(node)
                    nodeCount++
                    appendChildren(value, childPath, node, depth + 1)
                }
            }
        }
    }

    private fun describe(key: String?, value: JsonElement, path: String): Node {
        val container = value.isJsonObject || value.isJsonArray
        val summary = when {
            value.isJsonObject -> "{${value.asJsonObject.size()}}"
            value.isJsonArray -> "[${value.asJsonArray.size()}]"
            value.isJsonNull -> "null"
            value.asJsonPrimitive.isString -> "\"${value.asString}\""
            else -> value.asString
        }
        val shown = if (summary.length > 120) summary.take(120) + "\u2026" else summary
        val prefix = if (key == null) "" else "$key: "
        return Node(key, prefix + shown, value, path, container, key.orEmpty())
    }

    private fun expandDepth(levels: Int) {
        for (row in 0 until tree.rowCount) {
            val path = tree.getPathForRow(row)
            if (path.pathCount - 1 <= levels) tree.expandPath(path)
        }
    }

    private fun expandAll(expanded: Boolean) {
        for (row in 0 until tree.rowCount) {
            val path = tree.getPathForRow(row)
            if (expanded) tree.expandPath(path) else if (path.pathCount > 1) tree.collapsePath(path)
        }
    }

    private fun search() {
        val query = searchField.text.trim()
        if (query.isEmpty()) {
            countLabel.text = " "
            return
        }
        var matches = 0
        var first: DefaultMutableTreeNode? = null
        val stack = ArrayDeque<DefaultMutableTreeNode>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val data = node.userObject as? Node
            if (data != null && node !== root) {
                val haystack = "${data.keyText} ${data.display} ${data.path}"
                if (haystack.contains(query, ignoreCase = true)) {
                    matches++
                    if (first == null) first = node
                    expandTo(node)
                }
            }
            for (index in 0 until node.childCount) stack.addLast(node.getChildAt(index) as DefaultMutableTreeNode)
        }
        countLabel.text = if (matches == 0) "No matches" else "$matches match(es)"
        first?.let {
            val path = TreePath(it.path)
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        }
    }

    private fun expandTo(node: DefaultMutableTreeNode) {
        var parent = node.parent as? DefaultMutableTreeNode
        while (parent != null) {
            tree.expandPath(TreePath(parent.path))
            parent = parent.parent as? DefaultMutableTreeNode
        }
    }

    private fun selectedNode(): Node? =
        (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Node

    private fun copyNode(node: Node) {
        val text = when {
            node.container -> node.json.toString()
            node.json.isJsonNull -> "null"
            node.json.asJsonPrimitive.isString -> node.json.asString
            else -> node.json.asString
        }
        setClipboard(text)
    }

    private fun setClipboard(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    private fun installContextMenu() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShow(e)
            override fun mouseReleased(e: MouseEvent) = maybeShow(e)
        })
    }

    private fun maybeShow(e: MouseEvent) {
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        if (!e.isPopupTrigger && e.button != MouseEvent.BUTTON3) return
        tree.selectionPath = path
        val node = selectedNode() ?: return
        val menu = JPopupMenu().apply {
            add(JMenuItem("Copy Value").apply { addActionListener { copyNode(node) } })
            add(JMenuItem("Copy JSON").apply { addActionListener { setClipboard(node.json.toString()) } })
            if (node.key != null) {
                add(JMenuItem("Copy Key").apply { addActionListener { setClipboard(node.key) } })
            }
            add(JMenuItem("Copy Path").apply {
                addActionListener {
                    val previous = node.path
                    setClipboard(previous)
                }
            })
        }
        menu.show(tree, e.x, e.y)
    }

    private inner class Renderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ): java.awt.Component {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
            val data = (value as? DefaultMutableTreeNode)?.userObject as? Node
            if (data != null) {
                text = data.display
                if (!selected) {
                    foreground = when {
                        data.container -> JBColor(
                            java.awt.Color(0x2E, 0x5E, 0xB8),
                            java.awt.Color(0x7A, 0xA6, 0xF0),
                        )
                        data.json.isJsonNull -> JBColor.GRAY
                        data.json.asJsonPrimitive.isString -> JBColor(
                            java.awt.Color(0x1A, 0x7F, 0x37),
                            java.awt.Color(0x6A, 0xC7, 0x8A),
                        )
                        data.json.asJsonPrimitive.isBoolean -> JBColor(
                            java.awt.Color(0x8A, 0x3F, 0xB8),
                            java.awt.Color(0xC4, 0x8A, 0xF0),
                        )
                        else -> JBColor(
                            java.awt.Color(0x1D, 0x4E, 0xA6),
                            java.awt.Color(0x8A, 0xB4, 0xF8),
                        )
                    }
                }
                toolTipText = data.path
            }
            return this
        }
    }

    private companion object {
        const val MAX_NODES = 20_000
    }
}
