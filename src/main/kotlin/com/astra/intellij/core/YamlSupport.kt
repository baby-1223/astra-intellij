package com.astra.intellij.core

import com.google.gson.Gson
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Converts YAML documents to JSON so the existing JSON-based importers can read
 * `application.yml` and OpenAPI YAML files without a second parser.
 */
object YamlSupport {
    private val gson = Gson()

    fun isJson(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    /** Returns [text] unchanged when it already is JSON, otherwise converts YAML to JSON. */
    fun normalizeToJson(text: String): String = if (isJson(text)) text else toJson(text)

    /** Converts the first YAML document to a compact JSON string. */
    fun toJson(text: String): String {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = true
            maxAliasesForCollections = 200
            codePointLimit = 8 * 1024 * 1024
        }
        val loaded = Yaml(SafeConstructor(options)).load<Any?>(text)
            ?: throw IllegalArgumentException("The YAML document is empty")
        return gson.toJson(loaded)
    }

    /** Flattens a YAML document to dotted keys, e.g. `server.port` -> `8080`. */
    fun flatten(text: String): Map<String, String> {
        val root = Yaml(SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = true }))
            .load<Any?>(text)
        val result = LinkedHashMap<String, String>()
        flattenValue("", root, result)
        return result
    }

    private fun flattenValue(prefix: String, value: Any?, out: MutableMap<String, String>) {
        when (value) {
            is Map<*, *> -> value.forEach { (key, child) ->
                val name = key?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return@forEach
                flattenValue(if (prefix.isEmpty()) name else "$prefix.$name", child, out)
            }
            is Collection<*> -> value.forEachIndexed { index, child ->
                flattenValue("$prefix[$index]", child, out)
            }
            null -> if (prefix.isNotEmpty()) out[prefix] = ""
            else -> if (prefix.isNotEmpty()) out[prefix] = value.toString()
        }
    }
}
