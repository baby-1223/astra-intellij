package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.AuthConfig
import com.astra.intellij.model.KeyValue
import com.astra.intellij.model.RequestCollection
import com.astra.intellij.model.RequestFolder
import com.astra.intellij.model.allFolders
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.util.Locale

data class ApiImport(
    val collection: RequestCollection,
    val environment: AstraEnvironment?,
)

/**
 * Imports OpenAPI 3.x / Swagger 2.0 JSON and exports OpenAPI 3.0 JSON.
 */
class OpenApiService {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** Accepts OpenAPI / Swagger JSON and YAML documents. */
    fun parse(json: String): ApiImport {
        val root = JsonParser.parseString(YamlSupport.normalizeToJson(json)).asJsonObject
        val title = root.obj("info")?.string("title")?.ifBlank { null } ?: "Imported API"
        val collection = RequestCollection(name = title)
        val server = resolveServer(root)
        val environment = server?.let {
            AstraEnvironment(
                name = "$title Environment",
                variables = linkedMapOf("baseUrl" to it),
            )
        }
        val base = if (server != null) "{{baseUrl}}" else ""
        val folders = LinkedHashMap<String, RequestFolder>()

        root.obj("paths")?.entrySet()?.forEach { (path, element) ->
            if (!element.isJsonObject) return@forEach
            val pathItem = element.asJsonObject
            HTTP_METHODS.forEach { method ->
                val operation = pathItem.obj(method) ?: return@forEach
                val request = buildRequest(root, method, path, pathItem, operation, base)
                val tag = operation.array("tags")
                    ?.firstOrNull()
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                if (tag.isNullOrBlank()) {
                    collection.requests.add(request)
                } else {
                    folders.getOrPut(tag) { RequestFolder(name = tag) }.requests.add(request)
                }
            }
        }
        collection.folders.addAll(folders.values)
        return ApiImport(collection, environment)
    }

    fun export(collections: List<RequestCollection>, title: String): String {
        val paths = JsonObject()
        val schemes = JsonObject()
        var server: String? = null

        fun register(request: AstraRequest, tag: String?) {
            val (requestServer, rawPath) = splitUrl(request.url)
            if (server.isNullOrBlank() && !requestServer.isNullOrBlank()) server = requestServer
            val pathKey = normalizePath(rawPath)
            val method = request.method.lowercase(Locale.ROOT)
            val pathItem = paths.get(pathKey)?.takeIf { it.isJsonObject }?.asJsonObject
                ?: JsonObject().also { paths.add(pathKey, it) }
            if (pathItem.has(method)) return
            pathItem.add(method, operationJson(request, tag, schemes))
        }

        collections.forEach { collection ->
            collection.allFolders().forEach { folder ->
                folder.requests.forEach { register(it, folder.name) }
            }
            collection.requests.forEach { register(it, collection.name) }
        }

        val root = JsonObject()
        root.addProperty("openapi", "3.0.3")
        root.add("info", JsonObject().apply {
            addProperty("title", title)
            addProperty("version", "1.0.0")
        })
        server?.takeIf { it.isNotBlank() }?.let { url ->
            root.add("servers", JsonArray().apply {
                add(JsonObject().apply { addProperty("url", url) })
            })
        }
        root.add("paths", paths)
        if (schemes.size() > 0) {
            root.add("components", JsonObject().apply { add("securitySchemes", schemes) })
        }
        return gson.toJson(root)
    }

    private fun resolveServer(root: JsonObject): String? {
        val servers = root.array("servers")
        val first = servers?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        val raw = first?.string("url")
        if (!raw.isNullOrBlank()) {
            val variables = first.obj("variables")
            val resolved = Regex("\\{([^{}]+)}").replace(raw) { match ->
                variables?.obj(match.groupValues[1])?.string("default") ?: match.value
            }
            return resolved.trimEnd('/').ifBlank { null }
        }
        if (root.string("swagger") != null) {
            val host = root.string("host") ?: return null
            val scheme = root.array("schemes")
                ?.firstOrNull()
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?: "https"
            val basePath = root.string("basePath").orEmpty()
            return "$scheme://$host$basePath".trimEnd('/').ifBlank { null }
        }
        return null
    }

    private fun buildRequest(
        root: JsonObject,
        method: String,
        path: String,
        pathItem: JsonObject,
        operation: JsonObject,
        base: String,
    ): AstraRequest {
        val name = operation.string("summary")?.ifBlank { null }
            ?: operation.string("operationId")?.ifBlank { null }
            ?: "${method.uppercase(Locale.ROOT)} $path"
        val request = AstraRequest(
            name = name,
            method = method.uppercase(Locale.ROOT),
            url = base + path.replace(PATH_PARAMETER) { "{{${it.groupValues[1]}}}" },
        )
        val parameters = JsonArray()
        pathItem.array("parameters")?.forEach { parameters.add(it) }
        operation.array("parameters")?.forEach { parameters.add(it) }
        parameters.forEach { element ->
            if (element.isJsonObject) applyParameter(root, element.asJsonObject, request)
        }
        applyRequestBody(root, operation.obj("requestBody"), request)
        applySecurity(root, operation, request)
        return request
    }

    private fun applyParameter(root: JsonObject, parameter: JsonObject, request: AstraRequest) {
        val name = parameter.string("name")?.takeIf { it.isNotBlank() } ?: return
        val value = parameterValue(root, parameter)
        when (parameter.string("in")) {
            "query" -> request.params.add(KeyValue(key = name, value = value))
            "header" -> if (!name.equals("Content-Type", ignoreCase = true)) {
                request.headers.add(KeyValue(key = name, value = value))
            }
            "cookie" -> request.headers.add(KeyValue(key = "Cookie", value = "$name=$value"))
        }
    }

    private fun parameterValue(root: JsonObject, parameter: JsonObject): String {
        val example = parameter.get("example")
        if (example != null && !example.isJsonNull) {
            return if (example.isJsonPrimitive) example.asString else example.toString()
        }
        val schema = resolveSchema(root, parameter.obj("schema")) ?: return ""
        val schemaExample = schema.get("example")
        if (schemaExample != null && schemaExample.isJsonPrimitive) return schemaExample.asString
        val defaultValue = schema.get("default")
        if (defaultValue != null && defaultValue.isJsonPrimitive) return defaultValue.asString
        return schema.array("enum")?.firstOrNull()?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    }

    private fun applyRequestBody(root: JsonObject, body: JsonObject?, request: AstraRequest) {
        if (body == null) return
        val content = body.obj("content") ?: return
        val entry = content.entrySet().firstOrNull { it.key.contains("json", ignoreCase = true) }
            ?: content.entrySet().firstOrNull()
            ?: return
        val media = entry.value.takeIf { it.isJsonObject }?.asJsonObject ?: return
        val isJson = entry.key.contains("json", ignoreCase = true)
        val example = media.get("example")?.takeIf { !it.isJsonNull }
            ?: media.obj("examples")
                ?.entrySet()
                ?.firstOrNull()
                ?.value
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("value")
                ?.takeIf { !it.isJsonNull }
            ?: schemaExample(root, media.obj("schema"))
        request.body = when {
            example == null -> ""
            example.isJsonPrimitive -> example.asString
            else -> gson.toJson(example)
        }
        request.bodyMode = if (isJson) "json" else "text"
        if (!isJson && request.headers.none { it.key.equals("Content-Type", ignoreCase = true) }) {
            request.headers.add(KeyValue(key = "Content-Type", value = entry.key))
        }
    }

    private fun applySecurity(root: JsonObject, operation: JsonObject, request: AstraRequest) {
        val requirement = operation.array("security")
            ?.firstOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: root.array("security")
                ?.firstOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
        val schemeName = requirement?.entrySet()?.firstOrNull()?.key ?: return
        val scheme = root.obj("components")?.obj("securitySchemes")?.obj(schemeName)
            ?: root.obj("securityDefinitions")?.obj(schemeName)
            ?: return
        when (scheme.string("type")) {
            "http" -> when (scheme.string("scheme")?.lowercase(Locale.ROOT)) {
                "bearer" -> request.auth = AuthConfig(type = "bearer")
                "basic" -> request.auth = AuthConfig(type = "basic")
            }
            "apiKey" -> request.auth = AuthConfig(
                type = "apikey",
                apiKeyName = scheme.string("name").orEmpty(),
                apiKeyIn = scheme.string("in")?.ifBlank { "header" } ?: "header",
            )
        }
    }

    private fun resolveSchema(root: JsonObject, schema: JsonObject?): JsonObject? {
        if (schema == null) return null
        val ref = schema.string("\$ref") ?: return schema
        if (!ref.startsWith("#/")) return schema
        var current: JsonElement = root
        ref.removePrefix("#/").split('/').forEach { segment ->
            if (!current.isJsonObject) return schema
            current = current.asJsonObject.get(segment) ?: return schema
        }
        return current.takeIf { it.isJsonObject }?.asJsonObject ?: schema
    }

    private fun schemaExample(root: JsonObject, schema: JsonObject?, depth: Int = 0): JsonElement? {
        if (schema == null || depth > 6) return null
        schema.get("example")?.takeIf { !it.isJsonNull }?.let { return it }
        schema.get("default")?.takeIf { !it.isJsonNull }?.let { return it }
        val resolved = resolveSchema(root, schema) ?: return null
        if (resolved !== schema) return schemaExample(root, resolved, depth + 1)
        val properties = resolved.obj("properties")
        val items = resolved.obj("items")
        val type = resolved.string("type") ?: when {
            properties != null -> "object"
            items != null -> "array"
            else -> null
        }
        return when (type) {
            "object" -> JsonObject().apply {
                properties?.entrySet()?.forEach { (key, value) ->
                    val child = value.takeIf { it.isJsonObject }?.asJsonObject
                    schemaExample(root, child, depth + 1)?.let { add(key, it) }
                }
            }
            "array" -> JsonArray().apply {
                schemaExample(root, items, depth + 1)?.let { add(it) }
            }
            "integer", "number" -> JsonPrimitive(0)
            "boolean" -> JsonPrimitive(true)
            "string" -> JsonPrimitive(
                resolved.array("enum")
                    ?.firstOrNull()
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?: "string",
            )
            else -> null
        }
    }

    private fun operationJson(request: AstraRequest, tag: String?, schemes: JsonObject): JsonObject {
        val operation = JsonObject()
        operation.addProperty("summary", request.name)
        operation.addProperty("operationId", operationId(request.name))
        if (!tag.isNullOrBlank()) {
            operation.add("tags", JsonArray().apply { add(tag) })
        }
        val parameters = JsonArray()
        request.params.filter { it.enabled && it.key.isNotBlank() }.forEach { param ->
            parameters.add(parameterJson(param.key, "query", param.value))
        }
        inlineQuery(request.url).forEach { (key, value) ->
            if (request.params.none { it.key == key }) {
                parameters.add(parameterJson(key, "query", value))
            }
        }
        request.headers.filter {
            it.enabled && it.key.isNotBlank() &&
                !it.key.equals("Content-Type", ignoreCase = true) &&
                !it.key.equals("Authorization", ignoreCase = true)
        }.forEach { header ->
            parameters.add(parameterJson(header.key, "header", header.value))
        }
        if (parameters.size() > 0) operation.add("parameters", parameters)

        if (request.bodyMode != "none" && request.body.isNotBlank()) {
            val mediaType = if (request.bodyMode == "json") "application/json" else "text/plain"
            operation.add("requestBody", JsonObject().apply {
                add("content", JsonObject().apply {
                    add(mediaType, JsonObject().apply {
                        add("schema", JsonObject().apply { addProperty("type", "string") })
                        val example = runCatching { JsonParser.parseString(request.body) }.getOrNull()
                        if (example != null) add("example", example) else addProperty("example", request.body)
                    })
                })
            })
        }
        securityJson(request.auth, schemes)?.let { operation.add("security", it) }
        return operation
    }

    private fun parameterJson(name: String, location: String, value: String): JsonObject =
        JsonObject().apply {
            addProperty("name", name)
            addProperty("in", location)
            addProperty("required", false)
            add("schema", JsonObject().apply { addProperty("type", "string") })
            if (value.isNotBlank()) addProperty("example", value)
        }

    private fun securityJson(auth: AuthConfig, schemes: JsonObject): JsonArray? {
        val type = auth.type.lowercase(Locale.ROOT)
        val name = when (type) {
            "bearer" -> "bearerAuth"
            "basic" -> "basicAuth"
            "apikey" -> "apiKeyAuth"
            else -> return null
        }
        if (!schemes.has(name)) {
            schemes.add(name, when (type) {
                "bearer" -> JsonObject().apply {
                    addProperty("type", "http")
                    addProperty("scheme", "bearer")
                }
                "basic" -> JsonObject().apply {
                    addProperty("type", "http")
                    addProperty("scheme", "basic")
                }
                else -> JsonObject().apply {
                    addProperty("type", "apiKey")
                    addProperty("name", auth.apiKeyName.ifBlank { "X-API-Key" })
                    addProperty("in", auth.apiKeyIn.ifBlank { "header" })
                }
            })
        }
        return JsonArray().apply {
            add(JsonObject().apply { add(name, JsonArray()) })
        }
    }

    private fun splitUrl(url: String): Pair<String?, String> {
        val noQuery = url.trim().substringBefore('?').substringBefore('#')
        val match = Regex("^(https?://[^/]+)(/.*)?$").find(noQuery)
        if (match == null) {
            val path = noQuery.removePrefix("{{baseUrl}}")
            return null to path.ifBlank { "/" }
        }
        val server = match.groupValues[1]
        val path = match.groupValues[2].ifBlank { "/" }
        return server to path
    }

    private fun normalizePath(path: String): String {
        val converted = path.replace(Regex("\\{\\{([^{}]+)}}")) { "{${it.groupValues[1]}}" }
        return if (converted.startsWith("/")) converted else "/$converted"
    }

    private fun inlineQuery(url: String): List<Pair<String, String>> {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isBlank()) return emptyList()
        return query.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = pair.substringBefore('=')
            if (key.isBlank()) null else key to pair.substringAfter('=', "")
        }
    }

    private fun operationId(name: String): String {
        val slug = name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return slug.ifBlank { "operation" }
    }

    companion object {
        private val HTTP_METHODS = listOf("get", "post", "put", "patch", "delete", "head", "options")
        private val PATH_PARAMETER = Regex("\\{([^{}]+)}")
    }
}
