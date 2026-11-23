package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.HttpResult
import com.astra.intellij.model.KeyValue
import java.util.Locale

/**
 * Executes pre-request scripts. Two styles are supported:
 *
 * 1. Postman-compatible JavaScript, detected when the script uses pm./console./function/arrow
 *    functions or declarations. It runs through [ScriptService] and supports pm.sendRequest plus
 *    pm.environment / pm.variables, e.g.
 *
 *      pm.sendRequest({
 *        url: pm.variables.replaceIn('{{baseUrl}}/auth/login'),
 *        method: 'POST',
 *        header: { 'Content-Type': 'application/json' },
 *        body: { mode: 'raw', raw: JSON.stringify({ user: 'demo' }) }
 *      }, function (err, res) {
 *        pm.environment.set('token', res.json().data.token);
 *      });
 *
 * 2. Declarative instructions, one per line:
 *      set <name> <value>   -> stores in the active environment and this run
 *      unset <name>         -> removes from the active environment and this run
 *
 * Sub-request blocks (indent the lines under "request") send an extra HTTP call before the main
 * request. Values captured from its response are stored in the environment and can be used by the
 * main request:
 *
 *   request POST {{baseUrl}}/auth/login
 *     header Content-Type: application/json
 *     body {"user":"{{user}}","password":"{{pass}}"}
 *     set token json $.data.token
 *     set session header X-Session-Id
 *
 * Capture syntax matches the Post-response tab: json/header/body/status/value.
 * Lines starting with '#' are comments. Returns the variables for this request run.
 */
object PreRequestService {
    private val HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

    private class Block(val request: AstraRequest = AstraRequest()) {
        val captures: MutableList<String> = mutableListOf()
    }

    fun execute(
        lines: List<String>,
        baseVars: Map<String, String>,
        environment: AstraEnvironment?,
        request: AstraRequest? = null,
        sender: (AstraRequest, Map<String, String>) -> HttpResult = { _, _ ->
            throw IllegalStateException("Pre-request HTTP calls are not available in this context")
        },
    ): MutableMap<String, String> {
        if (ScriptService.looksLikeJavaScript(lines)) {
            val script = lines
                .filterNot { it.trimStart().startsWith("#") }
                .joinToString("\n")
            return ScriptService.runPreRequest(script, baseVars, environment, request, sender)
        }
        val vars = baseVars.toMutableMap()
        var block: Block? = null

        fun flush() {
            val pending = block ?: return
            block = null
            val result = sender(pending.request, vars)
            PostResponseService.execute(pending.captures, result, vars, environment)
        }

        lines.forEach { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val indented = raw.isNotEmpty() && raw.first().isWhitespace()
            val parts = trimmed.split(Regex("\\s+"), limit = 2)
            val keyword = parts[0].lowercase(Locale.ROOT)
            val rest = parts.getOrElse(1) { "" }

            if (keyword == "request") {
                flush()
                block = parseBlock(rest)
                return@forEach
            }
            if (block != null && indented) {
                when (keyword) {
                    "header" -> addHeader(block!!.request, rest)
                    "body" -> {
                        block!!.request.body = rest
                        block!!.request.bodyMode = if (looksLikeJson(rest)) "json" else "text"
                    }
                    "set", "unset" -> block!!.captures.add(trimmed)
                }
                return@forEach
            }
            if (block != null) flush()
            when (keyword) {
                "set" -> if (parts.size == 2) {
                    val tokens = trimmed.split(Regex("\\s+"), limit = 3)
                    if (tokens.size == 3) applySet(tokens[1], tokens[2], vars, environment)
                }
                "unset" -> if (rest.isNotBlank()) {
                    val name = rest.trim()
                    vars.remove(name)
                    environment?.variables?.remove(name)
                }
            }
        }
        flush()
        return vars
    }

    private fun parseBlock(rest: String): Block {
        val tokens = rest.trim().split(Regex("\\s+"), limit = 2)
        val first = tokens.getOrNull(0).orEmpty().uppercase(Locale.ROOT)
        val hasMethod = first in HTTP_METHODS
        val method = if (hasMethod) first else "GET"
        val url = if (hasMethod) tokens.getOrNull(1).orEmpty() else rest.trim()
        return Block(AstraRequest(name = "Pre-request", method = method, url = url))
    }

    private fun addHeader(request: AstraRequest, header: String) {
        val separator = header.indexOf(':')
        if (separator <= 0) return
        request.headers.add(
            KeyValue(
                key = header.substring(0, separator).trim(),
                value = header.substring(separator + 1).trim(),
            ),
        )
    }

    private fun applySet(
        name: String,
        rawValue: String,
        vars: MutableMap<String, String>,
        environment: AstraEnvironment?,
    ) {
        if (name.isBlank()) return
        val value = VariableResolver.resolve(rawValue, vars)
        vars[name] = value
        environment?.variables?.set(name, value)
    }

    private fun looksLikeJson(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }
}
