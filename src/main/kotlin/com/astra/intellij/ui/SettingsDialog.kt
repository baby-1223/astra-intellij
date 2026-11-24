package com.astra.intellij.ui

import com.astra.intellij.model.AstraSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.util.ui.JBUI
import java.awt.GridLayout
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField

class SettingsDialog(
    private val project: Project,
    private val settings: AstraSettings,
) : DialogWrapper(project) {
    private val proxyTypeBox = JComboBox(arrayOf("HTTP", "SOCKS5")).apply {
        selectedIndex = if (settings.proxyType.equals("socks", ignoreCase = true)) 1 else 0
    }
    private val proxyHostField = JTextField(settings.proxyHost)
    private val proxyPortField = JTextField(if (settings.proxyPort > 0) settings.proxyPort.toString() else "")
    private val proxyUserField = JTextField(settings.proxyUsername)
    private val proxyPasswordField = JPasswordField(settings.proxyPassword)
    private val bypassField = JTextField(settings.bypassHosts)
    private val certPathField = JTextField(settings.clientCertPath)
    private val certPasswordField = JPasswordField(settings.clientCertPassword)
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
            add(JLabel("Proxy type (empty host = direct)"))
            add(proxyTypeBox)
            add(JLabel("Proxy host"))
            add(proxyHostField)
            add(JLabel("Proxy port"))
            add(proxyPortField.apply { toolTipText = "e.g. 8080" })
            add(JLabel("Proxy username (optional)"))
            add(proxyUserField)
            add(JLabel("Proxy password"))
            add(proxyPasswordField)
            add(JLabel("Bypass proxy for hosts"))
            add(bypassField.apply { toolTipText = "Comma separated, e.g. localhost, *.internal.corp" })
            add(JLabel("Client certificate (.p12, optional)"))
            add(certPathField.apply { toolTipText = "PKCS#12 file used for mutual TLS" })
            add(JLabel("Certificate password"))
            add(certPasswordField)
            add(JLabel("Timeout (seconds)"))
            add(timeoutField)
            add(JLabel("TLS"))
            add(verifySslBox.apply { toolTipText = "Disable only for self-signed development certificates" })
            add(JLabel("Redirects"))
            add(redirectsBox)
            preferredSize = JBUI.size(480, 380)
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
        val certPath = certPathField.text.trim()
        if (certPath.isNotEmpty() && !File(certPath).isFile) {
            Messages.showErrorDialog(project, "Client certificate file not found:\n$certPath", "Settings")
            return
        }
        settings.proxyType = if (proxyTypeBox.selectedIndex == 1) "socks" else "http"
        settings.proxyHost = proxyHostField.text.trim()
        settings.proxyPort = port
        settings.proxyUsername = proxyUserField.text.trim()
        settings.proxyPassword = String(proxyPasswordField.password)
        settings.bypassHosts = bypassField.text.trim()
        settings.clientCertPath = certPath
        settings.clientCertPassword = String(certPasswordField.password)
        settings.timeoutSeconds = timeout
        settings.verifySsl = verifySslBox.isSelected
        settings.followRedirects = redirectsBox.isSelected
        super.doOKAction()
    }
}
