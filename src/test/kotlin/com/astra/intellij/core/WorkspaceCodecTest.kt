package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.KeyValue
import com.astra.intellij.model.RequestCollection
import com.astra.intellij.model.WorkspaceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkspaceCodecTest {
    private val codec = WorkspaceCodec()

    @Test
    fun `blank null and malformed content produce the default environment`() {
        listOf(null, "", "  \n", "{broken").forEach { content ->
            val data = codec.deserialize(content)

            assertEquals(1, data.environments.size)
            assertEquals("Default Environment", data.environments[0].name)
            assertEquals(data.environments[0].id, data.activeEnvironmentId)
            assertTrue(data.collections.isEmpty())
            assertTrue(data.history.isEmpty())
        }
    }

    @Test
    fun `deep copy uses json and does not share nested mutable state`() {
        val original = WorkspaceData(
            collections = mutableListOf(
                RequestCollection(
                    name = "Collection",
                    requests = mutableListOf(
                        AstraRequest(
                            name = "Request",
                            headers = mutableListOf(KeyValue(key = "X-Test", value = "before")),
                        ),
                    ),
                ),
            ),
            environments = mutableListOf(
                AstraEnvironment(name = "Dev", variables = mutableMapOf("token" to "before")),
            ),
        )

        val copy = codec.deepCopy(original)
        assertEquals(original, copy)
        assertEquals(original, codec.deserialize(codec.serialize(original)))
        assertNotSame(original.collections, copy.collections)
        assertNotSame(original.collections[0].requests[0], copy.collections[0].requests[0])

        original.collections[0].requests[0].headers[0].value = "after"
        original.environments[0].variables["token"] = "after"

        assertEquals("before", copy.collections[0].requests[0].headers[0].value)
        assertEquals("before", copy.environments[0].variables["token"])
    }
}
