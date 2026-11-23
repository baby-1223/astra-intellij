package com.astra.intellij.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VariableScopeTest {
    @Test
    fun `request variables follow Postman scope precedence`() {
        val request = AstraRequest()
        val folder = RequestFolder(
            name = "Auth",
            requests = mutableListOf(request),
            variables = mutableMapOf("a" to "folder", "b" to "folder", "hidden" to "folder"),
            disabledVariables = mutableSetOf("hidden"),
        )
        val collection = RequestCollection(
            name = "Team",
            folders = mutableListOf(folder),
            variables = mutableMapOf("a" to "collection", "c" to "collection", "off" to "collection"),
            disabledVariables = mutableSetOf("off"),
        )

        assertEquals(
            mapOf("a" to "folder", "b" to "folder", "c" to "collection"),
            resolveVariables(request, collection),
        )
        assertEquals(
            mapOf("a" to "env", "b" to "folder", "c" to "collection", "d" to "global"),
            resolveVariables(
                request,
                collection,
                globals = mapOf("a" to "global", "d" to "global"),
                environment = mapOf("a" to "env"),
            ),
        )
    }
}
