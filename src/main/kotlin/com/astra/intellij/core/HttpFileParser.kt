package com.astra.intellij.core

import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.FormField
import com.astra.intellij.model.KeyValue
import com.astra.intellij.model.RequestCollection
import java.util.Locale

/**
 * Parses IntelliJ HTTP Client files (`.http` / `.rest`) into an Astra collection.
 *
 * Supported syntax: `###` request separators, `@name = value` file variables, `# @name` request
 * names, request lines with or without an explicit method, header blocks, raw bodies and
 * `> {% ... %}` response handler scripts (ignored, since Astra keeps its own script model).
 */
object HttpFileParser {
    private val methods = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE")
    private val requestLine = Regex("^([A-Za-z]+)\\s+(.+)$")

    fun supports(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension == "http" || extension == "rest"
    }

    fun parse(text: String, fileName: String = "HTTP File"): RequestCollection {
        val collection = RequestCollection(name = fileName.substringBeforeLast('.').ifBlank { "HTTP File" })
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val requests = mutableListOf<AstraRequest>()

        var current: AstraRequest? = null
        var pendingName = ""
        var state = State.OUTSIDE
        var headers = mutableListOf<KeyValue>()
        var body = StringBuilder()

        fun flush() {
            val request = current ?: return
            request.headers = headers
            val rawBody = body.toString().trim('\n')
            applyBody(request, rawBody)
            if (request.name == "New Request" && pendingName.isNotBlank()) request.name = pendingName.trim()
            if (request.name == "New Request") request.name = fallbackName(request)
            requests += request
            current = null
            headers = mutableListOf()
            body = StringBuilder()
            pendingName = ""
        }

        var index = 0
        while (index < lines.size) {
            val raw = lines[index]
            val line = raw.trim()
            when (state) {
                State.OUTSIDE -> when {
                    line.isEmpty() -> Unit
                    line.startsWith("###") -> pendingName = line.removePrefix("###").trim()
                    isNameHint(line) -> pendingName = nameFromHint(line)
                    isComment(line) -> Unit
                    isVariable(line) -> assignVariable(line, collection)
                    line.startsWith(">") -> index = skipHandler(lines, index)
                    else -> {
                        val parsed = parseRequestLine(line)
                        if (parsed != null) {
                            current = AstraRequest(method = parsed.first, url = parsed.second)
                            state = State.HEADERS
                        }
                    }
                }
                State.HEADERS -> when {
                    line.startsWith("###") -> {
                        flush()
                        pendingName = line.removePrefix("###").trim()
                        state = State.OUTSIDE
                    }
                    line.isEmpty() -> state = State.BODY
                    isNameHint(line) -> pendingName = nameFromHint(line)
                    isComment(line) -> Unit
                    line.startsWith(">") -> {
                        flush()
                        state = State.OUTSIDE
                        index = skipHandler(lines, index) - 1
                    }
                    else -> {
                        val header = parseHeader(line)
                        if (header != null) {
                            headers.add(KeyValue(true, header.first, header.second))
                        } else {
                            body.append(raw).append('\n')
                            state = State.BODY
                        }
                    }
                }
                State.BODY -> when {
                    line.startsWith("###") -> {
                        flush()
                        pendingName = line.removePrefix("###").trim()
                        state = State.OUTSIDE
                    }
                    line.startsWith(">") -> {
                        // Response handlers and external body references are not part of the body.
                        flush()
                        state = State.OUTSIDE
                        index = skipHandler(lines, index) - 1
                    }
                    else -> body.append(raw).append('\n')
                }
            }
            index++
        }
        flush()
        collection.requests = requests
        return collection
    }

    private enum class State { OUTSIDE, HEADERS, BODY }

    private fun parseRequestLine(line: String): Pair<String, String>? {
        if (line.startsWith("{") || line.startsWith("@")) return null
        val match = requestLine.matchEntire(line)
        if (match != null && match.groupValues[1].uppercase(Locale.ROOT) in methods) {
            return match.groupValues[1].uppercase(Locale.ROOT) to match.groupValues[2].trim()
        }
        // A bare URL line is a GET request, matching the IntelliJ HTTP client behaviour.
        return if (line.startsWith("http://") || line.startsWith("https://") || line.startsWith("{{")) {
            "GET" to line
        } else {
            null
        }
    }

    private fun parseHeader(line: String): Pair<String, String>? {
        if (line.startsWith("{") || line.startsWith("[") || line.startsWith("//")) return null
        val separator = line.indexOf(':')
        if (separator <= 0) return null
        val key = line.substring(0, separator).trim()
        if (key.isEmpty() || key.any { it.isWhitespace() }) return null
        return key to line.substring(separator + 1).trim()
    }

    private fun isComment(line: String): Boolean =
        line.startsWith("#") || line.startsWith("//")

    private fun isNameHint(line: String): Boolean =
        line.startsWith("# @name") || line.startsWith("// @name")

    private fun nameFromHint(line: String): String = line.substringAfter("@name").trim()

    private fun isVariable(line: String): Boolean {
        if (!line.startsWith("@")) return false
        val separator = line.indexOf('=')
        return separator > 1
    }

    private fun assignVariable(line: String, collection: RequestCollection) {
        val separator = line.indexOf('=')
        val key = line.substring(1, separator).trim()
        if (key.isEmpty()) return
        collection.variables[key] = line.substring(separator + 1).trim().trim('"')
    }

    /** Skips a multi-line `> {% ... %}` handler or a single `> ./script.js` line. */
    private fun skipHandler(lines: List<String>, start: Int): Int {
        var index = start
        if (lines[index].trim().startsWith("> {%") || lines[index].trim() == "> {%") {
            while (index < lines.size && !lines[index].contains("%}")) index++
        }
        return index + 1
    }

    private fun applyBody(request: AstraRequest, rawBody: String) {
        if (rawBody.isBlank()) {
            request.bodyMode = "none"
            return
        }
        val contentType = request.headers
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        request.body = rawBody
        request.bodyMode = when {
            contentType.contains("json") -> "json"
            contentType.contains("x-www-form-urlencoded") -> "urlencoded"
            else -> if (rawBody.trimStart().startsWith("{") || rawBody.trimStart().startsWith("[")) {
                "json"
            } else {
                "text"
            }
        }
        if (request.bodyMode == "urlencoded") {
            request.formFields = rawBody.split('&')
                .filter { it.isNotBlank() }
                .map { pair ->
                    val separator = pair.indexOf('=')
                    if (separator < 0) {
                        FormField(true, decode(pair), "")
                    } else {
                        FormField(true, decode(pair.substring(0, separator)), decode(pair.substring(separator + 1)))
                    }
                }
                .toMutableList()
        }
    }

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault(value)

    private fun fallbackName(request: AstraRequest): String {
        val path = request.url
            .substringAfter("://", request.url)
            .substringAfter('/', "")
            .substringBefore('?')
        val label = path.ifBlank { request.url }.trim('/')
        return if (label.isBlank()) {
            request.method
        } else {
            "${request.method} /$label"
        }
    }

}
