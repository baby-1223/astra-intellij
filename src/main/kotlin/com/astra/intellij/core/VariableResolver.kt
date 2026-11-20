package com.astra.intellij.core

object VariableResolver {
    private val placeholder = Regex("\\{\\{([^{}]+)}}")

    fun resolve(input: String, vars: Map<String, String>): String =
        placeholder.replace(input) { match ->
            vars[match.groupValues[1].trim()] ?: match.value
        }

    fun missing(input: String, vars: Map<String, String>): Set<String> =
        placeholder.findAll(input)
            .map { it.groupValues[1].trim() }
            .filterNot(vars::containsKey)
            .toCollection(linkedSetOf())
}
