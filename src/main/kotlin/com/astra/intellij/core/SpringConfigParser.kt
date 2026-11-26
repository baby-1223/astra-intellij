package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import java.util.Locale

/**
 * Turns Spring Boot configuration files into Astra environment variables.
 *
 * Both `application.yml` (nested maps flattened to dotted keys) and `application.properties`
 * are supported. A `baseUrl` variable is derived from `server.port` and
 * `server.servlet.context-path` so controller requests generated from source code work as-is.
 */
object SpringConfigParser {
    fun parse(text: String, fileName: String = "application.yml"): Map<String, String> {
        if (fileName.lowercase(Locale.ROOT).endsWith(".properties")) {
            return parseProperties(text)
        }
        return YamlSupport.flatten(text)
    }

    fun parseProperties(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        text.replace("\r\n", "\n").split('\n').forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEach
            val separator = line.indexOfFirst { it == '=' || it == ':' }
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator).trim()
            if (key.isEmpty()) return@forEach
            result[key] = line.substring(separator + 1).trim()
        }
        return result
    }

    /** Collects variables worth exposing, resolving `${ENV:default}` placeholders to their default. */
    fun variables(properties: Map<String, String>): Map<String, String> {
        val variables = LinkedHashMap<String, String>()
        properties.forEach { (key, value) ->
            variables[key] = resolvePlaceholders(value, properties)
        }
        baseUrl(properties)?.let { variables.putIfAbsent("baseUrl", it) }
        return variables
    }

    /** `http://localhost:<port><context-path>` using Spring Boot's defaults. */
    fun baseUrl(properties: Map<String, String>): String? {
        val port = properties["server.port"]?.trim()?.takeIf { it.isNotEmpty() } ?: "8080"
        val context = properties["server.servlet.context-path"]?.trim().orEmpty().trimEnd('/')
        val prefix = if (context.isEmpty()) "" else if (context.startsWith("/")) context else "/$context"
        val scheme = if (properties["server.ssl.enabled"]?.trim().equals("true", ignoreCase = true)) "https" else "http"
        return "$scheme://localhost:$port$prefix"
    }

    fun environment(properties: Map<String, String>, name: String): AstraEnvironment =
        AstraEnvironment(name = name, variables = LinkedHashMap(variables(properties)))

    private fun resolvePlaceholders(value: String, properties: Map<String, String>): String {
        var resolved = value
        repeat(5) {
            val next = Regex("\\$\\{([^}:]+)(?::([^}]*))?}").replace(resolved) { match ->
                val key = match.groupValues[1]
                val fallback = match.groupValues[2]
                properties[key] ?: fallback
            }
            if (next == resolved) return resolved
            resolved = next
        }
        return resolved
    }
}
