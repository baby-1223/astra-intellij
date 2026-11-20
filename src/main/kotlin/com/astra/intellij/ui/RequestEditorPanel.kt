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
    private val methodBox = JComboBox(arrayOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"))
    val urlField = JTextField()
    private val paramsTable = KeyValueTable()
    private val headersTable = KeyValueTable()
    private val bodyModeBox = JComboBox(arrayOf("none", "json", "text"))
    private val bodyArea = JTextArea(10, 40)
    private val authTypeBox = JComboBox(arrayOf("none", "bearer", "basic", "apikey"))
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

        val top = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            gridy = 0
            insets = Insets(4, 4, 4, 4)
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
        }
        top.add(nameField, c.at(0, 0.0))
        top.add(methodBox, c.at(1, 0.0))
        top.add(urlField, c.at(2, 1.0))
        top.add(sendButton, c.at(3, 0.0))
        top.add(cancelButton, c.at(4, 0.0))
        nameField.columns = 14
        urlField.columns = 36

        val tabs = JTabbedPane()
        tabs.addTab("Params", paramsTable)
        tabs.addTab("Headers", headersTable)
        tabs.addTab("Body", JPanel(BorderLayout()).apply {
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2)).apply {
                add(JLabel("Type"))
                add(bodyModeBox)
            }, BorderLayout.NORTH)
            add(JScrollPane(bodyArea), BorderLayout.CENTER)
        })
        tabs.addTab("Authorization", buildAuthPanel())
        bodyModeBox.addActionListener {
            bodyArea.isEnabled = bodyModeBox.selectedItem != "none"
        }
        bodyArea.isEnabled = false

        add(top, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
    }

    fun showRequest(request: AstraRequest) {
        nameField.text = request.name
        methodBox.selectedItem = request.method
        urlField.text = request.url
        paramsTable.setValues(request.params)
        headersTable.setValues(request.headers)
        bodyModeBox.selectedItem = request.bodyMode
        bodyArea.text = request.body
        bodyArea.isEnabled = request.bodyMode != "none"
        authTypeBox.selectedItem = request.auth.type
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
        request.bodyMode = bodyModeBox.selectedItem.toString()
        request.body = bodyArea.text
        request.auth.type = authTypeBox.selectedItem.toString()
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

    private fun buildAuthPanel(): JPanel {
        val none = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 4))
        none.add(JLabel("This request does not use any authorization.").apply { foreground = JBColor.GRAY })
        val bearer = labeledField("Bearer Token", tokenField)
        val basic = JPanel(GridBagLayout())
        basic.add(JLabel("Username"), grid(0, 0))
        basic.add(usernameField, grid(1, 0, 1.0))
        basic.add(JLabel("Password"), grid(0, 1))
        basic.add(passwordField, grid(1, 1, 1.0))
        val api = JPanel(GridBagLayout())
        api.add(JLabel("Key"), grid(0, 0))
        api.add(apiKeyNameField, grid(1, 0, 1.0))
        api.add(JLabel("Value"), grid(0, 1))
        api.add(apiKeyValueField, grid(1, 1, 1.0))
        api.add(JLabel("Add to"), grid(0, 2))
        api.add(apiKeyInBox, grid(1, 2, 1.0))
        authCards.add(none, "none")
        authCards.add(bearer, "bearer")
        authCards.add(basic, "basic")
        authCards.add(api, "apikey")
        authTypeBox.addActionListener {
            (authCards.layout as CardLayout).show(authCards, authTypeBox.selectedItem.toString())
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
