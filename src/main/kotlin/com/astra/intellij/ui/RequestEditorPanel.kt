package com.astra.intellij.ui

import com.astra.intellij.model.AstraRequest
import com.intellij.ui.JBColor
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

class RequestEditorPanel : JPanel(BorderLayout()) {
    private val nameField = JTextField("New Request")
    private val nameLabel = JLabel()
    private val nameCards = JPanel(CardLayout())
    private val methodBox = JComboBox(arrayOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"))
    val urlField = JTextField()
    private val paramsTable = KeyValueTable()
    private val headersTable = KeyValueTable()
    private val bodyModes = listOf("none", "json", "text", "form-data", "urlencoded")
    private val bodyModeBox = JComboBox(arrayOf("None", "JSON", "Text", "Form Data", "URL Encoded"))
    private val bodyArea = JTextArea(10, 40)
    private val formTable = KeyValueTable()
    private val bodyCards = JPanel(CardLayout())
    private val testsArea = JTextArea(6, 40)
    private val preRequestArea = JTextArea(4, 40)
    private val authTypes = listOf("none", "bearer", "basic", "apikey")
    private val authTypeBox = JComboBox(arrayOf("None", "Bearer", "Basic", "API Key"))
    private val tokenField = JTextField()
    private val usernameField = JTextField()
    private val passwordField = JPasswordField()
    private val apiKeyNameField = JTextField()
    private val apiKeyValueField = JTextField()
    private val apiKeyInBox = JComboBox(arrayOf("header", "query"))
    private val authCards = JPanel(CardLayout())

    val sendButton = JButton("Send")
    val cancelButton = JButton("Cancel")

    init {
        urlField.toolTipText = "Supports {{variables}}, e.g. {{baseUrl}}/users"
        bodyArea.lineWrap = false
        bodyArea.font = JTextField().font
        cancelButton.isEnabled = false
        sendButton.toolTipText = "Send request (Ctrl+Enter)"
        sendButton.background = JBColor(java.awt.Color(0x2F6FED), java.awt.Color(0x3B82F6))
        sendButton.foreground = java.awt.Color.WHITE
        sendButton.isOpaque = true
        sendButton.isBorderPainted = false
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
            add(bodyCards, BorderLayout.CENTER)
        })
        tabs.addTab("Authorization", buildAuthPanel())
        tabs.addTab("Pre-request", JPanel(BorderLayout(0, 4)).apply {
            add(JLabel("<html>One instruction per line: <b>set token {{\$guid}}</b> stores a variable in the active " +
                "environment for this run · <b>unset debug</b> removes it · <b># comment</b></html>").apply {
                foreground = JBColor.GRAY
                border = BorderFactory.createEmptyBorder(4, 4, 0, 4)
            }, BorderLayout.NORTH)
            add(JScrollPane(preRequestArea), BorderLayout.CENTER)
        })
        tabs.addTab("Tests", JPanel(BorderLayout(0, 4)).apply {
            add(JLabel("<html>One assertion per line: <b>status 200</b> · <b>time &lt; 500</b> · " +
                "<b>body contains ok</b> · <b>json $.code == 0</b> · <b>header Content-Type contains json</b></html>").apply {
                foreground = JBColor.GRAY
                border = BorderFactory.createEmptyBorder(4, 4, 0, 4)
            }, BorderLayout.NORTH)
            add(JScrollPane(testsArea), BorderLayout.CENTER)
        })
        bodyModeBox.addActionListener {
            updateBodyCard()
        }
        updateBodyCard()

        add(top, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
    }

    /** Leaves rename mode and shows the plain title, e.g. after Ctrl+S. */
    fun finishNameEditing() {
        nameLabel.text = nameField.text
        (nameCards.layout as CardLayout).show(nameCards, "label")
    }

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
        testsArea.text = request.assertions.joinToString("\n")
        preRequestArea.text = request.preRequest.joinToString("\n")
        updateBodyCard()
        authTypeBox.selectedIndex = authTypes.indexOf(request.auth.type).coerceAtLeast(0)
        tokenField.text = request.auth.token
        usernameField.text = request.auth.username
        passwordField.text = request.auth.password
        apiKeyNameField.text = request.auth.apiKeyName
        apiKeyValueField.text = request.auth.apiKeyValue
        apiKeyInBox.selectedItem = request.auth.apiKeyIn
        (authCards.layout as CardLayout).show(authCards, request.auth.type)
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
        request.assertions = testsArea.text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        request.preRequest = preRequestArea.text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        request.auth.type = authTypes[authTypeBox.selectedIndex]
        request.auth.token = tokenField.text
        request.auth.username = usernameField.text
        request.auth.password = String(passwordField.password)
        request.auth.apiKeyName = apiKeyNameField.text
        request.auth.apiKeyValue = apiKeyValueField.text
        request.auth.apiKeyIn = apiKeyInBox.selectedItem.toString()
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
        (bodyCards.layout as CardLayout).show(bodyCards, if (form) "form" else "raw")
        bodyArea.isEnabled = mode != "none" && !form
        formTable.isEnabled = form
    }

    private fun buildAuthPanel(): JPanel {
        val none = JPanel(BorderLayout()).apply {
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 4)).apply {
                add(JLabel("This request does not use any authorization.").apply { foreground = JBColor.GRAY })
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
        authCards.add(bearer, "bearer")
        authCards.add(basic, "basic")
        authCards.add(api, "apikey")
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
