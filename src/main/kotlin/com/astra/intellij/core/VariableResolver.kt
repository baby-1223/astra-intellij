package com.astra.intellij.core

import java.time.Instant
import java.util.UUID

object VariableResolver {
    private val placeholder = Regex("\\{\\{([^{}]+)}}")

    fun resolve(input: String, vars: Map<String, String>): String {
        val replaced = placeholder.replace(input) { match ->
            val name = match.groupValues[1].trim()
            if (name.startsWith("$")) {
                dynamicValue(name.removePrefix("$")) ?: match.value
            } else {
                vars[name] ?: match.value
            }
        }
        return replaced
    }

    fun missing(input: String, vars: Map<String, String>): Set<String> =
        placeholder.findAll(input)
            .map { it.groupValues[1].trim() }
            .filterNot { it.startsWith("$") }
            .filterNot(vars::containsKey)
            .toCollection(linkedSetOf())

    private fun dynamicValue(name: String): String? = when (name) {
        "timestamp" -> (System.currentTimeMillis() / 1000).toString()
        "isoTimestamp" -> Instant.now().toString()
        "guid", "uuid" -> UUID.randomUUID().toString()
        "randomInt" -> (0..1000).random().toString()
        else -> null
    }
}
