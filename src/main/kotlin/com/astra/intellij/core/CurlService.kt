package com.astra.intellij.core

import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.AuthConfig
import com.astra.intellij.model.KeyValue
import java.util.Locale

class CurlService {
    fun parse(input: String): AstraRequest {
        val tokens = tokenize(input)
        val request = AstraRequest()
        val data = mutableListOf<String>()
        var explicitMethod = false
        var basicCredentials: String? = null
        var index = if (tokens.firstOrNull() == "curl") 1 else 0

        fun valueAfter(option: String): String? {
            val value = tokens.getOrNull(index + 1)
            if (value != null) index++
            return value
        }

        while (index < tokens.size) {
            val token = tokens[index]
            when {
                token == "-X" || token == "--request" -> valueAfter(token)?.let {
                    request.method = it.uppercase(Locale.ROOT)
                    explicitMethod = true
                }
                token.startsWith("--request=") -> {
                    request.method = token.substringAfter('=').uppercase(Locale.ROOT)
                    explicitMethod = true
                }
                token == "-H" || token == "--header" -> valueAfter(token)?.let {
                    addHeader(request, it)
                }
                token.startsWith("--header=") -> addHeader(request, token.substringAfter('='))
                token == "-d" || token == "--data" || token == "--data-raw" ||
                    token == "--data-binary" -> valueAfter(token)?.let(data::add)
                token.startsWith("--data=") || token.startsWith("--data-raw=") ||
                    token.startsWith("--data-binary=") -> data += token.substringAfter('=')
                token == "-u" || token == "--user" -> valueAfter(token)?.let { basicCredentials = it }
                token.startsWith("--user=") -> basicCredentials = token.substringAfter('=')
                token == "--url" -> valueAfter(token)?.let { request.url = it }
                token.startsWith("--url=") -> request.url = token.substringAfter('=')
                token == "--compressed" || token == "-k" || token == "--insecure" -> Unit
                token.startsWith('-') -> Unit
                request.url.isEmpty() -> request.url = token
            }
            index++
        }

        if (!explicitMethod && data.isNotEmpty()) request.method = "POST"
        if (data.isNotEmpty()) {
            request.body = data.joinToString("&")
            request.bodyMode = if (request.headers.any {
                    it.key.equals("Content-Type", ignoreCase = true) &&
                        it.value.contains("application/json", ignoreCase = true)
                }
            ) "json" else "text"
        }
        basicCredentials?.let { credentials ->
            val separator = credentials.indexOf(':')
            request.auth = AuthConfig(
                type = "basic",
                username = if (separator >= 0) credentials.substring(0, separator) else credentials,
                password = if (separator >= 0) credentials.substring(separator + 1) else "",
            )
        }
        return request
    }

    fun export(request: AstraRequest, vars: Map<String, String> = emptyMap()): String {
        val method = VariableResolver.resolve(request.method, vars).uppercase(Locale.ROOT)
        val parts = mutableListOf("curl", "-X", shellQuote(method), shellQuote(buildRequestUrl(request, vars)))
        val headers = request.headers.filter { it.enabled && it.key.isNotBlank() }.map { header ->
            VariableResolver.resolve(header.key, vars) to VariableResolver.resolve(header.value, vars)
        }.toMutableList()
        val hasBody = method != "GET" && method != "HEAD" && request.bodyMode != "none"

        if (hasBody && headers.none { it.first.equals("Content-Type", ignoreCase = true) }) {
            headers += "Content-Type" to
                if (request.bodyMode == "json") "application/json" else "text/plain; charset=utf-8"
        }
        when (request.auth.type.lowercase(Locale.ROOT)) {
            "bearer" -> headers += "Authorization" to
                "Bearer ${VariableResolver.resolve(request.auth.token, vars)}"
            "basic" -> {
                parts += "-u"
                parts += shellQuote(
                    "${VariableResolver.resolve(request.auth.username, vars)}:" +
                        VariableResolver.resolve(request.auth.password, vars),
                )
            }
            "apikey" -> if (request.auth.apiKeyIn.equals("header", ignoreCase = true)) {
                headers += VariableResolver.resolve(request.auth.apiKeyName, vars) to
                    VariableResolver.resolve(request.auth.apiKeyValue, vars)
            }
        }
        headers.forEach { (key, value) ->
            parts += "-H"
            parts += shellQuote("$key: $value")
        }
        if (hasBody) {
            parts += "--data"
            parts += shellQuote(VariableResolver.resolve(request.body, vars))
        }
        return parts.joinToString(" ")
    }

    private fun addHeader(request: AstraRequest, header: String) {
        val separator = header.indexOf(':')
        if (separator > 0) {
            request.headers += KeyValue(
                key = header.substring(0, separator).trim(),
                value = header.substring(separator + 1).trim(),
            )
        }
    }

    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var quote = Char.MIN_VALUE
        var started = false
        var index = 0
        while (index < input.length) {
            val char = input[index]
            when {
                quote == '\'' -> {
                    if (char == '\'') quote = Char.MIN_VALUE else token.append(char)
                    started = true
                }
                quote == Char.MIN_VALUE && char == '\'' -> {
                    quote = '\''
                    started = true
                }
                quote == '"' && char == '\\' -> {
                    val next = input.getOrNull(index + 1)
                    if (next == '\n' || next == '\r') {
                        index++
                        if (next == '\r' && input.getOrNull(index + 1) == '\n') index++
                    } else if (next != null &&
                        (next == '$' || next == '`' || next == '"' || next == '\\')
                    ) {
                        token.append(next)
                        index++
                    } else {
                        token.append(char)
                    }
                    started = true
                }
                char == '"' && quote == '"' -> {
                    quote = Char.MIN_VALUE
                    started = true
                }
                char == '"' -> {
                    quote = '"'
                    started = true
                }
                quote == Char.MIN_VALUE && char == '\\' -> {
                    val next = input.getOrNull(index + 1)
                    if (next != null) {
                        if (next != '\n' && next != '\r') token.append(next)
                        index++
                        if (next == '\r' && input.getOrNull(index + 1) == '\n') index++
                    } else {
                        token.append(char)
                    }
                    started = true
                }
                quote == Char.MIN_VALUE && char.isWhitespace() -> {
                    if (started) {
                        tokens += token.toString()
                        token.clear()
                        started = false
                    }
                }
                else -> {
                    token.append(char)
                    started = true
                }
            }
            index++
        }
        if (started) tokens += token.toString()
        return tokens
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
