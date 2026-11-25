package com.astra.intellij.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/**
 * Single-row tab strip plus the card area that hosts the open request editors.
 *
 * IntelliJ's tabbed pane UI cannot combine custom tab components with SCROLL_TAB_LAYOUT,
 * so the strip is drawn here: a horizontal flow of tabs inside a scroll pane that follows
 * the selected tab and scrolls with the mouse wheel.
 */
class RequestTabStrip(
    private val onSelectionChanged: (TabSession) -> Unit,
    private val onCloseRequested: (TabSession) -> Unit,
    private val onNewRequest: () -> Unit,
) : JPanel(BorderLayout()) {
    private val bar = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = true
        background = ThemeColors.stripBackground
    }
    private val scroll = JBScrollPane(
        bar,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
    )
    private val content = JPanel(CardLayout())
    private val headers = linkedMapOf<TabSession, TabHeader>()
    private val headerRow = JPanel(BorderLayout())
    private val plusLabel = JBLabel("+")
    private val prevButton = ArrowButton(left = true) { selectRelative(-1) }.apply {
        toolTipText = "Previous tab (Alt+Left)"
    }
    private val nextButton = ArrowButton(left = false) { selectRelative(1) }.apply {
        toolTipText = "Next tab (Alt+Right)"
    }

    var selectedSession: TabSession? = null
        private set

    init {
        scroll.border = BorderFactory.createEmptyBorder()
        scroll.viewport.isOpaque = true
        bar.addMouseWheelListener { event ->
            val overflow = bar.width - scroll.viewport.extentSize.width
            if (overflow <= 0) return@addMouseWheelListener
            val x = (scroll.viewport.viewPosition.x + event.unitsToScroll * JBUI.scale(20))
                .coerceIn(0, overflow)
            scroll.viewport.viewPosition = java.awt.Point(x, 0)
            event.consume()
        }

        headerRow.isOpaque = true
        scroll.horizontalScrollBar.apply {
            unitIncrement = JBUI.scale(40)
            preferredSize = Dimension(0, JBUI.scale(6))
            isOpaque = false
        }
        headerRow.add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
            isOpaque = false
            add(prevButton)
            add(nextButton)
        }, BorderLayout.WEST)
        headerRow.add(scroll, BorderLayout.CENTER)
        headerRow.add(plusLabel.apply {
            toolTipText = "New request (Ctrl+N)"
            border = JBUI.Borders.empty(0, 10, 0, 6)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) onNewRequest()
                }

                override fun mouseEntered(e: MouseEvent) {
                    foreground = UIUtil.getLabelForeground()
                }

                override fun mouseExited(e: MouseEvent) {
                    foreground = UIUtil.getContextHelpForeground()
                }
            })
        }, BorderLayout.EAST)

        add(headerRow, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
        refreshTheme()
    }

    /** Re-applies theme-dependent colors after the IDE appearance changes. */
    fun refreshTheme() {
        bar.background = ThemeColors.stripBackground
        scroll.viewport.background = ThemeColors.stripBackground
        headerRow.background = ThemeColors.stripBackground
        headerRow.border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
        plusLabel.foreground = UIUtil.getContextHelpForeground()
        scroll.horizontalScrollBar.ui = ThinScrollBarUI()
        prevButton.repaint()
        nextButton.repaint()
        updateNavigationState()
        headers.values.forEach { it.refreshTheme() }
        repaint()
    }

    fun selectPrevious() = selectRelative(-1)

    fun selectNext() = selectRelative(1)

    private fun selectRelative(delta: Int) {
        val order = headers.keys.toList()
        if (order.isEmpty()) return
        val current = selectedSession?.let { order.indexOf(it) } ?: -1
        val target = if (current < 0) 0 else (current + delta).coerceIn(0, order.size - 1)
        if (target != current) selectSession(order[target])
    }

    /** Square paging button that draws a centered chevron, with a hover highlight. */
    private inner class ArrowButton(
        private val left: Boolean,
        private val onClick: () -> Unit,
    ) : JComponent() {
        private var hovered = false

        init {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = JBUI.size(28, 28)
            minimumSize = preferredSize
            maximumSize = preferredSize
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (isEnabled && SwingUtilities.isLeftMouseButton(e)) onClick()
                }

                override fun mouseEntered(e: MouseEvent) {
                    hovered = isEnabled
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                if (hovered) {
                    g2.color = ThemeColors.hoverTabBackground
                    g2.fillRoundRect(0, JBUI.scale(2), width + 1, height - JBUI.scale(4), JBUI.scale(6), JBUI.scale(6))
                }
                g2.color = when {
                    !isEnabled -> UIUtil.getLabelDisabledForeground()
                    hovered -> UIUtil.getLabelForeground()
                    else -> UIUtil.getContextHelpForeground()
                }
                val centerX = width / 2.0
                val centerY = height / 2.0
                val arm = JBUIScale.scale(5f).toDouble()
                val inset = JBUIScale.scale(2.5f).toDouble()
                val path = Path2D.Float()
                if (left) {
                    path.moveTo(centerX + inset, centerY - arm)
                    path.lineTo(centerX - inset, centerY)
                    path.lineTo(centerX + inset, centerY + arm)
                } else {
                    path.moveTo(centerX - inset, centerY - arm)
                    path.lineTo(centerX + inset, centerY)
                    path.lineTo(centerX - inset, centerY + arm)
                }
                g2.stroke = BasicStroke(JBUIScale.scale(1.8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(path)
            } finally {
                g2.dispose()
            }
        }
    }

    /** Scrollbar rendered as a thin line instead of the default chunky bar. */
    private class ThinScrollBarUI : javax.swing.plaf.basic.BasicScrollBarUI() {
        override fun configureScrollBarColors() {
            thumbColor = JBColor.namedColor("ScrollBar.thumbColor", JBColor(0xB4B8C0, 0x5A5E66))
            trackColor = Color(0, 0, 0, 0)
        }

        override fun createDecreaseButton(orientation: Int): JButton = invisibleButton()

        override fun createIncreaseButton(orientation: Int): JButton = invisibleButton()

        private fun invisibleButton(): JButton = JButton().apply {
            preferredSize = Dimension(0, 0)
            minimumSize = Dimension(0, 0)
            maximumSize = Dimension(0, 0)
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusable = false
        }

        override fun paintThumb(g: Graphics, c: JComponent, thumbBounds: java.awt.Rectangle) {
            if (thumbBounds.isEmpty || !scrollbar.isEnabled) return
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = thumbColor
                val thickness = JBUI.scale(3)
                val y = thumbBounds.y + (thumbBounds.height - thickness) / 2
                g2.fillRoundRect(thumbBounds.x, y, thumbBounds.width, thickness, thickness, thickness)
            } finally {
                g2.dispose()
            }
        }

        override fun paintTrack(g: Graphics, c: JComponent, trackBounds: java.awt.Rectangle) = Unit
    }

    fun addSession(session: TabSession) {
        val header = TabHeader(session)
        headers[session] = header
        bar.add(header)
        content.add(session, cardName(session))
        bar.revalidate()
        bar.repaint()
        content.revalidate()
        selectSession(session)
    }

    fun removeSession(session: TabSession) {
        val header = headers[session] ?: return
        val index = headers.keys.indexOf(session)
        val wasSelected = selectedSession === session
        headers.remove(session)
        bar.remove(header)
        content.remove(session)
        if (wasSelected) {
            selectedSession = null
            val remaining = headers.keys.toList()
            val next = remaining.getOrNull(index) ?: remaining.lastOrNull()
            if (next != null) {
                selectSession(next)
            }
        }
        bar.revalidate()
        bar.repaint()
        content.revalidate()
        content.repaint()
        updateNavigationState()
    }

    fun selectSession(session: TabSession) {
        if (selectedSession === session) {
            scrollToSelected()
            return
        }
        selectedSession?.let { headers[it]?.isSelected = false }
        selectedSession = session
        headers[session]?.isSelected = true
        (content.layout as CardLayout).show(content, cardName(session))
        content.revalidate()
        content.repaint()
        // The first card added after all tabs were closed needs a second layout pass:
        // at add time the card area had no children yet, so its size was never propagated.
        SwingUtilities.invokeLater {
            content.revalidate()
            content.doLayout()
            session.revalidate()
            content.repaint()
        }
        bar.repaint()
        scrollToSelected()
        updateNavigationState()
        onSelectionChanged(session)
    }

    /** Enables the paging arrows only when another tab exists in that direction. */
    private fun updateNavigationState() {
        val order = headers.keys.toList()
        val index = selectedSession?.let { order.indexOf(it) } ?: -1
        prevButton.isEnabled = index > 0
        nextButton.isEnabled = index >= 0 && index < order.size - 1
    }

    fun updateTitle(session: TabSession) {
        headers[session]?.refreshTitle()
        bar.revalidate()
        bar.repaint()
    }

    private fun cardName(session: TabSession): String =
        System.identityHashCode(session).toString()

    private fun scrollToSelected() {
        val header = selectedSession?.let { headers[it] } ?: return
        SwingUtilities.invokeLater {
            if (header.parent != null) bar.scrollRectToVisible(header.bounds)
        }
    }

    private inner class TabHeader(private val session: TabSession) : JPanel(BorderLayout()) {
        private val label = JBLabel()
        private val closeLabel = JBLabel("\u00D7")
        private val baseFont = label.font
        private var hovered = false

        var isSelected: Boolean = false
            set(value) {
                field = value
                label.font = baseFont.deriveFont(if (value) java.awt.Font.BOLD else java.awt.Font.PLAIN)
                repaint()
            }

        init {
            isOpaque = false
            label.foreground = UIUtil.getLabelForeground()
            label.border = JBUI.Borders.empty(7, 12, 7, 6)
            closeLabel.toolTipText = "Close tab (Ctrl+W)"
            closeLabel.foreground = UIUtil.getContextHelpForeground()
            closeLabel.border = JBUI.Borders.empty(7, 2, 7, 10)
            closeLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            closeLabel.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) onCloseRequested(session)
                }

                override fun mouseEntered(e: MouseEvent) {
                    closeLabel.foreground = UIUtil.getLabelForeground()
                }

                override fun mouseExited(e: MouseEvent) {
                    closeLabel.foreground = UIUtil.getContextHelpForeground()
                }
            })
            add(label, BorderLayout.CENTER)
            add(closeLabel, BorderLayout.EAST)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) selectSession(session)
                }

                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }
            })
            refreshTitle()
        }

        fun refreshTitle() {
            val full = session.title()
            val metrics: FontMetrics = label.getFontMetrics(baseFont)
            label.text = ellipsize(full, metrics, JBUI.scale(190))
            toolTipText = full
        }

        fun refreshTheme() {
            label.foreground = UIUtil.getLabelForeground()
            closeLabel.foreground = UIUtil.getContextHelpForeground()
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.color = when {
                    isSelected -> ThemeColors.selectedTabBackground
                    hovered -> ThemeColors.hoverTabBackground
                    else -> ThemeColors.stripBackground
                }
                g2.fillRect(0, 0, width, height)
                if (isSelected) {
                    g2.color = ThemeColors.accent
                    g2.fillRect(0, height - JBUI.scale(2), width, JBUI.scale(2))
                }
                g2.color = JBColor.border()
                g2.drawLine(width - 1, 0, width - 1, height)
            } finally {
                g2.dispose()
            }
        }

        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            return Dimension(size.width.coerceAtMost(JBUI.scale(260)), size.height)
        }
    }

    private fun ellipsize(text: String, metrics: FontMetrics, maxWidth: Int): String {
        if (metrics.stringWidth(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + "\u2026") > maxWidth) end--
        return text.substring(0, end).trimEnd() + "\u2026"
    }

    private object ThemeColors {
        val stripBackground: Color get() = JBColor.namedColor("EditorTabs.background", JBColor(0xECEEF1, 0x2B2D30))
        val selectedTabBackground: Color get() =
            JBColor.namedColor("EditorTabs.underlinedTabBackground", JBColor(0xFFFFFF, 0x1E1F22))
        val hoverTabBackground: Color get() =
            JBColor.namedColor("EditorTabs.hoverBackground", JBColor(0xE2E6EB, 0x35373A))
        val accent: Color get() =
            JBColor.namedColor("EditorTabs.underlinedTabForeground", JBColor(0x2F65CA, 0x4A88C7))
    }
}
