package com.astra.intellij.ui

import com.astra.intellij.core.CodegenLanguage
import com.astra.intellij.model.AstraRequest
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField

class RequestEditorPanel(
    private val variablesProvider: () -> Collection<String> = { emptyList() },
) : JPanel(BorderLayout()) {
    /** Fired when the displayed request name or method changes, so the tab title can follow. */
    var onTitleChanged: (() -> Unit)? = null
    private val nameField = JTextField("New Request")
    private val nameLabel = JLabel()
    private val nameCards = JPanel(CardLayout())
    private val methodBox = JComboBox(arrayOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"))
    val urlField = JTextField()
    private val paramsTable = KeyValueTable()
    private val headersTable = KeyValueTable()
    private val bodyModes = listOf("none", "json", "text", "form-data", "urlencoded", "graphql", "grpc")
    private val bodyModeBox = JComboBox(
        arrayOf("None", "JSON", "Text", "Form Data", "URL Encoded", "GraphQL", "gRPC-Web"),
    )
    private val bodyArea = JTextArea(10, 40)
    private val formTable = FormDataTable()
    private val graphqlQueryArea = JTextArea(10, 40)
    private val graphqlVarsArea = JTextArea(6, 40)
    private val grpcPayloadArea = JTextArea(10, 40)
    private val grpcBinaryBox = javax.swing.JCheckBox("Payload is base64 protobuf", true)
    private val bodyCards = JPanel(CardLayout())
    private val testsArea = JTextArea(6, 40)
    private val preRequestArea = JTextArea(4, 40)
    private val postResponseArea = JTextArea(4, 40)
    private val codeLanguageBox = JComboBox(CodegenLanguage.values().map { it.label }.toTypedArray())
    private val codeArea = JTextArea(14, 60)
    private val codeStatus = JLabel()
    private val codePanel = JPanel(BorderLayout(0, 4))

    /** Produces the snippet shown in the Code tab from the current editor state. */
    var codeProvider: ((CodegenLanguage) -> String)? = null
    private val authTypes = listOf("none", "inherit", "bearer", "basic", "apikey", "oauth2")
    private val authTypeBox = JComboBox(arrayOf("None", "Inherit from parent", "Bearer", "Basic", "API Key", "OAuth 2.0"))
    private val tokenField = JTextField()
    private val usernameField = JTextField()
    private val passwordField = JPasswordField()
    private val apiKeyNameField = JTextField()
    private val apiKeyValueField = JTextField()
    private val apiKeyInBox = JComboBox(arrayOf("header", "query"))
    private val oauthGrantTypes = listOf("authorization_code", "client_credentials", "password")
    private val oauthGrantBox = JComboBox(arrayOf("Authorization Code (PKCE)", "Client Credentials", "Password"))
    private val oauthAuthUrlField = JTextField()
    private val oauthTokenUrlField = JTextField()
    private val oauthClientIdField = JTextField()
    private val oauthClientSecretField = JPasswordField()
    private val oauthScopeField = JTextField()
    private val oauthUsernameField = JTextField()
    private val oauthPasswordField = JPasswordField()
    private val oauthPkceBox = javax.swing.JCheckBox("Use PKCE (S256)", true)
    private val oauthTokenArea = JTextArea(2, 30)
    private val oauthStatus = JLabel()
    private var oauthExpiresAt = 0L
    private var oauthRefreshToken = ""
    private val authCards = JPanel(CardLayout())
    private val timeoutSpinner = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(0, 0, 3600, 1))
    private val retrySpinner = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(0, 0, 10, 1))
    private val redirectBox = JComboBox(arrayOf("Use global setting", "Follow redirects", "Do not follow"))
    private val streamSseBox = javax.swing.JCheckBox("Receive response as an event stream (SSE)")

    val sendButton = JButton("Send")
    val cancelButton = JButton("Cancel")

    init {
        urlField.toolTipText = "Supports {{variables}}, e.g. {{baseUrl}}/users"
        bodyArea.lineWrap = false
        bodyArea.font = JTextField().font
        listOf(graphqlQueryArea, graphqlVarsArea, grpcPayloadArea).forEach {
            it.lineWrap = false
            it.font = JTextField().font
        }
        cancelButton.isEnabled = false
        sendButton.toolTipText = "Send request (Ctrl+Enter)"
        // Let the active IDE theme/LAF choose both colors. On Windows the default button
        // may be light with a dark label, so forcing a white foreground hides the text.
        sendButton.putClientProperty("JButton.buttonType", "default")
        cancelButton.toolTipText = "Cancel the running request"
        methodBox.renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (!isSelected) foreground = java.awt.Color.decode(AstraIcons.methodColor(value?.toString() ?: ""))
                font = font.deriveFont(java.awt.Font.BOLD)
                return this
            }
        }

        val top = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            gridy = 0
            insets = Insets(4, 4, 4, 4)
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
        }
        nameField.font = nameField.font.deriveFont(java.awt.Font.BOLD)
        nameLabel.font = nameField.font
        nameCards.add(nameLabel, "label")
        nameCards.add(nameField, "field")
        val editNameButton = JButton(AstraIcons.rename).apply {
            toolTipText = "Rename request"
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusable = false
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            margin = Insets(2, 4, 2, 4)
            addActionListener {
                (nameCards.layout as CardLayout).show(nameCards, "field")
                nameField.requestFocusInWindow()
                nameField.selectAll()
            }
        }
        nameField.addActionListener { finishNameEditing() }
        nameField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent?) = finishNameEditing()
        })
        methodBox.addActionListener { onTitleChanged?.invoke() }
        finishNameEditing()
        top.add(JPanel(BorderLayout(2, 0)).apply {
            add(nameCards, BorderLayout.CENTER)
            add(editNameButton, BorderLayout.EAST)
        }, c.at(0, 1.0).apply { gridwidth = 4 })
        c.gridy = 1
        c.gridwidth = 1
        top.add(methodBox, c.at(0, 0.0))
        top.add(urlField, c.at(1, 1.0))
        top.add(sendButton, c.at(2, 0.0))
        top.add(cancelButton, c.at(3, 0.0))
        urlField.columns = 36

        val tabs = JTabbedPane()
        tabs.addTab("Params", paramsTable)
        tabs.addTab("Headers", headersTable)
        tabs.addTab("Body", JPanel(BorderLayout()).apply {
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2)).apply {
                add(JLabel("Type"))
                add(bodyModeBox)
            }, BorderLayout.NORTH)
            bodyCards.add(JScrollPane(bodyArea), "raw")
            bodyCards.add(formTable, "form")
            bodyCards.add(buildGraphqlPanel(), "graphql")
            bodyCards.add(buildGrpcPanel(), "grpc")
            add(bodyCards, BorderLayout.CENTER)
        })
        tabs.addTab("Authorization", buildAuthPanel())
        tabs.addTab("Settings", buildSettingsPanel())
        tabs.addTab("Pre-request", JPanel(BorderLayout(0, 4)).apply {
            add(JLabel("<html>Postman-compatible JavaScript, e.g. send a sub-request and store a value:<br/>" +
                "<b>pm.sendRequest({url: pm.variables.replaceIn('{{baseUrl}}/auth/login'), method: 'POST',</b><br/>" +
                "&nbsp;&nbsp;<b>header: {'Content-Type': 'application/json'},</b><br/>" +
                "&nbsp;&nbsp;<b>body: {mode: 'raw', raw: JSON.stringify({user: 'demo'})}},</b><br/>" +
                "&nbsp;&nbsp;<b>function (err, res) { pm.environment.set('token', res.json().data.token); });</b><br/>" +
                "Also available: pm.variables.set/get, pm.request.headers.add, console.log.<br/>" +
                "Simple lines still work: <b>set token {{\$guid}}</b> · <b>unset debug</b> · <b># comment</b>.</html>").apply {
                foreground = UIUtil.getContextHelpForeground()
                border = BorderFactory.createEmptyBorder(4, 4, 0, 4)
            }, BorderLayout.NORTH)
            add(JScrollPane(preRequestArea), BorderLayout.CENTER)
        })
        tabs.addTab("Tests", JPanel(BorderLayout(0, 4)).apply {
            add(JLabel("<html>One assertion per line: <b>status 200</b> · <b>time &lt; 500</b> · " +
                "<b>body contains ok</b> · <b>jsonpath $.code == 0</b> · <b>jsonpath $.items[*].id exists</b> · " +
                "<b>header Content-Type contains json</b><br/>" +
                "Extract a value into the active environment: <b>extract token = $.data.token</b></html>").apply {
                foreground = UIUtil.getContextHelpForeground()
                border = BorderFactory.createEmptyBorder(4, 4, 0, 4)
            }, BorderLayout.NORTH)
            add(JScrollPane(testsArea), BorderLayout.CENTER)
        })
        tabs.addTab("Post-response", JPanel(BorderLayout(0, 4)).apply {
            add(JLabel("<html>Runs after every response and saves values into the active environment: " +
                "<b>set token jsonpath $.data.token</b> · <b>set sid header Set-Cookie</b> · " +
                "<b>set id body \"id=(\\w+)\"</b> · <b>set host value {{baseUrl}}</b> · <b>unset token</b></html>").apply {
                foreground = UIUtil.getContextHelpForeground()
                border = BorderFactory.createEmptyBorder(4, 4, 0, 4)
            }, BorderLayout.NORTH)
            add(JScrollPane(postResponseArea), BorderLayout.CENTER)
        })
        codeArea.isEditable = false
        codeArea.lineWrap = false
        codeArea.font = JTextField().font
        codePanel.add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2)).apply {
            add(JLabel("Language"))
            add(codeLanguageBox)
            add(JButton("Copy").apply {
                addActionListener { copyCode() }
            })
            add(codeStatus)
        }, BorderLayout.NORTH)
        codePanel.add(JScrollPane(codeArea), BorderLayout.CENTER)
        tabs.addTab("Code", codePanel)
        codeLanguageBox.addActionListener { refreshCode() }
        tabs.addChangeListener {
            if (tabs.selectedComponent === codePanel) refreshCode()
        }
        bodyModeBox.addActionListener {
            updateBodyCard()
        }
        updateBodyCard()

        add(top, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        installVariableCompletion()
    }

    /** Regenerates the Code tab from the latest request fields. */
    fun refreshCode() {
        val language = CodegenLanguage.values()[codeLanguageBox.selectedIndex.coerceAtLeast(0)]
        codeArea.text = runCatching { codeProvider?.invoke(language).orEmpty() }
            .getOrElse { "Unable to generate a snippet for this request." }
        codeArea.caretPosition = 0
        codeStatus.text = ""
    }

    private fun copyCode() {
        if (codeArea.text.isBlank()) refreshCode()
        if (codeArea.text.isBlank()) return
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(codeArea.text), null)
        codeStatus.text = "Copied"
    }

    /** Leaves rename mode and shows the plain title, e.g. after Ctrl+S. */
    fun finishNameEditing() {
        nameLabel.text = nameField.text
        (nameCards.layout as CardLayout).show(nameCards, "label")
        onTitleChanged?.invoke()
    }

    fun currentName(): String = nameField.text.trim().ifEmpty { "Untitled Request" }

    fun currentMethod(): String = methodBox.selectedItem?.toString() ?: "GET"

    fun showRequest(request: AstraRequest) {
        nameField.text = request.name
        nameLabel.text = request.name
        (nameCards.layout as CardLayout).show(nameCards, "label")
        methodBox.selectedItem = request.method
        urlField.text = request.url
        paramsTable.setValues(request.params)
        headersTable.setValues(request.headers)
        bodyModeBox.selectedIndex = bodyModes.indexOf(request.bodyMode).coerceAtLeast(0)
        bodyArea.text = request.body
        formTable.setValues(request.formFields)
        graphqlQueryArea.text = request.graphqlQuery
        graphqlVarsArea.text = request.graphqlVars
        grpcPayloadArea.text = request.grpcPayload
        grpcBinaryBox.isSelected = request.grpcBinary
        streamSseBox.isSelected = request.streamSse
        testsArea.text = request.assertions.joinToString("\n")
        preRequestArea.text = request.preRequest.joinToString("\n")
        postResponseArea.text = request.postResponse.joinToString("\n")
        updateBodyCard()
        authTypeBox.selectedIndex = authTypes.indexOf(request.auth.type).coerceAtLeast(0)
        tokenField.text = request.auth.token
        usernameField.text = request.auth.username
        passwordField.text = request.auth.password
        apiKeyNameField.text = request.auth.apiKeyName
        apiKeyValueField.text = request.auth.apiKeyValue
        apiKeyInBox.selectedItem = request.auth.apiKeyIn
        oauthGrantBox.selectedIndex = oauthGrantTypes.indexOf(request.auth.oauthGrantType).coerceAtLeast(0)
        oauthAuthUrlField.text = request.auth.oauthAuthUrl
        oauthTokenUrlField.text = request.auth.oauthTokenUrl
        oauthClientIdField.text = request.auth.oauthClientId
        oauthClientSecretField.text = request.auth.oauthClientSecret
        oauthScopeField.text = request.auth.oauthScope
        oauthUsernameField.text = request.auth.username
        oauthPasswordField.text = request.auth.password
        oauthPkceBox.isSelected = request.auth.oauthPkce
        oauthTokenArea.text = request.auth.token
        oauthExpiresAt = request.auth.oauthExpiresAt
        oauthRefreshToken = request.auth.oauthRefreshToken
        oauthStatus.text = if (request.auth.oauthExpiresAt > 0) {
            "Token expires at ${java.time.Instant.ofEpochMilli(request.auth.oauthExpiresAt)}"
        } else {
            ""
        }
        updateOAuthFields()
        (authCards.layout as CardLayout).show(authCards, request.auth.type)
        timeoutSpinner.value = request.timeoutSeconds
        retrySpinner.value = request.retries
        redirectBox.selectedIndex = when (request.followRedirects) {
            null -> 0
            true -> 1
            false -> 2
        }
        if (codePanel.isVisible) refreshCode()
    }

    fun readInto(request: AstraRequest): AstraRequest {
        request.name = nameField.text.trim().ifEmpty { "Untitled Request" }
        request.method = methodBox.selectedItem.toString()
        request.url = urlField.text.trim()
        request.params = paramsTable.values()
        request.headers = headersTable.values()
        request.bodyMode = bodyModes[bodyModeBox.selectedIndex]
        request.body = bodyArea.text
        request.formFields = formTable.values()
        request.graphqlQuery = graphqlQueryArea.text
        request.graphqlVars = graphqlVarsArea.text
        request.grpcPayload = grpcPayloadArea.text
        request.grpcBinary = grpcBinaryBox.isSelected
        request.streamSse = streamSseBox.isSelected
        request.assertions = testsArea.text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        // Keep indentation and blank lines: scripts use them for blocks and readability.
        request.preRequest = preRequestArea.text.lines()
            .dropLastWhile { it.isBlank() }
            .toMutableList()
        request.postResponse = postResponseArea.text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        request.auth.type = authTypes[authTypeBox.selectedIndex]
        request.auth.token = if (request.auth.type == "oauth2") {
            oauthTokenArea.text.trim()
        } else {
            tokenField.text
        }
        request.auth.username = usernameField.text
        request.auth.password = String(passwordField.password)
        request.auth.apiKeyName = apiKeyNameField.text
        request.auth.apiKeyValue = apiKeyValueField.text
        request.auth.apiKeyIn = apiKeyInBox.selectedItem.toString()
        request.auth.oauthGrantType = oauthGrantTypes[oauthGrantBox.selectedIndex]
        request.auth.oauthAuthUrl = oauthAuthUrlField.text.trim()
        request.auth.oauthTokenUrl = oauthTokenUrlField.text.trim()
        request.auth.oauthClientId = oauthClientIdField.text.trim()
        request.auth.oauthClientSecret = String(oauthClientSecretField.password)
        request.auth.oauthScope = oauthScopeField.text.trim()
        request.auth.oauthPkce = oauthPkceBox.isSelected
        request.auth.oauthExpiresAt = oauthExpiresAt
        request.auth.oauthRefreshToken = oauthRefreshToken
        request.timeoutSeconds = (timeoutSpinner.value as Number).toInt()
        request.retries = (retrySpinner.value as Number).toInt()
        request.followRedirects = when (redirectBox.selectedIndex) {
            1 -> true
            2 -> false
            else -> null
        }
        return request
    }

    fun setSending(sending: Boolean) {
        sendButton.isEnabled = !sending
        sendButton.text = if (sending) "Sending..." else "Send"
        cancelButton.isEnabled = sending
    }

    private fun updateBodyCard() {
        val mode = bodyModes.getOrElse(bodyModeBox.selectedIndex) { "none" }
        val form = mode == "form-data" || mode == "urlencoded"
        when {
            form -> (bodyCards.layout as CardLayout).show(bodyCards, "form")
            mode == "graphql" -> (bodyCards.layout as CardLayout).show(bodyCards, "graphql")
            mode == "grpc" -> (bodyCards.layout as CardLayout).show(bodyCards, "grpc")
            else -> (bodyCards.layout as CardLayout).show(bodyCards, "raw")
        }
        bodyArea.isEnabled = mode != "none" && !form
        formTable.isEnabled = form
        graphqlQueryArea.isEnabled = mode == "graphql"
        graphqlVarsArea.isEnabled = mode == "graphql"
        grpcPayloadArea.isEnabled = mode == "grpc"
        grpcBinaryBox.isEnabled = mode == "grpc"
    }

    private fun buildGraphqlPanel(): JPanel = JPanel(BorderLayout(0, 4)).apply {
        val queryPanel = JPanel(BorderLayout()).apply {
            add(JLabel("Query"), BorderLayout.NORTH)
            add(JScrollPane(graphqlQueryArea), BorderLayout.CENTER)
        }
        val varsPanel = JPanel(BorderLayout()).apply {
            add(JLabel("Variables (JSON)"), BorderLayout.NORTH)
            add(JScrollPane(graphqlVarsArea), BorderLayout.CENTER)
        }
        val split = com.intellij.ui.JBSplitter(true, 0.62f).apply {
            firstComponent = queryPanel
            secondComponent = varsPanel
        }
        add(split, BorderLayout.CENTER)
        add(JLabel("Sent as {\"query\": …, \"variables\": …} with Content-Type application/json.").apply {
            foreground = UIUtil.getContextHelpForeground()
            border = BorderFactory.createEmptyBorder(4, 4, 0, 4)
        }, BorderLayout.SOUTH)
    }

    private fun buildGrpcPanel(): JPanel = JPanel(BorderLayout(0, 4)).apply {
        val toolbar = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2)).apply {
            add(JLabel("Payload"))
            add(grpcBinaryBox)
        }
        add(toolbar, BorderLayout.NORTH)
        add(JScrollPane(grpcPayloadArea), BorderLayout.CENTER)
        add(JLabel("Wrapped in a gRPC-Web frame and sent with x-grpc-web: 1. " +
            "Response frames are decoded in the Body tab.").apply {
            foreground = UIUtil.getContextHelpForeground()
            border = BorderFactory.createEmptyBorder(4, 4, 0, 4)
        }, BorderLayout.SOUTH)
    }

    private fun buildAuthPanel(): JPanel {
        val none = JPanel(BorderLayout()).apply {
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 4)).apply {
                add(JLabel("This request does not use any authorization.").apply {
                    foreground = UIUtil.getContextHelpForeground()
                })
            }, BorderLayout.NORTH)
        }
        val bearer = JPanel(BorderLayout()).apply {
            add(labeledField("Bearer Token", tokenField), BorderLayout.NORTH)
        }
        val basic = JPanel(GridBagLayout())
        basic.add(JLabel("Username"), grid(0, 0))
        basic.add(usernameField, grid(1, 0, 1.0))
        basic.add(JLabel("Password"), grid(0, 1))
        basic.add(passwordField, grid(1, 1, 1.0))
        basic.add(javax.swing.Box.createVerticalGlue(), grid(0, 2).apply { weighty = 1.0; gridwidth = 2 })
        val api = JPanel(GridBagLayout())
        api.add(JLabel("Key"), grid(0, 0))
        api.add(apiKeyNameField, grid(1, 0, 1.0))
        api.add(JLabel("Value"), grid(0, 1))
        api.add(apiKeyValueField, grid(1, 1, 1.0))
        api.add(JLabel("Add to"), grid(0, 2))
        api.add(apiKeyInBox, grid(1, 2, 1.0))
        api.add(javax.swing.Box.createVerticalGlue(), grid(0, 3).apply { weighty = 1.0; gridwidth = 2 })
        authCards.add(none, "none")
        val inherit = JPanel(BorderLayout()).apply {
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 4)).apply {
                add(JLabel("Uses the authorization configured on the folder or collection.").apply {
                    foreground = UIUtil.getContextHelpForeground()
                })
            }, BorderLayout.NORTH)
        }
        authCards.add(inherit, "inherit")
        authCards.add(bearer, "bearer")
        authCards.add(basic, "basic")
        authCards.add(api, "apikey")
        authCards.add(buildOAuthPanel(), "oauth2")
        authTypeBox.addActionListener {
            (authCards.layout as CardLayout).show(authCards, authTypes[authTypeBox.selectedIndex])
        }
        return JPanel(BorderLayout(0, 8)).apply {
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2)).apply {
                add(JLabel("Auth Type"))
                add(authTypeBox)
            }, BorderLayout.NORTH)
            add(authCards, BorderLayout.CENTER)
        }
    }

    private fun buildOAuthPanel(): JPanel {
        oauthTokenArea.isEditable = false
        oauthTokenArea.lineWrap = true
        oauthTokenArea.font = JTextField().font
        oauthStatus.foreground = UIUtil.getContextHelpForeground()
        val getToken = JButton("Get Token").apply {
            addActionListener { acquireOAuthToken(this) }
        }
        oauthGrantBox.addActionListener { updateOAuthFields() }
        val form = JPanel(GridBagLayout())
        form.add(JLabel("Grant"), grid(0, 0))
        form.add(oauthGrantBox, grid(1, 0, 1.0))
        form.add(oauthPkceBox, grid(2, 0))
        form.add(JLabel("Authorization URL"), grid(0, 1))
        form.add(oauthAuthUrlField, grid(1, 1, 1.0).apply { gridwidth = 2 })
        form.add(JLabel("Token URL"), grid(0, 2))
        form.add(oauthTokenUrlField, grid(1, 2, 1.0).apply { gridwidth = 2 })
        form.add(JLabel("Client ID"), grid(0, 3))
        form.add(oauthClientIdField, grid(1, 3, 1.0).apply { gridwidth = 2 })
        form.add(JLabel("Client Secret"), grid(0, 4))
        form.add(oauthClientSecretField, grid(1, 4, 1.0).apply { gridwidth = 2 })
        form.add(JLabel("Scope"), grid(0, 5))
        form.add(oauthScopeField, grid(1, 5, 1.0).apply { gridwidth = 2 })
        form.add(JLabel("Username"), grid(0, 6))
        form.add(oauthUsernameField, grid(1, 6, 1.0).apply { gridwidth = 2 })
        form.add(JLabel("Password"), grid(0, 7))
        form.add(oauthPasswordField, grid(1, 7, 1.0).apply { gridwidth = 2 })
        form.add(JLabel("Access Token"), grid(0, 8))
        form.add(JScrollPane(oauthTokenArea), grid(1, 8, 1.0).apply {
            gridwidth = 2
            weighty = 0.3
            fill = GridBagConstraints.BOTH
        })
        form.add(getToken, grid(1, 9, 1.0).apply { gridwidth = 2 })
        form.add(oauthStatus, grid(1, 10, 1.0).apply { gridwidth = 2 })
        form.add(javax.swing.Box.createVerticalGlue(), grid(0, 11).apply {
            weighty = 1.0
            gridwidth = 3
        })
        updateOAuthFields()
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
            add(form, BorderLayout.NORTH)
            add(JLabel("<html>Authorization Code opens the browser and listens on " +
                "<b>http://127.0.0.1:8765/callback</b>. Tokens refresh automatically before a request " +
                "when a refresh token is stored.</html>").apply {
                foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.SOUTH)
        }
    }

    /** Enables the fields that apply to the selected OAuth grant type. */
    private fun updateOAuthFields() {
        val grant = oauthGrantTypes.getOrElse(oauthGrantBox.selectedIndex) { "client_credentials" }
        val authorizationCode = grant == "authorization_code"
        val passwordGrant = grant == "password"
        oauthAuthUrlField.isEnabled = authorizationCode
        oauthPkceBox.isEnabled = authorizationCode
        oauthUsernameField.isEnabled = passwordGrant
        oauthPasswordField.isEnabled = passwordGrant
    }

    private fun acquireOAuthToken(button: JButton) {
        val grant = oauthGrantTypes[oauthGrantBox.selectedIndex]
        val tokenUrl = oauthTokenUrlField.text.trim()
        val clientId = oauthClientIdField.text.trim()
        val clientSecret = String(oauthClientSecretField.password)
        val scope = oauthScopeField.text.trim()
        button.isEnabled = false
        oauthStatus.text = "Requesting token..."
        oauthStatus.foreground = UIUtil.getContextHelpForeground()
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val result = when (grant) {
                "authorization_code" -> com.astra.intellij.core.OAuth2Service.authorizationCode(
                    authUrl = oauthAuthUrlField.text.trim(),
                    tokenUrl = tokenUrl,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    scope = scope,
                    pkce = oauthPkceBox.isSelected,
                )
                "password" -> com.astra.intellij.core.OAuth2Service.password(
                    tokenUrl = tokenUrl,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    scope = scope,
                    username = oauthUsernameField.text.trim(),
                    password = String(oauthPasswordField.password),
                )
                else -> com.astra.intellij.core.OAuth2Service.clientCredentials(
                    tokenUrl = tokenUrl,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    scope = scope,
                )
            }
            javax.swing.SwingUtilities.invokeLater {
                button.isEnabled = true
                if (!result.ok) {
                    oauthStatus.foreground = com.intellij.ui.JBColor.RED
                    oauthStatus.text = result.error ?: "Failed to get token"
                    return@invokeLater
                }
                oauthTokenArea.text = result.accessToken
                oauthExpiresAt = result.expiresAt
                if (result.refreshToken.isNotEmpty()) oauthRefreshToken = result.refreshToken
                oauthStatus.foreground = UIUtil.getContextHelpForeground()
                oauthStatus.text = if (result.expiresAt > 0) {
                    "Token acquired — expires at ${java.time.Instant.ofEpochMilli(result.expiresAt)}"
                } else {
                    "Token acquired"
                }
            }
        }
    }

    private fun buildSettingsPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.add(JLabel("Timeout (seconds)"), grid(0, 0))
        panel.add(timeoutSpinner, grid(1, 0, 1.0))
        panel.add(JLabel("0 = use the global timeout"), grid(2, 0).apply {
            insets = Insets(4, 0, 4, 4)
        })
        panel.add(JLabel("Retries"), grid(0, 1))
        panel.add(retrySpinner, grid(1, 1, 1.0))
        panel.add(JLabel("Extra attempts after transport failures"), grid(2, 1).apply {
            insets = Insets(4, 0, 4, 4)
        })
        panel.add(JLabel("Redirects"), grid(0, 2))
        panel.add(redirectBox, grid(1, 2, 1.0))
        panel.add(streamSseBox, grid(0, 3).apply { gridwidth = 3 })
        panel.add(javax.swing.Box.createVerticalGlue(), grid(0, 4).apply {
            weighty = 1.0
            gridwidth = 3
        })
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(panel, BorderLayout.NORTH)
        }
    }

    /** Installs `{{variable}}` completion on every free-text editor in the request form. */
    private fun installVariableCompletion() {
        listOf(
            urlField,
            bodyArea,
            graphqlQueryArea,
            graphqlVarsArea,
            grpcPayloadArea,
            testsArea,
            preRequestArea,
            postResponseArea,
            tokenField,
            usernameField,
            passwordField,
            apiKeyNameField,
            apiKeyValueField,
            oauthAuthUrlField,
            oauthTokenUrlField,
            oauthClientIdField,
            oauthScopeField,
        ).forEach { VariableCompletion.install(it, variablesProvider) }
        paramsTable.installVariableCompletion(variablesProvider)
        headersTable.installVariableCompletion(variablesProvider)
        formTable.installVariableCompletion(variablesProvider)
    }

    private fun labeledField(label: String, field: JTextField): JPanel =
        JPanel(BorderLayout(8, 0)).apply {
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
            add(JLabel(label), BorderLayout.WEST)
            add(field, BorderLayout.CENTER)
        }

    private fun grid(x: Int, y: Int, weight: Double = 0.0): GridBagConstraints =
        GridBagConstraints().apply {
            gridx = x
            gridy = y
            weightx = weight
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = Insets(4, 4, 4, 4)
        }
}

private fun GridBagConstraints.at(x: Int, weight: Double): GridBagConstraints {
    gridx = x
    weightx = weight
    return this
}
