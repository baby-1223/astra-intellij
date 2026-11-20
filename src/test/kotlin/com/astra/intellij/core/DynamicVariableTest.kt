package com.astra.intellij.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DynamicVariableTest {
    @Test
    fun `timestamp and iso timestamp resolve`() {
        val timestamp = VariableResolver.resolve("{{\$timestamp}}", emptyMap())
        assertTrue(timestamp.toLong() > 1_000_000_000L)
        val iso = VariableResolver.resolve("{{\$isoTimestamp}}", emptyMap())
        assertTrue(iso.contains("T") && iso.endsWith("Z"))
    }

    @Test
    fun `guid resolves to unique ids`() {
        val first = VariableResolver.resolve("{{\$guid}}", emptyMap())
        val second = VariableResolver.resolve("{{\$guid}}", emptyMap())
        assertNotEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f-]{36}")))
    }

    @Test
    fun `random int within range and unknown dynamic kept`() {
        val value = VariableResolver.resolve("{{\$randomInt}}", emptyMap()).toInt()
        assertTrue(value in 0..1000)
        assertEquals("{{\$unknownDynamic}}", VariableResolver.resolve("{{\$unknownDynamic}}", emptyMap()))
    }

    @Test
    fun `dynamic variables are not reported missing`() {
        assertEquals(emptySet<String>(), VariableResolver.missing("{{\$timestamp}} {{\$guid}}", emptyMap()))
        assertEquals(setOf("host"), VariableResolver.missing("{{host}} {{\$timestamp}}", emptyMap()))
    }

    @Test
    fun `regular variables still resolve`() {
        assertEquals("https://api.test/v1", VariableResolver.resolve("{{host}}/v1", mapOf("host" to "https://api.test")))
    }
}
