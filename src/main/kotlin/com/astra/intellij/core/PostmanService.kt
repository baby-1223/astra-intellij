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
import java.util.Locale

/**
 * Imports and exports Postman Collection v2.x and Postman Environment JSON.
 */
class PostmanService {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun parseCollection(json: String): RequestCollection {
        val root = JsonParser.parseString(json).asJsonObject
        val info = root.obj("info")
        val collection = RequestCollection(name = info?.string("name")?.ifBlank { null } ?: "Imported Collection")
        root.array("item")?.let { parseItems(it, collection, null) }
        return collection
    }

    fun parseEnvironment(json: String): AstraEnvironment? {
        val root = JsonParser.parseString(json).asJsonObject
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
        collection.folders.forEach { folder ->
            items.add(JsonObject().apply {
                addProperty("name", folder.name)
                add("item", JsonArray().also { array -> folder.requests.forEach { array.add(requestJson(it)) } })
            })
        }
        collection.requests.forEach { items.add(requestJson(it)) }
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
                    if (parent != null) parent.requests.add(parsed) else collection.requests.add(parsed)
                }
                children != null -> {
                    val folder = RequestFolder(name = if (parent == null) name else "${parent.name} / $name")
                    collection.folders.add(folder)
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
