package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.HttpResult
import com.google.gson.JsonParser

/**
 * Executes declarative post-response instructions, one per line, and stores the extracted
 * values in the active environment. Typical use: capture an auth token from the response
 * of a login request and reuse it via {{token}} in later requests.
 *
 * Supported instructions:
 *   set <name> json <path>          -> value at a JSON body path, e.g. $.data.token
 *   set <name> header <HeaderName>  -> response header value
 *   set <name> body <regex>         -> first capture group (or whole match) from the body
 *   set <name> status               -> numeric HTTP status
 *   set <name> value <text>         -> literal text; {{variables}} are resolved
 *   set <name> <text>               -> shorthand for "value"
 *   unset <name>                    -> removes the variable from the active environment
 * Lines starting with '#' are comments.
 */
object PostResponseService {
    data class Update(val name: String, val value: String)

    fun execute(
        lines: List<String>,
        result: HttpResult,
        vars: MutableMap<String, String>,
        environment: AstraEnvironment?,
    ): List<Update> {
        val updates = mutableListOf<Update>()
        lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            val tokens = tokenize(line)
            if (tokens.isEmpty()) return@forEach
            when (tokens[0].lowercase()) {
                "set" -> {
                    if (tokens.size < 3) return@forEach
                    val name = tokens[1]
                    val value = extract(tokens, result, vars) ?: return@forEach
                    vars[name] = value
                    environment?.variables?.set(name, value)
                    updates.add(Update(name, value))
                }
                "unset" -> {
                    val name = tokens.getOrNull(1) ?: return@forEach
                    vars.remove(name)
                    environment?.variables?.remove(name)
                    updates.add(Update(name, ""))
                }
            }
        }
        return updates
    }

    private fun extract(tokens: List<String>, result: HttpResult, vars: Map<String, String>): String? {
        val mode = tokens.getOrNull(2)?.lowercase() ?: return null
        val body = ResponseCharset.decode(result)
        return when (mode) {
            "json" -> {
                val path = tokens.getOrNull(3) ?: return null
                val root = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return null
                val element = TestService.jsonAt(root, path) ?: return null
                if (element.isJsonPrimitive) element.asString else element.toString()
            }
            "header" -> {
                val header = tokens.getOrNull(3) ?: return null
                result.headers.entries
                    .firstOrNull { it.key.equals(header, ignoreCase = true) }
                    ?.value
                    ?.joinToString(", ")
            }
            "body" -> {
                val pattern = tokens.drop(3).joinToString(" ").ifBlank { return null }
                val match = runCatching { Regex(pattern).find(body) }.getOrNull() ?: return null
                match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: match.value
            }
            "status" -> result.status.toString()
            "value" -> VariableResolver.resolve(tokens.drop(3).joinToString(" "), vars)
            else -> VariableResolver.resolve(tokens.drop(2).joinToString(" "), vars)
        }
    }

    private fun tokenize(line: String): List<String> =
        Regex("\"([^\"]*)\"|(\\S+)").findAll(line)
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            .toList()
}
