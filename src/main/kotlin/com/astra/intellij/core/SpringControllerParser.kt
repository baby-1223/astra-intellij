package com.astra.intellij.core

import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.FormField
import com.astra.intellij.model.KeyValue
import com.astra.intellij.model.RequestCollection

/** One HTTP operation declared by a Spring controller method. */
data class ControllerEndpoint(
    val name: String,
    val method: String,
    val path: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val query: List<Pair<String, String>> = emptyList(),
    val pathVariables: List<Pair<String, String>> = emptyList(),
    val body: String? = null,
    val bodyMode: String = "none",
    val formFields: List<SpringFormField> = emptyList(),
    val contentType: String? = null,
)

data class SpringFormField(
    val name: String,
    val value: String,
    val file: Boolean = false,
    val fileName: String = "",
)

data class ControllerSpec(
    val className: String,
    val basePath: String,
    val endpoints: List<ControllerEndpoint>,
)

/**
 * Reads Spring MVC controllers from Java / Kotlin source text without the PSI.
 *
 * The parser is text-based so it can be unit tested without the IDE runtime and works before the
 * project has finished indexing. Passing [sources] lets DTOs declared in other files resolve too.
 */
object SpringControllerParser {
    private val stringLiteral = Regex("\"([^\"]*)\"")
    private val controllerAnnotation = Regex("@(?:[\\w.]+\\.)?(RestController|Controller)\\b")
    private val standardMappings = setOf(
        "GetMapping",
        "PostMapping",
        "PutMapping",
        "PatchMapping",
        "DeleteMapping",
        "RequestMapping",
    )
    private val simpleTypes = setOf(
        "String", "CharSequence", "Char", "char",
        "Int", "Integer", "Long", "Short", "Byte", "BigInteger",
        "int", "long", "short", "byte",
        "Double", "Float", "BigDecimal", "double", "float",
        "Boolean", "boolean", "LocalDate", "LocalDateTime", "Instant",
        "OffsetDateTime", "ZonedDateTime", "UUID", "Date", "Timestamp", "Time",
    )
    private val ignoredParameterTypes = setOf(
        "HttpServletRequest", "HttpServletResponse", "ServletRequest", "ServletResponse",
        "HttpSession", "Principal", "Authentication", "BindingResult", "Errors",
        "Model", "ModelMap", "ModelAndView", "UriComponentsBuilder", "Locale",
        "LocaleContext", "SessionStatus", "WebRequest", "NativeWebRequest",
        "MultipartHttpServletRequest", "RedirectAttributes", "ServerHttpRequest",
        "ServerHttpResponse", "ServerWebExchange",
    )

    fun looksLikeController(source: String): Boolean {
        val masked = SpringSourceIndex.maskSource(source)
        if (!controllerAnnotation.containsMatchIn(masked)) return false
        return Regex("@[A-Z][A-Za-z0-9_]*").containsMatchIn(masked)
    }

    fun parse(
        source: String,
        fileName: String = "Controller",
        sources: List<SpringSource> = emptyList(),
    ): ControllerSpec? = parseAll(source, fileName, sources).firstOrNull()

    fun parseAll(
        source: String,
        fileName: String = "Controller",
        sources: List<SpringSource> = emptyList(),
    ): List<ControllerSpec> {
        val current = SpringSource(fileName, source)
        val index = SpringSourceIndex(listOf(current) + sources.filter { it.name != fileName })
        val specs = mutableListOf<ControllerSpec>()

        val annotated = collectControllers(index, current)
        annotated.forEach { type -> controllerSpec(index, type)?.let { specs += it } }

        // No annotated type in this file: honour implementations of a controller interface.
        if (specs.isEmpty()) {
            index.typesIn(current).forEach { type ->
                val superController = type.superTypes.firstNotNullOfOrNull { index.find(it) }
                    ?.takeIf { isController(it) }
                if (superController != null) {
                    controllerSpec(index, superController, displayName = type.name)?.let { specs += it }
                }
            }
        }
        return specs
    }

    /** Builds a collection whose requests point at `{{baseUrl}}` resolved from the environment. */
    fun toCollection(spec: ControllerSpec): RequestCollection {
        val collection = RequestCollection(name = spec.className)
        val usedNames = mutableMapOf<String, Int>()
        spec.endpoints.forEach { endpoint ->
            val baseName = endpoint.name.ifBlank { "request" }
            val count = usedNames.merge(baseName, 1, Int::plus) ?: 1
            val name = if (count == 1) baseName else "$baseName ($count)"
            val request = AstraRequest(
                name = name,
                method = endpoint.method,
                url = "{{baseUrl}}${endpoint.path}",
            )
            request.headers = endpoint.headers
                .distinctBy { it.first.lowercase() }
                .map { (key, value) -> KeyValue(true, key, value) }
                .toMutableList()
            request.params = endpoint.query
                .map { (key, value) -> KeyValue(true, key, value) }
                .toMutableList()
            request.bodyMode = endpoint.bodyMode
            when (endpoint.bodyMode) {
                "form-data", "urlencoded" -> {
                    request.formFields = endpoint.formFields.map { field ->
                        if (field.file) {
                            FormField(
                                enabled = true,
                                key = field.name,
                                type = "file",
                                fileName = field.fileName,
                                filePath = "",
                            )
                        } else {
                            FormField(enabled = true, key = field.name, value = field.value)
                        }
                    }.toMutableList()
                }
                "json", "text" -> request.body = endpoint.body.orEmpty()
                else -> Unit
            }
            endpoint.contentType?.takeIf { it.isNotBlank() }?.let { contentType ->
                if (endpoint.bodyMode != "form-data" &&
                    request.headers.none { it.key.equals("Content-Type", ignoreCase = true) }
                ) {
                    request.headers += KeyValue(true, "Content-Type", contentType)
                }
            }
            collection.requests += request
        }
        return collection
    }

    private fun collectControllers(index: SpringSourceIndex, source: SpringSource): List<SpringType> =
        index.typesIn(source).filter { isController(it) }

    private fun isController(type: SpringType): Boolean =
        type.classAnnotations.any { it.name == "RestController" || it.name == "Controller" }

    private fun controllerSpec(
        index: SpringSourceIndex,
        type: SpringType,
        displayName: String = type.name,
    ): ControllerSpec? {
        val classPaths = classPaths(index, type)
        val endpoints = mutableListOf<ControllerEndpoint>()
        index.members(type).methods.forEach { method ->
            mappingAnnotations(index, method).forEach { mapping ->
                val methodPaths = mappingPaths(index, mapping).ifEmpty { listOf("") }
                val consumes = mediaTypes(index, mapping, "consumes")
                val httpMethod = httpMethodOf(index, mapping)
                val params = parseParameters(index, method.parameters, consumes)
                methodPaths.forEach { methodPath ->
                    classPaths.forEach { classPath ->
                        val joined = joinPath(classPath, methodPath)
                        val path = joined.replace(Regex("\\{(\\w+)}"), "{{$1}}")
                        endpoints += ControllerEndpoint(
                            name = method.name,
                            method = httpMethod,
                            path = path,
                            headers = params.headers,
                            query = params.query,
                            pathVariables = params.pathVariables,
                            body = params.body,
                            bodyMode = params.bodyMode,
                            formFields = params.formFields,
                            contentType = params.contentType,
                        )
                    }
                }
            }
        }
        if (endpoints.isEmpty()) return null
        return ControllerSpec(displayName, classPaths.firstOrNull().orEmpty(), endpoints)
    }

    private fun classPaths(index: SpringSourceIndex, type: SpringType, depth: Int = 0): List<String> {
        if (depth > 8) return listOf("")
        val own = index.classAnnotationsOf(type)
            .filter { it.name == "RequestMapping" }
            .flatMap { pathsOf(index, it.arguments) }
        if (own.isNotEmpty()) return own
        type.superTypes.forEach { parentName ->
            index.find(parentName)?.let { parent ->
                val inherited = classPaths(index, parent, depth + 1)
                if (inherited.any { it.isNotBlank() }) return inherited
            }
        }
        return listOf("")
    }

    private data class ParsedParameters(
        val headers: List<Pair<String, String>>,
        val query: List<Pair<String, String>>,
        val pathVariables: List<Pair<String, String>>,
        val body: String?,
        val bodyMode: String,
        val formFields: List<SpringFormField>,
        val contentType: String?,
    )

    private fun parseParameters(
        index: SpringSourceIndex,
        raw: String,
        consumes: List<String>,
    ): ParsedParameters {
        val query = mutableListOf<Pair<String, String>>()
        val headers = mutableListOf<Pair<String, String>>()
        val cookies = mutableListOf<String>()
        val pathVariables = mutableListOf<Pair<String, String>>()
        val formFields = mutableListOf<SpringFormField>()
        var bodyType: String? = null
        var bodyValue: String? = null
        var forceMultipart = false
        val media = consumes.firstOrNull()?.substringBefore(';')?.trim().orEmpty()
        val formMode = media.contains("multipart/form-data", ignoreCase = true) ||
            media.contains("x-www-form-urlencoded", ignoreCase = true)

        topLevelParameters(raw).forEach { parameter ->
            val parsed = SpringSourceIndex.extractAnnotations(parameter)
            val annotations = parsed.annotations
            val declaration = parsed.remaining
            val name = parameterName(annotations, declaration)
            val type = parameterType(declaration)
            val annotationNames = annotations.map { it.name }.toSet()
            val simple = typeSimpleName(type)
            when {
                "RequestBody" in annotationNames -> {
                    if (bodyType == null) {
                        bodyType = type
                        bodyValue = bodySample(index, type, media)
                    }
                }
                "RequestPart" in annotationNames -> {
                    forceMultipart = true
                    val partName = annotationValue(annotations, "RequestPart") ?: name
                    if (partName.isNotBlank()) {
                        val isFile = simple.contains("MultipartFile") || simple == "Part" ||
                            type.contains("MultipartFile") || type.endsWith("[]") && simple == "Part"
                        formFields += SpringFormField(
                            name = partName,
                            value = if (isFile) "" else bodySample(index, type, "application/json"),
                            file = isFile,
                        )
                    }
                }
                simple.contains("MultipartFile") || simple == "Part" -> {
                    forceMultipart = true
                    if (name.isNotBlank()) formFields += SpringFormField(name, "", file = true)
                }
                "PathVariable" in annotationNames -> {
                    if (name.isNotBlank()) pathVariables += name to sampleForType(type)
                }
                "RequestParam" in annotationNames -> {
                    if (name.isNotBlank() && simple !in mapTypes) {
                        val default = annotationAttribute(annotations, "RequestParam", "defaultValue")
                        query += name to (default ?: sampleForType(type))
                    }
                }
                "RequestHeader" in annotationNames -> {
                    val headerName = annotationValue(annotations, "RequestHeader") ?: name
                    if (headerName.isNotBlank()) {
                        val default = annotationAttribute(annotations, "RequestHeader", "defaultValue").orEmpty()
                        headers += headerName to default
                    }
                }
                "CookieValue" in annotationNames -> {
                    if (name.isNotBlank()) cookies += "$name=${sampleForType(type)}"
                }
                "ModelAttribute" in annotationNames -> {
                    addModelFields(index, type, if (formMode) formFields else null, query)
                }
                simple in ignoredParameterTypes -> Unit
                simple in mapTypes -> Unit
                simple.isBlank() -> Unit
                simple in simpleTypes -> if (name.isNotBlank()) query += name to sampleForType(type)
                else -> addModelFields(index, type, if (formMode) formFields else null, query)
            }
        }

        if (cookies.isNotEmpty()) headers += "Cookie" to cookies.joinToString("; ")

        var mode = when {
            forceMultipart -> "form-data"
            media.contains("multipart/form-data", ignoreCase = true) -> "form-data"
            media.contains("x-www-form-urlencoded", ignoreCase = true) -> "urlencoded"
            bodyType == null -> "none"
            typeSimpleName(bodyType!!) in setOf("String", "CharSequence") -> "text"
            media.contains("xml", ignoreCase = true) -> "text"
            media.startsWith("text/", ignoreCase = true) -> "text"
            else -> "json"
        }

        if ((mode == "form-data" || mode == "urlencoded") && bodyType != null && formFields.isEmpty()) {
            addModelFields(index, bodyType!!, formFields, null)
        }
        if (mode == "form-data" && formFields.isEmpty() && bodyType != null) {
            formFields += SpringFormField("body", bodySample(index, bodyType!!, "application/json"))
        }

        val body = when (mode) {
            "json" -> bodyValue ?: bodyType?.let { index.json(it) }
            "text" -> {
                if (bodyType != null && typeSimpleName(bodyType!!) in setOf("String", "CharSequence")) {
                    SpringSourceIndex.scalarText(typeSimpleName(bodyType!!)) ?: bodyValue
                } else {
                    bodyType?.let { xmlOrText(index, it, media) } ?: bodyValue
                }
            }
            else -> null
        }
        val contentType = when {
            mode == "form-data" -> null
            media.isNotBlank() -> media
            mode == "json" -> "application/json"
            mode == "urlencoded" -> "application/x-www-form-urlencoded"
            mode == "text" -> "text/plain"
            else -> null
        }
        return ParsedParameters(
            headers = headers,
            query = query,
            pathVariables = pathVariables,
            body = body,
            bodyMode = mode,
            formFields = formFields,
            contentType = contentType,
        )
    }

    private fun addModelFields(
        index: SpringSourceIndex,
        type: String,
        formFields: MutableList<SpringFormField>?,
        query: MutableList<Pair<String, String>>?,
    ) {
        if (typeSimpleName(type) in mapTypes) return
        val fields = index.fields(type).filter { it.type !in mapTypes && it.type !in collectionTypes }
        if (fields.isEmpty()) return
        fields.forEach { field ->
            val value = sampleForType(field.type)
            if (formFields != null) {
                formFields += SpringFormField(field.jsonName, value)
            } else {
                query?.add(field.jsonName to value)
            }
        }
    }

    private fun bodySample(index: SpringSourceIndex, type: String, media: String): String {
        val normalized = type.trim().removePrefix("?").trimEnd('?', '!')
        if (normalized.endsWith("[]")) {
            return "[\n    " + bodySample(index, normalized.removeSuffix("[]"), media) + "\n  ]"
        }
        val raw = SpringSourceIndex.simpleName(normalized)
        SpringSourceIndex.scalarJson(raw)?.let { return it }
        if (media.contains("xml", ignoreCase = true)) return index.xml(normalized)
        if (raw in collectionTypes) {
            val element = SpringSourceIndex.typeArguments(normalized).firstOrNull() ?: return "[]"
            return "[\n    " + bodySample(index, element, media) + "\n  ]"
        }
        return index.json(normalized)
    }

    private fun xmlOrText(index: SpringSourceIndex, type: String, media: String): String =
        if (media.contains("xml", ignoreCase = true)) index.xml(type) else sampleForType(type)

    private fun mappingAnnotations(index: SpringSourceIndex, method: SpringMethod): List<SpringAnnotation> =
        method.annotations.filter { annotation ->
            annotation.name in standardMappings || index.mappingKind(annotation.name) != null
        }

    private fun httpMethodOf(index: SpringSourceIndex, annotation: SpringAnnotation): String {
        when (annotation.name) {
            "GetMapping" -> return "GET"
            "PostMapping" -> return "POST"
            "PutMapping" -> return "PUT"
            "PatchMapping" -> return "PATCH"
            "DeleteMapping" -> return "DELETE"
            "RequestMapping" -> {
                val declared = annotationAttribute(listOf(annotation), "RequestMapping", "method")
                val token = declared?.let { Regex("(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE)").find(it)?.groupValues?.get(1) }
                return token ?: "GET"
            }
        }
        return index.mappingKind(annotation.name) ?: "GET"
    }

    private fun mappingPaths(index: SpringSourceIndex, annotation: SpringAnnotation): List<String> =
        pathsOf(index, annotation.arguments)

    private fun pathsOf(index: SpringSourceIndex, arguments: String): List<String> {
        if (arguments.isBlank()) return emptyList()
        val args = parseArguments(arguments)
        val value = args.named["value"] ?: args.named["path"] ?: args.positional.firstOrNull()
        return stringValues(index, value.orEmpty())
    }

    private fun mediaTypes(index: SpringSourceIndex, annotation: SpringAnnotation, attribute: String): List<String> {
        val args = parseArguments(annotation.arguments)
        val raw = args.named[attribute] ?: return emptyList()
        return stringValues(index, raw).map { it.substringBefore(';').trim() }.filter { it.isNotEmpty() }
    }

    private fun stringValues(index: SpringSourceIndex, expression: String): List<String> {
        var text = expression.trim()
        if (text.isEmpty()) return emptyList()
        if ((text.startsWith("{") && text.endsWith("}")) || (text.startsWith("[") && text.endsWith("]"))) {
            return SpringSourceIndex.splitTopLevel(text.substring(1, text.length - 1), ',')
                .flatMap { stringValues(index, it) }
        }
        Regex("^(?:listOf|arrayOf|setOf|mutableListOf|listOfNotNull)\\s*\\(([\\s\\S]*)\\)$").find(text)?.let { match ->
            return SpringSourceIndex.splitTopLevel(match.groupValues[1], ',')
                .flatMap { stringValues(index, it) }
        }
        if (text.startsWith("\"")) {
            val literal = stringLiteral.find(text)?.groupValues?.get(1) ?: return emptyList()
            val plus = text.substringAfter('"').trim()
            return if (plus.startsWith("+")) {
                listOf(literal) + stringValues(index, plus.removePrefix("+").trim())
            } else {
                listOf(literal)
            }
        }
        if (text.contains("+")) {
            return text.split('+').flatMap { stringValues(index, it) }
        }
        return index.constant(text)?.let { listOf(it) } ?: emptyList()
    }

    private data class ArgumentList(val named: Map<String, String>, val positional: List<String>)

    private fun parseArguments(raw: String): ArgumentList {
        val named = LinkedHashMap<String, String>()
        val positional = mutableListOf<String>()
        SpringSourceIndex.splitTopLevel(raw, ',').forEach { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) return@forEach
            val match = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([\\s\\S]*)$").find(trimmed)
            if (match != null) {
                named[match.groupValues[1]] = match.groupValues[2].trim()
            } else {
                positional += trimmed
            }
        }
        return ArgumentList(named, positional)
    }

    private fun annotationAttribute(
        annotations: List<SpringAnnotation>,
        annotationName: String,
        attribute: String,
    ): String? {
        val annotation = annotations.firstOrNull { it.name == annotationName } ?: return null
        val args = parseArguments(annotation.arguments)
        args.named[attribute]?.let { return stringLiteral.find(it)?.groupValues?.get(1) ?: it }
        if (attribute == "value") {
            return args.positional.firstOrNull()?.let {
                stringLiteral.find(it)?.groupValues?.get(1) ?: it
            }
        }
        return null
    }

    private fun annotationValue(annotations: List<SpringAnnotation>, annotationName: String): String? {
        val annotation = annotations.firstOrNull { it.name == annotationName } ?: return null
        val args = parseArguments(annotation.arguments)
        val raw = args.named["value"] ?: args.named["name"] ?: args.positional.firstOrNull() ?: return null
        return stringLiteral.find(raw)?.groupValues?.get(1) ?: raw
    }

    private fun topLevelParameters(raw: String): List<String> =
        SpringSourceIndex.splitTopLevel(raw, ',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun parameterName(annotations: List<SpringAnnotation>, declaration: String): String {
        listOf("RequestParam", "PathVariable", "RequestHeader", "CookieValue", "RequestPart").forEach { name ->
            annotationValue(annotations, name)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val colon = declaration.indexOf(':')
        val head = if (colon >= 0) declaration.substring(0, colon) else declaration
        return Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*$").find(head.trim())?.groupValues?.get(1).orEmpty()
    }

    private fun parameterType(declaration: String): String {
        val colon = declaration.indexOf(':')
        if (colon >= 0) {
            return declaration.substring(colon + 1).trim().removeSuffix("...")
        }
        // Java: everything before the trailing parameter name, so generics keep their commas and spaces.
        return Regex("^(.+?)\\s+[A-Za-z_][A-Za-z0-9_]*\\s*$").find(declaration.trim())
            ?.groupValues?.get(1)?.trim()?.removeSuffix("...")
            ?: declaration.split(Regex("\\s+")).firstOrNull().orEmpty()
    }

    private fun typeSimpleName(type: String): String = SpringSourceIndex.simpleName(type)

    private fun joinPath(base: String, path: String): String {
        val parts = listOf(base, path)
            .map { it.trim().trim('/') }
            .filter { it.isNotEmpty() }
        return "/" + parts.joinToString("/")
    }

    private fun sampleForType(type: String): String {
        val normalized = typeSimpleName(type)
        SpringSourceIndex.scalarText(normalized)?.let { return it }
        return "string"
    }

    private val collectionTypes = SpringSourceIndex.collectionTypes
    private val mapTypes = SpringSourceIndex.mapTypes
}
