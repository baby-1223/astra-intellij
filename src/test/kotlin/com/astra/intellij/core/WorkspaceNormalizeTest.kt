package com.astra.intellij.core

import com.astra.intellij.model.PROTOCOL_HTTP
import com.astra.intellij.model.WorkspaceData
import com.astra.intellij.model.allRequests
import com.astra.intellij.model.normalize
import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Legacy workspace payloads are missing fields that Gson leaves as null; normalize must repair them. */
class WorkspaceNormalizeTest {
    private val legacy = """
        {
          "collections": [
            {
              "id": null,
              "name": null,
              "requests": [
                {
                  "id": null,
                  "name": null,
                  "method": null,
                  "url": null,
                  "bodyMode": null,
                  "protocol": null,
                  "timeoutSeconds": -3,
                  "retries": 42,
                  "examples": [
                    { "id": null, "name": null, "headers": null, "body": null, "statusText": null }
                  ]
                }
              ],
              "folders": [
                { "name": null, "requests": null, "folders": null }
              ]
            }
          ],
          "history": [ { "id": null, "request": null } ],
          "monitors": [ { "id": null, "name": null, "intervalMinutes": 0, "iterations": 1000 } ],
          "settings": {}
        }
    """.trimIndent()

    @Test
    fun `fills defaults for legacy collections, requests and examples`() {
        val data = Gson().fromJson(legacy, WorkspaceData::class.java)
        data.normalize()

        val collection = data.collections.single()
        assertFalse(collection.id.isNullOrBlank())
        assertEquals("Collection", collection.name)

        val request = collection.requests.single()
        assertFalse(request.id.isNullOrBlank())
        assertEquals("New Request", request.name)
        assertEquals("GET", request.method)
        assertEquals("", request.url)
        assertEquals("none", request.bodyMode)
        assertEquals(PROTOCOL_HTTP, request.protocol)
        assertEquals(0, request.timeoutSeconds)
        assertEquals(10, request.retries)

        val example = request.examples.single()
        assertFalse(example.id.isNullOrBlank())
        assertEquals("Example", example.name)
        assertEquals("", example.body)

        val folder = collection.folders.single()
        assertEquals("Folder", folder.name)
        assertTrue(folder.folders.isEmpty())
    }

    @Test
    fun `normalizes history and monitor limits`() {
        val data = Gson().fromJson(legacy, WorkspaceData::class.java)
        data.normalize()

        val entry = data.history.single()
        assertFalse(entry.id.isNullOrBlank())
        assertNotNull(entry.request)
        assertEquals("GET", entry.request.method)

        val monitor = data.monitors.single()
        assertFalse(monitor.id.isNullOrBlank())
        assertEquals("Scheduled Monitor", monitor.name)
        assertEquals(1, monitor.intervalMinutes)
        assertEquals(100, monitor.iterations)
    }

    @Test
    fun `all requests walks nested folders`() {
        val data = Gson().fromJson(legacy, WorkspaceData::class.java)
        data.normalize()
        val collection = data.collections.single()
        collection.folders.single().requests.add(com.astra.intellij.model.AstraRequest(name = "Nested"))
        assertEquals(listOf("New Request", "Nested"), collection.allRequests().map { it.name })
    }
}
