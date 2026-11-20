package com.astra.intellij.model

import java.util.UUID

data class KeyValue(
    var enabled: Boolean = true,
    var key: String = "",
    var value: String = "",
)

data class AuthConfig(
    var type: String = "none",
    var token: String = "",
    var username: String = "",
    var password: String = "",
    var apiKeyName: String = "",
    var apiKeyValue: String = "",
    var apiKeyIn: String = "header",
)

data class AstraRequest(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "New Request",
    var method: String = "GET",
    var url: String = "",
    var params: MutableList<KeyValue> = mutableListOf(),
    var headers: MutableList<KeyValue> = mutableListOf(),
    var bodyMode: String = "none",
    var body: String = "",
    var auth: AuthConfig = AuthConfig(),
)

data class RequestFolder(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var requests: MutableList<AstraRequest> = mutableListOf(),
)

data class RequestCollection(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var folders: MutableList<RequestFolder> = mutableListOf(),
    var requests: MutableList<AstraRequest> = mutableListOf(),
)

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
)

data class CookieData(
    var name: String = "",
    var value: String = "",
    var domain: String = "",
    var path: String = "",
)

data class HttpResult(
    var status: Int = 0,
    var statusText: String = "",
    var headers: Map<String, List<String>> = emptyMap(),
    var body: ByteArray = ByteArray(0),
    var elapsedMs: Long = 0L,
    var cookies: MutableList<CookieData> = mutableListOf(),
)

data class WorkspaceData(
    var collections: MutableList<RequestCollection> = mutableListOf(),
    var environments: MutableList<AstraEnvironment> = mutableListOf(),
    var activeEnvironmentId: String? = null,
    var history: MutableList<HistoryEntry> = mutableListOf(),
)
