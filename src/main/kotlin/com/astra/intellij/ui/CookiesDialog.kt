package com.astra.intellij.ui

import com.astra.intellij.core.AstraCookieStore
import com.astra.intellij.core.HttpClients
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.net.HttpCookie
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/** Lists every cookie in the shared jar and allows adding, editing and deleting entries. */
class CookiesDialog(
    private val project: Project,
    private val store: AstraCookieStore = HttpClients.cookieStore,
) : DialogWrapper(project, true) {
    private val model = object : DefaultTableModel(arrayOf("Name", "Value", "Domain", "Path", "Secure"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val table = JBTable(model)

    init {
        title = "Cookies"
        init()
    }

    override fun createCenterPanel(): JComponent {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(JButton("Add\u2026").apply { addActionListener { addCookie() } })
            add(JButton("Edit\u2026").apply { addActionListener { editCookie() } })
            add(JButton("Delete").apply { addActionListener { deleteCookie() } })
            add(JButton("Clear All").apply { addActionListener { clearAll() } })
        }
        refresh()
        return JPanel(BorderLayout(0, 4)).apply {
            add(JLabel("Cookies stored by Astra and sent automatically with matching requests."), BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
            preferredSize = java.awt.Dimension(720, 340)
        }
    }

    private fun cookies(): List<HttpCookie> = store.cookies.sortedWith(
        compareBy({ it.domain.orEmpty().lowercase() }, { it.path.orEmpty() }, { it.name }),
    )

    private fun refresh() {
        model.rowCount = 0
        cookies().forEach { cookie ->
            model.addRow(
                arrayOf(
                    cookie.name,
                    cookie.value,
                    cookie.domain.orEmpty(),
                    cookie.path.orEmpty(),
                    if (cookie.secure) "Yes" else "",
                ),
            )
        }
    }

    private fun selected(): HttpCookie? {
        val row = table.selectedRow
        val list = cookies()
        return if (row in list.indices) list[row] else null
    }

    private fun addCookie() {
        val cookie = editCookieDialog(null) ?: return
        store.add(null, cookie)
        refresh()
    }

    private fun editCookie() {
        val existing = selected() ?: return
        val cookie = editCookieDialog(existing) ?: return
        store.remove(null, existing)
        store.add(null, cookie)
        refresh()
    }

    private fun deleteCookie() {
        val cookie = selected() ?: return
        store.remove(null, cookie)
        refresh()
    }

    private fun clearAll() {
        if (store.cookies.isEmpty()) return
        val answer = Messages.showYesNoDialog(
            "Remove all ${store.cookies.size} cookie(s)?",
            "Clear Cookies",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        store.removeAll()
        refresh()
    }

    /** Simple modal editor; returns null when the user cancels. */
    private fun editCookieDialog(existing: HttpCookie?): HttpCookie? {
        val nameField = JTextField(existing?.name.orEmpty())
        val valueField = JTextField(existing?.value.orEmpty())
        val domainField = JTextField(existing?.domain.orEmpty())
        val pathField = JTextField(existing?.path ?: "/")
        val secureBox = JCheckBox("Secure", existing?.secure ?: false)
        val panel = JPanel(GridBagLayout())
        fun field(label: String, component: JComponent, row: Int) {
            panel.add(JLabel(label), GridBagConstraints().apply {
                gridx = 0; gridy = row; anchor = GridBagConstraints.WEST; insets = Insets(3, 3, 3, 8)
            })
            panel.add(component, GridBagConstraints().apply {
                gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
                insets = Insets(3, 3, 3, 3)
            })
        }
        field("Name", nameField, 0)
        field("Value", valueField, 1)
        field("Domain", domainField, 2)
        field("Path", pathField, 3)
        panel.add(secureBox, GridBagConstraints().apply {
            gridx = 1; gridy = 4; anchor = GridBagConstraints.WEST; insets = Insets(3, 3, 3, 3)
        })

        while (true) {
            val answer = javax.swing.JOptionPane.showConfirmDialog(
                contentPanel,
                panel,
                if (existing == null) "New Cookie" else "Edit Cookie",
                javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE,
            )
            if (answer != javax.swing.JOptionPane.OK_OPTION) return null
            val name = nameField.text.trim()
            val domain = domainField.text.trim()
            if (name.isEmpty() || domain.isEmpty()) {
                Messages.showErrorDialog(contentPanel, "Name and domain are required.", "Invalid Cookie")
                continue
            }
            return try {
                HttpCookie(name, valueField.text).apply {
                    this.domain = domain
                    path = pathField.text.trim().ifEmpty { "/" }
                    secure = secureBox.isSelected
                    // Keep persistent cookies persistent; new ones live for the session.
                    maxAge = existing?.maxAge?.takeIf { it > 0 } ?: -1
                }
            } catch (error: IllegalArgumentException) {
                Messages.showErrorDialog(contentPanel, error.message ?: "Invalid cookie", "Invalid Cookie")
                null
            }
        }
    }

    override fun createActions(): Array<javax.swing.Action> = arrayOf(okAction)

    override fun getPreferredFocusedComponent(): JComponent = table
}
