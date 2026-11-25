package com.astra.intellij.model

import java.util.UUID

data class KeyValue(
    var enabled: Boolean = true,
    var key: String = "",
    var value: String = "",
)

/**
 * One row of a form body. Text rows send [value]; file rows send the bytes read from [filePath]
 * together with the original [fileName].
 */
data class FormField(
    var enabled: Boolean = true,
    var key: String = "",
    var value: String = "",
    /** "text" or "file". */
    var type: String = "text",
    var filePath: String = "",
    var fileName: String = "",
) {
    val isFile: Boolean
        get() = type.equals("file", ignoreCase = true)
}

data class AuthConfig(
    var type: String = "none",
    var token: String = "",
    var username: String = "",
    var password: String = "",
    var apiKeyName: String = "",
    var apiKeyValue: String = "",
    var apiKeyIn: String = "header",
    // OAuth 2.0 fields; type = "oauth2" uses [token] as the access token.
    var oauthTokenUrl: String = "",
    var oauthAuthUrl: String = "",
    var oauthClientId: String = "",
    var oauthClientSecret: String = "",
    var oauthScope: String = "",
    var oauthGrantType: String = "client_credentials",
    var oauthRefreshToken: String = "",
    /** Access-token expiry in epoch milliseconds; 0 means unknown. */
    var oauthExpiresAt: Long = 0L,
    var oauthPkce: Boolean = true,
)

/** "http" for REST calls, "websocket" for persistent connections. */
const val PROTOCOL_HTTP = "http"
const val PROTOCOL_WEBSOCKET = "websocket"

data class AstraRequest(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "New Request",
    /** HTTP method or, for WebSocket connections, the label shown in the tree. */
    var method: String = "GET",
    var url: String = "",
    var params: MutableList<KeyValue> = mutableListOf(),
    var headers: MutableList<KeyValue> = mutableListOf(),
    var bodyMode: String = "none",
    var body: String = "",
    var formFields: MutableList<FormField> = mutableListOf(),
    var assertions: MutableList<String> = mutableListOf(),
    var preRequest: MutableList<String> = mutableListOf(),
    var postResponse: MutableList<String> = mutableListOf(),
    var auth: AuthConfig = AuthConfig(),
    /** Per-request timeout in seconds; 0 means the global setting applies. */
    var timeoutSeconds: Int = 0,
    /** Extra transport-level attempts after the first failure. */
    var retries: Int = 0,
    /** Per-request redirect policy; null means the global setting applies. */
    var followRedirects: Boolean? = null,
    var examples: MutableList<ResponseExample> = mutableListOf(),
    var protocol: String = PROTOCOL_HTTP,
    /** GraphQL editor fields; [bodyMode] = "graphql" turns them into the JSON request body. */
    var graphqlQuery: String = "",
    var graphqlVars: String = "",
    /** gRPC-Web payload; [grpcBinary] selects base64 protobuf instead of JSON text. */
    var grpcPayload: String = "",
    var grpcBinary: Boolean = true,
    /** Keep the response connection open and list server-sent events instead of buffering. */
    var streamSse: Boolean = false,
    /** WebSocket subprotocols, comma separated. */
    var wsSubprotocol: String = "",
    var wsVerifyTls: Boolean = true,
) {
    val isWebSocket: Boolean
        get() = protocol.equals(PROTOCOL_WEBSOCKET, ignoreCase = true)
}

data class RequestFolder(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var requests: MutableList<AstraRequest> = mutableListOf(),
    var folders: MutableList<RequestFolder> = mutableListOf(),
    var variables: MutableMap<String, String> = mutableMapOf(),
    var disabledVariables: MutableSet<String> = mutableSetOf(),
    /** Folder-level authorization; "inherit" walks up to the collection. */
    var auth: AuthConfig = AuthConfig(type = "inherit"),
    var preRequest: MutableList<String> = mutableListOf(),
    var postResponse: MutableList<String> = mutableListOf(),
)

data class RequestCollection(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var folders: MutableList<RequestFolder> = mutableListOf(),
    var requests: MutableList<AstraRequest> = mutableListOf(),
    var variables: MutableMap<String, String> = mutableMapOf(),
    var disabledVariables: MutableSet<String> = mutableSetOf(),
    var starred: Boolean = false,
    /** Collection-level authorization used when requests/folders inherit. */
    var auth: AuthConfig = AuthConfig(type = "inherit"),
    var preRequest: MutableList<String> = mutableListOf(),
    var postResponse: MutableList<String> = mutableListOf(),
)

/** True when this collection or any of its folders owns [request] (identity comparison). */
fun RequestCollection.containsRequest(request: AstraRequest): Boolean =
    requests.any { it === request } || folders.any { it.containsRequest(request) }

/**
 * Authorization inherited by [request] when its own type is "inherit".
 *
 * Walks the folder chain from the request outwards, stopping at a folder that explicitly sets
 * "none", then falls back to the collection. Returns null when nothing is configured.
 */
fun RequestCollection.effectiveAuth(request: AstraRequest): AuthConfig? {
    if (!request.auth.type.equals("inherit", ignoreCase = true)) return null
    val chain = folderPathTo(request) ?: emptyList()
    for (folder in chain.asReversed()) {
        when (folder.auth.type.lowercase()) {
            "inherit" -> continue
            "none" -> return null
            else -> return folder.auth
        }
    }
    return when (collectionAuthType()) {
        "none", "inherit", "" -> null
        else -> auth
    }
}

private fun RequestCollection.collectionAuthType(): String = auth.type.lowercase()

/**
 * Collection and folder scripts that run before (or after) [request], ordered outermost first,
 * matching Postman's inheritance order.
 */
fun RequestCollection.inheritedScripts(request: AstraRequest, preRequest: Boolean): List<String> {
    val chain = folderPathTo(request) ?: return emptyList()
    val own = if (preRequest) this.preRequest else this.postResponse
    return own + chain.flatMap { if (preRequest) it.preRequest else it.postResponse }
}

fun RequestFolder.containsRequest(request: AstraRequest): Boolean =
    requests.any { it === request } || folders.any { it.containsRequest(request) }

/** Folder chain from the collection root down to the folder owning [request], or null. */
fun RequestCollection.folderPathTo(request: AstraRequest): List<RequestFolder>? {
    fun walk(folders: List<RequestFolder>, path: List<RequestFolder>): List<RequestFolder>? {
        for (folder in folders) {
            val next = path + folder
            if (folder.requests.any { it === request }) return next
            walk(folder.folders, next)?.let { return it }
        }
        return null
    }
    return if (requests.any { it === request }) emptyList() else walk(folders, emptyList())
}

/**
 * Resolves variables with Postman precedence: globals < collection < folder chain < environment.
 * Disabled collection/folder variables are skipped.
 */
fun resolveVariables(
    request: AstraRequest?,
    collection: RequestCollection?,
    globals: Map<String, String> = emptyMap(),
    environment: Map<String, String> = emptyMap(),
): Map<String, String> {
    val merged = LinkedHashMap<String, String>(globals)
    if (request != null && collection != null) {
        collection.variables.forEach { (key, value) ->
            if (key !in collection.disabledVariables) merged[key] = value
        }
        collection.folderPathTo(request)?.forEach { folder ->
            folder.variables.forEach { (key, value) ->
                if (key !in folder.disabledVariables) merged[key] = value
            }
        }
    }
    merged.putAll(environment)
    return merged
}

data class AstraEnvironment(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var variables: MutableMap<String, String> = mutableMapOf(),
)

data class HistoryEntry(
    var id: String = UUID.randomUUID().toString(),
    var request: AstraRequest = AstraRequest(),
    var status: Int = 0,
    var statusText: String = "",
    var timeMs: Long = 0L,
    var bodySize: Int = 0,
    var createdAt: Long = System.currentTimeMillis(),
    var pinned: Boolean = false,
)

/** A saved response snapshot attached to a request, similar to Postman examples. */
data class ResponseExample(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "Example",
    var status: Int = 0,
    var statusText: String = "",
    var headers: Map<String, List<String>> = emptyMap(),
    var body: String = "",
    var createdAt: Long = System.currentTimeMillis(),
)

data class CookieData(
    var name: String = "",
    var value: String = "",
    var domain: String = "",
    var path: String = "",
)

/** Scheduled local run of a collection, matching the desktop app's monitor model. */
data class MonitorConfig(
    var id: String = UUID.randomUUID().toString(),
    var collectionId: String = "",
    var name: String = "Scheduled Monitor",
    var intervalMinutes: Int = 30,
    var iterations: Int = 1,
    var enabled: Boolean = false,
    /** Epoch milliseconds of the last run; 0 means it never ran. */
    var lastRunAt: Long = 0L,
    var lastPassed: Int = 0,
    var lastTotal: Int = 0,
    var lastError: String = "",
)

data class HttpResult(
    var status: Int = 0,
    var statusText: String = "",
    var headers: Map<String, List<String>> = emptyMap(),
    var body: ByteArray = ByteArray(0),
    var elapsedMs: Long = 0L,
    var cookies: MutableList<CookieData> = mutableListOf(),
)

data class AstraSettings(
    var proxyHost: String = "",
    var proxyPort: Int = 0,
    /** "http" or "socks"; SOCKS5 is used when the port speaks the SOCKS protocol. */
    var proxyType: String = "http",
    var proxyUsername: String = "",
    var proxyPassword: String = "",
    /** Comma/space separated hosts that bypass the proxy; `*.example.com` matches subdomains. */
    var bypassHosts: String = "",
    /** PKCS#12 (.p12/.pfx) client certificate used for mutual TLS. */
    var clientCertPath: String = "",
    var clientCertPassword: String = "",
    var verifySsl: Boolean = true,
    var timeoutSeconds: Int = 90,
    var followRedirects: Boolean = true,
)

data class WorkspaceData(
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    var collections: MutableList<RequestCollection> = mutableListOf(),
    var environments: MutableList<AstraEnvironment> = mutableListOf(),
    var activeEnvironmentId: String? = null,
    var history: MutableList<HistoryEntry> = mutableListOf(),
    var globals: MutableMap<String, String> = mutableMapOf(),
    var settings: AstraSettings = AstraSettings(),
    var monitors: MutableList<MonitorConfig> = mutableListOf(),
) {
    companion object {
        /** Bump when the persisted shape changes so old payloads can be migrated on load. */
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/** Copies editable fields from a restored draft onto the saved request, keeping the saved id. */
fun AstraRequest.copyContentFrom(other: AstraRequest) {
    name = other.name
    method = other.method
    url = other.url
    params = other.params
    headers = other.headers
    bodyMode = other.bodyMode
    body = other.body
    formFields = other.formFields
    assertions = other.assertions
    preRequest = other.preRequest
    postResponse = other.postResponse
    auth = other.auth
    timeoutSeconds = other.timeoutSeconds
    retries = other.retries
    followRedirects = other.followRedirects
    examples = other.examples
    protocol = other.protocol
    graphqlQuery = other.graphqlQuery
    graphqlVars = other.graphqlVars
    grpcPayload = other.grpcPayload
    grpcBinary = other.grpcBinary
    streamSse = other.streamSse
    wsSubprotocol = other.wsSubprotocol
    wsVerifyTls = other.wsVerifyTls
}

fun RequestFolder.allFolders(): List<RequestFolder> =
    listOf(this) + folders.flatMap { it.allFolders() }

fun RequestFolder.allRequests(): List<AstraRequest> =
    requests + folders.flatMap { it.allRequests() }

fun RequestCollection.allFolders(): List<RequestFolder> = folders.flatMap { it.allFolders() }

fun RequestCollection.allRequests(): List<AstraRequest> =
    requests + folders.flatMap { it.allRequests() }

/** Legacy workspaces deserialized by Gson can miss fields; fill safe defaults before use. */
@Suppress("SENSELESS_COMPARISON")
fun AstraRequest.normalize(): AstraRequest {
    if (id.isNullOrEmpty()) id = java.util.UUID.randomUUID().toString()
    if (name == null) name = "New Request"
    if (method == null) method = "GET"
    if (url == null) url = ""
    if (body == null) body = ""
    if (bodyMode == null) bodyMode = "none"
    if (params == null) params = mutableListOf()
    if (headers == null) headers = mutableListOf()
    if (formFields == null) formFields = mutableListOf()
    formFields.forEach { field ->
        if (field.key == null) field.key = ""
        if (field.value == null) field.value = ""
        if (field.type == null || field.type.isBlank()) field.type = "text"
        if (field.filePath == null) field.filePath = ""
        if (field.fileName == null) field.fileName = ""
    }
    if (assertions == null) assertions = mutableListOf()
    if (preRequest == null) preRequest = mutableListOf()
    if (postResponse == null) postResponse = mutableListOf()
    if (auth == null) auth = AuthConfig()
    if (examples == null) examples = mutableListOf()
    if (protocol == null) protocol = PROTOCOL_HTTP
    if (graphqlQuery == null) graphqlQuery = ""
    if (graphqlVars == null) graphqlVars = ""
    if (grpcPayload == null) grpcPayload = ""
    if (wsSubprotocol == null) wsSubprotocol = ""
    timeoutSeconds = timeoutSeconds.coerceAtLeast(0)
    retries = retries.coerceIn(0, 10)
    examples.forEach { example ->
        if (example.id.isNullOrEmpty()) example.id = java.util.UUID.randomUUID().toString()
        if (example.name == null) example.name = "Example"
        if (example.statusText == null) example.statusText = ""
        if (example.headers == null) example.headers = emptyMap()
        if (example.body == null) example.body = ""
    }
    return this
}

@Suppress("SENSELESS_COMPARISON")
fun WorkspaceData.normalize(): WorkspaceData {
    if (collections == null) collections = mutableListOf()
    collections.forEach { collection ->
        if (collection.id.isNullOrEmpty()) collection.id = java.util.UUID.randomUUID().toString()
        if (collection.name == null) collection.name = "Collection"
        if (collection.requests == null) collection.requests = mutableListOf()
        if (collection.folders == null) collection.folders = mutableListOf()
        if (collection.variables == null) collection.variables = mutableMapOf()
        if (collection.disabledVariables == null) collection.disabledVariables = mutableSetOf()
        if (collection.auth == null) collection.auth = AuthConfig(type = "inherit")
        if (collection.preRequest == null) collection.preRequest = mutableListOf()
        if (collection.postResponse == null) collection.postResponse = mutableListOf()
        collection.requests.forEach { it.normalize() }
        fun normalizeFolder(folder: RequestFolder) {
            if (folder.id.isNullOrEmpty()) folder.id = java.util.UUID.randomUUID().toString()
            if (folder.name == null) folder.name = "Folder"
            if (folder.requests == null) folder.requests = mutableListOf()
            if (folder.folders == null) folder.folders = mutableListOf()
            if (folder.variables == null) folder.variables = mutableMapOf()
            if (folder.disabledVariables == null) folder.disabledVariables = mutableSetOf()
            if (folder.auth == null) folder.auth = AuthConfig(type = "inherit")
            if (folder.preRequest == null) folder.preRequest = mutableListOf()
            if (folder.postResponse == null) folder.postResponse = mutableListOf()
            folder.requests.forEach { it.normalize() }
            folder.folders.forEach { normalizeFolder(it) }
        }
        collection.folders.forEach { normalizeFolder(it) }
    }
    if (history == null) history = mutableListOf()
    history.forEach { entry ->
        if (entry.id.isNullOrEmpty()) entry.id = java.util.UUID.randomUUID().toString()
        if (entry.request == null) entry.request = AstraRequest() else entry.request.normalize()
    }
    if (monitors == null) monitors = mutableListOf()
    monitors.forEach { monitor ->
        if (monitor.id.isNullOrEmpty()) monitor.id = java.util.UUID.randomUUID().toString()
        if (monitor.name == null) monitor.name = "Scheduled Monitor"
        monitor.intervalMinutes = monitor.intervalMinutes.coerceIn(1, 24 * 60)
        monitor.iterations = monitor.iterations.coerceIn(1, 100)
        if (monitor.lastError == null) monitor.lastError = ""
    }
    if (settings == null) settings = AstraSettings()
    settings.let { value ->
        if (value.proxyHost == null) value.proxyHost = ""
        if (value.proxyType == null) value.proxyType = "http"
        if (value.proxyUsername == null) value.proxyUsername = ""
        if (value.proxyPassword == null) value.proxyPassword = ""
        if (value.bypassHosts == null) value.bypassHosts = ""
        if (value.clientCertPath == null) value.clientCertPath = ""
        if (value.clientCertPassword == null) value.clientCertPassword = ""
    }
    return this
}
