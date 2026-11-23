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
import java.util.UUID

data class InsomniaImport(
    val collections: List<RequestCollection> = emptyList(),
    val environments: List<AstraEnvironment> = emptyList(),
)

/**
 * Imports and exports Insomnia v4 JSON.
 *
 * Insomnia stores everything as a flat list of resources linked by parentId. A single
 * connection is exported together with its workspace and request_group ancestors so the
 * file can be imported back into Insomnia without dangling parents.
 */
class InsomniaService {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /* ------------------------------------------------------------------ */
    /* Export                                                             */
    /* ------------------------------------------------------------------ */

    fun exportRequest(
        request: AstraRequest,
        folderPath: List<String>,
        collectionName: String,
        environment: AstraEnvironment?,
    ): String {
        val resources = JsonArray()
        val workspaceId = "wrk_astra"
        resources.add(workspace(workspaceId, collectionName.ifBlank { "Astra" }))
        var parentId = workspaceId
        folderPath.forEachIndexed { index, name ->
            val id = "fld_astra_$index"
            resources.add(requestGroup(id, parentId, name))
            parentId = id
        }
        resources.add(request(request, parentId))
        environment?.let { resources.add(environmentResource(it, workspaceId)) }
        return wrapper(resources)
    }

    fun exportAll(
        collections: List<RequestCollection>,
        environments: List<AstraEnvironment>,
        workspaceName: String,
    ): String {
        val resources = JsonArray()
        val workspaceId = "wrk_astra"
        resources.add(workspace(workspaceId, workspaceName.ifBlank { "Astra" }))
        var sequence = 0

        fun addFolder(folder: RequestFolder, parentId: String) {
            val id = "fld_${sequence++}"
            resources.add(requestGroup(id, parentId, folder.name))
            folder.folders.forEach { addFolder(it, id) }
            folder.requests.forEach { resources.add(request(it, id)) }
        }

        collections.forEach { collection ->
            val id = "fld_${sequence++}"
            resources.add(requestGroup(id, workspaceId, collection.name))
            collection.folders.forEach { addFolder(it, id) }
            collection.requests.forEach { resources.add(request(it, id)) }
        }
        environments.forEach { resources.add(environmentResource(it, workspaceId)) }
        return wrapper(resources)
    }

    private fun wrapper(resources: JsonArray): String = gson.toJson(JsonObject().apply {
        addProperty("_type", "export")
        addProperty("__export_format", 4)
        addProperty("__export_source", "astra")
        add("resources", resources)
    })

    private fun workspace(id: String, name: String): JsonObject = JsonObject().apply {
        addProperty("_id", id)
        addProperty("_type", "workspace")
        addProperty("name", name)
        addProperty("description", "")
        addProperty("scope", "collection")
    }

    private fun requestGroup(id: String, parentId: String, name: String): JsonObject = JsonObject().apply {
        addProperty("_id", id)
        addProperty("_type", "request_group")
        addProperty("parentId", parentId)
        addProperty("name", name.ifBlank { "Folder" })
        addProperty("description", "")
    }

    private fun request(request: AstraRequest, parentId: String): JsonObject = JsonObject().apply {
        addProperty("_id", "req_${request.id.ifBlank { UUID.randomUUID().toString() }}")
        addProperty("_type", "request")
        addProperty("parentId", parentId)
        addProperty("name", request.name.ifBlank { "Untitled" })
        addProperty("description", "")
        addProperty("method", request.method.uppercase(Locale.ROOT))
        addProperty("url", toInsomniaVariables(request.url))
        add("parameters", JsonArray().apply { request.params.forEach { add(parameter(it)) } })
        add("headers", JsonArray().apply { request.headers.forEach { add(parameter(it)) } })
        body(request)?.let { add("body", it) }
        auth(request.auth)?.let { add("authentication", it) }
    }

    private fun parameter(value: KeyValue): JsonObject = JsonObject().apply {
        addProperty("name", value.key)
        addProperty("value", toInsomniaVariables(value.value))
        if (!value.enabled) addProperty("disabled", true)
    }

    private fun body(request: AstraRequest): JsonObject? = when (request.bodyMode) {
        "json" -> JsonObject().apply {
            addProperty("mimeType", "application/json")
            addProperty("text", toInsomniaVariables(request.body))
        }
        "text" -> JsonObject().apply {
            addProperty("mimeType", "text/plain")
            addProperty("text", toInsomniaVariables(request.body))
        }
        "urlencoded" -> JsonObject().apply {
            addProperty("mimeType", "application/x-www-form-urlencoded")
            add("params", JsonArray().apply {
                request.formFields.forEach { field ->
                    add(JsonObject().apply {
                        addProperty("name", field.key)
                        addProperty("value", toInsomniaVariables(field.value))
                        if (!field.enabled) addProperty("disabled", true)
                    })
                }
            })
        }
        "form-data" -> JsonObject().apply {
            addProperty("mimeType", "multipart/form-data")
            add("params", JsonArray().apply {
                request.formFields.forEach { field ->
                    add(JsonObject().apply {
                        addProperty("name", field.key)
                        addProperty("value", toInsomniaVariables(field.value))
                        addProperty("type", "text")
                        if (!field.enabled) addProperty("disabled", true)
                    })
                }
            })
        }
        else -> null
    }

    private fun auth(auth: AuthConfig): JsonObject? = when (auth.type.lowercase(Locale.ROOT)) {
        "bearer" -> JsonObject().apply {
            addProperty("type", "bearer")
            addProperty("token", toInsomniaVariables(auth.token))
        }
        "basic" -> JsonObject().apply {
            addProperty("type", "basic")
            addProperty("username", toInsomniaVariables(auth.username))
            addProperty("password", toInsomniaVariables(auth.password))
        }
        "apikey" -> JsonObject().apply {
            addProperty("type", "apikey")
            addProperty("key", toInsomniaVariables(auth.apiKeyName))
            addProperty("value", toInsomniaVariables(auth.apiKeyValue))
            addProperty("addTo", if (auth.apiKeyIn.equals("query", ignoreCase = true)) "query" else "header")
        }
        else -> null
    }

    private fun environmentResource(environment: AstraEnvironment, parentId: String): JsonObject = JsonObject().apply {
        addProperty("_id", "env_${environment.id.ifBlank { UUID.randomUUID().toString() }}")
        addProperty("_type", "environment")
        addProperty("parentId", parentId)
        addProperty("name", environment.name.ifBlank { "Environment" })
        add("data", JsonObject().apply {
            environment.variables.forEach { (key, value) -> addProperty(key, toInsomniaVariables(value)) }
        })
    }

    /* ------------------------------------------------------------------ */
    /* Import                                                             */
    /* ------------------------------------------------------------------ */

    fun parse(json: String): InsomniaImport {
        val root = JsonParser.parseString(json)
        val resources = when {
            root.isJsonArray -> root.asJsonArray.mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }
            !root.isJsonObject -> return InsomniaImport()
            else -> {
                val obj = root.asJsonObject
                when {
                    obj.array("resources") != null -> obj.array("resources")!!.mapNotNull {
                        it.takeIf { element -> element.isJsonObject }?.asJsonObject
                    }
                    obj.string("_type") != null -> listOf(obj)
                    else -> emptyList()
                }
            }
        }
        if (resources.isEmpty()) return InsomniaImport()

        val workspaces = resources.filter { it.string("_type") == "workspace" }
        val groups = resources.filter { it.string("_type") == "request_group" }
        val requests = resources.filter { it.string("_type") == "request" }
        val environments = resources.filter { it.string("_type") == "environment" }
            .map { parseEnvironment(it) }

        val collections = if (workspaces.isEmpty()) {
            listOf(buildCollection(null, groups, requests))
        } else {
            workspaces.map { buildCollection(it, groups, requests) }
        }
        return InsomniaImport(collections, environments)
    }

    private fun buildCollection(
        workspace: JsonObject?,
        groups: List<JsonObject>,
        requests: List<JsonObject>,
    ): RequestCollection {
        val workspaceId = workspace?.string("_id")
        val collection = RequestCollection(name = workspace?.string("name")?.ifBlank { null } ?: "Imported Insomnia")
        val groupsByParent = groups.groupBy { it.string("parentId").orEmpty() }
        val requestsByParent = requests.groupBy { it.string("parentId").orEmpty() }
        val attached = mutableSetOf<String>()

        fun append(parentId: String, folders: MutableList<RequestFolder>, direct: MutableList<AstraRequest>) {
            groupsByParent[parentId].orEmpty().forEach { group ->
                val folder = RequestFolder(name = group.string("name")?.ifBlank { null } ?: "Folder")
                append(group.string("_id").orEmpty(), folder.folders, folder.requests)
                folders.add(folder)
            }
            requestsByParent[parentId].orEmpty().forEach { element ->
                attached.add(element.string("_id").orEmpty())
                direct.add(parseRequest(element))
            }
        }

        append(workspaceId.orEmpty(), collection.folders, collection.requests)
        // Requests whose parent group is missing still need a home.
        requests.filter { it.string("_id").orEmpty() !in attached }.forEach {
            collection.requests.add(parseRequest(it))
        }
        return collection
    }

    private fun parseRequest(value: JsonObject): AstraRequest {
        val request = AstraRequest(name = value.string("name")?.ifBlank { null } ?: "Untitled")
        request.method = value.string("method")?.uppercase(Locale.ROOT) ?: "GET"
        request.url = fromInsomniaVariables(value.string("url").orEmpty())
        value.array("parameters")?.forEach { element ->
            val parameter = element.asJsonObject
            val key = parameter.string("name") ?: return@forEach
            if (key.isNotBlank()) {
                request.params.add(
                    KeyValue(
                        enabled = parameter.get("disabled")?.takeIf { it.isJsonPrimitive }?.asBoolean != true,
                        key = key,
                        value = fromInsomniaVariables(parameter.string("value").orEmpty()),
                    ),
                )
            }
        }
        value.array("headers")?.forEach { element ->
            val header = element.asJsonObject
            val key = header.string("name") ?: return@forEach
            if (key.isNotBlank()) {
                request.headers.add(
                    KeyValue(
                        enabled = header.get("disabled")?.takeIf { it.isJsonPrimitive }?.asBoolean != true,
                        key = key,
                        value = fromInsomniaVariables(header.string("value").orEmpty()),
                    ),
                )
            }
        }
        parseBody(value.obj("body"), request)
        parseAuth(value.obj("authentication"), request)
        return request
    }

    private fun parseBody(body: JsonObject?, request: AstraRequest) {
        if (body == null) return
        val mime = body.string("mimeType").orEmpty().lowercase(Locale.ROOT)
        when {
            mime.contains("multipart/form-data") -> {
                request.bodyMode = "form-data"
                request.formFields = body.array("params")?.mapNotNull { element ->
                    val field = element.asJsonObject
                    field.string("name")?.let { name ->
                        KeyValue(
                            enabled = field.get("disabled")?.takeIf { it.isJsonPrimitive }?.asBoolean != true,
                            key = name,
                            value = fromInsomniaVariables(field.string("value").orEmpty()),
                        )
                    }
                }?.toMutableList() ?: mutableListOf()
            }
            mime.contains("x-www-form-urlencoded") -> {
                request.bodyMode = "urlencoded"
                request.formFields = body.array("params")?.mapNotNull { element ->
                    val field = element.asJsonObject
                    field.string("name")?.let { name ->
                        KeyValue(
                            enabled = field.get("disabled")?.takeIf { it.isJsonPrimitive }?.asBoolean != true,
                            key = name,
                            value = fromInsomniaVariables(field.string("value").orEmpty()),
                        )
                    }
                }?.toMutableList() ?: mutableListOf()
            }
            else -> {
                request.body = fromInsomniaVariables(body.string("text").orEmpty())
                request.bodyMode = if (mime.contains("json") || looksLikeJson(request.body)) "json" else "text"
            }
        }
    }

    private fun parseAuth(auth: JsonObject?, request: AstraRequest) {
        when (auth?.string("type")?.lowercase(Locale.ROOT)) {
            "bearer" -> request.auth = AuthConfig(
                type = "bearer",
                token = fromInsomniaVariables(auth.string("token").orEmpty()),
            )
            "basic" -> request.auth = AuthConfig(
                type = "basic",
                username = fromInsomniaVariables(auth.string("username").orEmpty()),
                password = fromInsomniaVariables(auth.string("password").orEmpty()),
            )
            "apikey" -> request.auth = AuthConfig(
                type = "apikey",
                apiKeyName = fromInsomniaVariables(auth.string("key").orEmpty()),
                apiKeyValue = fromInsomniaVariables(auth.string("value").orEmpty()),
                apiKeyIn = if (auth.string("addTo") == "query") "query" else "header",
            )
        }
    }

    private fun parseEnvironment(value: JsonObject): AstraEnvironment {
        val environment = AstraEnvironment(name = value.string("name")?.ifBlank { null } ?: "Imported Insomnia")
        value.obj("data")?.entrySet()?.forEach { (key, element) ->
            environment.variables[key] = fromInsomniaVariables(scalar(element))
        }
        return environment
    }

    private fun scalar(element: JsonElement): String =
        if (element.isJsonPrimitive) element.asString else element.toString()

    private fun looksLikeJson(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    companion object {
        private val INSOMNIA_VARIABLE = Regex("\\{\\{\\s*_\\s*\\.\\s*([A-Za-z0-9_\\-]+)\\s*}}")
        private val ASTRA_VARIABLE = Regex("\\{\\{\\s*([A-Za-z0-9_\\-]+)\\s*}}")

        fun toInsomniaVariables(text: String): String =
            text.replace(ASTRA_VARIABLE) { "{{ _." + it.groupValues[1] + " }}" }

        fun fromInsomniaVariables(text: String): String =
            text.replace(INSOMNIA_VARIABLE) { "{{" + it.groupValues[1] + "}}" }
    }
}
