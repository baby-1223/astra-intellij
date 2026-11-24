package com.astra.intellij.core

import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.KeyValue
import com.astra.intellij.model.RequestCollection
import java.util.Locale

/** One HTTP operation declared by a Spring controller method. */
data class ControllerEndpoint(
    val name: String,
    val method: String,
    val path: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val query: List<Pair<String, String>> = emptyList(),
    val body: String? = null,
)

data class ControllerSpec(
    val className: String,
    val basePath: String,
    val endpoints: List<ControllerEndpoint>,
)

/**
 * Reads Spring MVC controllers from Java / Kotlin source text without the PSI.
 *
 * Keeping the parser text-based means it can be unit tested without the IDE runtime and works
 * even before the project has finished indexing.
 */
object SpringControllerParser {
    private val controllers = Regex("@(RestController|Controller)\\b")
    private val mappings = setOf(
        "GetMapping",
        "PostMapping",
        "PutMapping",
        "PatchMapping",
        "DeleteMapping",
        "RequestMapping",
    )
    private val stringLiteral = Regex("\"([^\"]*)\"")
    private val methodToken = Regex("RequestMethod\\.(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE)")

    fun looksLikeController(source: String): Boolean =
        controllers.containsMatchIn(source) && mappings.any { source.contains("@$it") }

    fun parse(source: String, fileName: String = "Controller"): ControllerSpec? {
        val controller = controllers.find(source) ?: return null
        val classMatch = Regex("\\bclass\\s+(\\w+)").find(source, controller.range.last + 1) ?: return null
        val className = classMatch.groupValues[1]
        val bodyStart = source.indexOf('{', classMatch.range.last + 1)
        if (bodyStart < 0) return null
        val bodyEnd = matchingBrace(source, bodyStart) ?: source.length

        val basePath = mappingAnnotations(source, controller.range.last + 1, classMatch.range.first)
            .lastOrNull { it.name == "RequestMapping" }
            ?.let { firstString(it.attributes) }
            .orEmpty()

        val endpoints = mappingAnnotations(source, bodyStart, bodyEnd).mapNotNull { annotation ->
            val signature = methodSignature(source, annotation.end, bodyEnd) ?: return@mapNotNull null
            val method = httpMethod(annotation)
            val declaredPath = firstString(annotation.attributes).orEmpty()
            val parameters = parameterList(signature.parameters)
            val pathVariables = mutableListOf<Pair<String, String>>()
            val query = mutableListOf<Pair<String, String>>()
            val headers = mutableListOf<Pair<String, String>>()
            var body: String? = null

            parameters.forEach { parameter ->
                val modifiers = parameterAnnotations(parameter)
                val declaration = parameter.replace(Regex("@\\w+(\\([^)]*\\))?"), " ").trim()
                val name = parameterName(parameter, declaration)
                val type = parameterType(declaration)
                when {
                    "@RequestBody" in modifiers -> {
                        body = dtoSample(type, source, depth = 0)
                    }
                    "@RequestPart" in modifiers || type.contains("MultipartFile") -> Unit
                    "@PathVariable" in modifiers && name.isNotBlank() -> {
                        pathVariables += name to sampleForType(type)
                    }
                    "@RequestParam" in modifiers && name.isNotBlank() -> {
                        val default = annotationString(parameter, "defaultValue")
                        query += name to (default ?: sampleForType(type))
                    }
                    "@RequestHeader" in modifiers && name.isNotBlank() -> {
                        val headerName = annotationString(parameter, "name")
                            ?: annotationString(parameter, "value")
                            ?: name
                        headers += headerName to (annotationString(parameter, "defaultValue") ?: "")
                    }
                    "@CookieValue" in modifiers && name.isNotBlank() -> {
                        headers += "Cookie" to "$name=${sampleForType(type)}"
                    }
                }
            }

            var path = joinPath(basePath, declaredPath)
            pathVariables.forEach { (key, value) ->
                path = path.replace("{$key}", value)
            }
            ControllerEndpoint(
                name = signature.name,
                method = method,
                path = path,
                headers = headers,
                query = query,
                body = body,
            )
        }
        if (endpoints.isEmpty()) return null
        return ControllerSpec(className.ifBlank { fileName }, basePath, endpoints)
    }

    /** Builds a collection whose requests point at `{{baseUrl}}` resolved from the environment. */
    fun toCollection(spec: ControllerSpec): RequestCollection {
        val collection = RequestCollection(name = spec.className)
        spec.endpoints.forEach { endpoint ->
            val request = AstraRequest(
                name = endpoint.name,
                method = endpoint.method,
                url = "{{baseUrl}}${endpoint.path}",
            )
            request.headers = endpoint.headers
                .map { (key, value) -> KeyValue(true, key, value) }
                .toMutableList()
            request.params = endpoint.query
                .map { (key, value) -> KeyValue(true, key, value) }
                .toMutableList()
            endpoint.body?.let { body ->
                request.bodyMode = "json"
                request.body = body
                if (request.headers.none { it.key.equals("Content-Type", ignoreCase = true) }) {
                    request.headers += KeyValue(true, "Content-Type", "application/json")
                }
            }
            collection.requests += request
        }
        return collection
    }

    private data class AnnotationHit(
        val name: String,
        val attributes: String,
        val start: Int,
        val end: Int,
    )

    private data class MethodSignature(val name: String, val parameters: String)

    private fun mappingAnnotations(source: String, from: Int, to: Int): List<AnnotationHit> {
        val result = mutableListOf<AnnotationHit>()
        val pattern = Regex("@(${mappings.joinToString("|")})\\b")
        pattern.findAll(source, from).forEach { match ->
            if (match.range.first >= to) return@forEach
            var cursor = match.range.last + 1
            while (cursor < source.length && source[cursor].isWhitespace()) cursor++
            if (cursor < source.length && source[cursor] == '(') {
                val close = matchingParen(source, cursor) ?: return@forEach
                result += AnnotationHit(
                    match.groupValues[1],
                    source.substring(cursor + 1, close),
                    match.range.first,
                    close + 1,
                )
            } else {
                result += AnnotationHit(match.groupValues[1], "", match.range.first, match.range.last + 1)
            }
        }
        return result.sortedBy { it.start }
    }

    private fun httpMethod(annotation: AnnotationHit): String {
        when (annotation.name) {
            "GetMapping" -> return "GET"
            "PostMapping" -> return "POST"
            "PutMapping" -> return "PUT"
            "PatchMapping" -> return "PATCH"
            "DeleteMapping" -> return "DELETE"
        }
        methodToken.find(annotation.attributes)?.let { return it.groupValues[1] }
        return "GET"
    }

    /** Locates the method name and parameter list that follow an annotation. */
    private fun methodSignature(source: String, from: Int, limit: Int): MethodSignature? {
        var cursor = from
        while (cursor < limit) {
            val char = source[cursor]
            when {
                char.isWhitespace() -> cursor++
                char == '@' -> {
                    val nameEnd = run {
                        var index = cursor + 1
                        while (index < limit && (source[index].isLetterOrDigit() || source[index] == '.')) index++
                        index
                    }
                    var next = nameEnd
                    while (next < limit && source[next].isWhitespace()) next++
                    cursor = if (next < limit && source[next] == '(') {
                        (matchingParen(source, next) ?: return null) + 1
                    } else {
                        nameEnd
                    }
                }
                char == '<' -> cursor = (matchingAngle(source, cursor) ?: return null) + 1
                char == '(' -> {
                    val close = matchingParen(source, cursor) ?: return null
                    val prefix = source.substring(from, cursor)
                    val name = Regex("(\\w+)\\s*$").find(prefix.trimEnd())?.groupValues?.get(1) ?: return null
                    return MethodSignature(name, source.substring(cursor + 1, close))
                }
                char == '{' || char == '}' || char == ';' -> return null
                else -> cursor++
            }
        }
        return null
    }

    private fun parameterList(raw: String): List<String> =
        splitTopLevel(raw, ',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun parameterAnnotations(parameter: String): Set<String> =
        Regex("@(\\w+)").findAll(parameter).map { "@" + it.groupValues[1] }.toSet()

    private fun parameterName(parameter: String, declaration: String): String {
        annotationString(parameter, "value")?.let { return it }
        annotationString(parameter, "name")?.let { return it }
        val colon = declaration.indexOf(':')
        val head = if (colon >= 0) declaration.substring(0, colon) else declaration
        return Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*$").find(head.trim())?.groupValues?.get(1).orEmpty()
    }

    private fun parameterType(declaration: String): String {
        val colon = declaration.indexOf(':')
        return if (colon >= 0) {
            declaration.substring(colon + 1).trim()
        } else {
            declaration.split(Regex("\\s+")).firstOrNull().orEmpty()
        }
    }

    private fun annotationString(text: String, attribute: String): String? {
        val named = Regex("(?<![A-Za-z0-9_])$attribute\\s*=\\s*\"([^\"]*)\"").find(text)
        if (named != null) return named.groupValues[1]
        if (attribute != "value" && attribute != "name") return null
        // A positional value only counts when the annotation arguments start with a string.
        val arguments = Regex("@\\w+\\s*\\(([^)]*)\\)").find(text)?.groupValues?.get(1)?.trim() ?: return null
        return if (arguments.startsWith("\"")) stringLiteral.find(arguments)?.groupValues?.get(1) else null
    }

    private fun firstString(attributes: String): String? =
        stringLiteral.find(attributes)?.groupValues?.get(1)

    private fun joinPath(base: String, path: String): String {
        val parts = listOf(base, path)
            .map { it.trim().trim('/') }
            .filter { it.isNotEmpty() }
        return "/" + parts.joinToString("/")
    }

    private fun sampleForType(type: String): String {
        val normalized = type
            .removePrefix("?")
            .replace(Regex("<.*>"), "")
            .substringAfterLast('.')
            .trim()
        return when (normalized) {
            "String", "CharSequence", "Char", "char" -> "string"
            "Int", "Integer", "Long", "Short", "Byte", "BigInteger",
            "int", "long", "short", "byte" -> "0"
            "Double", "Float", "BigDecimal", "double", "float" -> "0.0"
            "Boolean", "boolean" -> "true"
            "LocalDate" -> "2024-01-01"
            "LocalDateTime", "Instant", "OffsetDateTime", "ZonedDateTime" -> "2024-01-01T00:00:00Z"
            "UUID" -> "00000000-0000-4000-8000-000000000000"
            else -> "string"
        }
    }

    /** Builds a JSON body for a DTO declared in the same file, or `{}` when it cannot be resolved. */
    private fun dtoSample(type: String, source: String, depth: Int): String {
        val simple = type
            .removePrefix("?")
            .replace(Regex("<.*>"), "")
            .substringAfterLast('.')
            .trim()
        if (simple.isBlank()) return "{}"
        if (depth > 2) return "{}"
        val classMatch = Regex("\\bclass\\s+$simple\\b").find(source) ?: return "{}"
        val bodyStart = source.indexOf('{', classMatch.range.last + 1)
        if (bodyStart < 0) return "{}"
        val bodyEnd = matchingBrace(source, bodyStart) ?: return "{}"
        val body = source.substring(bodyStart + 1, bodyEnd)
        val fields = mutableListOf<Pair<String, String>>()
        // Kotlin: val name: String / Java: private String name;
        Regex("\\b(?:val|var)\\s+(\\w+)\\s*:\\s*([A-Za-z_][\\w.<>?\\[\\]]*)").findAll(body).forEach {
            fields += it.groupValues[1] to it.groupValues[2]
        }
        Regex("\\bprivate\\s+(?:final\\s+)?([A-Za-z_][\\w.<>?\\[\\]]*)\\s+(\\w+)\\s*;").findAll(body).forEach {
            fields += it.groupValues[2] to it.groupValues[1]
        }
        if (fields.isEmpty()) return "{}"
        return buildString {
            append("{\n")
            fields.forEachIndexed { index, (name, fieldType) ->
                append("  \"").append(name).append("\": ")
                val normalized = fieldType.replace(Regex("<.*>"), "").substringAfterLast('.')
                append(
                    when {
                        normalized == "String" || normalized == "char" -> "\"string\""
                        normalized in setOf(
                            "Int", "Integer", "Long", "Short", "Byte",
                            "int", "long", "short", "byte",
                        ) -> "0"
                        normalized in setOf("Double", "Float", "BigDecimal", "double", "float") -> "0.0"
                        normalized == "Boolean" || normalized == "boolean" -> "true"
                        fieldType.startsWith("List") || fieldType.startsWith("Set") ||
                            fieldType.startsWith("Collection") || fieldType.endsWith("[]") -> "[]"
                        else -> dtoSample(fieldType, source, depth + 1)
                    },
                )
                if (index < fields.lastIndex) append(',')
                append('\n')
            }
            append('}')
        }
    }

    private fun splitTopLevel(text: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        val buffer = StringBuilder()
        var round = 0
        var angle = 0
        var square = 0
        var quoted = false
        text.forEach { char ->
            when {
                char == '"' -> {
                    quoted = !quoted
                    buffer.append(char)
                }
                quoted -> buffer.append(char)
                char == '(' -> { round++; buffer.append(char) }
                char == ')' -> { round--; buffer.append(char) }
                char == '<' -> { angle++; buffer.append(char) }
                char == '>' -> { angle--; buffer.append(char) }
                char == '[' -> { square++; buffer.append(char) }
                char == ']' -> { square--; buffer.append(char) }
                char == separator && round == 0 && angle == 0 && square == 0 -> {
                    parts += buffer.toString()
                    buffer.setLength(0)
                }
                else -> buffer.append(char)
            }
        }
        parts += buffer.toString()
        return parts
    }

    private fun matchingParen(source: String, open: Int): Int? = matching(source, open, '(', ')')

    private fun matchingBrace(source: String, open: Int): Int? = matching(source, open, '{', '}')

    private fun matchingAngle(source: String, open: Int): Int? = matching(source, open, '<', '>')

    private fun matching(source: String, open: Int, start: Char, end: Char): Int? {
        var depth = 0
        var quoted = false
        var index = open
        while (index < source.length) {
            val char = source[index]
            if (char == '"' && source.getOrNull(index - 1) != '\\') quoted = !quoted
            if (!quoted) {
                when (char) {
                    start -> depth++
                    end -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
            index++
        }
        return null
    }
}
