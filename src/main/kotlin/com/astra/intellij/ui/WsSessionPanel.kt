package com.astra.intellij.ui

import com.astra.intellij.core.VariableResolver
import com.astra.intellij.core.WsClient
import com.astra.intellij.core.WsConnection
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.workspace.WorkspaceService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.KeyStroke

/**
 * Standalone WebSocket connection editor, mirroring the desktop WebSocket session:
 * connect/disconnect, headers, subprotocols, a message log with templates and resend.
 */
class WsSessionPanel(
    private val project: Project,
    private val workspace: WorkspaceService,
    initial: AstraRequest,
    private val onSaveToCollection: (WsSessionPanel) -> Unit,
) : TabSession() {
    private val nameField = JBTextField()
    private val urlField = JBTextField()
    private val connectButton = JButton("Connect")
    private val statusLabel = JBLabel("Disconnected")
    private val headersTable = KeyValueTable()
    private val subprotocolField = JBTextField()
    private val verifyTlsBox = JCheckBox("Verify TLS certificate", true)
    private val logArea = JTextArea(12, 40)
    private val messageArea = JTextArea(3, 40)
    private val templateBox = JComboBox(TEMPLATES.map { it.first }.toTypedArray())
    private var connection: WsConnection? = null
    private var generation = 0
    private var lastMessage: String? = null

    var request: AstraRequest = initial
        private set
    var savedRequest: AstraRequest? = null
        private set

    var onTitleChanged: (() -> Unit)? = null

    init {
        border = JBUI.Borders.empty()
        add(buildHeader(), BorderLayout.NORTH)
        add(buildBody(), BorderLayout.CENTER)
        registerShortcuts()
        showRequest(initial, bound = true)
    }

    override fun title(): String = "WS  ${request.name}"

    override fun modelRequest(): AstraRequest = request

    override fun savedModel(): AstraRequest? = savedRequest

    override fun reloadEditor() {
        nameField.text = request.name
        urlField.text = request.url
        onTitleChanged?.invoke()
    }

    override fun markBound() {
        savedRequest = request
    }

    override fun showMessage(message: String) {
        statusLabel.text = message
        statusLabel.foreground = JBColor.GRAY
    }

    fun showRequest(value: AstraRequest, bound: Boolean) {
        request = value
        savedRequest = if (bound) value else null
        nameField.text = value.name
        urlField.text = value.url
        headersTable.setValues(value.headers)
        subprotocolField.text = value.wsSubprotocol
        verifyTlsBox.isSelected = value.wsVerifyTls
        logArea.text = ""
        statusLabel.text = "Disconnected"
        statusLabel.foreground = JBColor.GRAY
        connectButton.text = "Connect"
        connectButton.isEnabled = true
    }

    override fun commitEditor() {
        request.name = nameField.text.trim().ifEmpty { "WebSocket Connection" }
        request.url = urlField.text.trim()
        request.headers = headersTable.values()
        request.wsSubprotocol = subprotocolField.text.trim()
        request.wsVerifyTls = verifyTlsBox.isSelected
        request.protocol = com.astra.intellij.model.PROTOCOL_WEBSOCKET
    }

    private fun buildHeader(): JPanel = JPanel(BorderLayout(0, 4)).apply {
        border = JBUI.Borders.empty(8, 10, 6, 10)

        val nameRow = JPanel(BorderLayout()).apply {
            add(JLabel("Name"), BorderLayout.WEST)
            add(nameField, BorderLayout.CENTER)
        }
        nameField.addActionListener { commitEditor(); onTitleChanged?.invoke() }
        nameField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) {
                commitEditor()
                onTitleChanged?.invoke()
            }
        })

        val urlRow = JPanel(BorderLayout(6, 0)).apply {
            add(JLabel("URL"), BorderLayout.WEST)
            add(urlField, BorderLayout.CENTER)
            add(connectButton, BorderLayout.EAST)
        }
        urlField.toolTipText = "ws:// or wss:// · {{variables}} supported"
        urlField.addActionListener { toggleConnection() }
        connectButton.addActionListener { toggleConnection() }

        val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 2)).apply {
            statusLabel.foreground = JBColor.GRAY
            add(statusLabel)
        }

        val stack = JPanel(BorderLayout(0, 4)).apply {
            add(nameRow, BorderLayout.NORTH)
            add(urlRow, BorderLayout.CENTER)
            add(statusRow, BorderLayout.SOUTH)
        }
        add(stack, BorderLayout.CENTER)
    }

    private fun buildBody(): JTabbedPane = JTabbedPane().apply {
        logArea.isEditable = false
        logArea.lineWrap = true
        addTab("Messages", buildMessagesPanel())
        addTab("Headers", JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 10)
            headersTable.setValues(request.headers)
            add(headersTable, BorderLayout.CENTER)
        })
        addTab("Settings", buildSettingsPanel())
    }

    private fun buildMessagesPanel(): JPanel = JPanel(BorderLayout(0, 6)).apply {
        border = JBUI.Borders.empty(6, 10, 8, 10)
        add(JScrollPane(logArea), BorderLayout.CENTER)

        val inputPanel = JPanel(BorderLayout(0, 4)).apply {
            messageArea.lineWrap = true
            add(JScrollPane(messageArea), BorderLayout.CENTER)
            val controls = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(templateBox.apply {
                    selectedIndex = -1
                    toolTipText = "Insert a message template"
                    addActionListener {
                        val index = selectedIndex
                        if (index >= 0) {
                            messageArea.append(TEMPLATES[index].second)
                            selectedIndex = -1
                        }
                    }
                })
                add(JButton("Send").apply {
                    toolTipText = "Send message (Ctrl+Enter)"
                    addActionListener { sendMessage() }
                })
                add(JButton("Resend Last").apply {
                    toolTipText = "Send the last message again"
                    addActionListener { lastMessage?.let { sendResolved(it) } }
                })
                add(JButton("Clear").apply {
                    toolTipText = "Clear the message log"
                    addActionListener { logArea.text = "" }
                })
            }
            add(controls, BorderLayout.SOUTH)
        }
        add(inputPanel, BorderLayout.SOUTH)
    }

    private fun buildSettingsPanel(): JPanel = JPanel(java.awt.GridBagLayout()).apply {
        border = JBUI.Borders.empty(10, 12)
        val gbc = java.awt.GridBagConstraints().apply {
            insets = JBUI.insets(4)
            anchor = java.awt.GridBagConstraints.WEST
            fill = java.awt.GridBagConstraints.HORIZONTAL
        }
        add(JLabel("Subprotocols"), gbc.apply { gridx = 0; gridy = 0; weightx = 0.0 })
        subprotocolField.toolTipText = "Comma separated, e.g. graphql-ws, mqtt"
        add(subprotocolField, gbc.apply { gridx = 1; gridy = 0; weightx = 1.0 })
        add(verifyTlsBox, gbc.apply { gridx = 0; gridy = 1; gridwidth = 2; weightx = 0.0 })
        add(JPanel(), gbc.apply {
            gridx = 0
            gridy = 2
            gridwidth = 2
            weighty = 1.0
            fill = java.awt.GridBagConstraints.BOTH
        })
    }

    private fun registerShortcuts() {
        val inputMap = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), SAVE_KEY)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.META_DOWN_MASK), SAVE_KEY)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), SEND_KEY)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK), SEND_KEY)
        actionMap.put(SAVE_KEY, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                commitEditor()
                onSaveToCollection(this@WsSessionPanel)
                onTitleChanged?.invoke()
            }
        })
        actionMap.put(SEND_KEY, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = sendMessage()
        })
    }

    private fun toggleConnection() {
        if (connection != null) {
            disconnect("Disconnected")
            return
        }
        commitEditor()
        val vars = workspace.variablesFor(request)
        val resolvedUrl = VariableResolver.resolve(request.url, vars).trim()
        if (resolvedUrl.isBlank()) {
            showMessage("Enter a WebSocket URL first")
            return
        }
        val normalized = when {
            resolvedUrl.startsWith("ws://", true) || resolvedUrl.startsWith("wss://", true) -> resolvedUrl
            resolvedUrl.startsWith("https://", true) -> "wss://" + resolvedUrl.removePrefix("https://").removePrefix("HTTPS://")
            resolvedUrl.startsWith("http://", true) -> "ws://" + resolvedUrl.removePrefix("http://").removePrefix("HTTP://")
            else -> "ws://$resolvedUrl"
        }
        val headers = request.headers
            .filter { it.enabled && it.key.isNotBlank() }
            .map { VariableResolver.resolve(it.key, vars) to VariableResolver.resolve(it.value, vars) }
        val subprotocol = VariableResolver.resolve(request.wsSubprotocol, vars)
        val gen = ++generation
        connectButton.isEnabled = false
        statusLabel.text = "Connecting…"
        statusLabel.foreground = JBColor.GRAY
        appendLog("…", "Connecting to $normalized")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                WsClient.connect(
                    normalized,
                    headers,
                    subprotocol,
                    workspace.data.settings,
                    request.wsVerifyTls,
                    onOpen = { },
                    onMessage = { text -> onUi(gen) { appendLog("←", text) } },
                    onClosed = { reason ->
                        onUi(gen) {
                            appendLog("·", reason)
                            connection = null
                            connectButton.text = "Connect"
                            connectButton.isEnabled = true
                            statusLabel.text = "Disconnected"
                            statusLabel.foreground = JBColor.GRAY
                        }
                    },
                    onError = { message ->
                        onUi(gen) {
                            appendLog("!", message)
                            statusLabel.text = message
                            statusLabel.foreground = JBColor.RED
                            connection = null
                            connectButton.text = "Connect"
                            connectButton.isEnabled = true
                        }
                    },
                )
            }
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed || gen != generation) return@invokeLater
                result.onSuccess { socket ->
                    connection = socket
                    connectButton.text = "Disconnect"
                    connectButton.isEnabled = true
                    statusLabel.text = "Connected"
                    statusLabel.foreground = JBColor.GREEN.darker()
                    appendLog("·", "Connected")
                }.onFailure { error ->
                    connection = null
                    connectButton.text = "Connect"
                    connectButton.isEnabled = true
                    statusLabel.text = error.message ?: "Connection failed"
                    statusLabel.foreground = JBColor.RED
                    appendLog("!", error.message ?: "Connection failed")
                }
            }, ModalityState.any())
        }
    }

    private fun disconnect(status: String) {
        generation += 1
        val socket = connection
        connection = null
        connectButton.text = "Connect"
        connectButton.isEnabled = true
        statusLabel.text = status
        statusLabel.foreground = JBColor.GRAY
        appendLog("·", status)
        socket?.let { runCatching { it.close() } }
    }

    private fun sendMessage() {
        val text = messageArea.text.trim()
        if (text.isEmpty()) return
        lastMessage = text
        sendResolved(text)
    }

    private fun sendResolved(raw: String) {
        val socket = connection
        if (socket == null) {
            showMessage("Connect before sending messages")
            return
        }
        val text = VariableResolver.resolve(raw, workspace.variablesFor(request))
        appendLog("→", text)
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { socket.send(text) }.onFailure { error ->
                onUi(generation) { appendLog("!", error.message ?: "Send failed") }
            }
        }
    }

    private fun onUi(gen: Int, block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed && gen == generation) block()
        }, ModalityState.any())
    }

    private fun appendLog(prefix: String, text: String) {
        val time = SimpleDateFormat("HH:mm:ss").format(Date())
        logArea.append("[$time] $prefix $text\n")
        logArea.caretPosition = logArea.document.length
    }

    override fun disposeSession() {
        generation += 1
        connection?.let { runCatching { it.close() } }
        connection = null
    }

    companion object {
        private const val SAVE_KEY = "astra.saveWs"
        private const val SEND_KEY = "astra.sendWs"

        private val TEMPLATES = listOf(
            "Ping" to "{\"type\":\"ping\"}",
            "Subscribe" to "{\"action\":\"subscribe\",\"channel\":\"updates\"}",
            "JSON-RPC ping" to "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}",
            "GraphQL subscribe" to "{\"type\":\"start\",\"payload\":{\"query\":\"subscription { ping }\"}}",
        )
    }
}
