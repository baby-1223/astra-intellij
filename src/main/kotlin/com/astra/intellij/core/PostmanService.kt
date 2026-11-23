package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.AuthConfig
import com.astra.intellij.model.KeyValue
import com.astra.intellij.model.RequestCollection
import com.astra.intellij.model.RequestFolder
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class PostmanDataDump(
    val collections: List<RequestCollection> = emptyList(),
    val environments: List<AstraEnvironment> = emptyList(),
    val globals: Map<String, String> = emptyMap(),
)

/**
 * Imports and exports Postman Collection v2.x and Postman Environment JSON.
 */
class PostmanService {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun parseCollection(json: String): RequestCollection =
        parseCollection(JsonParser.parseString(json).asJsonObject)

    fun parseEnvironment(json: String): AstraEnvironment? =
        parseEnvironment(JsonParser.parseString(json).asJsonObject)

    private fun parseCollection(root: JsonObject): RequestCollection {
        val info = root.obj("info")
        val collection = RequestCollection(name = info?.string("name")?.ifBlank { null } ?: "Imported Collection")
        root.array("item")?.let { parseItems(it, collection, null) }
        return collection
    }

    private fun parseEnvironment(root: JsonObject): AstraEnvironment? {
        val values = root.array("values") ?: return null
        val environment = AstraEnvironment(name = root.string("name")?.ifBlank { null } ?: "Imported Environment")
        values.forEach { element ->
            val value = element.asJsonObject
            val key = value.string("key") ?: return@forEach
            val enabled = value.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
            if (enabled && key.isNotBlank()) {
                environment.variables[key] = value.string("value").orEmpty()
            }
        }
        return environment
    }

    /**
     * Reads a Postman data dump. Accepts a ZIP produced by "Export Data" (one collection or
     * environment JSON per file), a single collection/environment JSON, or an array of JSON objects.
     */
    fun parseDataDump(bytes: ByteArray): PostmanDataDump {
        if (isZip(bytes)) return parseZip(bytes)
        return parseJsonPayload(String(bytes, StandardCharsets.UTF_8))
    }

    /**
     * Writes a Postman-compatible data dump: one JSON file per collection under "collections/"
     * and one per environment under "environments/".
     */
    fun exportDataDump(
        collections: List<RequestCollection>,
        environments: List<AstraEnvironment>,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val used = mutableSetOf<String>()
            fun add(folder: String, name: String, content: String) {
                val base = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "astra" }
                var fileName = "$folder/$base.json"
                var index = 2
                while (!used.add(fileName)) {
                    fileName = "$folder/$base-${index++}.json"
                }
                zip.putNextEntry(ZipEntry(fileName))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            collections.forEach { add("collections", it.name, exportCollection(listOf(it), it.name)) }
            environments.forEach { add("environments", it.name, exportEnvironment(it)) }
        }
        return output.toByteArray()
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    private fun parseZip(bytes: ByteArray): PostmanDataDump {
        val collections = mutableListOf<RequestCollection>()
        val environments = mutableListOf<AstraEnvironment>()
        val globals = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".json", ignoreCase = true)) {
                    val text = zip.readBytes().toString(StandardCharsets.UTF_8)
                    runCatching { parseJsonPayload(text) }.getOrNull()?.let {
                        collections.addAll(it.collections)
                        environments.addAll(it.environments)
                        globals.putAll(it.globals)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return PostmanDataDump(collections, environments, globals)
    }

    private fun parseJsonPayload(text: String): PostmanDataDump {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("[")) {
            val collections = mutableListOf<RequestCollection>()
            val environments = mutableListOf<AstraEnvironment>()
            val globals = linkedMapOf<String, String>()
            JsonParser.parseString(text).asJsonArray.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val obj = element.asJsonObject
                when {
                    isDump(obj) -> {
                        val dump = parseDump(obj)
                        collections.addAll(dump.collections)
                        environments.addAll(dump.environments)
                        globals.putAll(dump.globals)
                    }
                    isEnvironment(obj) -> parseEnvironment(obj)?.let { environments.add(it) }
                    isCollection(obj) -> collections.add(parseCollection(obj))
                    isInternalCollection(obj) -> collections.add(parseInternalCollection(obj))
                }
            }
            return PostmanDataDump(collections, environments, globals)
        }
        val root = JsonParser.parseString(text).asJsonObject
        return when {
            isDump(root) -> parseDump(root)
            isEnvironment(root) -> PostmanDataDump(environments = listOfNotNull(parseEnvironment(root)))
            isCollection(root) -> PostmanDataDump(collections = listOf(parseCollection(root)))
            isInternalCollection(root) -> PostmanDataDump(collections = listOf(parseInternalCollection(root)))
            else -> PostmanDataDump()
        }
    }

    private fun isEnvironment(root: JsonObject): Boolean =
        root.array("values") != null &&
            (root.string("_postman_variable_scope") == "environment" || !root.has("item"))

    /** A data dump wraps collections and environments in top-level arrays. */
    private fun isDump(root: JsonObject): Boolean =
        root.array("collections") != null || root.array("environments") != null

    private fun isCollection(root: JsonObject): Boolean = root.has("info") || root.has("item")

    /** Postman data dumps store collections in the legacy "requests"/"folders" shape. */
    private fun isInternalCollection(root: JsonObject): Boolean =
        root.has("requests") || root.has("folders")

    private fun parseDump(root: JsonObject): PostmanDataDump {
        val collections = mutableListOf<RequestCollection>()
        root.array("collections")?.forEach { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            collections.add(
                if (isCollection(obj)) parseCollection(obj) else parseInternalCollection(obj),
            )
        }
        val environments = mutableListOf<AstraEnvironment>()
        root.array("environments")?.forEach { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            parseEnvironment(obj)?.let { environments.add(it) }
        }
        val globals = linkedMapOf<String, String>()
        root.array("globals")?.forEach { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val key = obj.string("key")?.takeIf { it.isNotBlank() } ?: return@forEach
            val enabled = obj.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
            if (enabled) globals[key] = obj.string("value").orEmpty()
        }
        return PostmanDataDump(collections, environments, globals)
    }

    /** Imports a legacy data-dump collection, including nested folders and requests. */
    private fun parseInternalCollection(root: JsonObject): RequestCollection {
        val collection = RequestCollection(
            name = root.string("name")?.ifBlank { null } ?: "Imported Collection",
        )
        parseInternalItems(root.array("requests"), root.array("folders"), collection, null)
        return collection
    }

    private fun parseInternalItems(
        requests: JsonArray?,
        folders: JsonArray?,
        collection: RequestCollection,
        parent: RequestFolder?,
    ) {
        folders?.forEach { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val folder = RequestFolder(name = obj.string("name")?.ifBlank { null } ?: "Untitled")
            if (parent != null) parent.folders.add(folder) else collection.folders.add(folder)
            parseInternalItems(obj.array("requests"), obj.array("folders"), collection, folder)
        }
        requests?.forEach { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val request = parseInternalRequest(obj)
            if (parent != null) parent.requests.add(request) else collection.requests.add(request)
        }
    }

    private fun parseInternalRequest(obj: JsonObject): AstraRequest {
        val request = AstraRequest(name = obj.string("name")?.ifBlank { null } ?: "Untitled")
        request.method = obj.string("method")?.uppercase(Locale.ROOT) ?: "GET"
        parseUrl(obj.get("url"), request)
        parseInternalHeaders(obj.get("headers"), request)
        parseInternalBody(obj, request)
        parseInternalAuth(obj, request)
        obj.string("preRequestScript")?.takeIf { it.isNotBlank() }?.let { script ->
            request.preRequest = script.lines().toMutableList()
        }
        return request
    }

    /** Legacy dumps keep headers as one "Key: Value" per line string. */
    private fun parseInternalHeaders(element: JsonElement?, request: AstraRequest) {
        if (element == null || element.isJsonNull) return
        if (element.isJsonArray) {
            parseHeaders(element.asJsonArray, request)
            return
        }
        if (!element.isJsonPrimitive) return
        element.asString.lines().forEach { line ->
            val trimmed = line.trim()
            val colon = trimmed.indexOf(':')
            if (colon <= 0) return@forEach
            val key = trimmed.substring(0, colon).trim()
            val value = trimmed.substring(colon + 1).trim()
            if (key.isNotEmpty()) request.headers.add(KeyValue(key = key, value = value))
        }
    }

    private fun parseInternalBody(obj: JsonObject, request: AstraRequest) {
        val mode = obj.string("dataMode")?.lowercase(Locale.ROOT).orEmpty()
        val data = obj.get("data")
        when {
            mode == "raw" || (mode.isEmpty() && data?.isJsonPrimitive == true) -> {
                val text = obj.string("rawModeData") ?: obj.string("data").orEmpty()
                if (text.isNotBlank()) {
                    request.body = text
                    request.bodyMode = if (looksLikeJson(text)) "json" else "text"
                }
            }
            mode == "urlencoded" || mode == "params" || mode == "formdata" -> {
                val pairs = (data as? JsonArray)?.mapNotNull { element ->
                    val entry = element.takeIf { it.isJsonObject }?.asJsonObject
                        ?: return@mapNotNull null
                    val enabled = entry.get("enabled")
                        ?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
                    if (!enabled) return@mapNotNull null
                    entry.string("key")?.let { "$it=${entry.string("value").orEmpty()}" }
                }.orEmpty()
                if (pairs.isNotEmpty()) {
                    request.body = pairs.joinToString("&")
                    request.bodyMode = "text"
                    ensureContentType(
                        request,
                        if (mode == "urlencoded") {
                            "application/x-www-form-urlencoded"
                        } else {
                            "multipart/form-data"
                        },
                    )
                }
            }
        }
    }

    private fun parseInternalAuth(obj: JsonObject, request: AstraRequest) {
        val helper = obj.string("currentHelper")?.lowercase(Locale.ROOT)
            ?.takeIf { it != "normal" } ?: return
        val attributes = obj.obj("helperAttributes") ?: return
        when (helper) {
            "basicauth" -> request.auth = AuthConfig(
                type = "basic",
                username = attributes.string("username").orEmpty(),
                password = attributes.string("password").orEmpty(),
            )
            "bearerauth" -> request.auth = AuthConfig(
                type = "bearer",
                token = attributes.string("token").orEmpty(),
            )
            "apikeyauth" -> request.auth = AuthConfig(
                type = "apikey",
                apiKeyName = attributes.string("key").orEmpty(),
                apiKeyValue = attributes.string("value").orEmpty(),
                apiKeyIn = attributes.string("in")?.ifBlank { "header" } ?: "header",
            )
        }
    }


    fun exportCollection(collections: List<RequestCollection>, name: String): String {
        val root = JsonObject()
        root.add("info", JsonObject().apply {
            addProperty("name", name)
            addProperty("schema", COLLECTION_SCHEMA)
        })
        val items = JsonArray()
        if (collections.size == 1) {
            appendCollectionItems(collections.first(), items)
        } else {
            collections.forEach { collection ->
                items.add(JsonObject().apply {
                    addProperty("name", collection.name)
                    add("item", JsonArray().also { appendCollectionItems(collection, it) })
                })
            }
        }
        root.add("item", items)
        return gson.toJson(root)
    }

    fun exportEnvironment(environment: AstraEnvironment): String {
        val root = JsonObject()
        root.addProperty("id", environment.id)
        root.addProperty("name", environment.name)
        root.addProperty("_postman_variable_scope", "environment")
        root.add("values", JsonArray().apply {
            environment.variables.forEach { (key, value) ->
                add(JsonObject().apply {
                    addProperty("key", key)
                    addProperty("value", value)
                    addProperty("type", "default")
                    addProperty("enabled", true)
                })
            }
        })
        return gson.toJson(root)
    }

    private fun appendCollectionItems(collection: RequestCollection, items: JsonArray) {
        collection.folders.forEach { items.add(folderJson(it)) }
        collection.requests.forEach { items.add(requestJson(it)) }
    }

    private fun folderJson(folder: RequestFolder): JsonObject = JsonObject().apply {
        addProperty("name", folder.name)
        add("item", JsonArray().also { array ->
            folder.requests.forEach { array.add(requestJson(it)) }
            folder.folders.forEach { array.add(folderJson(it)) }
        })
    }

    private fun parseItems(items: JsonArray, collection: RequestCollection, parent: RequestFolder?) {
        items.forEach { element ->
            val item = element.asJsonObject
            val name = item.string("name")?.ifBlank { null } ?: "Untitled"
            val request = item.get("request")
            val children = item.array("item")
            when {
                request != null && !request.isJsonNull -> {
                    val parsed = parseRequest(name, request)
                    parseEvents(item.array("event"), parsed)
                    if (parent != null) parent.requests.add(parsed) else collection.requests.add(parsed)
                }
                children != null -> {
                    val folder = RequestFolder(name = name)
                    if (parent != null) parent.folders.add(folder) else collection.folders.add(folder)
                    parseItems(children, collection, folder)
                }
            }
        }
    }

    private fun parseRequest(name: String, element: JsonElement): AstraRequest {
        val request = AstraRequest(name = name)
        if (element.isJsonPrimitive) {
            request.url = element.asString
            return request
        }
        val obj = element.asJsonObject
        request.method = obj.string("method")?.uppercase(Locale.ROOT) ?: "GET"
        parseUrl(obj.get("url"), request)
        parseHeaders(obj.array("header"), request)
        parseBody(obj.obj("body"), request)
        parseAuth(obj.obj("auth"), request)
        return request
    }

    /** Imports the "prerequest" event script so Postman scripts keep working after the import. */
    private fun parseEvents(events: JsonArray?, request: AstraRequest) {
        events?.forEach { element ->
            val event = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (event.string("listen") != "prerequest") return@forEach
            val exec = event.obj("script")?.array("exec")?.mapNotNull { line ->
                if (line.isJsonPrimitive) line.asString else null
            } ?: return@forEach
            if (exec.isNotEmpty()) request.preRequest = exec.toMutableList()
        }
    }

    private fun parseUrl(element: JsonElement?, request: AstraRequest) {
        if (element == null || element.isJsonNull) return
        if (element.isJsonPrimitive) {
            request.url = element.asString.replace(PATH_VARIABLE) { "{{${it.groupValues[1]}}}" }
            return
        }
        val url = element.asJsonObject
        val raw = url.string("raw")?.ifBlank { null } ?: buildRawUrl(url)
        request.url = raw.replace(PATH_VARIABLE) { "{{${it.groupValues[1]}}}" }
        url.array("query")?.forEach { entry ->
            val query = entry.asJsonObject
            val disabled = query.get("disabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            request.params.add(
                KeyValue(
                    enabled = !disabled,
                    key = query.string("key").orEmpty(),
                    value = query.string("value").orEmpty(),
                ),
            )
        }
        url.array("variable")?.forEach { entry ->
            val variable = entry.asJsonObject
            val key = variable.string("key") ?: return@forEach
            if (key.isNotBlank() && request.params.none { it.key == key }) {
                request.params.add(KeyValue(key = key, value = variable.string("value").orEmpty()))
            }
        }
    }

    private fun buildRawUrl(url: JsonObject): String {
        val protocol = url.string("protocol") ?: "https"
        val host = url.array("host")?.joinToString(".") { it.asString }.orEmpty()
        val port = url.string("port")?.takeIf { it.isNotBlank() }?.let { ":$it" }.orEmpty()
        val path = url.array("path")?.joinToString("/") { it.asString }.orEmpty()
        return "$protocol://$host$port" + if (path.isNotBlank()) "/$path" else ""
    }

    private fun parseHeaders(headers: JsonArray?, request: AstraRequest) {
        headers?.forEach { element ->
            val header = element.asJsonObject
            val key = header.string("key") ?: return@forEach
            val disabled = header.get("disabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            if (key.isNotBlank()) {
                request.headers.add(
                    KeyValue(enabled = !disabled, key = key, value = header.string("value").orEmpty()),
                )
            }
        }
    }

    private fun parseBody(body: JsonObject?, request: AstraRequest) {
        if (body == null) return
        when (body.string("mode")) {
            "raw" -> {
                request.body = body.string("raw").orEmpty()
                val language = body.obj("options")?.obj("raw")?.string("language")
                request.bodyMode = if (language == "json" || looksLikeJson(request.body)) "json" else "text"
            }
            "urlencoded" -> {
                val pairs = body.array("urlencoded")?.mapNotNull { element ->
                    val entry = element.asJsonObject
                    val disabled = entry.get("disabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                    if (disabled) null else entry.string("key")?.let { "$it=${entry.string("value").orEmpty()}" }
                }.orEmpty()
                request.body = pairs.joinToString("&")
                request.bodyMode = "text"
                ensureContentType(request, "application/x-www-form-urlencoded")
            }
            "formdata" -> {
                val pairs = body.array("formdata")?.mapNotNull { element ->
                    val entry = element.asJsonObject
                    val disabled = entry.get("disabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                    if (disabled) null else entry.string("key")?.let { "$it=${entry.string("value").orEmpty()}" }
                }.orEmpty()
                request.body = pairs.joinToString("&")
                request.bodyMode = "text"
                ensureContentType(request, "multipart/form-data")
            }
            "graphql" -> {
                val graphql = body.obj("graphql") ?: return
                val query = graphql.string("query").orEmpty()
                val variables = graphql.string("variables")
                request.body = if (variables.isNullOrBlank()) {
                    JsonObject().apply { addProperty("query", query) }.toString()
                } else {
                    JsonObject().apply {
                        addProperty("query", query)
                        runCatching { add("variables", JsonParser.parseString(variables)) }
                    }.toString()
                }
                request.bodyMode = "json"
            }
        }
    }

    private fun parseAuth(auth: JsonObject?, request: AstraRequest) {
        when (auth?.string("type")) {
            "bearer" -> request.auth = AuthConfig(
                type = "bearer",
                token = authValue(auth, "bearer", "token"),
            )
            "basic" -> request.auth = AuthConfig(
                type = "basic",
                username = authValue(auth, "basic", "username"),
                password = authValue(auth, "basic", "password"),
            )
            "apikey" -> request.auth = AuthConfig(
                type = "apikey",
                apiKeyName = authValue(auth, "apikey", "key"),
                apiKeyValue = authValue(auth, "apikey", "value"),
                apiKeyIn = authValue(auth, "apikey", "in").ifBlank { "header" },
            )
        }
    }

    private fun authValue(auth: JsonObject, section: String, key: String): String =
        auth.array(section)
            ?.mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }
            ?.firstOrNull { it.string("key") == key }
            ?.string("value")
            .orEmpty()

    private fun ensureContentType(request: AstraRequest, value: String) {
        if (request.headers.none { it.key.equals("Content-Type", ignoreCase = true) }) {
            request.headers.add(KeyValue(key = "Content-Type", value = value))
        }
    }

    private fun requestJson(request: AstraRequest): JsonObject = JsonObject().apply {
        addProperty("name", request.name)
        add("request", JsonObject().apply {
            addProperty("method", request.method)
            add("header", JsonArray().also { array ->
                request.headers.forEach { header ->
                    array.add(JsonObject().apply {
                        addProperty("key", header.key)
                        addProperty("value", header.value)
                        if (!header.enabled) addProperty("disabled", true)
                        addProperty("type", "text")
                    })
                }
                authHeaders(request).forEach { (key, value) ->
                    array.add(JsonObject().apply {
                        addProperty("key", key)
                        addProperty("value", value)
                        addProperty("type", "text")
                    })
                }
                if (request.bodyMode != "none" &&
                    request.headers.none { it.key.equals("Content-Type", ignoreCase = true) }
                ) {
                    array.add(JsonObject().apply {
                        addProperty("key", "Content-Type")
                        addProperty(
                            "value",
                            if (request.bodyMode == "json") "application/json" else "text/plain",
                        )
                        addProperty("type", "text")
                    })
                }
            })
            add("url", urlJson(request))
            if (request.bodyMode != "none" && request.body.isNotBlank()) {
                add("body", JsonObject().apply {
                    addProperty("mode", "raw")
                    addProperty("raw", request.body)
                    add("options", JsonObject().apply {
                        add("raw", JsonObject().apply {
                            addProperty("language", if (request.bodyMode == "json") "json" else "text")
                        })
                    })
                })
            }
            authJson(request.auth)?.let { add("auth", it) }
        })
        if (request.preRequest.isNotEmpty()) {
            add("event", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("listen", "prerequest")
                    add("script", JsonObject().apply {
                        addProperty("type", "text/javascript")
                        add("exec", JsonArray().apply { request.preRequest.forEach { add(it) } })
                    })
                })
            })
        }
    }

    private fun urlJson(request: AstraRequest): JsonObject = JsonObject().apply {
        val enabled = request.params.filter { it.enabled && it.key.isNotBlank() }
        addProperty("raw", if (enabled.isEmpty()) {
            request.url
        } else {
            val separator = when {
                !request.url.contains('?') -> "?"
                request.url.endsWith("?") || request.url.endsWith("&") -> ""
                else -> "&"
            }
            request.url + separator + enabled.joinToString("&") { "${it.key}=${it.value}" }
        })
        if (request.params.isNotEmpty()) {
            add("query", JsonArray().also { array ->
                request.params.forEach { param ->
                    array.add(JsonObject().apply {
                        addProperty("key", param.key)
                        addProperty("value", param.value)
                        if (!param.enabled) addProperty("disabled", true)
                    })
                }
            })
        }
    }

    private fun authJson(auth: AuthConfig): JsonObject? = when (auth.type.lowercase(Locale.ROOT)) {
        "bearer" -> JsonObject().apply {
            addProperty("type", "bearer")
            add("bearer", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("key", "token")
                    addProperty("value", auth.token)
                    addProperty("type", "string")
                })
            })
        }
        "basic" -> JsonObject().apply {
            addProperty("type", "basic")
            add("basic", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("key", "username")
                    addProperty("value", auth.username)
                    addProperty("type", "string")
                })
                add(JsonObject().apply {
                    addProperty("key", "password")
                    addProperty("value", auth.password)
                    addProperty("type", "string")
                })
            })
        }
        "apikey" -> JsonObject().apply {
            addProperty("type", "apikey")
            add("apikey", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("key", "key")
                    addProperty("value", auth.apiKeyName)
                    addProperty("type", "string")
                })
                add(JsonObject().apply {
                    addProperty("key", "value")
                    addProperty("value", auth.apiKeyValue)
                    addProperty("type", "string")
                })
                add(JsonObject().apply {
                    addProperty("key", "in")
                    addProperty("value", auth.apiKeyIn)
                    addProperty("type", "string")
                })
            })
        }
        else -> null
    }

    private fun authHeaders(request: AstraRequest): List<Pair<String, String>> =
        when (request.auth.type.lowercase(Locale.ROOT)) {
            "bearer" -> listOf("Authorization" to "Bearer ${request.auth.token}")
            "basic" -> listOf(
                "Authorization" to
                    "Basic " + java.util.Base64.getEncoder().encodeToString(
                        "${request.auth.username}:${request.auth.password}".toByteArray(),
                    ),
            )
            "apikey" -> if (request.auth.apiKeyIn.equals("header", ignoreCase = true)) {
                listOf(request.auth.apiKeyName to request.auth.apiKeyValue)
            } else {
                emptyList()
            }
            else -> emptyList()
        }

    private fun looksLikeJson(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    companion object {
        private const val COLLECTION_SCHEMA =
            "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
        private val PATH_VARIABLE = Regex("(?<=/):([A-Za-z0-9_\\-]+)")
    }
}

internal fun JsonObject.string(name: String): String? {
    val element = get(name) ?: return null
    if (element.isJsonNull) return null
    return if (element.isJsonPrimitive) element.asString else null
}

internal fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.array(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray
