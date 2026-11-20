package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreRequestServiceTest {
    @Test
    fun `set stores resolved value in environment and run variables`() {
        val environment = AstraEnvironment(name = "dev")
        val vars = PreRequestService.execute(
            listOf("set token {{\$guid}}", "set host api.test", "set full https://{{host}}/v1"),
            emptyMap(),
            environment,
        )

        assertTrue(environment.variables["token"]!!.matches(Regex("[0-9a-f-]{36}")))
        assertEquals(vars["token"], environment.variables["token"])
        assertEquals("api.test", environment.variables["host"])
        assertEquals("https://api.test/v1", vars["full"])
    }

    @Test
    fun `unset removes variable and comments are ignored`() {
        val environment = AstraEnvironment(name = "dev").apply {
            variables["debug"] = "true"
            variables["keep"] = "yes"
        }
        val vars = PreRequestService.execute(
            listOf("# unset keep", "", "unset debug"),
            mapOf("keep" to "yes"),
            environment,
        )

        assertFalse(vars.containsKey("debug"))
        assertFalse(environment.variables.containsKey("debug"))
        assertEquals("yes", vars["keep"])
        assertEquals("yes", environment.variables["keep"])
    }

    @Test
    fun `base variables are not mutated`() {
        val base = mapOf("a" to "1")
        val vars = PreRequestService.execute(listOf("set b 2"), base, null)
        assertEquals(mapOf("a" to "1"), base)
        assertEquals(mapOf("a" to "1", "b" to "2"), vars)
    }
}
