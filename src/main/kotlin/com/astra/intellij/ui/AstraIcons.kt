package com.astra.intellij.ui

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import javax.swing.Icon

object AstraIcons {
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
}
