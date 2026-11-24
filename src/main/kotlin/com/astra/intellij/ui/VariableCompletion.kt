package com.astra.intellij.ui

import java.awt.Dimension
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

/**
 * Shows a variable picker after the user types `{{`, mirroring the desktop app. Picking an
 * entry inserts the full `{{name}}` placeholder at the caret.
 */
object VariableCompletion {
    fun install(component: JTextComponent, variables: () -> Collection<String>) {
        val list = JList<String>()
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        val scroll = JScrollPane(list).apply {
            preferredSize = Dimension(220, 140)
            border = null
        }
        val popup = JPopupMenu().apply {
            isFocusable = false
            add(scroll)
        }

        fun hide() {
            if (popup.isVisible) popup.isVisible = false
        }

        fun insert(name: String) {
            val caret = component.caretPosition.coerceIn(0, component.text.length)
            val before = component.text.substring(0, caret)
            val start = before.lastIndexOf("{{")
            if (start < 0) {
                hide()
                return
            }
            val suffix = component.text.substring(caret)
            val replacement = "{{$name}}"
            component.text = before.substring(0, start) + replacement + suffix
            component.caretPosition = start + replacement.length
            hide()
        }

        fun refreshPopup() {
            if (!component.isShowing || !component.hasFocus()) {
                hide()
                return
            }
            val caret = component.caretPosition.coerceIn(0, component.text.length)
            val before = component.text.substring(0, caret)
            val start = before.lastIndexOf("{{")
            if (start < 0 || before.indexOf("}}", start) >= 0) {
                hide()
                return
            }
            val partial = before.substring(start + 2)
            if (partial.any { it == '{' || it == '}' || it.isWhitespace() }) {
                hide()
                return
            }
            val matches = variables()
                .asSequence()
                .filter { it.contains(partial, ignoreCase = true) }
                .distinct()
                .sorted()
                .toList()
            if (matches.isEmpty()) {
                hide()
                return
            }
            list.model = DefaultListModel<String>().apply { matches.forEach { addElement(it) } }
            list.selectedIndex = 0
            val location = runCatching {
                val view = component.modelToView2D(caret)
                view?.let { it.bounds.x to (it.bounds.y + it.bounds.height) }
            }.getOrNull()
            if (location != null && component.isShowing) {
                popup.show(component, location.first, location.second)
                component.requestFocusInWindow()
            }
        }

        fun scheduleRefresh() = SwingUtilities.invokeLater { refreshPopup() }

        component.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = scheduleRefresh()
            override fun removeUpdate(e: DocumentEvent) = scheduleRefresh()
            override fun changedUpdate(e: DocumentEvent) = scheduleRefresh()
        })
        component.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (!popup.isVisible) return
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        list.selectedIndex = (list.selectedIndex + 1).coerceAtMost(list.model.size - 1)
                        e.consume()
                    }
                    KeyEvent.VK_UP -> {
                        list.selectedIndex = (list.selectedIndex - 1).coerceAtLeast(0)
                        e.consume()
                    }
                    KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                        list.selectedValue?.let {
                            insert(it)
                            e.consume()
                        }
                    }
                    KeyEvent.VK_ESCAPE -> {
                        hide()
                        e.consume()
                    }
                }
            }
        })
        component.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = hide()
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                list.selectedValue?.let { insert(it) }
            }
        })
    }
}
