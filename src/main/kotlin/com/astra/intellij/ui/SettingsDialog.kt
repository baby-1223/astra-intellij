package com.astra.intellij.ui

import com.astra.intellij.model.AstraSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.BorderFactory

class SettingsDialog(
    private val project: Project,
    private val settings: AstraSettings,
) : DialogWrapper(project) {
    private val proxyHostField = JTextField(settings.proxyHost)
    private val proxyPortField = JTextField(if (settings.proxyPort > 0) settings.proxyPort.toString() else "")
    private val timeoutField = JTextField(settings.timeoutSeconds.toString())
    private val verifySslBox = JCheckBox("Verify SSL certificates", settings.verifySsl)
    private val redirectsBox = JCheckBox("Follow redirects", settings.followRedirects)

    init {
        title = "Request Settings"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return JPanel(GridLayout(0, 2, 8, 8)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(JLabel("HTTP proxy host (empty = direct)"))
            add(proxyHostField)
            add(JLabel("HTTP proxy port"))
            add(proxyPortField.apply { toolTipText = "e.g. 8080" })
            add(JLabel("Timeout (seconds)"))
            add(timeoutField)
            add(JLabel("TLS"))
            add(verifySslBox.apply { toolTipText = "Disable only for self-signed development certificates" })
            add(JLabel("Redirects"))
            add(redirectsBox)
            preferredSize = Dimension(420, 190)
        }
    }

    override fun doOKAction() {
        val port = proxyPortField.text.trim().toIntOrNull() ?: 0
        val timeout = timeoutField.text.trim().toIntOrNull()
        if (proxyHostField.text.isNotBlank() && (port <= 0 || port > 65535)) {
            Messages.showErrorDialog(project, "Enter a valid proxy port (1-65535), or clear the proxy host", "Settings")
            return
        }
        if (timeout == null || timeout !in 1..3600) {
            Messages.showErrorDialog(project, "Timeout must be between 1 and 3600 seconds", "Settings")
            return
        }
        settings.proxyHost = proxyHostField.text.trim()
        settings.proxyPort = port
        settings.timeoutSeconds = timeout
        settings.verifySsl = verifySslBox.isSelected
        settings.followRedirects = redirectsBox.isSelected
        super.doOKAction()
    }
}
