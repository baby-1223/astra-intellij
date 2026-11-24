package com.astra.intellij.ui

import com.astra.intellij.model.AstraRequest
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Common surface for everything that can live in a request tab.
 *
 * HTTP requests and WebSocket connections have different editors but share the tab strip,
 * so the strip only depends on this small contract.
 */
abstract class TabSession : JPanel(BorderLayout()) {
    abstract fun title(): String

    /** Model backing the tab, used to keep the tree selection and tab state in sync. */
    open fun modelRequest(): AstraRequest? = null

    /** The saved collection request this tab is bound to, if any. */
    open fun savedModel(): AstraRequest? = null

    /** Re-reads the model after an external rename. */
    open fun reloadEditor() = Unit

    /** Called once a tab is attached to a collection node. */
    open fun markBound() = Unit

    /** Releases sockets, streams and background work owned by the session. */
    open fun disposeSession() = Unit

    /** Writes pending editor state into the underlying model. */
    open fun commitEditor() = Unit

    /** Shows a transient status line inside the session. */
    open fun showMessage(message: String) = Unit
}
