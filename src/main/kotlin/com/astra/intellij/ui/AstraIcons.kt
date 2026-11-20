package com.astra.intellij.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object AstraIcons {
    val collection: Icon = load("collection")
    val folder: Icon = load("folder")
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

    fun methodColor(method: String): String = when (method.uppercase()) {
        "GET" -> "#2E9E6B"
        "POST" -> "#E8A33D"
        "PUT" -> "#3B82F6"
        "PATCH" -> "#9B59D0"
        "DELETE" -> "#E05252"
        else -> "#8A94A6"
    }

    private fun load(name: String): Icon =
        IconLoader.getIcon("/icons/astra/$name.svg", AstraIcons::class.java.classLoader)
}
