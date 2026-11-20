package com.astra.intellij.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VariableResolverTest {
    @Test
    fun `resolves known variables and preserves unknown placeholders`() {
        val vars = mapOf("host" to "example.com", "token" to "secret")
        val input = "https://{{host}}/{{missing}}?token={{ token }}&again={{missing}}"

        assertEquals(
            "https://example.com/{{missing}}?token=secret&again={{missing}}",
            VariableResolver.resolve(input, vars),
        )
        assertEquals("plain text", VariableResolver.resolve("plain text", vars))
    }

    @Test
    fun `reports each missing variable once`() {
        val vars = mapOf("known" to "value")

        assertEquals(
            linkedSetOf("first", "second"),
            VariableResolver.missing("{{first}}/{{ known }}/{{second}}/{{first}}", vars),
        )
    }
}
