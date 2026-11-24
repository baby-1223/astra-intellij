package com.astra.intellij.ui

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import javax.swing.Icon

object AstraIcons {
    /** Filled five-pointed star drawn as a vector, so it stays crisp on HiDPI displays. */
    val starFilled: Icon = StarIcon(filled = true)

    /** Outline star shown on hover, before a collection is starred. */
    val starOutline: Icon = StarIcon(filled = false)

    val collection: Icon = load("collection")
    val folder: Icon = load("folder")
    val run: Icon = load("run")
    val rename: Icon = load("rename")
    val duplicate: Icon = load("duplicate")
    val delete: Icon = load("delete")
    private val requestDefault: Icon = load("request")
    private val requestGet: Icon = load("request-get")
    private val requestPost: Icon = load("request-post")
    private val requestPut: Icon = load("request-put")
    private val requestPatch: Icon = load("request-patch")
    private val requestDelete: Icon = load("request-delete")

    fun request(method: String): Icon = when (method.uppercase()) {
        "GET" -> requestGet
        "POST" -> requestPost
        "PUT" -> requestPut
        "PATCH" -> requestPatch
        "DELETE" -> requestDelete
        else -> requestDefault
    }

    fun methodColor(method: String): String {
        // Light themes need darker colors for readable text on a white tree/combo background.
        val color = when (method.uppercase()) {
            "GET" -> JBColor(0x1F7A4D, 0x4CC38A)
            "POST" -> JBColor(0x8A5200, 0xE8A33D)
            "PUT" -> JBColor(0x1D4ED8, 0x8AB4F8)
            "PATCH" -> JBColor(0x7E22CE, 0xC084FC)
            "DELETE" -> JBColor(0xB3261E, 0xF08A85)
            else -> JBColor(0x4B5563, 0xA7B0BE)
        }
        return "#" + (color.rgb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
    }

    private fun load(name: String): Icon =
        IconLoader.getIcon("/icons/astra/$name.svg", AstraIcons::class.java.classLoader)

    /**
     * A five-pointed star painted with Java2D.
     *
     * The previous implementation used a Unicode glyph, which some fonts render as three dots or
     * fall back to a different symbol. Drawing the polygon guarantees the same shape everywhere.
     */
    private class StarIcon(private val filled: Boolean) : Icon {
        private val size = JBUI.scale(15)
        private val unit = JBUI.scale(1)

        override fun getIconWidth(): Int = size

        override fun getIconHeight(): Int = size

        override fun paintIcon(component: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val centerX = x + size / 2.0
                val centerY = y + size / 2.0
                val outer = size / 2.0 - unit * 0.5
                val inner = outer * 0.382
                val path = Path2D.Double()
                for (index in 0 until 10) {
                    val radius = if (index % 2 == 0) outer else inner
                    val angle = Math.toRadians(-90.0 + index * 36.0)
                    val px = centerX + radius * Math.cos(angle)
                    val py = centerY + radius * Math.sin(angle)
                    if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.closePath()
                if (filled) {
                    g2.color = FILL
                    g2.fill(path)
                    g2.color = FILL_BORDER
                    g2.stroke = BasicStroke(unit.toFloat())
                    g2.draw(path)
                } else {
                    g2.color = OUTLINE
                    g2.stroke = BasicStroke(
                        unit * 1.2f,
                        BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND,
                    )
                    g2.draw(path)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    private val FILL = JBColor(0xE39A16, 0xF2B74A)
    private val FILL_BORDER = JBColor(0xB87A0C, 0xD9A03C)
    private val OUTLINE = JBColor(0x9AA3AF, 0x6E7681)
}
