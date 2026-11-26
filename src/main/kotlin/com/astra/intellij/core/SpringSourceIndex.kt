package com.astra.intellij.core

/**
 * One source file known to [SpringSourceIndex]. The controller file itself is always included;
 * extra files let DTOs declared in other files resolve during generation.
 */
data class SpringSource(val name: String, val text: String) {
    internal val masked: String by lazy { SpringSourceIndex.maskSource(text) }
    internal val kotlin: Boolean
        get() = name.endsWith(".kt", ignoreCase = true) ||
            (!name.contains('.') && Regex("\\bfun\\s+\\w+\\s*\\(").containsMatchIn(masked))
}

internal data class SpringAnnotation(
    val name: String,
    val arguments: String,
    val start: Int,
    val end: Int,
)

internal data class SpringField(
    val name: String,
    val type: String,
    val jsonName: String,
)

/**
 * Lightweight type index built from Java / Kotlin source text. It deliberately avoids the PSI so
 * generation works before indexing finishes and stays unit-testable.
 */
internal class SpringSourceIndex(sources: List<SpringSource>) {
    private val types = LinkedHashMap<String, SpringType>()
    private val fieldsCache = HashMap<String, List<SpringField>>()
    private val constants = HashMap<String, String>()
    private val customMappings = HashMap<String, String>()

    init {
        sources.forEach { source ->
            scanDeclarations(source).forEach { type ->
                types.putIfAbsent(type.name, type)
                if (type.kind == SpringTypeKind.ANNOTATION) {
                    kindOfAnnotations(type.classAnnotations)?.let { customMappings.putIfAbsent(type.name, it) }
                }
            }
            scanConstants(source).forEach { (name, value) -> constants.putIfAbsent(name, value) }
        }
    }

    fun find(name: String): SpringType? = types[name]

    fun typesIn(source: SpringSource): List<SpringType> = types.values.filter { it.source === source }

    fun mappingKind(annotationName: String): String? = when (annotationName) {
        "GetMapping" -> "GET"
        "PostMapping" -> "POST"
        "PutMapping" -> "PUT"
        "PatchMapping" -> "PATCH"
        "DeleteMapping" -> "DELETE"
        else -> customMappings[annotationName]
    }

    fun constant(name: String): String? = constants[name] ?: constants[name.substringAfterLast('.')]

    /** Renders a JSON sample for [type]; generic arguments are substituted into fields. */
    fun json(type: String, visiting: Set<String> = emptySet()): String = renderJson(type, 0, emptyMap(), visiting)

    /** Renders a minimal XML sample for a DTO, used when the mapping consumes XML. */
    fun xml(type: String): String {
        val raw = simpleName(type)
        val body = renderXml(type, 1, emptyMap(), emptySet())
        return if (body.isBlank()) "<$raw/>" else "<$raw>\n$body\n</$raw>"
    }

    fun fields(type: String): List<SpringField> {
        val raw = simpleName(type)
        return fieldsOf(types[raw] ?: return emptyList())
    }

    fun firstEnumConstant(type: String): String? {
        val info = types[simpleName(type)] ?: return null
        if (info.kind != SpringTypeKind.ENUM) return null
        if (info.bodyStart < 0) return null
        val body = info.source.text.substring(info.bodyStart, info.bodyEnd)
        return body.substringBefore(';')
            .split(',')
            .map { it.trim().removePrefix("@") }
            .firstOrNull { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
    }

    private fun renderJson(
        type: String,
        depth: Int,
        bindings: Map<String, String>,
        visiting: Set<String>,
    ): String {
        if (depth > 8) return "{}"
        val normalized = type.trim().removePrefix("?").trimEnd('?', '!')
        if (normalized.isBlank()) return "{}"
        if (normalized.endsWith("[]")) {
            return arrayOf(renderJson(normalized.removeSuffix("[]"), depth + 1, bindings, visiting), depth)
        }
        val raw = simpleName(normalized)
        if (raw.isBlank()) return "{}"
        val args = typeArguments(normalized)

        if (args.isEmpty()) bindings[raw]?.let { return renderJson(it, depth + 1, bindings, visiting) }
        scalarJson(raw)?.let { return it }
        if (raw in collectionTypes) {
            val element = args.firstOrNull() ?: return "[]"
            return arrayOf(renderJson(element, depth + 1, bindings, visiting), depth)
        }
        if (raw in mapTypes) {
            val value = args.getOrNull(1) ?: return "{}"
            return "{\n${pad(depth + 1)}\"key\": ${renderJson(value, depth + 1, bindings, visiting)}\n${pad(depth)}}"
        }
        if (raw in wrapperTypes) {
            return renderJson(args.firstOrNull() ?: "Object", depth + 1, bindings, visiting)
        }

        val info = types[raw]
        if (info == null) {
            val inner = args.firstOrNull() ?: return "{}"
            return renderJson(inner, depth + 1, bindings, visiting)
        }
        if (info.kind == SpringTypeKind.ENUM) {
            val constant = firstEnumConstant(raw) ?: return "\"$raw\""
            return "\"$constant\""
        }
        if (raw in visiting) return "{}"
        val classBindings = if (info.typeParameters.isEmpty()) {
            bindings
        } else {
            bindings + info.typeParameters.zip(args).filter { it.second.isNotBlank() }.toMap()
        }
        val fields = fieldsOf(info)
        if (fields.isEmpty()) return "{}"
        val nextVisiting = visiting + raw
        return buildString {
            append("{\n")
            fields.forEachIndexed { index, field ->
                append(pad(depth + 1)).append('"').append(field.jsonName).append("\": ")
                append(renderJson(field.type, depth + 1, classBindings, nextVisiting))
                if (index < fields.lastIndex) append(',')
                append('\n')
            }
            append(pad(depth)).append('}')
        }
    }

    private fun renderXml(
        type: String,
        depth: Int,
        bindings: Map<String, String>,
        visiting: Set<String>,
    ): String {
        if (depth > 8) return ""
        val normalized = type.trim().removePrefix("?").trimEnd('?', '!')
        val raw = simpleName(normalized)
        if (raw.isBlank()) return ""
        val args = typeArguments(normalized)
        if (args.isEmpty()) bindings[raw]?.let { return renderXml(it, depth, bindings, visiting) }
        scalarText(raw)?.let { return it }
        if (raw in collectionTypes) {
            val element = args.firstOrNull() ?: return ""
            val item = simpleName(element)
            val inner = renderXml(element, depth + 1, bindings, visiting)
            return if (inner.isBlank()) "<item/>" else "<item>\n$inner\n${pad(depth - 1)}</item>"
        }
        if (raw in mapTypes) return ""
        val info = types[raw] ?: return scalarText(raw).orEmpty()
        if (raw in visiting) return ""
        if (info.kind == SpringTypeKind.ENUM) return firstEnumConstant(raw) ?: ""
        val classBindings = if (info.typeParameters.isEmpty()) {
            bindings
        } else {
            bindings + info.typeParameters.zip(args).filter { it.second.isNotBlank() }.toMap()
        }
        val nextVisiting = visiting + raw
        return buildString {
            fieldsOf(info).forEachIndexed { index, field ->
                val tag = field.jsonName
                val inner = renderXml(field.type, depth + 1, classBindings, nextVisiting)
                if (inner.isBlank()) {
                    append(pad(depth)).append("<").append(tag).append("/>")
                } else if (inner.contains('\n')) {
                    append(pad(depth)).append("<").append(tag).append(">\n")
                    append(inner)
                    append('\n').append(pad(depth)).append("</").append(tag).append(">")
                } else {
                    append(pad(depth)).append("<").append(tag).append(">")
                    append(inner)
                    append("</").append(tag).append(">")
                }
                if (index < fieldsOf(info).lastIndex) append('\n')
            }
        }
    }

    private fun fieldsOf(info: SpringType): List<SpringField> {
        fieldsCache[info.name]?.let { return it }
        val result = mutableListOf<SpringField>()
        info.superTypes.forEach { parent ->
            types[parent]?.let { result += fieldsOf(it) }
        }
        val source = info.source
        val isKotlin = source.kotlin
        info.constructorParams?.let { params ->
            splitTopLevel(params, ',').forEach { param ->
                parseParameterField(param, isKotlin)?.let { result += it }
            }
        }
        if (info.bodyStart >= 0) {
            scanMembers(info).fields.forEach { result += it }
        }
        val deduped = result.distinctBy { it.jsonName }
        fieldsCache[info.name] = deduped
        return deduped
    }

    private fun parseParameterField(raw: String, isKotlin: Boolean): SpringField? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        val annotations = extractAnnotations(text)
        val jsonName = annotations.jsonName()
        text = annotations.remaining
        if (isKotlin) {
            val match = Regex("\\b(?:val|var)\\s+(\\w+)\\s*:\\s*(.+)").find(text) ?: return null
            val type = match.groupValues[2].substringBefore('=').trim()
            return SpringField(match.groupValues[1], type, jsonName ?: match.groupValues[1])
        }
        return parseJavaField(text, jsonName)
    }

    private fun scanMembers(info: SpringType): MemberScan {
        val source = info.source
        val text = source.text
        val masked = source.masked
        val methods = mutableListOf<SpringMethod>()
        val fields = mutableListOf<SpringField>()
        var index = info.bodyStart
        if (index < 0) return MemberScan(methods, fields)
        val end = info.bodyEnd
        var pending = mutableListOf<SpringAnnotation>()
        while (index < end) {
            when {
                masked[index].isWhitespace() -> index++
                masked[index] == '@' -> {
                    val annotation = readAnnotation(source, masked, index, end) ?: run {
                        index++
                        null
                    }
                    if (annotation != null) {
                        pending += annotation
                        index = annotation.end
                    }
                }
                masked[index] == ';' -> {
                    pending.clear()
                    index++
                }
                masked[index] == '{' -> {
                    index = (matching(masked, index, '{', '}') ?: end) + 1
                    pending.clear()
                }
                else -> {
                    val start = index
                    var round = 0
                    var angle = 0
                    var square = 0
                    var brace = -1
                    var semicolon = -1
                    var lineEnd = -1
                    var cursor = index
                    while (cursor < end) {
                        val char = masked[cursor]
                        when {
                            char == '(' -> round++
                            char == ')' -> round--
                            char == '<' -> angle++
                            char == '>' -> angle--
                            char == '[' -> square++
                            char == ']' -> square--
                            char == '{' && round == 0 && angle == 0 && square == 0 -> {
                                brace = cursor
                                break
                            }
                            char == ';' && round == 0 && angle == 0 && square == 0 -> {
                                semicolon = cursor
                                break
                            }
                            char == '\n' && round == 0 && angle == 0 && square == 0 && source.kotlin -> {
                                lineEnd = cursor
                                break
                            }
                        }
                        cursor++
                    }
                    val declaration = text.substring(start, if (brace >= 0) brace else if (semicolon >= 0) semicolon else if (lineEnd >= 0) lineEnd else end)
                    methodSignature(declaration)?.let { signature ->
                        if (pending.any { it.name.isNotEmpty() }) {
                            methods += SpringMethod(signature.name, signature.parameters, pending.toList())
                        }
                    } ?: parseFieldDeclaration(declaration, source.kotlin, pending)?.let { fields += it }
                    index = when {
                        brace >= 0 -> (matching(masked, brace, '{', '}') ?: end) + 1
                        semicolon >= 0 -> semicolon + 1
                        lineEnd >= 0 -> lineEnd + 1
                        else -> end
                    }
                    pending = mutableListOf()
                }
            }
        }
        return MemberScan(methods, fields)
    }

    private fun parseFieldDeclaration(
        declaration: String,
        isKotlin: Boolean,
        annotations: List<SpringAnnotation>,
    ): SpringField? {
        val jsonName = annotations.jsonName()
        if (isKotlin) {
            val match = Regex("\\b(?:val|var)\\s+(\\w+)\\s*:\\s*([^=]+)").find(declaration) ?: return null
            val type = match.groupValues[2].trim()
            if (type.isEmpty()) return null
            return SpringField(match.groupValues[1], type, jsonName ?: match.groupValues[1])
        }
        if (Regex("\\b(?:static|transient)\\b").containsMatchIn(declaration)) return null
        return parseJavaField(declaration, jsonName)
    }

    private fun parseJavaField(declaration: String, jsonName: String?): SpringField? {
        var text = declaration.trim()
        if (text.isEmpty() || text.contains('(')) return null
        text = text.replace(Regex("^(?:(?:public|protected|private|final|volatile)\\s+)+"), "")
        if (text.startsWith("return") || text.startsWith("class ") || text.startsWith("enum ")) return null
        val collapsed = text.replace(Regex("\\s+"), " ").trim()
        val separator = collapsed.lastIndexOf(' ')
        if (separator <= 0) return null
        val type = collapsed.substring(0, separator).trim()
        val name = collapsed.substring(separator + 1).trim()
        if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return null
        if (type.isEmpty() || type in keywordTypes) return null
        return SpringField(name, type, jsonName ?: name)
    }

    private fun scanDeclarations(source: SpringSource): List<SpringType> {
        val masked = source.masked
        val result = mutableListOf<SpringType>()
        val pattern = Regex("(?<![\\w.$])(class|interface|record|enum(?!\\s+class))\\s+(\\w+)")
        pattern.findAll(masked).forEach { match ->
            var kind = when (match.groupValues[1]) {
                "class" -> SpringTypeKind.CLASS
                "interface" -> SpringTypeKind.INTERFACE
                "record" -> SpringTypeKind.RECORD
                else -> SpringTypeKind.ENUM
            }
            if (kind == SpringTypeKind.CLASS) {
                val prefix = masked.substring(maxOf(0, match.range.first - 12), match.range.first).trimEnd()
                if (prefix.endsWith("enum")) kind = SpringTypeKind.ENUM
            }
            readType(source, kind, match.groupValues[2], match.range.last + 1)?.let { result += it }
        }
        Regex("@interface\\s+(\\w+)").findAll(masked).forEach { match ->
            readType(source, SpringTypeKind.ANNOTATION, match.groupValues[1], match.range.last + 1)
                ?.let { result += it }
        }
        return result
    }

    private fun readType(source: SpringSource, kind: SpringTypeKind, name: String, nameEnd: Int): SpringType? {
        val text = source.text
        val masked = source.masked
        var cursor = skipSpace(masked, nameEnd)
        val typeParameters = mutableListOf<String>()
        if (masked.getOrNull(cursor) == '<') {
            val close = matchingAngle(masked, cursor) ?: return null
            typeParameters += splitTopLevel(text.substring(cursor + 1, close), ',')
                .map { it.trim().substringBefore(' ').substringBefore(':') }
                .filter { it.isNotEmpty() }
            cursor = skipSpace(masked, close + 1)
        }
        var constructorParams: String? = null
        if (masked.getOrNull(cursor) == '(') {
            val close = matching(masked, cursor, '(', ')') ?: return null
            constructorParams = text.substring(cursor + 1, close)
            cursor = skipSpace(masked, close + 1)
        }
        var round = 0
        var angle = 0
        var square = 0
        var bodyStart = -1
        var scan = cursor
        while (scan < masked.length) {
            val char = masked[scan]
            when {
                char == '(' -> round++
                char == ')' -> round--
                char == '<' -> angle++
                char == '>' -> angle--
                char == '[' -> square++
                char == ']' -> square--
                char == '{' && round == 0 && angle == 0 && square == 0 -> {
                    bodyStart = scan
                    break
                }
                char == ';' && round == 0 && angle == 0 && square == 0 -> break
                char == '\n' && round == 0 && angle == 0 && square == 0 && source.kotlin -> {
                    if (kind != SpringTypeKind.ENUM) break
                }
            }
            scan++
        }
        val headerEnd = if (bodyStart >= 0) bodyStart else scan
        val header = text.substring(cursor, headerEnd)
        val bodyEnd = if (bodyStart >= 0) (matching(masked, bodyStart, '{', '}') ?: masked.length) else -1
        val superTypes = superTypesOf(header)
        return SpringType(
            name = name,
            kind = kind,
            source = source,
            nameStart = nameEnd - name.length,
            typeParameters = typeParameters,
            constructorParams = constructorParams,
            bodyStart = if (bodyStart >= 0) bodyStart + 1 else -1,
            bodyEnd = if (bodyEnd >= 0) bodyEnd else -1,
            superTypes = superTypes,
            classAnnotations = classAnnotations(source, nameEnd - name.length),
        )
    }

    private fun classAnnotations(source: SpringSource, nameStart: Int): List<SpringAnnotation> {
        val masked = source.masked
        var from = 0
        var index = nameStart - 1
        while (index >= 0) {
            if (masked[index] == '}' || masked[index] == ';') {
                from = index + 1
                break
            }
            index--
        }
        return annotationsIn(source, masked, from, nameStart)
    }

    private fun kindOfAnnotations(annotations: List<SpringAnnotation>): String? {
        annotations.forEach { annotation ->
            when (annotation.name) {
                "GetMapping" -> return "GET"
                "PostMapping" -> return "POST"
                "PutMapping" -> return "PUT"
                "PatchMapping" -> return "PATCH"
                "DeleteMapping" -> return "DELETE"
                "RequestMapping" -> return "GET"
            }
        }
        return null
    }

    private fun scanConstants(source: SpringSource): Map<String, String> {
        val result = HashMap<String, String>()
        Regex("(?:(?:static\\s+final|const)\\s+String\\s+(\\w+)|const\\s+val\\s+(\\w+))\\s*=\\s*\"([^\"]*)\"")
            .findAll(source.text)
            .forEach { match ->
                val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
                if (name.isNotEmpty()) result.putIfAbsent(name, match.groupValues[3])
            }
        return result
    }

    private fun superTypesOf(header: String): List<String> {
        val result = mutableListOf<String>()
        Regex("\\bextends\\s+([^{]+?)(?:\\bimplements\\b|$)").find(header)?.groupValues?.get(1)?.let {
            splitTopLevel(it, ',').forEach { part -> result += simpleName(part) }
        }
        Regex("\\bimplements\\s+([^{]+)$").find(header)?.groupValues?.get(1)?.let {
            splitTopLevel(it, ',').forEach { part -> result += simpleName(part) }
        }
        val kotlinInheritance = Regex(":(?![^()]*\\))\\s*([^{]+)$").find(header)?.groupValues?.get(1)
        kotlinInheritance?.let {
            splitTopLevel(it, ',').forEach { part -> result += simpleName(part.substringBefore('(')) }
        }
        return result.map { it.trim() }.filter {
            it.isNotEmpty() && it !in setOf("Object", "Any", "Serializable", "Comparable", "Enum")
        }.distinct()
    }

    internal data class MemberScan(val methods: List<SpringMethod>, val fields: List<SpringField>)

    internal fun members(type: SpringType): MemberScan = scanMembers(type)

    internal fun classAnnotationsOf(type: SpringType): List<SpringAnnotation> = type.classAnnotations

    companion object {
        val collectionTypes = setOf(
            "List", "Set", "Collection", "Iterable", "ArrayList", "LinkedList", "HashSet", "Queue", "Deque",
        )
        val mapTypes = setOf("Map", "HashMap", "LinkedHashMap", "TreeMap", "ConcurrentHashMap")
        val wrapperTypes = setOf("Optional", "ResponseEntity", "HttpEntity")
        val keywordTypes = setOf(
            "return", "new", "if", "else", "for", "while", "switch", "case", "break", "continue",
            "public", "private", "protected", "static", "final", "class", "interface", "enum", "record",
        )

        fun simpleName(type: String): String =
            type.substringBefore('<').substringAfterLast('.').trim().trimEnd('?', '!', '[', ']')

        fun typeArguments(type: String): List<String> {
            val open = type.indexOf('<')
            if (open < 0) return emptyList()
            var depth = 0
            var end = -1
            for (index in open until type.length) {
                when (type[index]) {
                    '<' -> depth++
                    '>' -> {
                        depth--
                        if (depth == 0) {
                            end = index
                            break
                        }
                    }
                }
            }
            if (end < 0) return emptyList()
            return splitTopLevel(type.substring(open + 1, end), ',').map { it.trim() }.filter { it.isNotEmpty() }
        }

        fun scalarJson(raw: String): String? = when (raw) {
            "String", "CharSequence" -> "\"string\""
            "Char", "char" -> "\"a\""
            "Int", "Integer", "Long", "Short", "Byte", "BigInteger",
            "int", "long", "short", "byte" -> "0"
            "Double", "Float", "BigDecimal", "double", "float" -> "0.0"
            "Boolean", "boolean" -> "true"
            "LocalDate" -> "\"2024-01-01\""
            "LocalDateTime", "Instant", "OffsetDateTime", "ZonedDateTime" -> "\"2024-01-01T00:00:00Z\""
            "UUID" -> "\"00000000-0000-4000-8000-000000000000\""
            "Object" -> "{}"
            else -> null
        }

        fun scalarText(raw: String): String? = when (raw) {
            "String", "CharSequence", "Char", "char" -> "string"
            "Int", "Integer", "Long", "Short", "Byte", "BigInteger",
            "int", "long", "short", "byte" -> "0"
            "Double", "Float", "BigDecimal", "double", "float" -> "0.0"
            "Boolean", "boolean" -> "true"
            "LocalDate" -> "2024-01-01"
            "LocalDateTime", "Instant", "OffsetDateTime", "ZonedDateTime" -> "2024-01-01T00:00:00Z"
            "UUID" -> "00000000-0000-4000-8000-000000000000"
            else -> null
        }

        private fun pad(depth: Int): String = "  ".repeat(depth)

        private fun arrayOf(element: String, depth: Int): String {
            if (element == "{}" || element.isBlank()) return "[]"
            return "[\n${pad(depth + 1)}$element\n${pad(depth)}]"
        }

        fun skipSpace(text: String, from: Int): Int {
            var index = from
            while (index < text.length && text[index].isWhitespace()) index++
            return index
        }

        fun annotationsIn(source: SpringSource, masked: String, from: Int, to: Int): List<SpringAnnotation> {
            val result = mutableListOf<SpringAnnotation>()
            var index = from
            while (index < to) {
                if (masked[index] == '@' && masked.getOrNull(index - 1) != '@') {
                    val annotation = readAnnotation(source, masked, index, to) ?: run {
                        index++
                        null
                    }
                    if (annotation != null) {
                        result += annotation
                        index = annotation.end
                    }
                } else {
                    index++
                }
            }
            return result
        }

        fun extractAnnotations(raw: String): AnnotationText {
            var text = raw
            val annotations = mutableListOf<SpringAnnotation>()
            while (true) {
                val trimmed = text.trimStart()
                if (!trimmed.startsWith("@")) break
                val source = SpringSource("inline", trimmed)
                val annotation = readAnnotation(source, trimmed, 0, trimmed.length) ?: break
                annotations += annotation
                text = trimmed.substring(annotation.end)
            }
            return AnnotationText(annotations, text.trim())
        }

        fun readAnnotation(source: SpringSource, masked: String, start: Int, limit: Int): SpringAnnotation? {
            if (masked.getOrNull(start) != '@') return null
            var index = start + 1
            val nameStart = index
            while (index < limit && (masked[index].isLetterOrDigit() || masked[index] == '_' || masked[index] == '.')) {
                index++
            }
            val fullName = masked.substring(nameStart, index)
            if (fullName.isBlank()) return null
            val simpleName = fullName.substringAfterLast('.')
            val argsStart = skipSpace(masked, index)
            if (argsStart < limit && masked[argsStart] == '(') {
                val close = matching(masked, argsStart, '(', ')') ?: return null
                return SpringAnnotation(simpleName, source.text.substring(argsStart + 1, close), start, close + 1)
            }
            return SpringAnnotation(simpleName, "", start, index)
        }

        fun methodSignature(declaration: String): SpringMethodSignature? {
            var round = 0
            var angle = 0
            val text = declaration.trim()
            for (index in text.indices) {
                when (text[index]) {
                    '<' -> angle++
                    '>' -> angle--
                    '(' -> {
                        if (round == 0 && angle == 0) {
                            val prefix = text.substring(0, index)
                            val name = Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*$").find(prefix)?.groupValues?.get(1)
                                ?: return null
                            val close = matching(text, index, '(', ')') ?: return null
                            return SpringMethodSignature(name, text.substring(index + 1, close))
                        }
                        round++
                    }
                    ')' -> round--
                }
            }
            return null
        }

        fun splitTopLevel(text: String, separator: Char): List<String> {
            val parts = mutableListOf<String>()
            val buffer = StringBuilder()
            var round = 0
            var angle = 0
            var square = 0
            var curly = 0
            var quote = 0.toChar()
            text.forEach { char ->
                when {
                    quote != 0.toChar() -> {
                        buffer.append(char)
                        if (char == quote && buffer.length >= 2 && buffer[buffer.length - 2] != '\\') quote = 0.toChar()
                    }
                    char == '"' -> {
                        quote = char
                        buffer.append(char)
                    }
                    char == '(' -> { round++; buffer.append(char) }
                    char == ')' -> { round--; buffer.append(char) }
                    char == '<' -> { angle++; buffer.append(char) }
                    char == '>' -> { angle--; buffer.append(char) }
                    char == '[' -> { square++; buffer.append(char) }
                    char == ']' -> { square--; buffer.append(char) }
                    char == '{' -> { curly++; buffer.append(char) }
                    char == '}' -> { curly--; buffer.append(char) }
                    char == separator && round == 0 && angle == 0 && square == 0 && curly == 0 -> {
                        parts += buffer.toString()
                        buffer.setLength(0)
                    }
                    else -> buffer.append(char)
                }
            }
            parts += buffer.toString()
            return parts
        }

        fun matching(text: String, open: Int, start: Char, end: Char): Int? {
            var depth = 0
            var index = open
            while (index < text.length) {
                when (text[index]) {
                    start -> depth++
                    end -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
                index++
            }
            return null
        }

        fun matchingAngle(text: String, open: Int): Int? = matching(text, open, '<', '>')

        /** Blanks comments and string literals so structural scanning cannot be confused by them. */
        fun maskSource(text: String): String {
            val chars = text.toCharArray()
            var index = 0
            fun blank(from: Int, to: Int) {
                for (position in from until to.coerceAtMost(chars.size)) {
                    if (chars[position] != '\n') chars[position] = ' '
                }
            }
            while (index < chars.size) {
                val char = text[index]
                when {
                    char == '/' && text.getOrNull(index + 1) == '/' -> {
                        val end = text.indexOf('\n', index).let { if (it < 0) chars.size else it }
                        blank(index, end)
                        index = end
                    }
                    char == '/' && text.getOrNull(index + 1) == '*' -> {
                        val end = text.indexOf("*/", index + 2).let { if (it < 0) chars.size else it + 2 }
                        blank(index, end)
                        index = end
                    }
                    char == '"' && text.startsWith("\"\"\"", index) -> {
                        val end = text.indexOf("\"\"\"", index + 3).let { if (it < 0) chars.size else it + 3 }
                        blank(index, end)
                        index = end
                    }
                    char == '"' -> {
                        var cursor = index + 1
                        while (cursor < chars.size) {
                            if (text[cursor] == '\\') {
                                cursor += 2
                                continue
                            }
                            if (text[cursor] == '"') break
                            cursor++
                        }
                        blank(index, cursor + 1)
                        index = cursor + 1
                    }
                    char == '\'' -> {
                        var cursor = index + 1
                        while (cursor < chars.size) {
                            if (text[cursor] == '\\') {
                                cursor += 2
                                continue
                            }
                            if (text[cursor] == '\'') break
                            cursor++
                        }
                        blank(index, cursor + 1)
                        index = cursor + 1
                    }
                    else -> index++
                }
            }
            return String(chars)
        }
    }
}

internal data class AnnotationText(val annotations: List<SpringAnnotation>, val remaining: String) {
    fun jsonName(): String? = annotations.jsonName()
}

internal fun List<SpringAnnotation>.jsonName(): String? = firstOrNull {
    it.name == "JsonProperty" || it.name == "SerialName" || it.name == "SerializedName"
}?.let { annotation ->
    Regex("\"([^\"]*)\"").find(annotation.arguments)?.groupValues?.get(1)
}

internal data class SpringMethodSignature(val name: String, val parameters: String)

internal data class SpringMethod(
    val name: String,
    val parameters: String,
    val annotations: List<SpringAnnotation>,
)

internal data class SpringType(
    val name: String,
    val kind: SpringTypeKind,
    val source: SpringSource,
    val nameStart: Int,
    val typeParameters: List<String>,
    val constructorParams: String?,
    val bodyStart: Int,
    val bodyEnd: Int,
    val superTypes: List<String>,
    val classAnnotations: List<SpringAnnotation>,
)

internal enum class SpringTypeKind { CLASS, INTERFACE, RECORD, ENUM, ANNOTATION }
