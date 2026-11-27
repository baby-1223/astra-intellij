package com.astra.intellij.core

import com.google.gson.JsonElement

/**
 * Shared JSONPath subset used by assertions, post-response captures and scripts:
 *   `$.data.token`, `$['data']['token']`, `$.items[0]`, `$.items[-1]`,
 *   `$.items[*].id`, `$..id` (recursive descent).
 */
object JsonPathSupport {
    fun firstAt(root: JsonElement, path: String): JsonElement? = allAt(root, path).firstOrNull()

    fun allAt(root: JsonElement, path: String): List<JsonElement> {
        val segments = parsePath(path)
        if (segments.isEmpty()) return listOf(root)
        var current = listOf(root)
        for (segment in segments) {
            current = when (segment) {
                is Segment.Key -> current.mapNotNull { node ->
                    node.takeIf { it.isJsonObject }?.asJsonObject?.get(segment.name)
                }
                is Segment.Index -> current.mapNotNull { node ->
                    node.takeIf { it.isJsonArray }?.asJsonArray?.let { array ->
                        val index = if (segment.index < 0) array.size() + segment.index else segment.index
                        if (index in 0 until array.size()) array[index] else null
                    }
                }
                Segment.Wildcard -> current.flatMap { node ->
                    when {
                        node.isJsonArray -> node.asJsonArray.toList()
                        node.isJsonObject -> node.asJsonObject.entrySet().map { it.value }
                        else -> emptyList()
                    }
                }
                is Segment.Recursive -> current.flatMap { collectNamed(it, segment.name) }
            }
            if (current.isEmpty()) return emptyList()
        }
        return current
    }

    private fun collectNamed(node: JsonElement, name: String): List<JsonElement> {
        val found = mutableListOf<JsonElement>()
        fun walk(element: JsonElement) {
            when {
                element.isJsonObject -> element.asJsonObject.entrySet().forEach { (key, value) ->
                    if (key == name) found.add(value)
                    walk(value)
                }
                element.isJsonArray -> element.asJsonArray.forEach { walk(it) }
            }
        }
        walk(node)
        return found
    }

    private sealed class Segment {
        data class Key(val name: String) : Segment()
        data class Index(val index: Int) : Segment()
        data object Wildcard : Segment()
        data class Recursive(val name: String) : Segment()
    }

    private fun parsePath(path: String): List<Segment> {
        val input = path.trim().removePrefix("$")
        val segments = mutableListOf<Segment>()
        var index = 0
        while (index < input.length) {
            when {
                input.startsWith("..", index) -> {
                    index += 2
                    val start = index
                    while (index < input.length && input[index] != '.' && input[index] != '[') index++
                    val name = input.substring(start, index).trim()
                    if (name.isNotEmpty()) segments.add(Segment.Recursive(unquote(name)))
                }
                input[index] == '.' -> {
                    index++
                    if (index < input.length && input[index] == '*') {
                        segments.add(Segment.Wildcard)
                        index++
                    } else {
                        val start = index
                        while (index < input.length && input[index] != '.' && input[index] != '[') index++
                        val name = input.substring(start, index).trim()
                        if (name.isNotEmpty()) segments.add(Segment.Key(unquote(name)))
                    }
                }
                input[index] == '[' -> {
                    val end = input.indexOf(']', index)
                    if (end < 0) break
                    val raw = input.substring(index + 1, end).trim()
                    when {
                        raw == "*" -> segments.add(Segment.Wildcard)
                        raw.toIntOrNull() != null -> segments.add(Segment.Index(raw.toInt()))
                        else -> segments.add(Segment.Key(unquote(raw)))
                    }
                    index = end + 1
                }
                else -> break
            }
        }
        return segments
    }

    private fun unquote(value: String): String =
        value.removeSurrounding("'").removeSurrounding("\"")
}
