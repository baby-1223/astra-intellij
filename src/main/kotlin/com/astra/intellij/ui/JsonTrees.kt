package com.astra.intellij.ui

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

object JsonTrees {
    fun parse(text: String): JTree {
        val root = try {
            val json = JsonParser.parseString(text)
            DefaultMutableTreeNode("JSON").apply { add(children(json, "$")) }
        } catch (_: Exception) {
            DefaultMutableTreeNode("非 JSON 响应")
        }
        return JTree(DefaultTreeModel(root)).apply {
            isRootVisible = true
            showsRootHandles = true
        }
    }

    private fun children(json: JsonElement, path: String): DefaultMutableTreeNode =
        DefaultMutableTreeNode("$path = ${preview(json)}").apply {
            when {
                json.isJsonObject -> json.asJsonObject.entrySet().forEach { (key, value) ->
                    add(children(value, key))
                }
                json.isJsonArray -> json.asJsonArray.forEachIndexed { index, value ->
                    add(children(value, "[$index]"))
                }
            }
        }

    private fun preview(json: JsonElement): String = when {
        json.isJsonNull -> "null"
        json.isJsonPrimitive -> json.asString
        json.isJsonArray -> "[${json.asJsonArray.size()} 项]"
        else -> "{${json.asJsonObject.size()} 个字段}"
    }
}
