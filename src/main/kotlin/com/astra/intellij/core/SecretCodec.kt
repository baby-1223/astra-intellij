package com.astra.intellij.core

import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.WorkspaceData
import com.astra.intellij.model.allRequests

/** Placeholder written to the workspace when a credential lives in the system credential store. */
const val SECRET_PLACEHOLDER = "secure:"

fun interface SecretWriter {
    /** Returns true when the value was stored successfully. */
    fun write(ref: String, value: String): Boolean
}

fun interface SecretReader {
    fun read(ref: String): String?
}

fun secretRef(requestId: String, field: String): String = "request/$requestId/$field"

private val SECRET_FIELDS = listOf("token", "password", "apiKeyValue")

/**
 * Replaces request credentials with `secure:<ref>` placeholders, handing the real values to
 * [writer]. Values that are already placeholders are left untouched so a failed keychain read
 * never destroys the stored reference.
 */
fun redactSecrets(data: WorkspaceData, writer: SecretWriter) {
    for (request in requestsOf(data)) redactRequestSecrets(request, writer)
}

/** Restores credentials previously stored by [redactSecrets]. Missing entries stay as placeholders. */
fun hydrateSecrets(data: WorkspaceData, reader: SecretReader) {
    for (request in requestsOf(data)) hydrateRequestSecrets(request, reader)
}

fun redactRequestSecrets(request: AstraRequest, writer: SecretWriter) {
    for (field in SECRET_FIELDS) {
        val value = readField(request, field)
        if (value.isNullOrEmpty() || value.startsWith(SECRET_PLACEHOLDER)) continue
        val ref = secretRef(request.id, field)
        if (writer.write(ref, value)) writeField(request, field, SECRET_PLACEHOLDER + ref)
    }
}

fun hydrateRequestSecrets(request: AstraRequest, reader: SecretReader) {
    for (field in SECRET_FIELDS) {
        val value = readField(request, field) ?: continue
        if (!value.startsWith(SECRET_PLACEHOLDER)) continue
        reader.read(value.removePrefix(SECRET_PLACEHOLDER))?.let { writeField(request, field, it) }
    }
}

/** Removes stored credentials for every request in [data], used when resetting the workspace. */
fun forgetSecrets(data: WorkspaceData, forget: (String) -> Unit) {
    for (request in requestsOf(data)) {
        for (field in SECRET_FIELDS) forget(secretRef(request.id, field))
    }
}

private fun requestsOf(data: WorkspaceData): Sequence<AstraRequest> = sequence {
    data.collections.forEach { collection -> yieldAll(collection.allRequests()) }
    data.history.forEach { yield(it.request) }
}

private fun readField(request: AstraRequest, field: String): String? = when (field) {
    "token" -> request.auth.token
    "password" -> request.auth.password
    "apiKeyValue" -> request.auth.apiKeyValue
    else -> null
}

private fun writeField(request: AstraRequest, field: String, value: String) {
    when (field) {
        "token" -> request.auth.token = value
        "password" -> request.auth.password = value
        "apiKeyValue" -> request.auth.apiKeyValue = value
    }
}
