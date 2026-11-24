package com.astra.intellij.ui

import com.astra.intellij.model.AuthConfig
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField

/** Authorization editor for collections and folders, including the "inherit" option. */
class ParentAuthDialog(
    parent: Component,
    title: String,
    private val auth: AuthConfig,
) : DialogWrapper(parent, true) {
    private val types = listOf("inherit", "none", "bearer", "basic", "apikey", "oauth2")
    private val typeBox = JComboBox(arrayOf("Inherit from parent", "No Auth", "Bearer", "Basic", "API Key", "OAuth 2.0 Token"))
    private val tokenField = JTextField(auth.token)
    private val oauthTokenField = JTextField(auth.token)
    private val usernameField = JTextField(auth.username)
    private val passwordField = JPasswordField(auth.password)
    private val keyNameField = JTextField(auth.apiKeyName)
    private val keyValueField = JTextField(auth.apiKeyValue)
    private val keyInBox = JComboBox(arrayOf("header", "query"))
    private val cards = JPanel(CardLayout())

    init {
        this.title = title
        typeBox.selectedIndex = types.indexOf(auth.type).coerceAtLeast(0)
        keyInBox.selectedItem = auth.apiKeyIn
        init()
    }

    override fun createCenterPanel(): JComponent {
        val bearer = labeled("Token", tokenField)
        val basic = JPanel(GridBagLayout()).apply {
            add(JLabel("Username"), constraints(0, 0))
            add(usernameField, constraints(1, 0, 1.0))
            add(JLabel("Password"), constraints(0, 1))
            add(passwordField, constraints(1, 1, 1.0))
        }
        val apiKey = JPanel(GridBagLayout()).apply {
            add(JLabel("Key"), constraints(0, 0))
            add(keyNameField, constraints(1, 0, 1.0))
            add(JLabel("Value"), constraints(0, 1))
            add(keyValueField, constraints(1, 1, 1.0))
            add(JLabel("Add to"), constraints(0, 2))
            add(keyInBox, constraints(1, 2, 1.0))
        }
        cards.add(JPanel(BorderLayout()).apply {
            add(JLabel("Uses the parent collection's authorization."), BorderLayout.NORTH)
        }, "inherit")
        cards.add(JPanel(BorderLayout()).apply {
            add(JLabel("No authorization is applied."), BorderLayout.NORTH)
        }, "none")
        cards.add(bearer, "bearer")
        cards.add(basic, "basic")
        cards.add(apiKey, "apikey")
        cards.add(labeled("Access Token", oauthTokenField), "oauth2")
        typeBox.addActionListener {
            (cards.layout as CardLayout).show(cards, types[typeBox.selectedIndex])
        }
        (cards.layout as CardLayout).show(cards, types[typeBox.selectedIndex])
        return JPanel(BorderLayout(0, 8)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(JPanel(BorderLayout()).apply {
                add(JLabel("Auth Type"), BorderLayout.WEST)
                add(typeBox, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(cards, BorderLayout.CENTER)
            preferredSize = JBUI.size(460, 260)
        }
    }

    override fun doOKAction() {
        auth.type = types[typeBox.selectedIndex]
        auth.token = if (auth.type == "oauth2") oauthTokenField.text.trim() else tokenField.text.trim()
        auth.username = usernameField.text
        auth.password = String(passwordField.password)
        auth.apiKeyName = keyNameField.text
        auth.apiKeyValue = keyValueField.text
        auth.apiKeyIn = keyInBox.selectedItem.toString()
        super.doOKAction()
    }

    private fun labeled(label: String, field: JTextField): JPanel =
        JPanel(GridBagLayout()).apply {
            add(JLabel(label), constraints(0, 0))
            add(field, constraints(1, 0, 1.0))
        }

    private fun constraints(x: Int, y: Int, weight: Double = 0.0) = GridBagConstraints().apply {
        gridx = x
        gridy = y
        weightx = weight
        fill = GridBagConstraints.HORIZONTAL
        insets = Insets(4, 4, 4, 4)
    }
}

/** Pre-request / post-response script editor for collections and folders. */
class ParentScriptsDialog(
    parent: Component,
    title: String,
    private val preRequest: MutableList<String>,
    private val postResponse: MutableList<String>,
) : DialogWrapper(parent, true) {
    private val preArea = JTextArea(preRequest.joinToString("\n"), 8, 60)
    private val postArea = JTextArea(postResponse.joinToString("\n"), 8, 60)

    init {
        this.title = title
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        add(JPanel(BorderLayout()).apply {
            add(JLabel("Pre-request script (runs before every request in this scope)"), BorderLayout.NORTH)
            add(JScrollPane(preArea), BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout()).apply {
            add(JLabel("Post-response script"), BorderLayout.NORTH)
            add(JScrollPane(postArea), BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        preferredSize = JBUI.size(680, 460)
    }

    override fun doOKAction() {
        preRequest.clear()
        preRequest.addAll(preArea.text.lines().dropLastWhile { it.isBlank() })
        postResponse.clear()
        postResponse.addAll(
            postArea.text.lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
        super.doOKAction()
    }
}
