package com.astra.intellij.core

import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.AuthConfig
import com.astra.intellij.model.HistoryEntry
import com.astra.intellij.model.RequestCollection
import com.astra.intellij.model.WorkspaceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SecretCodecTest {
    @Test
    fun `redacts credentials and restores them`() {
        val request = AstraRequest(
            id = "req-1",
            auth = AuthConfig(type = "bearer", token = "secret-token", password = "secret-pass"),
        )
        val data = WorkspaceData(collections = mutableListOf(RequestCollection(requests = mutableListOf(request))))
        val vault = mutableMapOf<String, String>()

        redactSecrets(data) { ref, value -> vault[ref] = value; true }

        assertEquals("secure:request/req-1/token", request.auth.token)
        assertEquals("secure:request/req-1/password", request.auth.password)
        assertEquals("secret-token", vault["request/req-1/token"])
        assertEquals("secret-pass", vault["request/req-1/password"])

        hydrateSecrets(data) { vault[it] }

        assertEquals("secret-token", request.auth.token)
        assertEquals("secret-pass", request.auth.password)
    }

    @Test
    fun `keeps plaintext when the credential store rejects the value`() {
        val request = AstraRequest(id = "req-2", auth = AuthConfig(apiKeyValue = "key-2"))
        val data = WorkspaceData(collections = mutableListOf(RequestCollection(requests = mutableListOf(request))))

        redactSecrets(data) { _, _ -> false }

        assertEquals("key-2", request.auth.apiKeyValue)
    }

    @Test
    fun `does not overwrite an existing placeholder`() {
        val request = AstraRequest(id = "req-3", auth = AuthConfig(token = SECRET_PLACEHOLDER + "request/req-3/token"))
        val data = WorkspaceData(collections = mutableListOf(RequestCollection(requests = mutableListOf(request))))
        var writes = 0

        redactSecrets(data) { _, _ -> writes++; true }

        assertEquals(0, writes)
        assertEquals("secure:request/req-3/token", request.auth.token)
    }

    @Test
    fun `history snapshots are redacted too`() {
        val snapshot = AstraRequest(id = "req-4", auth = AuthConfig(token = "history-token"))
        val data = WorkspaceData(history = mutableListOf(HistoryEntry(request = snapshot)))
        val vault = mutableMapOf<String, String>()

        redactSecrets(data) { ref, value -> vault[ref] = value; true }

        assertEquals("secure:request/req-4/token", snapshot.auth.token)
        assertEquals("history-token", vault["request/req-4/token"])
    }

    @Test
    fun `new workspaces carry the current schema version`() {
        assertEquals(WorkspaceData.CURRENT_SCHEMA_VERSION, WorkspaceData().schemaVersion)
    }
}
